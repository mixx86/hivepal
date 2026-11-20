import type { FastifyInstance } from "fastify";
import { MAIN, convOf, dmOf, parseConv } from "../agents.js";
import { type Principal, partyOf } from "../auth.js";
import { type Ctx, principal, requireAuth } from "../context.js";
import { READ_TYPING_MS, RECENT_MS, TYPING_MS } from "../typing.js";
import { ApiError, forbidden, notFound } from "../errors.js";
import { newId, wellFormed } from "../ids.js";
import type { Message, Party, Schemas } from "../protocol.js";
import { checkMessage } from "../quota.js";

type Attachment = Schemas["Attachment"];

export interface MessageRow {
  msg_id: string;
  sender: Party;
  text: string;
  reply_to: string | null;
  client_msg_id: string | null;
  device_id: string | null;
  created_at: number;
  read_at: number | null;
  conv: string | null;
  agent_id: string | null;
  /** JSON array of quick replies. */
  options: string | null;
  /** JSON array of Attachment. */
  attachments: string | null;
}

const parseJson = <T>(s: string | null): T | undefined => {
  if (!s) return undefined;
  try {
    return JSON.parse(s) as T;
  } catch {
    return undefined;
  }
};

export function fromRow(r: MessageRow): Message {
  const options = parseJson<string[]>(r.options);
  const attachments = parseJson<Attachment[]>(r.attachments);
  return {
    msg_id: r.msg_id,
    from: r.sender,
    text: r.text,
    ...(r.reply_to ? { reply_to: r.reply_to } : {}),
    ...(r.client_msg_id ? { client_msg_id: r.client_msg_id } : {}),
    ...(r.device_id ? { device_id: r.device_id } : {}),
    conv: convOf(r.conv),
    ...(r.sender === "agent" ? { agent_id: r.agent_id ?? MAIN } : {}),
    ...(options?.length ? { options } : {}),
    ...(attachments?.length ? { attachments } : {}),
    created_at: r.created_at,
    ...(r.read_at ? { read_at: r.read_at } : {}),
  };
}

const ID = { type: "string", maxLength: 64 } as const;
export const CONV_SCHEMA = {
  type: "string",
  pattern: "^((agent|group):[a-z0-9][a-z0-9_-]{0,31}|bots:[a-z0-9][a-z0-9_-]{0,31}\\.[a-z0-9][a-z0-9_-]{0,31})$",
} as const;
export const OPTIONS_SCHEMA = { type: "array", maxItems: 10, items: { type: "string", minLength: 1, maxLength: 40 } } as const;

export interface NewMessage {
  text: string;
  conv?: string;
  reply_to?: string;
  client_msg_id?: string;
  options?: string[];
  attachments?: string[];
}

/** Who writes: an agent (by id) or the user from a device. */
export type Author = { agentId: string } | { deviceId: string };

/**
 * Checks, stores and announces a message. Shared by POST /v1/messages and the reminder scheduler.
 * Throws ApiError for a conversation the author can't write in, unknown files, or an empty message.
 */
export function createMessage(ctx: Ctx, author: Author, body: NewMessage): { row: MessageRow; created: boolean } {
  const { db, events } = ctx;
  const agentId = "agentId" in author ? author.agentId : null;
  const from: Party = agentId !== null ? "agent" : "user";
  const conv = body.conv ?? dmOf(agentId ?? MAIN);
  if (agentId !== null) {
    // An agent writes in its own conversation, its groups, and bots: conversations with an existing agent.
    // (Checked here too because the reminder scheduler writes without a request: the agent may be gone.)
    const c = parseConv(conv);
    const ok = ctx.agents.exists(agentId) && ctx.agents.canSee(agentId, conv) && (c?.kind !== "bots" || ctx.agents.convExists(conv));
    if (!ok) throw forbidden("An agent can only write in its own conversation, its groups and its bots: conversations");
  }
  if (agentId === null && !ctx.agents.convExists(conv)) throw new ApiError(404, "not_found", "Conversation not found");
  if (agentId === null && body.options?.length) throw new ApiError(400, "invalid_request", "Only agents send quick replies");

  if (body.client_msg_id) {
    const existing = db
      .prepare("SELECT * FROM messages WHERE sender = ? AND client_msg_id = ? AND COALESCE(agent_id, 'main') = ?")
      .get(from, body.client_msg_id, agentId ?? MAIN) as unknown as MessageRow | undefined;
    if (existing) return { row: existing, created: false };
  }

  const attachments: Attachment[] = [];
  for (const id of new Set(body.attachments ?? [])) {
    const f = db.prepare("SELECT file_id, mime, size, duration_ms, agent_id, device_id FROM files WHERE file_id = ?").get(id) as
      | { file_id: string; mime: string; size: number; duration_ms: number | null; agent_id: string | null; device_id: string | null }
      | undefined;
    // Only your own uploads.
    const mine = f && ("agentId" in author ? f.agent_id === author.agentId : f.device_id !== null);
    if (!f || !mine) throw new ApiError(400, "invalid_request", `Unknown file ${id}`);
    attachments.push({ file_id: f.file_id, mime: f.mime, size: f.size, ...(f.duration_ms ? { duration_ms: f.duration_ms } : {}) });
  }
  const text = wellFormed(body.text);
  if (!text.trim() && attachments.length === 0) throw new ApiError(400, "invalid_request", "text must not be empty");
  checkMessage(db, ctx.limits());

  const options = body.options?.map((o) => wellFormed(o).trim()).filter(Boolean);
  const row: MessageRow = {
    msg_id: newId("msg"),
    sender: from,
    text,
    reply_to: body.reply_to === undefined ? null : wellFormed(body.reply_to),
    client_msg_id: body.client_msg_id ?? null,
    device_id: "deviceId" in author ? author.deviceId : null,
    created_at: Date.now(),
    read_at: null,
    conv,
    agent_id: agentId,
    options: options?.length ? JSON.stringify(options) : null,
    attachments: attachments.length ? JSON.stringify(attachments) : null,
  };
  db.prepare(
    `INSERT INTO messages (msg_id, sender, text, reply_to, client_msg_id, device_id, created_at, conv, agent_id, options, attachments)
     VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)`,
  ).run(
    row.msg_id, row.sender, row.text, row.reply_to, row.client_msg_id, row.device_id, row.created_at,
    conv, agentId, row.options, row.attachments,
  );
  events.emit({
    type: "msg.new",
    msg_id: row.msg_id,
    from,
    text,
    conv,
    ...(agentId ? { agent_id: agentId } : {}),
    ...(row.reply_to ? { reply_to: row.reply_to } : {}),
    ...(options?.length ? { options } : {}),
    ...(attachments.length ? { attachments } : {}),
  });
  return { row, created: true };
}

export function messageRoutes(app: FastifyInstance, ctx: Ctx): void {
  const { db, events } = ctx;
  const auth = requireAuth(ctx, "agent", "device");
  const get = (id: string) =>
    db.prepare("SELECT * FROM messages WHERE msg_id = ?").get(id) as unknown as MessageRow | undefined;
  /** A message the principal may see: a device sees everything, an agent its conversations. */
  const visible = (r: MessageRow | undefined, p: Principal): r is MessageRow =>
    r !== undefined && (p.kind === "device" || ctx.agents.canSee(p.agentId, r.conv));

  app.post<{ Body: NewMessage }>(
    "/v1/messages",
    {
      preHandler: auth,
      schema: {
        body: {
          type: "object",
          additionalProperties: false,
          required: ["text"],
          properties: {
            text: { type: "string", maxLength: 4000 },
            reply_to: ID,
            client_msg_id: ID,
            conv: CONV_SCHEMA,
            options: OPTIONS_SCHEMA,
            attachments: { type: "array", maxItems: 4, items: ID },
          },
        },
      },
    },
    async (req, reply) => {
      const p = principal(req);
      const author: Author = p.kind === "agent" ? { agentId: p.agentId } : { deviceId: p.deviceId };
      const { row } = createMessage(ctx, author, req.body);
      return reply.code(201).send(fromRow(row));
    },
  );

  app.get<{ Querystring: { after?: string; since?: number; from?: Party; conv?: string; limit?: number } }>(
    "/v1/messages",
    {
      preHandler: auth,
      schema: {
        querystring: {
          type: "object",
          properties: {
            after: ID,
            since: { type: "integer", minimum: 0 },
            from: { type: "string", enum: ["agent", "user"] },
            conv: CONV_SCHEMA,
            limit: { type: "integer", minimum: 1, maximum: 500, default: 100 },
          },
        },
      },
    },
    async (req) => {
      const { after, since, from, conv, limit = 100 } = req.query;
      const p = principal(req);
      const where: string[] = [];
      const args: Array<string | number> = [];
      if (p.kind === "agent") {
        const mine = ctx.agents.convsOf(p.agentId);
        where.push(
          `(COALESCE(conv, 'agent:main') IN (${mine.exact.map(() => "?").join(",")}) OR conv LIKE ? ESCAPE '\\' OR conv LIKE ? ESCAPE '\\')`,
        );
        args.push(...mine.exact, ...mine.botsLike);
      }
      if (conv) {
        where.push("COALESCE(conv, 'agent:main') = ?");
        args.push(conv);
      }
      if (after) {
        where.push("msg_id > ?");
        args.push(after);
      }
      if (since !== undefined) {
        where.push("created_at >= ?");
        args.push(since);
      }
      if (from) {
        where.push("sender = ?");
        args.push(from);
      }
      const rows = db
        .prepare(
          `SELECT * FROM messages ${where.length ? "WHERE " + where.join(" AND ") : ""} ORDER BY msg_id LIMIT ?`,
        )
        .all(...args, limit + 1) as unknown as MessageRow[];
      return { messages: rows.slice(0, limit).map(fromRow), has_more: rows.length > limit };
    },
  );

  app.get<{ Params: { msg_id: string } }>("/v1/messages/:msg_id", { preHandler: auth }, async (req) => {
    const row = get(req.params.msg_id);
    if (!visible(row, principal(req))) throw notFound("Message");
    return fromRow(row);
  });

  app.post<{ Params: { msg_id: string } }>("/v1/messages/:msg_id/delete", { preHandler: auth }, async (req, reply) => {
    const p = principal(req);
    const row = get(req.params.msg_id);
    // Only the author: the user (any of its phones) or that very agent.
    const mine =
      row !== undefined &&
      (p.kind === "device" ? row.sender === "user" : row.sender === "agent" && (row.agent_id ?? MAIN) === p.agentId);
    if (!mine) throw notFound("Message");
    db.prepare("DELETE FROM messages WHERE msg_id = ?").run(row.msg_id);
    events.emit({ type: "msg.deleted", msg_id: row.msg_id, conv: convOf(row.conv) });
    return reply.code(204).send();
  });

  app.post<{ Params: { msg_id: string } }>("/v1/messages/:msg_id/read", { preHandler: auth }, async (req, reply) => {
    const p = principal(req);
    const by = partyOf(p);
    const row = get(req.params.msg_id);
    // You can only mark the other party's messages as read.
    if (!visible(row, p) || row.sender === by) throw notFound("Message");
    const res = db
      .prepare("UPDATE messages SET read_at = ? WHERE msg_id = ? AND read_at IS NULL")
      .run(Date.now(), row.msg_id);
    if (res.changes === 1) events.emit({ type: "msg.read", msg_id: row.msg_id, by, conv: convOf(row.conv) });
    // Hivepal: an agent reading what the user just wrote is about to answer (each agent, also in a group
    // where another one already read it).
    if (p.kind === "agent" && row.sender === "user" && Date.now() - row.created_at < RECENT_MS) {
      ctx.typing?.set(p.agentId, convOf(row.conv), READ_TYPING_MS);
    }
    return reply.code(204).send();
  });

  app.post<{ Body: { conv?: string; typing?: boolean } | undefined }>(
    "/v1/typing",
    {
      preHandler: requireAuth(ctx, "agent"),
      // The body is optional: none means "typing in my own conversation".
      preValidation: async (req) => {
        req.body ??= {};
      },
      schema: {
        body: {
          type: "object",
          additionalProperties: false,
          properties: { conv: CONV_SCHEMA, typing: { type: "boolean" } },
        },
      },
    },
    async (req, reply) => {
      const p = principal(req);
      if (p.kind !== "agent") throw forbidden("Only agents write");
      const conv = req.body?.conv ?? dmOf(p.agentId);
      const c = parseConv(conv);
      if (!ctx.agents.canSee(p.agentId, conv) || (c?.kind === "bots" && !ctx.agents.convExists(conv))) {
        throw forbidden("An agent can only write in its own conversation, its groups and its bots: conversations");
      }
      ctx.typing?.set(p.agentId, conv, req.body?.typing === false ? 0 : TYPING_MS);
      return reply.code(204).send();
    },
  );
}
