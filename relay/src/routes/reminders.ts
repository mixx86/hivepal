import type { FastifyInstance } from "fastify";
import { dmOf } from "../agents.js";
import { type Ctx, principal, requireAuth } from "../context.js";
import { ApiError, notFound } from "../errors.js";
import { newId, wellFormed } from "../ids.js";
import type { Schemas } from "../protocol.js";
import { CONV_SCHEMA, OPTIONS_SCHEMA, createMessage } from "./messages.js";

type Reminder = Schemas["Reminder"];

interface ReminderRow {
  rem_id: string;
  agent_id: string;
  conv: string;
  text: string;
  options: string | null;
  at: number;
  created_at: number;
}

const view = (r: ReminderRow): Reminder => ({
  rem_id: r.rem_id,
  agent_id: r.agent_id,
  conv: r.conv,
  text: r.text,
  ...(r.options ? { options: JSON.parse(r.options) as string[] } : {}),
  at: r.at,
  created_at: r.created_at,
});

const YEAR_MS = 366 * 24 * 3600 * 1000;
const TICK_MS = 10_000;

/**
 * Messages an agent schedules: at their time the relay posts them as the agent (with a push),
 * so a bot that only runs now and then can still remind the user at 19:00 sharp.
 */
export class Reminders {
  private timer?: NodeJS.Timeout;

  constructor(private readonly ctx: Ctx) {}

  start(): void {
    this.tick();
    this.timer = setInterval(() => this.tick(), TICK_MS);
    this.timer.unref();
  }

  stop(): void {
    clearInterval(this.timer);
  }

  /** Sends what is due. A reminder that can no longer be sent (agent removed, group gone) is canceled. */
  tick(now = Date.now()): number {
    const { db, log } = this.ctx;
    const due = db
      .prepare("SELECT * FROM reminders WHERE sent_at IS NULL AND canceled_at IS NULL AND at <= ? ORDER BY at")
      .all(now) as unknown as ReminderRow[];
    for (const r of due) {
      try {
        createMessage(this.ctx, { agentId: r.agent_id }, {
          text: r.text,
          conv: r.conv,
          ...(r.options ? { options: JSON.parse(r.options) as string[] } : {}),
          client_msg_id: r.rem_id,
        });
        db.prepare("UPDATE reminders SET sent_at = ? WHERE rem_id = ?").run(now, r.rem_id);
      } catch (err) {
        log.warn({ err, reminder: r.rem_id }, "reminder could not be sent; canceled");
        db.prepare("UPDATE reminders SET canceled_at = ? WHERE rem_id = ?").run(now, r.rem_id);
      }
    }
    return due.length;
  }
}

export function reminderRoutes(app: FastifyInstance, ctx: Ctx, reminders: Reminders): void {
  const { db } = ctx;

  app.post<{ Body: { text: string; at: number; conv?: string; options?: string[] } }>(
    "/v1/reminders",
    {
      preHandler: requireAuth(ctx, "agent"),
      schema: {
        body: {
          type: "object",
          additionalProperties: false,
          required: ["text", "at"],
          properties: {
            text: { type: "string", minLength: 1, maxLength: 4000 },
            at: { type: "integer", minimum: 0 },
            conv: CONV_SCHEMA,
            options: OPTIONS_SCHEMA,
          },
        },
      },
    },
    async (req, reply) => {
      const p = principal(req);
      if (p.kind !== "agent") throw new Error("unreachable");
      const conv = req.body.conv ?? dmOf(p.agentId);
      if (!ctx.agents.canSee(p.agentId, conv) && !conv.startsWith("bots:")) {
        throw new ApiError(403, "forbidden", "An agent can only schedule in its own conversations");
      }
      const now = Date.now();
      if (req.body.at > now + YEAR_MS) throw new ApiError(400, "invalid_request", "at is more than a year ahead");
      const row: ReminderRow = {
        rem_id: newId("rem"),
        agent_id: p.agentId,
        conv,
        text: wellFormed(req.body.text),
        options: req.body.options?.length ? JSON.stringify(req.body.options) : null,
        at: req.body.at,
        created_at: now,
      };
      db.prepare("INSERT INTO reminders (rem_id, agent_id, conv, text, options, at, created_at) VALUES (?, ?, ?, ?, ?, ?, ?)").run(
        row.rem_id, row.agent_id, row.conv, row.text, row.options, row.at, row.created_at,
      );
      // One already due (at in the past) goes out now.
      if (row.at <= now) reminders.tick(now);
      return reply.code(201).send(view(row));
    },
  );

  app.get("/v1/reminders", { preHandler: requireAuth(ctx, "agent", "device") }, async (req) => {
    const p = principal(req);
    const rows = (
      p.kind === "agent"
        ? db.prepare("SELECT * FROM reminders WHERE sent_at IS NULL AND canceled_at IS NULL AND agent_id = ? ORDER BY at").all(p.agentId)
        : db.prepare("SELECT * FROM reminders WHERE sent_at IS NULL AND canceled_at IS NULL ORDER BY at").all()
    ) as unknown as ReminderRow[];
    return { reminders: rows.map(view) };
  });

  app.delete<{ Params: { rem_id: string } }>(
    "/v1/reminders/:rem_id",
    { preHandler: requireAuth(ctx, "agent", "device") },
    async (req, reply) => {
      const p = principal(req);
      const res = db
        .prepare(
          `UPDATE reminders SET canceled_at = ? WHERE rem_id = ? AND sent_at IS NULL AND canceled_at IS NULL
           ${p.kind === "agent" ? "AND agent_id = ?" : ""}`,
        )
        .run(Date.now(), req.params.rem_id, ...(p.kind === "agent" ? [p.agentId] : []));
      if (res.changes !== 1) throw notFound("Reminder");
      return reply.code(204).send();
    },
  );
}
