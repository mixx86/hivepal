/**
 * Hivepal: "typing…" (issue #48): live-only agent.typing events, set by the agent or by reading the
 * user's message, ended by its answer or by time.
 */
import { afterEach, describe, expect, it, vi } from "vitest";
import type { Event } from "../src/protocol.js";
import { AGENT, pairDevice, setup } from "./helpers.js";

type B = Awaited<ReturnType<typeof setup>>;
let b: B | undefined;
afterEach(async () => {
  vi.useRealTimers();
  await b?.app.close();
  b = undefined;
});

async function start() {
  b = await setup();
  const seen: Event[] = [];
  b.ctx.events.onEvent((e) => {
    if (e.type === "agent.typing") seen.push(e);
  });
  const r = await b.app.inject({ method: "POST", url: "/v1/agents", headers: AGENT, payload: { agent_id: "nubi", name: "Nubi" } });
  const nubi = { authorization: `Bearer ${r.json().api_key}` };
  const dev = await pairDevice(b);
  return { seen, nubi, dev };
}
const last = (seen: Event[]) => seen.at(-1) as Extract<Event, { type: "agent.typing" }>;

describe("typing", () => {
  it("an agent says it's writing, live only; its message ends it", async () => {
    const { seen, nubi, dev } = await start();
    expect((await b!.app.inject({ method: "POST", url: "/v1/typing", headers: nubi })).statusCode).toBe(204);
    expect(last(seen)).toMatchObject({ agent_id: "nubi", conv: "agent:nubi", typing: true });
    expect(last(seen).seq).toBeUndefined();
    expect(last(seen).until! - Date.now()).toBeGreaterThan(15_000);
    // Never in the event log a phone replays.
    const log = b!.ctx.events.list(0, 500);
    expect(log.events.some((e) => e.type === "agent.typing")).toBe(false);
    // Phones can't say it.
    expect((await b!.app.inject({ method: "POST", url: "/v1/typing", headers: dev.headers })).statusCode).toBe(403);
    // Nor an agent in someone else's conversation.
    expect((await b!.app.inject({ method: "POST", url: "/v1/typing", headers: nubi, payload: { conv: "agent:main" } })).statusCode).toBe(403);

    await b!.app.inject({ method: "POST", url: "/v1/messages", headers: nubi, payload: { text: "done" } });
    expect(last(seen)).toMatchObject({ agent_id: "nubi", conv: "agent:nubi", typing: false });
    expect(b!.ctx.typing!.current()).toEqual([]);
  });

  it("reading a recent message from the user starts it, and it ends by itself", async () => {
    const { seen, nubi, dev } = await start();
    const m = (await b!.app.inject({ method: "POST", url: "/v1/messages", headers: dev.headers, payload: { text: "hi", conv: "agent:nubi" } })).json();
    vi.useFakeTimers({ toFake: ["setTimeout", "clearTimeout"] });
    expect((await b!.app.inject({ method: "POST", url: `/v1/messages/${m.msg_id}/read`, headers: nubi })).statusCode).toBe(204);
    expect(last(seen)).toMatchObject({ agent_id: "nubi", conv: "agent:nubi", typing: true });
    expect(b!.ctx.typing!.current()).toHaveLength(1);
    vi.advanceTimersByTime(61_000);
    expect(last(seen)).toMatchObject({ typing: false });
    expect(b!.ctx.typing!.current()).toEqual([]);
  });

  it("stops when asked, and a phone that connects gets who is writing now", async () => {
    const { seen, nubi } = await start();
    await b!.app.inject({ method: "POST", url: "/v1/typing", headers: nubi });
    expect(b!.ctx.typing!.current()).toEqual([expect.objectContaining({ type: "agent.typing", agent_id: "nubi", typing: true })]);
    await b!.app.inject({ method: "POST", url: "/v1/typing", headers: nubi, payload: { typing: false } });
    expect(last(seen)).toMatchObject({ typing: false });
    // Stopping twice says nothing more.
    const n = seen.length;
    await b!.app.inject({ method: "POST", url: "/v1/typing", headers: nubi, payload: { typing: false } });
    expect(seen).toHaveLength(n);
  });
});
