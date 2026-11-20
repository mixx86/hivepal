import type { FastifyBaseLogger, FastifyRequest } from "fastify";
import type { Agents } from "./agents.js";
import type { Auth, Principal } from "./auth.js";
import type { Config } from "./config.js";
import type { Db } from "./db/database.js";
import type { EventLog } from "./events.js";
import type { LiveKitService } from "./livekit.js";
import { forbidden } from "./errors.js";
import type { PushConfig } from "./protocol.js";
import type { Limits } from "./quota.js";
import type { Calls } from "./routes/calls.js";
import type { WsHub } from "./routes/ws.js";

export interface Ctx {
  cfg: Config;
  log: FastifyBaseLogger;
  db: Db;
  auth: Auth;
  agents: Agents;
  events: EventLog;
  relayId: string;
  pushConfig: PushConfig;
  pushProviderName: string;
  hub: WsHub;
  /** Undefined when LiveKit isn't configured: calls are then unavailable. */
  livekit?: LiveKitService;
  calls?: Calls;
  /** Hivepal: transcription and text-to-speech; undefined without GEMINI_API_KEY. */
  speech?: import("./gemini.js").Speech;
  /** Scheduled messages (Hivepal). */
  reminders?: import("./routes/reminders.js").Reminders;
  /** Hivepal: wake-up emails (sent when the relay has a mail account). */
  wake?: import("./wake.js").Wake;
  /** Hivepal: the relay's delivery settings (mail account, mailbox repository). */
  settings?: import("./settings.js").Settings;
  /** Hivepal: agents' Markdown mailboxes. */
  mailbox?: import("./mailbox.js").Mailbox;
  /** Hivepal, tests: how a mailer is built for an SMTP URL (checking a new account). */
  mailerFor?: (url: string) => import("./wake.js").Mailer;
  /** The tenant's current limits (hub mode); unlimited on a self-hosted relay. */
  limits: () => Limits;
  /** Prefix of LiveKit room names ("" self-hosted, "{tenant_id}." in hub mode: one LiveKit for all tenants). */
  roomPrefix: string;
}

declare module "fastify" {
  interface FastifyRequest {
    principal?: Principal;
  }
}

type Kind = Principal["kind"];

/** Fastify preHandler that authenticates and restricts to the given principal kinds. */
export function requireAuth(ctx: Ctx, ...kinds: Kind[]) {
  return async (req: FastifyRequest) => {
    const p = await ctx.auth.authenticate(req.headers.authorization);
    if (!kinds.includes(p.kind)) throw forbidden(`This endpoint is not available to ${p.kind} credentials`);
    req.principal = p;
  };
}

/**
 * The tenant's API key only: pairing phones and revoking them need it. A short-lived
 * agent token (`at_…`) must not be able to mint a credential that outlives it.
 */
export function requireAgentKey(ctx: Ctx) {
  return async (req: FastifyRequest) => {
    const p = await ctx.auth.authenticate(req.headers.authorization);
    if (p.kind !== "agent") throw forbidden(`This endpoint is not available to ${p.kind} credentials`);
    if (p.expiresAt !== undefined) throw forbidden("This endpoint needs the API key, not a short-lived agent token");
    if (p.agentId !== "main") throw forbidden("This endpoint needs the relay's API key, not an agent's own key");
    req.principal = p;
  };
}

export function principal(req: FastifyRequest): Principal {
  if (!req.principal) throw new Error("route is missing requireAuth");
  return req.principal;
}
