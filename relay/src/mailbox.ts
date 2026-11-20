/**
 * Hivepal: agents' mailboxes, written by the relay as Markdown files in git (issue #41).
 *
 * For an agent with a mailbox, every message in its conversations is written twice:
 * - `inbox/<date>-<time>-<sender>-<msg id>.md`, one file per message it receives (never its own), which
 *   it may move away once read (the relay never rewrites a file);
 * - `history/<conversation>/<YYYY-MM-DD>.md`, the day's conversation with both sides, its own replies
 *   included: its memory of Hivepal, when the relay keeps memory as Markdown (`Settings.memory`).
 *
 * Into two places: a git repository per agent kept in the relay (`DATA_DIR/mailboxes/<agent>`, served
 * read-only over git's dumb HTTP protocol), and, when one is set, an external repository the relay
 * clones and pushes to (`mailbox/<agent>/…` in it), with its own SSH deploy key. Writes go to disk at
 * once; commits and pushes are batched a few seconds later, and then `onFlushed` names the agents that
 * got a message from the user, so their wake-up email goes out after the files are there.
 *
 * git runs as a child process with an argv list; a repository URL is the user's, but only https://,
 * ssh:// and scp-like addresses are accepted, other transports are disabled and option-like values are
 * refused.
 */
import { execFile } from "node:child_process";
import { existsSync, mkdirSync, readFileSync, appendFileSync, writeFileSync, rmSync } from "node:fs";
import { join } from "node:path";
import type { FastifyBaseLogger } from "fastify";
import type { Agents } from "./agents.js";
import { parseConv } from "./agents.js";
import type { Db } from "./db/database.js";
import type { Event } from "./protocol.js";
import type { Settings } from "./settings.js";

const SAFE = /[^A-Za-z0-9_.-]+/g;
const GIT_TIMEOUT_MS = 120_000;

/** Why a repository URL can't be used, or null. */
export function repoUrlProblem(url: string, allowFile = false): string | null {
  const u = url.trim();
  if (!u || u.startsWith("-") || /\s/.test(u)) return "not a repository address";
  if (/^https:\/\/[^/]+\/.+/.test(u) || /^ssh:\/\/[^/]+\/.+/.test(u)) return null;
  if (/^[A-Za-z0-9._-]+@[A-Za-z0-9.-]+:[^/].*/.test(u)) return null; // git@github.com:me/notes.git
  if (allowFile && u.startsWith("file://")) return null;
  return "use an https://, ssh:// or git@host:path address";
}

interface MsgRow {
  msg_id: string;
  sender: "agent" | "user";
  agent_id: string | null;
  text: string;
  conv: string | null;
  reply_to: string | null;
  options: string | null;
  attachments: string | null;
  created_at: number;
}

const pad = (n: number) => String(n).padStart(2, "0");

/** Local time parts (the relay's TZ). */
function parts(ms: number) {
  const d = new Date(ms);
  const off = -d.getTimezoneOffset();
  const sign = off >= 0 ? "+" : "-";
  return {
    day: `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}`,
    compact: `${d.getFullYear()}${pad(d.getMonth() + 1)}${pad(d.getDate())}-${pad(d.getHours())}${pad(d.getMinutes())}`,
    time: `${pad(d.getHours())}:${pad(d.getMinutes())}`,
    iso: `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}T${pad(d.getHours())}:${pad(d.getMinutes())}:${pad(d.getSeconds())}${sign}${pad(Math.floor(Math.abs(off) / 60))}:${pad(Math.abs(off) % 60)}`,
  };
}

export interface GitRunner {
  (args: string[], cwd: string, env?: Record<string, string>): Promise<string>;
}

/** git's own message, without its hints, in one line. */
function gitError(stderr: string): string {
  const lines = stderr.split("\n").map((l) => l.trim()).filter((l) => l && !l.startsWith("hint:"));
  return lines.slice(-4).join(" ") || "git failed";
}

export const runGit: GitRunner = (args, cwd, env) =>
  new Promise((resolve, reject) => {
    execFile(
      "git",
      // Only these transports, whatever the URL or the repository's config say. LC_ALL=C: git's
      // messages in English whatever the host's language (the relay matches some of them).
      ["-c", "protocol.allow=never", "-c", "protocol.https.allow=always", "-c", "protocol.ssh.allow=always",
        "-c", "core.hooksPath=/dev/null", "-c", "commit.gpgsign=false", ...args],
      { cwd, timeout: GIT_TIMEOUT_MS, env: { ...process.env, GIT_TERMINAL_PROMPT: "0", LC_ALL: "C", LANGUAGE: "C", ...env }, maxBuffer: 4 << 20 },
      (err, stdout, stderr) => (err ? reject(new Error(gitError(stderr || err.message))) : resolve(stdout)),
    );
  });

export interface MailboxOptions {
  dataDir: string;
  db: Db;
  agents: Agents;
  settings: Settings;
  log: FastifyBaseLogger;
  /** Called after a batch is committed (and pushed): the agents that got a message from the user. */
  onFlushed?: (agentIds: string[]) => void;
  flushDelayMs?: number;
  git?: GitRunner;
  /** Tests: file:// repositories. */
  allowFileRepos?: boolean;
}

export interface RepoStatus {
  last_push_at?: number;
  last_error?: string;
}

export class Mailbox {
  private chain: Promise<unknown> = Promise.resolve();
  private timer?: NodeJS.Timeout;
  /** Agents with files written since the last flush, and those of them a user message should wake. */
  private dirty = new Set<string>();
  private toWake = new Set<string>();
  private status: RepoStatus = {};
  private readonly git: GitRunner;

  constructor(private readonly o: MailboxOptions) {
    const run = o.git ?? runGit;
    // Tests clone file:// repositories; nothing else may.
    this.git = o.allowFileRepos ? (args, cwd, env) => run(["-c", "protocol.file.allow=always", ...args], cwd, env) : run;
  }

  get allowsFileRepos(): boolean {
    return !!this.o.allowFileRepos;
  }

  get localRoot(): string {
    return join(this.o.dataDir, "mailboxes");
  }

  private get repoDir(): string {
    return join(this.o.dataDir, "mailbox-repo");
  }

  get keyFile(): string {
    return join(this.o.dataDir, "mailbox_deploy_key");
  }

  /** The agent's own repository's .git (served read-only), or undefined before its first message. */
  gitDir(agentId: string): string | undefined {
    const d = join(this.localRoot, agentId.replace(SAFE, "_"), ".git");
    return existsSync(d) ? d : undefined;
  }

  repoStatus(): RepoStatus {
    return { ...this.status };
  }

  /** Another repository (or none): what happened with the old one no longer applies. */
  repoChanged(): void {
    this.status = {};
  }

  /** The SSH public key the external repository must accept (with write access); created on first use. */
  async publicKey(): Promise<string> {
    if (!existsSync(this.keyFile)) {
      mkdirSync(this.o.dataDir, { recursive: true });
      await new Promise<void>((resolve, reject) =>
        execFile("ssh-keygen", ["-q", "-t", "ed25519", "-N", "", "-C", "hivepal-relay", "-f", this.keyFile], (e) => (e ? reject(e) : resolve())),
      );
    }
    return readFileSync(`${this.keyFile}.pub`, "utf8").trim();
  }

  private sshEnv(): Record<string, string> {
    const known = join(this.o.dataDir, "mailbox_known_hosts");
    return {
      GIT_SSH_COMMAND: `ssh -i ${this.keyFile} -o IdentitiesOnly=yes -o BatchMode=yes -o StrictHostKeyChecking=accept-new -o UserKnownHostsFile=${known}`,
    };
  }

  /** The agents a new message is written for (those with a mailbox that take part in its conversation). */
  recipients(e: Event): string[] {
    if (e.type !== "msg.new") return [];
    const c = parseConv(e.conv ?? "agent:main");
    const ids = c?.kind === "agent" ? [c.id] : c?.kind === "group" ? (this.o.agents.group(c.id)?.members ?? []) : c?.kind === "bots" ? [...c.pair] : [];
    return ids.filter((id) => this.o.agents.exists(id) && this.o.agents.hasMailbox(id));
  }

  /** Writes the message for its recipients; returns them (their wake-up email waits for the flush). */
  handle(e: Event): string[] {
    const ids = this.recipients(e);
    if (ids.length === 0 || e.type !== "msg.new") return [];
    const row = this.o.db.prepare("SELECT * FROM messages WHERE msg_id = ?").get(e.msg_id) as unknown as MsgRow | undefined;
    if (!row) return [];
    this.enqueue(async () => {
      const repo = await this.ensureRepo();
      for (const id of ids) {
        this.writeFor(id, row, true, this.localDir(id));
        if (repo) this.writeFor(id, row, true, join(repo, "mailbox", id.replace(SAFE, "_")));
        this.dirty.add(id);
        if (e.from === "user") this.toWake.add(id);
      }
    });
    this.schedule();
    return ids;
  }

  /** On turning a mailbox (or Markdown memory) on: the agent's history so far (no inbox files: those are new messages). */
  backfill(agentId: string, days = 90): void {
    if (!this.o.settings.memory().markdown) return;
    const since = Date.now() - days * 86_400_000;
    const rows = (this.o.db.prepare("SELECT * FROM messages WHERE created_at >= ? ORDER BY created_at, rowid").all(since) as unknown as MsgRow[])
      .filter((r) => this.o.agents.canSee(agentId, r.conv));
    if (rows.length === 0) return;
    this.enqueue(async () => {
      const repo = await this.ensureRepo();
      for (const r of rows) {
        this.writeFor(agentId, r, false, this.localDir(agentId));
        if (repo) this.writeFor(agentId, r, false, join(repo, "mailbox", agentId.replace(SAFE, "_")));
      }
      this.dirty.add(agentId);
    });
    this.schedule();
  }

  /** Commits and pushes now (tests, and before stopping). */
  flush(): Promise<void> {
    clearTimeout(this.timer);
    this.timer = undefined;
    return this.enqueue(() => this.doFlush());
  }

  /** Checks the external repository can be cloned with the deploy key (and resets the clone). */
  async testRepo(): Promise<void> {
    const r = this.o.settings.repo();
    if (!r) throw new Error("no repository set");
    await this.publicKey();
    await this.enqueue(async () => {
      rmSync(this.repoDir, { recursive: true, force: true });
      const dir = await this.ensureRepo(true);
      if (!dir) throw new Error(this.status.last_error ?? "couldn't clone it");
      // Pushing is what matters: an empty commit-less push checks write access without changing anything.
      await this.git(["push", "--dry-run", "origin", `HEAD:${await this.branch(dir)}`], dir, this.sshEnv()).catch(async (err: Error) => {
        // An empty repository has no HEAD to push yet: that's fine, the first message creates it.
        if (!/does not match any|src refspec/i.test(err.message)) throw err;
      });
      this.status = { ...this.status, last_error: undefined };
    });
  }

  stop(): void {
    clearTimeout(this.timer);
  }

  // ---------- internals ----------

  private enqueue<T>(job: () => Promise<T> | T): Promise<T> {
    const p = this.chain.then(job, job);
    this.chain = p.catch((err: unknown) => this.o.log.warn({ err }, "mailbox job failed"));
    return p;
  }

  private schedule(): void {
    if (this.timer) return;
    this.timer = setTimeout(() => {
      this.timer = undefined;
      void this.enqueue(() => this.doFlush());
    }, this.o.flushDelayMs ?? 3000);
    this.timer.unref?.();
  }

  private localDir(agentId: string): string {
    const dir = join(this.localRoot, agentId.replace(SAFE, "_"));
    if (!existsSync(join(dir, ".git"))) {
      mkdirSync(dir, { recursive: true });
      writeFileSync(join(dir, "README.md"), README(agentId));
    }
    return dir;
  }

  private async ensureLocalRepo(dir: string): Promise<void> {
    if (existsSync(join(dir, ".git"))) return;
    await this.git(["init", "-q", "-b", "main"], dir);
    await this.git(["config", "user.name", "Hivepal relay"], dir);
    await this.git(["config", "user.email", "relay@hivepal.invalid"], dir);
  }

  /** The external clone, cloned (again) when the setting changed; undefined when none is set or it failed. */
  private async ensureRepo(force = false): Promise<string | undefined> {
    const r = this.o.settings.repo();
    if (!r) return undefined;
    const dir = this.repoDir;
    const mark = join(this.o.dataDir, "mailbox-repo.url");
    const want = `${r.url}#${r.branch ?? ""}`;
    if (!force && existsSync(join(dir, ".git")) && existsSync(mark) && readFileSync(mark, "utf8") === want) return dir;
    const problem = repoUrlProblem(r.url, this.o.allowFileRepos);
    if (problem) {
      this.status = { ...this.status, last_error: problem };
      return undefined;
    }
    rmSync(dir, { recursive: true, force: true });
    try {
      await this.publicKey();
      await this.git(["-c", "init.defaultBranch=main", "clone", "-q", ...(r.branch ? ["--branch", r.branch] : []), "--", r.url, dir], this.o.dataDir, this.sshEnv());
    } catch (err) {
      // A branch that doesn't exist yet: clone the default one and create it.
      if (r.branch && /not found|Remote branch/i.test(String(err))) {
        await this.git(["-c", "init.defaultBranch=main", "clone", "-q", "--", r.url, dir], this.o.dataDir, this.sshEnv()).catch(() => undefined);
        if (existsSync(join(dir, ".git"))) await this.git(["checkout", "-q", "-B", r.branch], dir);
      }
      if (!existsSync(join(dir, ".git"))) {
        this.status = { ...this.status, last_error: `clone failed: ${err instanceof Error ? err.message : String(err)}` };
        return undefined;
      }
    }
    await this.git(["config", "user.name", "Hivepal relay"], dir);
    await this.git(["config", "user.email", "relay@hivepal.invalid"], dir);
    writeFileSync(mark, want);
    this.status = { ...this.status, last_error: undefined };
    return dir;
  }

  private async branch(dir: string): Promise<string> {
    const r = this.o.settings.repo();
    if (r?.branch) return r.branch;
    return (await this.git(["symbolic-ref", "--short", "HEAD"], dir)).trim() || "main";
  }

  private async doFlush(): Promise<void> {
    const agents = [...this.dirty];
    const wake = [...this.toWake];
    this.dirty.clear();
    this.toWake.clear();
    if (agents.length === 0) return;
    const message = `Hivepal: ${agents.join(", ")}`;
    for (const id of agents) {
      const dir = this.localDir(id);
      try {
        await this.ensureLocalRepo(dir);
        await this.git(["add", "-A"], dir);
        if ((await this.git(["status", "--porcelain"], dir)).trim()) await this.git(["commit", "-q", "-m", message], dir);
        await this.git(["update-server-info"], dir);
      } catch (err) {
        this.o.log.warn({ err, agent: id }, "mailbox commit failed");
      }
    }
    const repo = this.o.settings.repo() ? this.repoDir : undefined;
    if (repo && existsSync(join(repo, ".git"))) {
      try {
        await this.git(["add", "-A", "mailbox"], repo);
        if ((await this.git(["status", "--porcelain", "mailbox"], repo)).trim()) await this.git(["commit", "-q", "-m", message], repo);
        await this.push(repo);
        this.status = { last_push_at: Date.now() };
      } catch (err) {
        this.status = { ...this.status, last_error: `push failed: ${err instanceof Error ? err.message : String(err)}` };
        this.o.log.warn({ err }, "mailbox push failed");
      }
    }
    if (wake.length) this.o.onFlushed?.(wake);
  }

  /** Pushes; when the remote moved on (the agents write there too), rebases onto it and tries again. */
  private async push(dir: string): Promise<void> {
    const branch = await this.branch(dir);
    const env = this.sshEnv();
    for (let attempt = 0; ; attempt++) {
      try {
        await this.git(["push", "-q", "origin", `HEAD:${branch}`], dir, env);
        return;
      } catch (err) {
        if (attempt >= 2 || !/rejected|fetch first|non-fast-forward/i.test(String(err))) throw err;
        // Ours win on a conflict: the relay only writes its own files.
        await this.git(["pull", "-q", "--rebase", "-X", "theirs", "origin", branch], dir, env).catch(async (e: Error) => {
          await this.git(["rebase", "--abort"], dir).catch(() => undefined);
          throw e;
        });
      }
    }
  }

  private name(id: string): string {
    return this.o.agents.get(id)?.name ?? id;
  }

  private convDir(conv: string, me: string): { dir: string; title: string } {
    const c = parseConv(conv);
    if (c?.kind === "group") return { dir: `group-${c.id}`, title: `Group ${this.o.agents.group(c.id)?.name ?? c.id}` };
    if (c?.kind === "bots") {
      const other = c.pair.find((p) => p !== me) ?? me;
      return { dir: `with-${other}`, title: `With ${this.name(other)}` };
    }
    return { dir: "user", title: "With the user" };
  }

  private writeFor(me: string, r: MsgRow, inbox: boolean, root: string): void {
    const conv = r.conv ?? "agent:main";
    const sender = r.sender === "user" ? "user" : (r.agent_id ?? "main");
    const senderName = sender === "user" ? "User" : this.name(sender);
    const t = parts(r.created_at);
    const quoted = r.reply_to
      ? (this.o.db.prepare("SELECT text FROM messages WHERE msg_id = ?").get(r.reply_to) as { text: string } | undefined)?.text
      : undefined;
    const extras: string[] = [];
    if (quoted !== undefined) extras.push(`> In reply to: ${(quoted.length > 200 ? quoted.slice(0, 200) + "…" : quoted).replace(/\n/g, "\n> ")}`);
    const options = r.options ? (JSON.parse(r.options) as string[]) : [];
    if (options.length) extras.push(`Quick replies offered: ${options.join(" · ")}`);
    const attachments = r.attachments ? (JSON.parse(r.attachments) as Array<{ file_id: string; mime?: string }>) : [];
    if (attachments.length)
      extras.push(["Attachments (download with `hivepal file <file_id>`):", ...attachments.map((a) => `- ${String(a.mime ?? "file").split("/")[0]}: \`${a.file_id}\``)].join("\n"));
    const body = [r.text, ...extras].filter(Boolean).join("\n\n");

    // The day's conversation, both sides, appended in order: the agent's memory, when it's kept as Markdown.
    if (this.o.settings.memory().markdown) this.appendHistory(me, conv, r, t, senderName, body, root);

    // One file per received message.
    this.writeInbox(me, conv, r, t, sender, senderName, body, inbox, root);
  }

  private appendHistory(me: string, conv: string, r: MsgRow, t: ReturnType<typeof parts>, senderName: string, body: string, root: string): void {
    const { dir, title } = this.convDir(conv, me);
    const histDir = join(root, "history", dir);
    mkdirSync(histDir, { recursive: true });
    const hist = join(histDir, `${t.day}.md`);
    if (!existsSync(hist)) writeFileSync(hist, `# ${title}, ${t.day}\n\n`);
    const already = readFileSync(hist, "utf8").includes(`<!-- ${r.msg_id} -->`);
    if (!already) appendFileSync(hist, `**${t.time} · ${senderName}** <!-- ${r.msg_id} -->\n\n${body}\n\n`);
  }

  private writeInbox(me: string, conv: string, r: MsgRow, t: ReturnType<typeof parts>, sender: string, senderName: string, body: string, inbox: boolean, root: string): void {
    if (!inbox || sender === me) return;
    const inDir = join(root, "inbox");
    mkdirSync(inDir, { recursive: true });
    const file = join(inDir, `${t.compact}-${sender.replace(SAFE, "_")}-${r.msg_id.replace(SAFE, "_")}.md`);
    if (existsSync(file)) return;
    const y = (s: string) => JSON.stringify(s);
    const head = ["---", `msg_id: ${r.msg_id}`, `from: ${y(sender)}`, `from_name: ${y(senderName)}`, `to: ${y(me)}`, `conv: ${y(conv)}`];
    if (conv.startsWith("group:")) head.push(`group: ${y(this.o.agents.group(conv.slice(6))?.name ?? conv.slice(6))}`);
    head.push(`date: ${t.iso}`);
    if (r.reply_to) head.push(`reply_to: ${r.reply_to}`);
    head.push("---");
    writeFileSync(file, `${head.join("\n")}\n\n${body}\n`);
  }
}

const README = (agentId: string) => `# Hivepal mailbox: ${agentId}

Written by the Hivepal relay. Don't edit \`history/\`: the relay appends to it.

- \`inbox/\`: one file per message you received. Move or delete them once handled.
- \`history/<conversation>/<YYYY-MM-DD>.md\` (when the relay keeps your memory as Markdown): each day's
  conversation, both sides. Read it to remember what was said.

Answer with \`hivepal msg\` (or the relay's API), never by writing here.
`;
