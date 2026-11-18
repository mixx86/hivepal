/**
 * Hivepal: wake-up emails. Some agents only run on a trigger (a schedule, or an email arriving);
 * when the user writes to one (directly or in one of its groups), the relay emails the address set
 * with PUT /v1/agents/{id}/wake. The email is only a doorbell: it never carries the message, so a
 * leaked address gets nothing, and the agent reads its messages from the relay as usual.
 *
 * At most one email per agent per `gapMs`: messages that arrive in between are covered by one
 * trailing email when the gap ends (the agent may already be running and miss them).
 */
import type { FastifyBaseLogger } from "fastify";
import { createTransport } from "nodemailer";
import type { Agents } from "./agents.js";
import { parseConv } from "./agents.js";
import type { Event } from "./protocol.js";

export interface Mailer {
  send(to: string, subject: string, text: string): Promise<void>;
}

/** smtps://user:password@smtp.example.com:465 (or smtp://… with STARTTLS). */
export function smtpMailer(url: string, from?: string): Mailer {
  const transport = createTransport(url);
  const sender = from ?? decodeURIComponent(new URL(url).username);
  return {
    async send(to, subject, text) {
      await transport.sendMail({ from: sender, to, subject, text });
    },
  };
}

export class Wake {
  private readonly last = new Map<string, number>();
  private readonly pending = new Map<string, NodeJS.Timeout>();

  constructor(
    private readonly agents: Agents,
    private readonly mailer: Mailer,
    private readonly log: FastifyBaseLogger,
    private readonly gapMs = 60_000,
    private readonly now: () => number = Date.now,
  ) {}

  handle(e: Event): void {
    if (e.type !== "msg.new" || e.from !== "user") return;
    const c = parseConv(e.conv ?? "agent:main");
    const targets = c?.kind === "agent" ? [c.id] : c?.kind === "group" ? (this.agents.group(c.id)?.members ?? []) : [];
    for (const id of targets) this.ring(id);
  }

  private ring(agentId: string): void {
    if (!this.agents.wakeEmail(agentId) || this.pending.has(agentId)) return;
    const wait = (this.last.get(agentId) ?? -Infinity) + this.gapMs - this.now();
    if (wait <= 0) {
      this.send(agentId);
      return;
    }
    const t = setTimeout(() => {
      this.pending.delete(agentId);
      this.send(agentId);
    }, wait);
    t.unref?.();
    this.pending.set(agentId, t);
  }

  private send(agentId: string): void {
    const to = this.agents.wakeEmail(agentId);
    if (!to) return;
    this.last.set(agentId, this.now());
    const name = this.agents.nameOf(agentId);
    this.mailer
      .send(to, "Hivepal", `Tienes mensajes nuevos en Hivepal, ${name}. Léelos con: hivepal replies`)
      .catch((err: unknown) => this.log.warn({ err, agentId }, "wake email failed"));
  }

  stop(): void {
    for (const t of this.pending.values()) clearTimeout(t);
    this.pending.clear();
  }
}
