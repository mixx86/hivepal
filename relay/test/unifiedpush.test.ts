import { afterEach, describe, expect, it } from "vitest";
import { buildApp } from "../src/app.js";
import { signEvent } from "../src/push/dispatcher.js";
import { UnifiedPushProvider, endpointProblem } from "../src/push/unifiedpush.js";
import { AGENT, FakeLiveKit, FakePush, pairDevice, testConfig } from "./helpers.js";

let close: (() => Promise<unknown>) | undefined;
afterEach(async () => {
  await close?.();
  close = undefined;
});

describe("UnifiedPush endpoints", () => {
  it("only https on a public host", () => {
    expect(endpointProblem("https://ntfy.sh/upAbC123?up=1")).toBeNull();
    expect(endpointProblem("https://push.example.com/x")).toBeNull();
    for (const bad of [
      "http://ntfy.sh/up",
      "https://localhost/up",
      "https://127.0.0.1/up",
      "https://10.0.0.5/up",
      "https://192.168.1.2/up",
      "https://172.20.0.1/up",
      "https://169.254.169.254/latest",
      "https://100.100.1.1/up",
      "https://[::1]/up",
      "https://[fd00::1]/up",
      "https://ntfy/up",
      "https://relay.internal/up",
      "https://user:pw@ntfy.sh/up",
      "not a url",
    ]) {
      expect(endpointProblem(bad), bad).not.toBeNull();
    }
  });

  it("posts the payload with TTL and Urgency, forgets a 410, refuses a name that resolves inside", async () => {
    const calls: Array<{ url: string; init: RequestInit }> = [];
    let status = 200;
    const fetchFake = (async (url: string, init: RequestInit) => {
      calls.push({ url, init });
      return new Response(null, { status });
    }) as unknown as typeof fetch;
    const up = new UnifiedPushProvider(fetchFake, async () => ["93.184.216.34"]);
    const res = await up.send({ deviceId: "d", token: "https://ntfy.sh/upX" }, { v: "1", event: "{}" }, { ttlSeconds: 30, highPriority: true });
    expect(res).toEqual({ ok: true });
    const h = calls[0]!.init.headers as Record<string, string>;
    expect(h.ttl).toBe("30");
    expect(h.urgency).toBe("high");
    expect(JSON.parse(String(calls[0]!.init.body))).toEqual({ v: "1", event: "{}" });
    expect(calls[0]!.init.redirect).toBe("manual");
    status = 410;
    expect(await up.send({ deviceId: "d", token: "https://ntfy.sh/upX" }, {}, { ttlSeconds: 1, highPriority: false })).toMatchObject({
      ok: false,
      unregistered: true,
    });
    const sneaky = new UnifiedPushProvider(fetchFake, async () => ["10.0.0.7"]);
    const before = calls.length;
    expect(await sneaky.send({ deviceId: "d", token: "https://evil.example/up" }, {}, { ttlSeconds: 1, highPriority: false })).toMatchObject({ ok: false });
    expect(calls.length).toBe(before);
  });
});

describe("devices on UnifiedPush", () => {
  it("a phone with an endpoint is woken through it (signed like FCM), and goes back to FCM without", async () => {
    const fcm = new FakePush();
    const up = new FakePush();
    const b = await buildApp(testConfig(), { logger: false, pushProvider: fcm, unifiedPush: up, livekit: new FakeLiveKit() });
    close = () => b.app.close();
    const dev = await pairDevice(b);

    expect((await b.app.inject({ method: "POST", url: "/v1/device/unifiedpush", headers: dev.headers, payload: { endpoint: "http://10.0.0.2/up" } })).statusCode).toBe(400);
    expect((await b.app.inject({ method: "POST", url: "/v1/device/unifiedpush", headers: dev.headers, payload: { endpoint: "https://ntfy.sh/upAbc" } })).statusCode).toBe(204);
    const listed = (await b.app.inject({ method: "GET", url: "/v1/devices", headers: AGENT })).json().devices[0];
    expect(listed).toMatchObject({ push_via: "unifiedpush", has_push_token: true });

    await b.app.inject({ method: "POST", url: "/v1/messages", headers: AGENT, payload: { text: "hola" } });
    await new Promise((r) => setImmediate(r));
    expect(fcm.sent).toHaveLength(0);
    expect(up.sent).toHaveLength(1);
    const sent = up.sent[0]!;
    expect(sent.target.token).toBe("https://ntfy.sh/upAbc");
    expect(sent.payload.sig).toBe(signEvent(dev.push_secret, sent.payload.event!));

    expect((await b.app.inject({ method: "DELETE", url: "/v1/device/unifiedpush", headers: dev.headers })).statusCode).toBe(204);
    await b.app.inject({ method: "POST", url: "/v1/messages", headers: AGENT, payload: { text: "otra" } });
    await new Promise((r) => setImmediate(r));
    expect(fcm.sent).toHaveLength(1);
    expect((await b.app.inject({ method: "GET", url: "/v1/devices", headers: AGENT })).json().devices[0].push_via).toBe("fcm");
  });

  it("works on a relay without Firebase", async () => {
    const up = new FakePush();
    const b = await buildApp(testConfig({ fcm: undefined }), { logger: false, unifiedPush: up, livekit: new FakeLiveKit() });
    close = () => b.app.close();
    const dev = await pairDevice(b, "Pixel", undefined);
    await b.app.inject({ method: "POST", url: "/v1/device/unifiedpush", headers: dev.headers, payload: { endpoint: "https://ntfy.sh/upAbc" } });
    await b.app.inject({ method: "POST", url: "/v1/messages", headers: AGENT, payload: { text: "hola" } });
    await new Promise((r) => setImmediate(r));
    expect(up.sent).toHaveLength(1);
  });
});
