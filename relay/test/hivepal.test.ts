import { afterEach, describe, expect, it } from "vitest";
import { AGENT, pairDevice, setup } from "./helpers.js";

type Built = Awaited<ReturnType<typeof setup>>;
let b: Built | undefined;
afterEach(async () => {
  await b?.app.close();
  b = undefined;
});

const bearer = (key: string) => ({ authorization: `Bearer ${key}` });
const addAgent = async (id: string, name: string) =>
  (await b!.app.inject({ method: "POST", url: "/v1/agents", headers: AGENT, payload: { agent_id: id, name } })).json().api_key as string;
const post = (headers: Record<string, string>, payload: Record<string, unknown>) =>
  b!.app.inject({ method: "POST", url: "/v1/messages", headers, payload });
const texts = async (headers: Record<string, string>, q = "") =>
  (await b!.app.inject({ method: "GET", url: `/v1/messages${q}`, headers })).json().messages.map((m: { text: string }) => m.text);

describe("quick replies", () => {
  it("an agent sends options; they reach the phone in the message and the push; the user can't", async () => {
    b = await setup();
    const nubi = await addAgent("nubi", "Nubi");
    const dev = await pairDevice(b);
    const r = await post(bearer(nubi), { text: "¿Vas al gym?", options: ["Sí", "No", "Luego"] });
    expect(r.statusCode).toBe(201);
    expect(r.json().options).toEqual(["Sí", "No", "Luego"]);
    const pushed = JSON.parse(b.push.sent.at(-1)!.payload.event);
    expect(pushed.options).toEqual(["Sí", "No", "Luego"]);
    expect((await post(dev.headers, { text: "x", conv: "agent:nubi", options: ["a"] })).statusCode).toBe(400);
    expect((await post(bearer(nubi), { text: "x", options: ["a".repeat(41)] })).statusCode).toBe(400);
  });
});

describe("attachments", () => {
  it("upload a photo, attach it, and only those who see the message can download it", async () => {
    b = await setup();
    const nubi = await addAgent("nubi", "Nubi");
    const rutina = await addAgent("rutina", "Rutina");
    const dev = await pairDevice(b);
    const png = Buffer.from("89504e470d0a1a0a0000000d49484452", "hex");
    const up = await b.app.inject({ method: "POST", url: "/v1/files", headers: { ...dev.headers, "content-type": "image/png" }, payload: png });
    expect(up.statusCode).toBe(201);
    const file = up.json();
    expect(file).toMatchObject({ mime: "image/png", size: png.length });
    const m = await post(dev.headers, { text: "", conv: "agent:nubi", attachments: [file.file_id] });
    expect(m.statusCode).toBe(201);
    expect(m.json().attachments).toEqual([{ file_id: file.file_id, mime: "image/png", size: png.length }]);

    const get = (h: Record<string, string>) => b!.app.inject({ method: "GET", url: `/v1/files/${file.file_id}`, headers: h });
    const g = await get(bearer(nubi));
    expect(g.statusCode).toBe(200);
    expect(g.headers["content-type"]).toBe("image/png");
    expect(g.rawPayload.equals(png)).toBe(true);
    expect((await get(bearer(rutina))).statusCode).toBe(404);

    // Unsupported type, empty text without files, someone else's file.
    expect((await b.app.inject({ method: "POST", url: "/v1/files", headers: { ...dev.headers, "content-type": "image/gif" }, payload: png })).statusCode).toBe(400);
    expect((await post(dev.headers, { text: "  ", conv: "agent:nubi" })).statusCode).toBe(400);
    expect((await post(bearer(rutina), { text: "mira", attachments: [file.file_id] })).statusCode).toBe(400);
  });

  it("accepts files over the JSON body limit", async () => {
    b = await setup();
    const dev = await pairDevice(b);
    const big = Buffer.alloc(300 * 1024, 7);
    const up = await b.app.inject({ method: "POST", url: "/v1/files?duration_ms=4200", headers: { ...dev.headers, "content-type": "audio/mp4" }, payload: big });
    expect(up.statusCode).toBe(201);
    expect(up.json()).toMatchObject({ size: big.length, duration_ms: 4200 });
  });
});

describe("reminders", () => {
  it("the relay sends a reminder at its time, as the agent, with a push", async () => {
    b = await setup();
    const nubi = await addAgent("nubi", "Nubi");
    const dev = await pairDevice(b);
    const at = Date.now() + 3_600_000;
    const r = await b.app.inject({
      method: "POST", url: "/v1/reminders", headers: bearer(nubi), payload: { text: "Hora de IPE", at, options: ["Voy", "Hoy no"] },
    });
    expect(r.statusCode).toBe(201);
    const list = await b.app.inject({ method: "GET", url: "/v1/reminders", headers: dev.headers });
    expect(list.json().reminders.map((x: { text: string }) => x.text)).toEqual(["Hora de IPE"]);

    expect(b.ctx.reminders!.tick(at - 1)).toBe(0);
    expect(await texts(dev.headers)).toEqual([]);
    expect(b.ctx.reminders!.tick(at + 1)).toBe(1);
    const msgs = (await b.app.inject({ method: "GET", url: "/v1/messages", headers: dev.headers })).json().messages;
    expect(msgs[0]).toMatchObject({ text: "Hora de IPE", conv: "agent:nubi", agent_id: "nubi", options: ["Voy", "Hoy no"] });
    expect(JSON.parse(b.push.sent.at(-1)!.payload.event).text).toBe("Hora de IPE");
    expect(b.ctx.reminders!.tick(at + 2)).toBe(0); // only once
    expect((await b.app.inject({ method: "GET", url: "/v1/reminders", headers: dev.headers })).json().reminders).toEqual([]);
  });

  it("can be canceled by its agent or the user, not by another agent", async () => {
    b = await setup();
    const nubi = await addAgent("nubi", "Nubi");
    const rutina = await addAgent("rutina", "Rutina");
    const dev = await pairDevice(b);
    const mk = async () =>
      (await b!.app.inject({ method: "POST", url: "/v1/reminders", headers: bearer(nubi), payload: { text: "x", at: Date.now() + 60_000 } })).json().rem_id;
    const one = await mk();
    expect((await b.app.inject({ method: "DELETE", url: `/v1/reminders/${one}`, headers: bearer(rutina) })).statusCode).toBe(404);
    expect((await b.app.inject({ method: "DELETE", url: `/v1/reminders/${one}`, headers: bearer(nubi) })).statusCode).toBe(204);
    const two = await mk();
    expect((await b.app.inject({ method: "DELETE", url: `/v1/reminders/${two}`, headers: dev.headers })).statusCode).toBe(204);
    expect(b.ctx.reminders!.tick(Date.now() + 120_000)).toBe(0);
  });

  it("a reminder whose agent was removed is dropped", async () => {
    b = await setup();
    const nubi = await addAgent("nubi", "Nubi");
    await b.app.inject({ method: "POST", url: "/v1/reminders", headers: bearer(nubi), payload: { text: "x", at: Date.now() + 1000 } });
    await b.app.inject({ method: "DELETE", url: "/v1/agents/nubi", headers: AGENT });
    b.ctx.reminders!.tick(Date.now() + 5000);
    const dev = await pairDevice(b);
    expect(await texts(dev.headers)).toEqual([]);
  });
});

describe("conversations between bots", () => {
  it("two bots talk in bots:a.b; others don't see it; the user reads it but isn't woken", async () => {
    b = await setup();
    const nubi = await addAgent("nubi", "Nubi");
    const rutina = await addAgent("rutina", "Rutina");
    const casa = await addAgent("casa", "Casa");
    const dev = await pairDevice(b);
    const r = await post(bearer(nubi), { text: "¿Movemos el gym al jueves?", conv: "bots:nubi.rutina" });
    expect(r.statusCode).toBe(201);
    await post(bearer(rutina), { text: "Vale", conv: "bots:nubi.rutina" });
    expect(await texts(bearer(rutina))).toEqual(["¿Movemos el gym al jueves?", "Vale"]);
    expect(await texts(bearer(casa))).toEqual([]);
    expect(await texts(dev.headers, "?conv=bots:nubi.rutina")).toHaveLength(2);
    expect(b.push.sent.filter((s) => JSON.parse(s.payload.event).type === "msg.new")).toHaveLength(0);
    // Not part of it, wrong order, or with a missing agent.
    expect((await post(bearer(casa), { text: "x", conv: "bots:nubi.rutina" })).statusCode).toBe(403);
    expect((await post(bearer(nubi), { text: "x", conv: "bots:rutina.nubi" })).statusCode).toBe(403);
    expect((await post(bearer(nubi), { text: "x", conv: "bots:nadie.nubi" })).statusCode).toBe(403);
  });
});

describe("calling a specific agent", () => {
  it("the user calls Nubi: the call carries Nubi; main (the voice daemon) and Nubi see it, others don't", async () => {
    b = await setup();
    const nubi = await addAgent("nubi", "Nubi");
    const rutina = await addAgent("rutina", "Rutina");
    const dev = await pairDevice(b);
    b.ctx.hub.isAgentOnline = () => true;
    const c = await b.app.inject({ method: "POST", url: "/v1/calls", headers: dev.headers, payload: { agent_id: "nubi" } });
    expect(c.statusCode).toBe(201);
    expect(c.json().call).toMatchObject({ direction: "user_to_agent", agent_id: "nubi", agent_name: "Nubi" });
    const id = c.json().call.call_id as string;
    expect((await b.app.inject({ method: "GET", url: `/v1/calls/${id}`, headers: AGENT })).statusCode).toBe(200);
    expect((await b.app.inject({ method: "GET", url: `/v1/calls/${id}`, headers: bearer(nubi) })).statusCode).toBe(200);
    expect((await b.app.inject({ method: "GET", url: `/v1/calls/${id}`, headers: bearer(rutina) })).statusCode).toBe(404);
    expect((await b.app.inject({ method: "POST", url: `/v1/calls/${id}/accept`, headers: AGENT })).statusCode).toBe(200);
    await b.app.inject({ method: "POST", url: `/v1/calls/${id}/end`, headers: AGENT });
    expect((await b.app.inject({ method: "POST", url: "/v1/calls", headers: dev.headers, payload: { agent_id: "nadie" } })).statusCode).toBe(400);
  });
});

describe("deleting messages", () => {
  it("only the author deletes; everyone who saw it gets msg.deleted", async () => {
    b = await setup();
    const nubi = await addAgent("nubi", "Nubi");
    const dev = await pairDevice(b);
    const mine = (await post(dev.headers, { text: "typo", conv: "agent:nubi" })).json().msg_id;
    const theirs = (await post(bearer(nubi), { text: "hola" })).json().msg_id;
    const del = (h: Record<string, string>, id: string) => b!.app.inject({ method: "POST", url: `/v1/messages/${id}/delete`, headers: h });
    expect((await del(bearer(nubi), mine)).statusCode).toBe(404);
    expect((await del(dev.headers, theirs)).statusCode).toBe(404);
    expect((await del(dev.headers, mine)).statusCode).toBe(204);
    expect(await texts(bearer(nubi))).toEqual(["hola"]);
    const evs = (await b.app.inject({ method: "GET", url: "/v1/events?after_seq=0", headers: bearer(nubi) })).json().events;
    expect(evs.at(-1)).toMatchObject({ type: "msg.deleted", msg_id: mine, conv: "agent:nubi" });
    expect((await del(bearer(nubi), theirs)).statusCode).toBe(204);
  });
});

describe("reminders that insist", () => {
  it("follow up, then call, until the user writes in that conversation", async () => {
    b = await setup();
    const rutina = await addAgent("rutina", "Rutina");
    const dev = await pairDevice(b);
    const at = Date.now() + 1000;
    await b.app.inject({
      method: "POST", url: "/v1/reminders", headers: bearer(rutina),
      payload: { text: "Gym a las 18:00", at, options: ["Voy", "Hoy no"], insist_s: 600, call: true },
    });
    b.ctx.reminders!.tick(at + 10);
    expect(await texts(dev.headers)).toEqual(["Gym a las 18:00"]);
    b.ctx.reminders!.tick(at + 599_000);
    expect(await texts(dev.headers)).toHaveLength(1);
    b.ctx.reminders!.tick(at + 601_000);
    expect(await texts(dev.headers)).toEqual(["Gym a las 18:00", "⏰ Gym a las 18:00"]);
    b.ctx.reminders!.tick(at + 1_201_000);
    const calls = (await b.app.inject({ method: "GET", url: "/v1/calls", headers: dev.headers })).json().calls;
    expect(calls[0]).toMatchObject({ agent_id: "rutina", reason: "Gym a las 18:00", priority: "urgent" });
    b.ctx.reminders!.tick(at + 3_000_000);
    expect((await b.app.inject({ method: "GET", url: "/v1/calls", headers: dev.headers })).json().calls).toHaveLength(1);
  });

  it("stop as soon as the user answers", async () => {
    b = await setup();
    const rutina = await addAgent("rutina", "Rutina");
    const dev = await pairDevice(b);
    const at = Date.now() + 1000;
    await b.app.inject({
      method: "POST", url: "/v1/reminders", headers: bearer(rutina),
      payload: { text: "A dormir", at, insist_s: 600, call: true },
    });
    b.ctx.reminders!.tick(at + 10);
    await post(dev.headers, { text: "Ya voy", conv: "agent:rutina" });
    b.ctx.reminders!.tick(at + 2_000_000);
    expect(await texts(dev.headers)).toEqual(["A dormir", "Ya voy"]);
    expect((await b.app.inject({ method: "GET", url: "/v1/calls", headers: dev.headers })).json().calls).toEqual([]);
  });
});

describe("speech", () => {
  it("transcribes a voice note once and keeps it; makes voice notes from text", async () => {
    b = await setup();
    const nubi = await addAgent("nubi", "Nubi");
    const dev = await pairDevice(b);
    let calls = 0;
    b.ctx.speech = {
      transcribe: async () => { calls++; return "voy a las seis"; },
      tts: async (text) => ({ data: (await import("../src/gemini.js")).wav(Buffer.alloc(48000), 24000), mime: "audio/wav" + (text ? "" : "") }),
    };
    const up = await b.app.inject({ method: "POST", url: "/v1/files", headers: { ...dev.headers, "content-type": "audio/mp4" }, payload: Buffer.alloc(100, 1) });
    const id = up.json().file_id as string;
    await post(dev.headers, { text: "", conv: "agent:nubi", attachments: [id] });
    for (let i = 0; i < 2; i++) {
      const t = await b.app.inject({ method: "GET", url: `/v1/files/${id}/transcript`, headers: bearer(nubi) });
      expect(t.json()).toEqual({ text: "voy a las seis" });
    }
    expect(calls).toBe(1);
    const v = await b.app.inject({ method: "POST", url: "/v1/tts", headers: bearer(nubi), payload: { text: "Buenos días" } });
    expect(v.statusCode).toBe(201);
    expect(v.json()).toMatchObject({ mime: "audio/wav", duration_ms: 1000 });
    expect((await post(bearer(nubi), { text: "", attachments: [v.json().file_id] })).statusCode).toBe(201);
  });

  it("says so when it isn't configured", async () => {
    b = await setup();
    const nubi = await addAgent("nubi", "Nubi");
    expect((await b.app.inject({ method: "POST", url: "/v1/tts", headers: bearer(nubi), payload: { text: "x" } })).statusCode).toBe(503);
  });
});
