import { afterEach, describe, expect, it } from "vitest";
import { mkdtempSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { MIGRATIONS_FOR_TEST, openDatabase } from "../src/db/database.js";
import { AGENT, pairDevice, setup } from "./helpers.js";

type Built = Awaited<ReturnType<typeof setup>>;
let b: Built | undefined;
afterEach(async () => {
  await b?.app.close();
  b = undefined;
});

const bearer = (key: string) => ({ authorization: `Bearer ${key}` });

async function addAgent(app: Built["app"], agent_id: string, name: string): Promise<string> {
  const r = await app.inject({ method: "POST", url: "/v1/agents", headers: AGENT, payload: { agent_id, name } });
  expect(r.statusCode).toBe(201);
  return r.json().api_key as string;
}

async function events(app: Built["app"], headers: Record<string, string>) {
  const r = await app.inject({ method: "GET", url: "/v1/events?after_seq=0&limit=500", headers });
  expect(r.statusCode).toBe(200);
  return r.json().events as Array<Record<string, unknown>>;
}

describe("multi-agent: agents with their own keys", () => {
  it("lists main first; a new agent gets a working key that only it uses", async () => {
    b = await setup();
    const nubi = await addAgent(b.app, "nubi", "Nubi");
    expect(nubi).toMatch(/^ak_/);

    const list = await b.app.inject({ method: "GET", url: "/v1/agents", headers: AGENT });
    expect(list.json().agents.map((a: { agent_id: string; name: string }) => [a.agent_id, a.name])).toEqual([
      ["main", "TestAgent"],
      ["nubi", "Nubi"],
    ]);

    const st = await b.app.inject({ method: "GET", url: "/v1/status", headers: bearer(nubi) });
    expect(st.statusCode).toBe(200);
    const seen = (await b.app.inject({ method: "GET", url: "/v1/agents", headers: AGENT })).json().agents;
    expect(seen.find((a: { agent_id: string }) => a.agent_id === "nubi").last_seen_at).toBeGreaterThan(0);
    expect(st.json().self).toEqual({ agent_id: "nubi", name: "Nubi" });
    expect(st.json().agent_name).toBe("Nubi");

    expect((await b.app.inject({ method: "POST", url: "/v1/agents", headers: AGENT, payload: { agent_id: "nubi", name: "x" } })).statusCode).toBe(409);
    expect((await b.app.inject({ method: "GET", url: "/v1/status", headers: bearer("ak_nope") })).statusCode).toBe(401);
  });

  it("an agent's own key can't pair phones or manage agents", async () => {
    b = await setup();
    const nubi = await addAgent(b.app, "nubi", "Nubi");
    expect((await b.app.inject({ method: "POST", url: "/v1/pairing-tokens", headers: bearer(nubi) })).statusCode).toBe(403);
    expect(
      (await b.app.inject({ method: "POST", url: "/v1/agents", headers: bearer(nubi), payload: { agent_id: "x", name: "X" } })).statusCode,
    ).toBe(403);
    expect(
      (await b.app.inject({ method: "PATCH", url: "/v1/agents/main", headers: bearer(nubi), payload: { name: "Hacked" } })).statusCode,
    ).toBe(403);
  });

  it("the user renames agents from the phone, main included; removing revokes the key", async () => {
    b = await setup();
    const nubi = await addAgent(b.app, "nubi", "Nubi");
    const dev = await pairDevice(b);
    const r = await b.app.inject({ method: "PATCH", url: "/v1/agents/nubi", headers: dev.headers, payload: { name: "Nubi ✨" } });
    expect(r.statusCode).toBe(200);
    expect(r.json().name).toBe("Nubi ✨");
    await b.app.inject({ method: "PATCH", url: "/v1/agents/main", headers: dev.headers, payload: { name: "Centralita" } });
    const st = await b.app.inject({ method: "GET", url: "/v1/status", headers: dev.headers });
    expect(st.json().agent_name).toBe("Centralita");
    const evs = await events(b.app, dev.headers);
    expect(evs.filter((e) => e.type === "agent.updated").map((e) => e.name)).toEqual(["Nubi", "Nubi ✨", "Centralita"]);

    expect((await b.app.inject({ method: "DELETE", url: "/v1/agents/nubi", headers: AGENT })).statusCode).toBe(204);
    expect((await b.app.inject({ method: "GET", url: "/v1/status", headers: bearer(nubi) })).statusCode).toBe(401);
    expect((await b.app.inject({ method: "DELETE", url: "/v1/agents/main", headers: AGENT })).statusCode).toBe(403);
  });

  it("rotating a key stops the old one", async () => {
    b = await setup();
    const old = await addAgent(b.app, "nubi", "Nubi");
    const r = await b.app.inject({ method: "POST", url: "/v1/agents/nubi/key", headers: AGENT });
    expect(r.statusCode).toBe(200);
    expect((await b.app.inject({ method: "GET", url: "/v1/status", headers: bearer(old) })).statusCode).toBe(401);
    expect((await b.app.inject({ method: "GET", url: "/v1/status", headers: bearer(r.json().api_key) })).statusCode).toBe(200);
  });
});

describe("multi-agent: conversations", () => {
  it("each agent writes in its own conversation and only sees it", async () => {
    b = await setup();
    const nubi = await addAgent(b.app, "nubi", "Nubi");
    const rutina = await addAgent(b.app, "rutina", "Rutina");
    const dev = await pairDevice(b);

    const m1 = await b.app.inject({ method: "POST", url: "/v1/messages", headers: bearer(nubi), payload: { text: "hola de Nubi" } });
    expect(m1.statusCode).toBe(201);
    expect(m1.json()).toMatchObject({ conv: "agent:nubi", agent_id: "nubi", from: "agent" });
    await b.app.inject({ method: "POST", url: "/v1/messages", headers: bearer(rutina), payload: { text: "hola de Rutina" } });
    await b.app.inject({ method: "POST", url: "/v1/messages", headers: AGENT, payload: { text: "hola de main" } });
    const u = await b.app.inject({ method: "POST", url: "/v1/messages", headers: dev.headers, payload: { text: "para Nubi", conv: "agent:nubi" } });
    expect(u.json()).toMatchObject({ conv: "agent:nubi", from: "user" });
    const old = await b.app.inject({ method: "POST", url: "/v1/messages", headers: dev.headers, payload: { text: "sin conv" } });
    expect(old.json().conv).toBe("agent:main");

    const seen = async (h: Record<string, string>) =>
      (await b!.app.inject({ method: "GET", url: "/v1/messages", headers: h })).json().messages.map((m: { text: string }) => m.text);
    expect(await seen(bearer(nubi))).toEqual(["hola de Nubi", "para Nubi"]);
    expect(await seen(bearer(rutina))).toEqual(["hola de Rutina"]);
    expect(await seen(AGENT)).toEqual(["hola de main", "sin conv"]);
    expect(await seen(dev.headers)).toHaveLength(5);

    const evs = await events(b.app, bearer(rutina));
    expect(evs.filter((e) => e.type === "msg.new").map((e) => e.text)).toEqual(["hola de Rutina"]);

    // Other agents' messages are invisible, also one by one and for read receipts.
    const nubiMsg = m1.json().msg_id as string;
    expect((await b.app.inject({ method: "GET", url: `/v1/messages/${nubiMsg}`, headers: bearer(rutina) })).statusCode).toBe(404);
    expect((await b.app.inject({ method: "POST", url: `/v1/messages/${u.json().msg_id}/read`, headers: bearer(rutina) })).statusCode).toBe(404);
    expect((await b.app.inject({ method: "POST", url: `/v1/messages/${u.json().msg_id}/read`, headers: bearer(nubi) })).statusCode).toBe(204);

    // An agent can't write in someone else's conversation; the user can't write to nobody.
    expect(
      (await b.app.inject({ method: "POST", url: "/v1/messages", headers: bearer(rutina), payload: { text: "x", conv: "agent:nubi" } })).statusCode,
    ).toBe(403);
    expect(
      (await b.app.inject({ method: "POST", url: "/v1/messages", headers: dev.headers, payload: { text: "x", conv: "agent:nadie" } })).statusCode,
    ).toBe(404);
  });

  it("a group: every member reads the user and the other members; outsiders don't", async () => {
    b = await setup();
    const nubi = await addAgent(b.app, "nubi", "Nubi");
    const rutina = await addAgent(b.app, "rutina", "Rutina");
    const casa = await addAgent(b.app, "casa", "Casa");
    const dev = await pairDevice(b);

    const g = await b.app.inject({
      method: "POST",
      url: "/v1/groups",
      headers: dev.headers,
      payload: { group_id: "todos", name: "Todos", members: ["nubi", "rutina"] },
    });
    expect(g.statusCode).toBe(201);
    expect(
      (await b.app.inject({ method: "POST", url: "/v1/groups", headers: dev.headers, payload: { group_id: "x", name: "X", members: ["nadie"] } }))
        .statusCode,
    ).toBe(400);
    expect(
      (await b.app.inject({ method: "POST", url: "/v1/groups", headers: bearer(nubi), payload: { group_id: "y", name: "Y", members: [] } })).statusCode,
    ).toBe(403);

    await b.app.inject({ method: "POST", url: "/v1/messages", headers: dev.headers, payload: { text: "¿Quién va?", conv: "group:todos" } });
    await b.app.inject({ method: "POST", url: "/v1/messages", headers: bearer(nubi), payload: { text: "Yo apunto", conv: "group:todos" } });
    await b.app.inject({ method: "POST", url: "/v1/messages", headers: bearer(rutina), payload: { text: "Y yo", conv: "group:todos" } });
    expect(
      (await b.app.inject({ method: "POST", url: "/v1/messages", headers: bearer(casa), payload: { text: "¿y yo?", conv: "group:todos" } })).statusCode,
    ).toBe(403);

    const inGroup = async (h: Record<string, string>) =>
      (await b!.app.inject({ method: "GET", url: "/v1/messages?conv=group:todos", headers: h })).json().messages.map((m: { text: string }) => m.text);
    expect(await inGroup(bearer(rutina))).toEqual(["¿Quién va?", "Yo apunto", "Y yo"]);
    expect(await inGroup(bearer(casa))).toEqual([]);
    expect((await events(b.app, bearer(casa))).filter((e) => e.type === "msg.new")).toHaveLength(0);

    expect((await b.app.inject({ method: "GET", url: "/v1/groups", headers: AGENT })).json().groups).toHaveLength(1);
    const groups = await b.app.inject({ method: "GET", url: "/v1/groups", headers: bearer(casa) });
    expect(groups.json().groups).toEqual([]);
    expect((await b.app.inject({ method: "GET", url: "/v1/groups", headers: bearer(nubi) })).json().groups[0].members).toEqual(["nubi", "rutina"]);

    // A message from an agent in a group wakes the phone like any other.
    const pushed = b.push.sent.map((s) => JSON.parse(s.payload.event)).filter((e) => e.type === "msg.new");
    expect(pushed.map((e) => [e.conv, e.agent_id])).toEqual([
      ["group:todos", "nubi"],
      ["group:todos", "rutina"],
    ]);

    // Adding Casa lets it read the group's history too.
    const p = await b.app.inject({ method: "PATCH", url: "/v1/groups/todos", headers: dev.headers, payload: { members: ["nubi", "rutina", "casa"] } });
    expect(p.statusCode).toBe(200);
    expect(await inGroup(bearer(casa))).toHaveLength(3);

    // Removing an agent takes it out of its groups.
    await b.app.inject({ method: "DELETE", url: "/v1/agents/rutina", headers: AGENT });
    expect((await b.app.inject({ method: "GET", url: "/v1/groups", headers: dev.headers })).json().groups[0].members).toEqual(["nubi", "casa"]);
    expect((await b.app.inject({ method: "DELETE", url: "/v1/groups/todos", headers: dev.headers })).statusCode).toBe(204);
    expect(await inGroup(bearer(nubi))).toEqual([]);
  });

  it("a call from an agent carries its id and name, and other agents don't see it", async () => {
    b = await setup();
    const nubi = await addAgent(b.app, "nubi", "Nubi");
    const rutina = await addAgent(b.app, "rutina", "Rutina");
    const dev = await pairDevice(b);
    const c = await b.app.inject({ method: "POST", url: "/v1/calls", headers: bearer(nubi), payload: { reason: "¿Vas al gym?" } });
    expect(c.statusCode).toBe(201);
    expect(c.json().call).toMatchObject({ agent_id: "nubi", agent_name: "Nubi" });
    const id = c.json().call.call_id as string;
    expect((await b.app.inject({ method: "GET", url: `/v1/calls/${id}`, headers: bearer(rutina) })).statusCode).toBe(404);
    expect((await b.app.inject({ method: "GET", url: "/v1/calls", headers: bearer(rutina) })).json().calls).toEqual([]);
    expect((await b.app.inject({ method: "GET", url: "/v1/calls", headers: dev.headers })).json().calls).toHaveLength(1);
    expect((await events(b.app, bearer(rutina))).some((e) => String(e.type).startsWith("call."))).toBe(false);
    const invite = (await events(b.app, dev.headers)).find((e) => e.type === "call.invite");
    expect(invite).toMatchObject({ agent_id: "nubi", agent_name: "Nubi" });
  });
});

describe("multi-agent: upgrade", () => {
  it("messages from before the fork belong to agent:main", async () => {
    const dir = mkdtempSync(join(tmpdir(), "hivepal-upgrade-"));
    // A database at the upstream schema (4 migrations) with one message in it.
    const old = openDatabase(join(dir, "relay.db"), MIGRATIONS_FOR_TEST.slice(0, 4));
    old.prepare("INSERT INTO messages (msg_id, sender, text, created_at) VALUES ('msg_00000000000000000000000001', 'agent', 'viejo', 1)").run();
    old.close();
    b = await setup({ dataDir: dir });
    const r = await b.app.inject({ method: "GET", url: "/v1/messages", headers: AGENT });
    expect(r.json().messages[0]).toMatchObject({ text: "viejo", conv: "agent:main", agent_id: "main" });
  });
});

describe("multi-agent: isolation", () => {
  it("an id with `_` doesn't read other agents' bots: conversations (LIKE wildcards)", async () => {
    b = await setup();
    const coach = await addAgent(b.app, "coach", "Coach");
    await addAgent(b.app, "nubi", "Nubi");
    const sneaky = await addAgent(b.app, "c_ach", "Sneaky"); // `_` matches any one character in LIKE
    const sent = await b.app.inject({
      method: "POST", url: "/v1/messages", headers: bearer(coach), payload: { text: "private plan", conv: "bots:coach.nubi" },
    });
    expect(sent.statusCode).toBe(201);
    const seen = await b.app.inject({ method: "GET", url: "/v1/messages", headers: bearer(sneaky) });
    expect(seen.statusCode).toBe(200);
    expect(seen.json().messages.map((m: { text: string }) => m.text)).not.toContain("private plan");
    const one = await b.app.inject({ method: "GET", url: `/v1/messages/${sent.json().msg_id}`, headers: bearer(sneaky) });
    expect(one.statusCode).toBe(404);
  });

  it("an agent can't schedule a reminder in another pair's bots: conversation", async () => {
    b = await setup();
    await addAgent(b.app, "coach", "Coach");
    await addAgent(b.app, "nubi", "Nubi");
    const evil = await addAgent(b.app, "evil", "Evil");
    const at = Date.now() + 60_000;
    const r = await b.app.inject({
      method: "POST", url: "/v1/reminders", headers: bearer(evil), payload: { text: "x", at, conv: "bots:coach.nubi" },
    });
    expect(r.statusCode).toBe(403);
    const own = await b.app.inject({
      method: "POST", url: "/v1/reminders", headers: bearer(evil), payload: { text: "x", at, conv: "bots:coach.evil" },
    });
    expect(own.statusCode).toBe(201);
  });
});
