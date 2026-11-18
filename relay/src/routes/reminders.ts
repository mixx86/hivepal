import type { FastifyInstance } from "fastify";
import { dmOf } from "../agents.js";
import { type Ctx, principal, requireAuth } from "../context.js";
import { ApiError, notFound } from "../errors.js";
import { newId, wellFormed } from "../ids.js";
import type { Schemas } from "../protocol.js";
import { ROOM_GRACE_S, roomName } from "./calls.js";
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
  insist_s?: number | null;
  call?: number;
  msg_id?: string | null;
  sent_at?: number | null;
  followups?: number;
}

const view = (r: ReminderRow): Reminder => ({
  rem_id: r.rem_id,
  agent_id: r.agent_id,
  conv: r.conv,
  text: r.text,
  ...(r.options ? { options: JSON.parse(r.options) as string[] } : {}),
  at: r.at,
  created_at: r.created_at,
  ...(r.insist_s ? { insist_s: r.insist_s, call: r.call === 1 } : {}),
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
        const { row } = createMessage(this.ctx, { agentId: r.agent_id }, {
          text: r.text,
          conv: r.conv,
          ...(r.options ? { options: JSON.parse(r.options) as string[] } : {}),
          client_msg_id: r.rem_id,
        });
        // sent_at = when the message really went out: "answered" means a user message after it.
        db.prepare("UPDATE reminders SET sent_at = ?, msg_id = ?, done_at = CASE WHEN insist_s IS NULL THEN ? END WHERE rem_id = ?").run(
          row.created_at, row.msg_id, now, r.rem_id,
        );
      } catch (err) {
        log.warn({ err, reminder: r.rem_id }, "reminder could not be sent; canceled");
        db.prepare("UPDATE reminders SET canceled_at = ? WHERE rem_id = ?").run(now, r.rem_id);
      }
    }
    this.insist(now);
    return due.length;
  }

  /**
   * Reminders sent with `insist`: while the user hasn't written in that conversation since,
   * the relay writes again after `insist_s`, and (with `call`) rings the phone after another `insist_s`.
   */
  private insist(now: number): void {
    const { db, log } = this.ctx;
    const open = db
      .prepare("SELECT * FROM reminders WHERE insist_s IS NOT NULL AND sent_at IS NOT NULL AND done_at IS NULL AND canceled_at IS NULL")
      .all() as unknown as ReminderRow[];
    for (const r of open) {
      const answered = db
        .prepare("SELECT 1 FROM messages WHERE sender = 'user' AND COALESCE(conv, 'agent:main') = ? AND created_at >= ? LIMIT 1")
        .get(r.conv, r.sent_at!);
      const done = () => db.prepare("UPDATE reminders SET done_at = ? WHERE rem_id = ?").run(now, r.rem_id);
      if (answered) {
        done();
        continue;
      }
      const followups = r.followups ?? 0;
      if (now < r.sent_at! + r.insist_s! * 1000 * (followups + 1)) continue;
      try {
        if (followups === 0) {
          createMessage(this.ctx, { agentId: r.agent_id }, {
            text: `⏰ ${r.text}`,
            conv: r.conv,
            ...(r.options ? { options: JSON.parse(r.options) as string[] } : {}),
            client_msg_id: `${r.rem_id}-again`,
          });
          db.prepare("UPDATE reminders SET followups = 1 WHERE rem_id = ?").run(r.rem_id);
          if (!r.call) done();
        } else {
          done();
          this.ring(r);
        }
      } catch (err) {
        log.warn({ err, reminder: r.rem_id }, "reminder follow-up failed");
        done();
      }
    }
  }

  /** The last step of an insisting reminder: the phone rings with the reminder as the reason. */
  private ring(r: ReminderRow): void {
    const calls = this.ctx.calls;
    if (!calls) return;
    try {
      const call = calls.create({ kind: "agent", agentId: r.agent_id }, { reason: r.text.slice(0, 500), priority: "urgent" });
      void this.ctx.livekit
        ?.createRoom(roomName(this.ctx, call.call_id), this.ctx.cfg.ringTimeoutS + ROOM_GRACE_S)
        .catch(() => undefined);
    } catch (err) {
      this.ctx.log.warn({ err, reminder: r.rem_id }, "reminder call failed");
    }
  }
}

export function reminderRoutes(app: FastifyInstance, ctx: Ctx, reminders: Reminders): void {
  const { db } = ctx;

  app.post<{ Body: { text: string; at: number; conv?: string; options?: string[]; insist_s?: number; call?: boolean } }>(
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
            insist_s: { type: "integer", minimum: 60, maximum: 6 * 3600 },
            call: { type: "boolean" },
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
      row.insist_s = req.body.insist_s ?? null;
      row.call = req.body.call && req.body.insist_s ? 1 : 0;
      db.prepare(
        "INSERT INTO reminders (rem_id, agent_id, conv, text, options, at, created_at, insist_s, call) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
      ).run(row.rem_id, row.agent_id, row.conv, row.text, row.options, row.at, row.created_at, row.insist_s, row.call);
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
