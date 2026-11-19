import { afterEach, describe, expect, it } from "vitest";
import { buildApp } from "../src/app.js";
import { signEvent } from "../src/push/dispatcher.js";
import { UnifiedPushProvider, endpointProblem } from "../src/push/unifiedpush.js";
import { createDecipheriv, createECDH, hkdfSync } from "node:crypto";
import { encrypt } from "../src/push/webpush.js";
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
    expect(JSON.parse(Buffer.from(calls[0]!.init.body as Uint8Array).toString())).toEqual({ v: "1", event: "{}" });
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


// RFC 8291, Appendix A.
const RFC = {
  plaintext: "When I grow up, I want to be a watermelon",
  uaPrivate: "q1dXpw3UpT5VOmu_cf_v6ih07Aems3njxI-JWgLcM94",
  uaPublic: "BCVxsr7N_eNgVRqvHtD0zTZsEc6-VV-JvLexhqUzORcxaOzi6-AYWXvTBHm4bjyPjs7Vd8pZGH6SRpkNtoIAiw4",
  auth: "BTBZMqHH6r4Tts7J_aSIgg",
  asPrivate: "yfWPiYE-n46HLnH0KqZOF1fJJU3MYrct3AELtAQ-oRw",
  salt: "DGv6ra1nlYgDCS1FRnbzlw",
  body:
    "DGv6ra1nlYgDCS1FRnbzlwAAEABBBP4z9KsN6nGRTbVYI_c7VJSPQTBtkgcy27mlmlMoZIIgDll6e3vCYLocInmYWAmS6TlzAC8wEqKK6PBru3jl7A_yl95bQpu6cVPTpK4Mqgkf1CXztLVBSt2Ks3oZwbuwXPXLWyouBWLVWGNWQexSgSxsj_Qulcy4a-fN",
};

/** The receiving side, as an app would decrypt. */
function decrypt(body: Buffer, uaPrivate: string, auth: string): Buffer {
  const salt = body.subarray(0, 16);
  const idlen = body[20]!;
  const asPublic = body.subarray(21, 21 + idlen);
  const ua = createECDH("prime256v1");
  ua.setPrivateKey(Buffer.from(uaPrivate, "base64url"));
  const secret = ua.computeSecret(asPublic);
  const info = Buffer.concat([Buffer.from("WebPush: info\0"), ua.getPublicKey(), asPublic]);
  const ikm = Buffer.from(hkdfSync("sha256", secret, Buffer.from(auth, "base64url"), info, 32));
  const cek = Buffer.from(hkdfSync("sha256", ikm, salt, Buffer.from("Content-Encoding: aes128gcm\0"), 16));
  const nonce = Buffer.from(hkdfSync("sha256", ikm, salt, Buffer.from("Content-Encoding: nonce\0"), 12));
  const data = body.subarray(21 + idlen);
  const d = createDecipheriv("aes-128-gcm", cek, nonce);
  d.setAuthTag(data.subarray(data.length - 16));
  const out = Buffer.concat([d.update(data.subarray(0, data.length - 16)), d.final()]);
  expect(out[out.length - 1]).toBe(0x02);
  return out.subarray(0, out.length - 1);
}

describe("Web Push encryption (RFC 8291)", () => {
  it("matches the RFC's test vector byte for byte", () => {
    const as = createECDH("prime256v1");
    as.setPrivateKey(Buffer.from(RFC.asPrivate, "base64url"));
    const body = encrypt(Buffer.from(RFC.plaintext), { p256dh: RFC.uaPublic, auth: RFC.auth }, Buffer.from(RFC.salt, "base64url"), as);
    expect(body.toString("base64url")).toBe(RFC.body);
  });

  it("an app with the keys reads it; a fresh key pair and salt each time", () => {
    const ua = createECDH("prime256v1");
    ua.generateKeys();
    const keys = { p256dh: ua.getPublicKey().toString("base64url"), auth: Buffer.alloc(16, 7).toString("base64url") };
    const a = encrypt(Buffer.from('{"v":"1"}'), keys);
    const b = encrypt(Buffer.from('{"v":"1"}'), keys);
    expect(a.equals(b)).toBe(false);
    expect(decrypt(a, ua.getPrivateKey().toString("base64url"), keys.auth).toString()).toBe('{"v":"1"}');
  });

  it("the provider sends ciphertext with Content-Encoding aes128gcm when the phone gave keys", async () => {
    const ua = createECDH("prime256v1");
    ua.generateKeys();
    const keys = { p256dh: ua.getPublicKey().toString("base64url"), auth: Buffer.alloc(16, 1).toString("base64url") };
    let seen: RequestInit | undefined;
    const up = new UnifiedPushProvider((async (_u: string, init: RequestInit) => {
      seen = init;
      return new Response(null, { status: 201 });
    }) as unknown as typeof fetch, async () => ["93.184.216.34"]);
    expect(await up.send({ deviceId: "d", token: "https://ntfy.sh/up", webPush: keys }, { v: "1", event: "secret text" }, { ttlSeconds: 5, highPriority: true })).toEqual({ ok: true });
    const h = seen!.headers as Record<string, string>;
    expect(h["content-encoding"]).toBe("aes128gcm");
    const body = Buffer.from(seen!.body as Uint8Array);
    expect(body.includes(Buffer.from("secret text"))).toBe(false);
    expect(JSON.parse(decrypt(body, ua.getPrivateKey().toString("base64url"), keys.auth).toString())).toEqual({ v: "1", event: "secret text" });
  });

  it("the relay keeps the keys and encrypts that phone's pushes; bad keys are refused", async () => {
    const up = new FakePush();
    const b = await buildApp(testConfig(), { logger: false, pushProvider: new FakePush(), unifiedPush: up, livekit: new FakeLiveKit() });
    close = () => b.app.close();
    const dev = await pairDevice(b);
    const ua = createECDH("prime256v1");
    ua.generateKeys();
    const keys = { p256dh: ua.getPublicKey().toString("base64url"), auth: Buffer.alloc(16, 3).toString("base64url") };
    const set = (payload: object) => b.app.inject({ method: "POST", url: "/v1/device/unifiedpush", headers: dev.headers, payload });
    expect((await set({ endpoint: "https://ntfy.sh/upK", p256dh: keys.p256dh })).statusCode).toBe(400);
    expect((await set({ endpoint: "https://ntfy.sh/upK", p256dh: "A".repeat(87), auth: keys.auth })).statusCode).toBe(400);
    expect((await set({ endpoint: "https://ntfy.sh/upK", ...keys })).statusCode).toBe(204);
    await b.app.inject({ method: "POST", url: "/v1/messages", headers: AGENT, payload: { text: "hola" } });
    await new Promise((r) => setImmediate(r));
    expect(up.sent[0]!.target.webPush).toEqual(keys);
  });
});
