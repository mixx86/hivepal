/**
 * Hivepal: delivery to agents (issue #41): the relay's email account, mailboxes written as Markdown in
 * git (kept in the relay and pushed to an external repository), the wake-up email after the files.
 */
import { execFile, execFileSync } from "node:child_process";
import { promisify } from "node:util";
import { existsSync, mkdtempSync, readdirSync, readFileSync, rmSync, statSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { afterEach, describe, expect, it } from "vitest";
import type { Mailer } from "../src/wake.js";
import { AGENT, pairDevice, setup } from "./helpers.js";

type B = Awaited<ReturnType<typeof setup>>;
let b: B | undefined;
const temps: string[] = [];
afterEach(async () => {
  await b?.app.close();
  b = undefined;
  for (const t of temps.splice(0)) rmSync(t, { recursive: true, force: true });
});

const tmp = () => {
  const d = mkdtempSync(join(tmpdir(), "hivepal-delivery-test-"));
  temps.push(d);
  return d;
};
const git = (cwd: string, ...args: string[]) =>
  execFileSync("git", ["-c", "protocol.file.allow=always", "-c", "user.name=t", "-c", "user.email=t@t", ...args], { cwd, encoding: "utf8" });

async function addAgent(id: string, name: string): Promise<{ authorization: string }> {
  const r = await b!.app.inject({ method: "POST", url: "/v1/agents", headers: AGENT, payload: { agent_id: id, name } });
  return { authorization: `Bearer ${r.json().api_key}` };
}

async function say(headers: Record<string, string>, text: string, conv?: string) {
  const r = await b!.app.inject({ method: "POST", url: "/v1/messages", headers, payload: { text, ...(conv ? { conv } : {}) } });
  expect(r.statusCode).toBeLessThan(300);
  return r.json();
}

/** The fake mail server: what was verified and sent. */
function fakeMail(opts: { failLogin?: boolean } = {}) {
  const sent: Array<[string, string]> = [];
  const verified: string[] = [];
  const factory = (url: string): Mailer => ({
    send: async (to, subject) => void sent.push([to, subject]),
    verify: async () => {
      if (opts.failLogin) throw new Error("535 Username and Password not accepted");
      verified.push(url);
    },
  });
  return { sent, verified, factory };
}

describe("the relay's email account", () => {
  it("is set by the relay key or a phone, logs in first, guesses the server, never returns the password", async () => {
    const mail = fakeMail();
    b = await setup({}, { mailerFactory: mail.factory });
    const nubi = await addAgent("nubi", "Nubi");
    const dev = await pairDevice(b);
    const put = (headers: Record<string, string>, payload: object) =>
      b!.app.inject({ method: "PUT", url: "/v1/settings/email", headers, payload });

    expect((await put(nubi, { address: "bots.hive@gmail.com", password: "x" })).statusCode).toBe(403);
    expect((await put(AGENT, { address: "nope", password: "x" })).statusCode).toBe(400);
    expect((await put(AGENT, { address: "me@unknown-host.example", password: "x" })).json().error.message).toMatch(/server/);

    const r = await put(dev.headers, { address: "bots.hive@gmail.com", password: "abcd efgh ijkl mnop" });
    expect(r.statusCode).toBe(200);
    expect(mail.verified).toEqual(["smtps://bots.hive%40gmail.com:abcd%20efgh%20ijkl%20mnop@smtp.gmail.com:465"]);
    expect(r.json().email).toEqual({ configured: true, address: "bots.hive@gmail.com", server: "smtp.gmail.com:465" });
    const view = (await b.app.inject({ method: "GET", url: "/v1/settings/delivery", headers: AGENT })).body;
    expect(view).not.toContain("mnop");
    expect(view).toContain("ssh-ed25519 ");
    expect((await b.app.inject({ method: "GET", url: "/v1/status", headers: AGENT })).json().features.wake_email).toBe(true);

    // Another provider, by host:port: 587 is STARTTLS.
    await put(AGENT, { address: "me@example.org", password: "p", server: "mail.example.org:587" });
    expect(mail.verified.at(-1)).toBe("smtp://me%40example.org:p@mail.example.org:587");

    // Off, even when the environment has one.
    const prev = process.env.HIVEPAL_SMTP_URL;
    process.env.HIVEPAL_SMTP_URL = "smtps://env%40example.com:p@smtp.example.com:465";
    try {
      expect((await put(AGENT, { address: null })).json().email).toEqual({ configured: false });
      expect((await b.app.inject({ method: "GET", url: "/v1/status", headers: AGENT })).json().features.wake_email).toBe(false);
    } finally {
      if (prev === undefined) delete process.env.HIVEPAL_SMTP_URL;
      else process.env.HIVEPAL_SMTP_URL = prev;
    }
  });

  it("a wrong password fails when saving, not at the first wake-up email", async () => {
    b = await setup({}, { mailerFactory: fakeMail({ failLogin: true }).factory });
    const r = await b.app.inject({ method: "PUT", url: "/v1/settings/email", headers: AGENT, payload: { address: "a@gmail.com", password: "bad" } });
    expect(r.statusCode).toBe(502);
    expect(r.json().error).toMatchObject({ code: "email_failed" });
    expect(r.json().error.message).toMatch(/535/);
    expect(b.ctx.settings!.email().configured).toBe(false);
  });

  it("a phone sets an agent's wake-up address and sends a test", async () => {
    const mail = fakeMail();
    b = await setup({}, { mailerFactory: mail.factory });
    await addAgent("nubi", "Nubi");
    const dev = await pairDevice(b);
    await b.app.inject({ method: "PUT", url: "/v1/settings/email", headers: AGENT, payload: { address: "bots@gmail.com", password: "p" } });
    const w = await b.app.inject({ method: "PUT", url: "/v1/agents/nubi/wake", headers: dev.headers, payload: { email: "nubi@mail.example.com" } });
    expect(w.json().wakes).toBe(true);
    expect((await b.app.inject({ method: "POST", url: "/v1/agents/nubi/wake/test", headers: dev.headers })).statusCode).toBe(204);
    expect(mail.sent).toEqual([["nubi@mail.example.com", "Hivepal: nubi"]]);
  });
});

describe("mailboxes", () => {
  it("inbox for what it receives, history with both sides, a real git clone over HTTP, only for itself", async () => {
    b = await setup({}, { mailbox: { flushDelayMs: 60_000 } });
    const nubi = await addAgent("nubi", "Nubi");
    const coach = await addAgent("coach", "Coach");
    const dev = await pairDevice(b);
    await b.app.inject({ method: "PUT", url: "/v1/settings/memory", headers: dev.headers, payload: { markdown: true, sqlite: false } });
    const on = await b.app.inject({ method: "PUT", url: "/v1/agents/nubi/mailbox", headers: dev.headers, payload: { enabled: true } });
    expect(on.json().mailbox).toBe(true);
    expect((await b.app.inject({ method: "PUT", url: "/v1/agents/nubi/mailbox", headers: coach, payload: { enabled: true } })).statusCode).toBe(403);

    await say(dev.headers, "Gym at 18:00?", "agent:nubi");
    const r = await b.app.inject({ method: "POST", url: "/v1/messages", headers: nubi, payload: { text: "Sure. Remind you at 17:30?", options: ["Yes", "No"] } });
    expect(r.statusCode).toBeLessThan(300);
    await say(coach, "Stretch first", "bots:coach.nubi");
    await b.ctx.mailbox!.flush();

    // A real clone, as an agent would.
    await b.app.listen({ port: 0, host: "127.0.0.1" });
    const port = (b.app.server.address() as { port: number }).port;
    const key = nubi.authorization.slice(7);
    const out = tmp();
    // Not execFileSync: the relay answering the clone runs in this process.
    await promisify(execFile)("git", ["clone", "-q", `http://x:${key}@127.0.0.1:${port}/v1/agents/nubi/mailbox.git`, "nubi"], { cwd: out, timeout: 30_000 });
    const inbox = readdirSync(join(out, "nubi", "inbox"));
    expect(inbox).toHaveLength(2); // the user's and coach's, not its own
    const userMsg = readFileSync(join(out, "nubi", "inbox", inbox.find((f) => f.includes("-user-"))!), "utf8");
    expect(userMsg).toContain('conv: "agent:nubi"');

    const today = readdirSync(join(out, "nubi", "history", "user"))[0]!;
    const hist = readFileSync(join(out, "nubi", "history", "user", today), "utf8");
    expect(hist).toMatch(/· User\*\*[^\n]*\n\nGym at 18:00\?/);
    expect(hist).toMatch(/· Nubi\*\*[^\n]*\n\nSure. Remind you at 17:30\?\n\nQuick replies offered: Yes · No/);
    expect(readdirSync(join(out, "nubi", "history"))).toContain("with-coach");

    // Without credentials git is asked for them; another agent's key is refused; a phone may read it.
    const anon = await fetch(`http://127.0.0.1:${port}/v1/agents/nubi/mailbox.git/info/refs`);
    expect(anon.status).toBe(401);
    expect(anon.headers.get("www-authenticate")).toMatch(/Basic/);
    const other = await fetch(`http://127.0.0.1:${port}/v1/agents/nubi/mailbox.git/info/refs`, { headers: coach });
    expect(other.status).toBe(403);
    expect((await fetch(`http://127.0.0.1:${port}/v1/agents/nubi/mailbox.git/info/refs`, { headers: dev.headers })).status).toBe(200);
    expect((await fetch(`http://127.0.0.1:${port}/v1/agents/nubi/mailbox.git/config`, { headers: nubi })).status).toBe(404);
    expect((await fetch(`http://127.0.0.1:${port}/v1/agents/nubi/mailbox.git/..%2F..%2Fdb`, { headers: nubi })).status).toBe(404);
  });

  it("turning it on writes the history so far, without inbox files", async () => {
    b = await setup({}, { mailbox: { flushDelayMs: 60_000 } });
    const nubi = await addAgent("nubi", "Nubi");
    const dev = await pairDevice(b);
    await say(dev.headers, "old question", "agent:nubi");
    await say(nubi, "old answer");
    await b.app.inject({ method: "PUT", url: "/v1/settings/memory", headers: AGENT, payload: { markdown: true, sqlite: false } });
    await b.app.inject({ method: "PUT", url: "/v1/agents/nubi/mailbox", headers: AGENT, payload: { enabled: true } });
    await b.ctx.mailbox!.flush();
    const root = join(b.ctx.mailbox!.localRoot, "nubi");
    expect(existsSync(join(root, "inbox"))).toBe(false);
    const day = readdirSync(join(root, "history", "user"))[0]!;
    const hist = readFileSync(join(root, "history", "user", day), "utf8");
    expect(hist).toContain("old question");
    expect(hist).toContain("old answer");
    expect(git(root, "log", "--oneline").trim().split("\n")).toHaveLength(1);
  });

  it("pushes to an external repository, rebasing over what the agents pushed meanwhile", async () => {
    b = await setup({}, { mailbox: { flushDelayMs: 60_000, allowFileRepos: true } });
    const nubi = await addAgent("nubi", "Nubi");
    const dev = await pairDevice(b);
    const remote = join(tmp(), "notes.git");
    git(tmpdir(), "init", "-q", "--bare", "-b", "main", remote);
    // The agents' own notes are already there.
    const work = tmp();
    git(work, "clone", "-q", remote, "w");
    writeFileSync(join(work, "w", "notes.md"), "mine\n");
    git(join(work, "w"), "add", "-A");
    git(join(work, "w"), "commit", "-q", "-m", "notes");
    git(join(work, "w"), "push", "-q", "origin", "HEAD:main");

    expect((await b.app.inject({ method: "PUT", url: "/v1/settings/mailbox-repo", headers: AGENT, payload: { url: "/etc/passwd" } })).statusCode).toBe(400);
    expect((await b.app.inject({ method: "PUT", url: "/v1/settings/mailbox-repo", headers: AGENT, payload: { url: "--upload-pack=x" } })).statusCode).toBe(400);
    const set = await b.app.inject({ method: "PUT", url: "/v1/settings/mailbox-repo", headers: dev.headers, payload: { url: `file://${remote}` } });
    expect(set.json().mailbox_repo).toMatchObject({ url: `file://${remote}`, public_key: expect.stringMatching(/^ssh-ed25519 /) });
    expect((await b.app.inject({ method: "POST", url: "/v1/settings/mailbox-repo/test", headers: AGENT })).statusCode).toBe(204);
    await b.app.inject({ method: "PUT", url: "/v1/settings/memory", headers: AGENT, payload: { markdown: true, sqlite: false } });
    await b.app.inject({ method: "PUT", url: "/v1/agents/nubi/mailbox", headers: AGENT, payload: { enabled: true } });

    await say(dev.headers, "first", "agent:nubi");
    await b.ctx.mailbox!.flush();
    // The agent pushes its own work meanwhile; the relay's next push must rebase over it.
    git(join(work, "w"), "pull", "-q", "--rebase", "origin", "main");
    writeFileSync(join(work, "w", "notes.md"), "mine, more\n");
    git(join(work, "w"), "commit", "-qam", "more notes");
    git(join(work, "w"), "push", "-q", "origin", "HEAD:main");
    await say(nubi, "second");
    await b.ctx.mailbox!.flush();

    expect(b.ctx.mailbox!.repoStatus()).toMatchObject({ last_push_at: expect.any(Number) });
    expect(b.ctx.mailbox!.repoStatus().last_error).toBeUndefined();
    const check = tmp();
    git(check, "clone", "-q", remote, "c");
    expect(readFileSync(join(check, "c", "notes.md"), "utf8")).toBe("mine, more\n");
    const day = readdirSync(join(check, "c", "mailbox", "nubi", "history", "user"))[0]!;
    const hist = readFileSync(join(check, "c", "mailbox", "nubi", "history", "user", day), "utf8");
    expect(hist).toContain("first");
    expect(hist).toContain("second");
    expect(readdirSync(join(check, "c", "mailbox", "nubi", "inbox"))).toHaveLength(1);
    expect((await b.app.inject({ method: "GET", url: "/v1/status", headers: AGENT })).json().features.mailbox_repo).toBe(true);
  });

  it("an agent with both gets its wake-up email after its files are written", async () => {
    const mail = fakeMail();
    b = await setup({}, { mailerFactory: mail.factory, mailbox: { flushDelayMs: 60_000 } });
    await addAgent("nubi", "Nubi");
    await addAgent("coach", "Coach");
    const dev = await pairDevice(b);
    await b.app.inject({ method: "PUT", url: "/v1/settings/email", headers: AGENT, payload: { address: "bots@gmail.com", password: "p" } });
    b.ctx.agents.setWake("nubi", "nubi@mail.example.com");
    b.ctx.agents.setWake("coach", "coach@mail.example.com");
    await b.app.inject({ method: "PUT", url: "/v1/agents/nubi/mailbox", headers: AGENT, payload: { enabled: true } });

    await say(dev.headers, "hi nubi", "agent:nubi");
    await say(dev.headers, "hi coach", "agent:coach");
    await new Promise((r) => setTimeout(r, 20));
    expect(mail.sent).toEqual([["coach@mail.example.com", "Hivepal: coach"]]); // no mailbox: at once
    await b.ctx.mailbox!.flush();
    expect(mail.sent).toContainEqual(["nubi@mail.example.com", "Hivepal: nubi"]);
    expect(readdirSync(join(b.ctx.mailbox!.localRoot, "nubi", "inbox"))).toHaveLength(1);
  });

  it("without Markdown memory only the inbox is written; mailboxes can be on for every agent", async () => {
    b = await setup({}, { mailbox: { flushDelayMs: 60_000 } });
    const nubi = await addAgent("nubi", "Nubi");
    await addAgent("coach", "Coach");
    const dev = await pairDevice(b);
    const set = await b.app.inject({ method: "PUT", url: "/v1/settings/mailboxes", headers: dev.headers, payload: { all_agents: true } });
    expect(set.json()).toMatchObject({ mailboxes: { all_agents: true }, memory: { markdown: false, sqlite: false } });
    expect((await b.app.inject({ method: "GET", url: "/v1/agents", headers: AGENT })).json().agents.filter((a: { mailbox?: boolean }) => a.mailbox).length).toBeGreaterThanOrEqual(2);
    // Off for coach only; null goes back to the default.
    expect((await b.app.inject({ method: "PUT", url: "/v1/agents/coach/mailbox", headers: AGENT, payload: { enabled: false } })).json().mailbox).toBeUndefined();
    await say(dev.headers, "hello", "agent:nubi");
    await say(nubi, "hi!");
    await say(dev.headers, "hey coach", "agent:coach");
    await b.ctx.mailbox!.flush();
    const root = b.ctx.mailbox!.localRoot;
    expect(readdirSync(join(root, "nubi", "inbox"))).toHaveLength(1);
    expect(existsSync(join(root, "nubi", "history"))).toBe(false);
    expect(existsSync(join(root, "coach"))).toBe(false);
    expect((await b.app.inject({ method: "PUT", url: "/v1/agents/coach/mailbox", headers: AGENT, payload: { enabled: null } })).json().mailbox).toBe(true);
  });
});

describe("memory as SQLite", () => {
  it("an agent searches its own conversations and downloads them; off means 404", async () => {
    b = await setup();
    const nubi = await addAgent("nubi", "Nubi");
    const coach = await addAgent("coach", "Coach");
    const dev = await pairDevice(b);
    await say(dev.headers, "Gym at 18:00 on Friday", "agent:nubi");
    await say(nubi, "Noted: gym Friday 18:00");
    await say(dev.headers, "Secret plans for coach's gym", "agent:coach");
    await say(coach, "Stretch before the gym", "bots:coach.nubi");
    const q = (headers: Record<string, string>, query: string, id = "nubi") =>
      b!.app.inject({ method: "GET", url: `/v1/agents/${id}/memory?${query}`, headers });

    expect((await q(nubi, "q=gym")).statusCode).toBe(404); // off
    await b.app.inject({ method: "PUT", url: "/v1/settings/memory", headers: dev.headers, payload: { markdown: false, sqlite: true } });
    const r = (await q(nubi, "q=gym")).json().messages as Array<{ text: string; from: string; from_name: string }>;
    expect(r.map((m) => m.text)).toEqual(["Stretch before the gym", "Noted: gym Friday 18:00", "Gym at 18:00 on Friday"]);
    expect(r[0]).toMatchObject({ from: "coach", from_name: "Coach" });
    expect((await q(nubi, "q=friday%20gym&conv=agent:nubi&limit=1")).json().messages).toHaveLength(1);
    expect((await q(nubi, `q=${encodeURIComponent('gym" OR secret')}`)).json().messages).toHaveLength(0); // no query syntax
    expect((await q(nubi, "")).json().messages).toHaveLength(3);
    expect((await q(coach, "q=gym")).statusCode).toBe(403);
    expect((await q(coach, "q=gym", "coach")).json().messages).toHaveLength(2);
    expect((await q(dev.headers, "q=gym")).statusCode).toBe(200);

    // The file: its own conversations only, searchable.
    const file = await b.app.inject({ method: "GET", url: "/v1/agents/nubi/memory.sqlite", headers: nubi });
    expect(file.statusCode).toBe(200);
    const path = join(tmp(), "m.sqlite");
    writeFileSync(path, file.rawPayload);
    const { DatabaseSync } = await import("node:sqlite");
    const m = new DatabaseSync(path, { readOnly: true });
    expect((m.prepare("SELECT count(*) AS n FROM messages").get() as { n: number }).n).toBe(3);
    expect((m.prepare("SELECT m.text FROM messages_fts f JOIN messages m ON m.rowid = f.rowid WHERE messages_fts MATCH 'friday' ORDER BY m.created_at").all() as Array<{ text: string }>).map((x) => x.text))
      .toEqual(["Gym at 18:00 on Friday", "Noted: gym Friday 18:00"]);
    m.close();
  });
});


describe("errors people can act on", () => {
  it("a mail server that can't be reached says so in words", async () => {
    const { mailError } = await import("../src/routes/delivery.js");
    // What nodemailer's verify() throws (seen with a closed port, and an unknown host).
    const refused = Object.assign(new AggregateError([Object.assign(new Error("connect ECONNREFUSED 127.0.0.1:1026"), { code: "ECONNREFUSED" })], ""), { code: "ESOCKET", command: "CONN" });
    expect(mailError(Object.assign(new Error("getaddrinfo ENOTFOUND x.invalid"), { code: "EDNS", originalCode: "ENOTFOUND" }), "smtps://x.invalid:465")).toMatch(/find the server x\.invalid/);
    expect(mailError(refused, "smtp://localhost:1026")).toMatch(/Couldn't connect to localhost:1026/);
    expect(mailError(Object.assign(new Error("getaddrinfo ENOTFOUND smtp.nope.test"), { code: "ENOTFOUND" }), "smtps://smtp.nope.test:465")).toMatch(/find the server smtp\.nope\.test/);
    expect(mailError(Object.assign(new Error("535 bad credentials"), { code: "EAUTH", responseCode: 535 }), "smtps://smtp.gmail.com:465")).toMatch(/refused the address or password.*app password/);
    expect(mailError(new Error(""), "smtp://x:25")).not.toMatch(/said: $/);
  });

  it("a repository check that works clears the error of the one before", async () => {
    b = await setup({}, { mailbox: { flushDelayMs: 60_000, allowFileRepos: true } });
    const remote = join(tmp(), "notes.git");
    await b.app.inject({ method: "PUT", url: "/v1/settings/mailbox-repo", headers: AGENT, payload: { url: `file://${remote}` } });
    expect((await b.app.inject({ method: "POST", url: "/v1/settings/mailbox-repo/test", headers: AGENT })).statusCode).toBe(502);
    expect(b.ctx.mailbox!.repoStatus().last_error).toMatch(/clone failed/);
    git(tmpdir(), "init", "-q", "--bare", "-b", "main", remote);
    expect((await b.app.inject({ method: "POST", url: "/v1/settings/mailbox-repo/test", headers: AGENT })).statusCode).toBe(204);
    const view = (await b.app.inject({ method: "GET", url: "/v1/settings/delivery", headers: AGENT })).json();
    expect(view.mailbox_repo.last_error).toBeUndefined();
    // Another repository forgets the old one's error.
    await b.app.inject({ method: "PUT", url: "/v1/settings/mailbox-repo", headers: AGENT, payload: { url: `file://${remote}x` } });
    await b.app.inject({ method: "POST", url: "/v1/settings/mailbox-repo/test", headers: AGENT });
    expect(b.ctx.mailbox!.repoStatus().last_error).toBeDefined();
    await b.app.inject({ method: "PUT", url: "/v1/settings/mailbox-repo", headers: AGENT, payload: { url: `file://${remote}` } });
    expect(b.ctx.mailbox!.repoStatus().last_error).toBeUndefined();
  });
});

describe("the email password at rest", () => {
  it("is encrypted with a key in its own file; an old plain value is encrypted when read; another key can't read it", async () => {
    const { Settings, SecretBox } = await import("../src/settings.js");
    const { openDatabase } = await import("../src/db/database.js");
    const dir = tmp();
    const db = openDatabase(":memory:");
    const box = new SecretBox(join(dir, "secret_key"));
    const s = new Settings(db, box);
    const url = "smtps://bots%40gmail.com:app%20pass@smtp.gmail.com:465";
    s.setSmtpUrl(url);
    const raw = () => (db.prepare("SELECT value FROM meta WHERE key = 'setting:smtp_url'").get() as { value: string }).value;
    expect(raw()).toMatch(/^enc:v1:/);
    expect(raw()).not.toContain("app");
    expect(s.smtpUrl()).toBe(url);
    expect(s.email()).toMatchObject({ configured: true, address: "bots@gmail.com" });
    expect(statSync(join(dir, "secret_key")).mode & 0o777).toBe(0o600);
    // The same key file, read again (a restart), still opens it.
    expect(new Settings(db, new SecretBox(join(dir, "secret_key"))).smtpUrl()).toBe(url);
    // Saved by an older relay in plain text: it works, and is encrypted on the way.
    db.prepare("UPDATE meta SET value = ? WHERE key = 'setting:smtp_url'").run(url);
    expect(s.smtpUrl()).toBe(url);
    expect(raw()).toMatch(/^enc:v1:/);
    // The key file lost (a database restored elsewhere): not configured, so it is asked for again.
    const other = new Settings(db, new SecretBox(join(tmp(), "secret_key")));
    expect(other.smtpUrl()).toBeUndefined();
    expect(other.email().configured).toBe(false);
  });
});
