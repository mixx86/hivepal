/**
 * Hivepal: UnifiedPush, the push channel without Google. The phone registers with a distributor app
 * (ntfy, NextPush…), gets an endpoint URL and gives it to the relay; the relay POSTs each push there
 * and the distributor wakes the app. The body is the same signed `PushData` map FCM carries, as
 * JSON, so the app checks it the same way. `TTL` and `Urgency` follow RFC 8030 (Web Push).
 *
 * The endpoint comes from the phone, so it's checked before use: https only, and never a loopback,
 * private, link-local or otherwise internal address, so a paired phone can't make the relay call
 * services on its own network.
 */
import { lookup } from "node:dns/promises";
import { isIP } from "node:net";
import type { PushOptions, PushPayload, PushProvider, PushResult, PushTarget } from "./push-provider.js";

const TIMEOUT_MS = 10_000;

function privateV4(ip: string): boolean {
  const [a, b] = ip.split(".").map(Number) as [number, number];
  return a === 0 || a === 10 || a === 127 || (a === 100 && b >= 64 && b <= 127) || (a === 169 && b === 254) ||
    (a === 172 && b >= 16 && b <= 31) || (a === 192 && b === 168) || a >= 224;
}

/** Why an endpoint can't be used, or null when it's fine. */
export function endpointProblem(raw: string): string | null {
  let u: URL;
  try {
    u = new URL(raw);
  } catch {
    return "not a URL";
  }
  if (u.protocol !== "https:") return "must be https://";
  if (u.username || u.password) return "must not carry credentials";
  const host = u.hostname.replace(/^\[|\]$/g, "").toLowerCase();
  if (host === "localhost" || host.endsWith(".localhost") || host.endsWith(".local") || host.endsWith(".internal") || !host.includes(".") && !isIP(host)) {
    return "must be a public host";
  }
  const v = isIP(host);
  if (v === 4 && privateV4(host)) return "must be a public host";
  if (v === 6 && (host === "::1" || host === "::" || /^(fc|fd|fe8|fe9|fea|feb)/.test(host) || host.startsWith("::ffff:"))) {
    return "must be a public host";
  }
  return null;
}

export class UnifiedPushProvider implements PushProvider {
  readonly name = "unifiedpush";

  constructor(
    private readonly fetchImpl: typeof fetch = fetch,
    /** Resolves the endpoint's host; a name that resolves to an internal address is refused too. */
    private readonly resolve: (host: string) => Promise<string[]> = async (h) => (await lookup(h, { all: true })).map((a) => a.address),
  ) {}

  async send(target: PushTarget, payload: PushPayload, opts: PushOptions): Promise<PushResult> {
    const problem = endpointProblem(target.token);
    if (problem) return { ok: false, unregistered: true, error: `endpoint ${problem}` };
    try {
      const host = new URL(target.token).hostname.replace(/^\[|\]$/g, "");
      if (!isIP(host)) {
        const addrs = await this.resolve(host);
        if (addrs.some((a) => endpointProblem(`https://${a.includes(":") ? `[${a}]` : a}/`))) {
          return { ok: false, unregistered: false, error: "endpoint resolves to an internal address" };
        }
      }
      const r = await this.fetchImpl(target.token, {
        method: "POST",
        redirect: "manual", // never follow: a redirect could point anywhere
        headers: {
          "content-type": "application/json",
          ttl: String(opts.ttlSeconds),
          urgency: opts.highPriority ? "high" : "normal",
          ...(opts.collapseKey ? { topic: opts.collapseKey.slice(0, 32).replace(/[^A-Za-z0-9_-]/g, "_") } : {}),
        },
        body: JSON.stringify(payload),
        signal: AbortSignal.timeout(TIMEOUT_MS),
      });
      if (r.status >= 200 && r.status < 300) return { ok: true };
      return { ok: false, unregistered: r.status === 404 || r.status === 410, error: `endpoint answered ${r.status}` };
    } catch (e) {
      return { ok: false, unregistered: false, error: e instanceof Error ? e.message : String(e) };
    }
  }
}
