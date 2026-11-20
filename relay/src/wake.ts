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
  /** Logs in to the mail server without sending (checking a new account). */
  verify?(): Promise<void>;
}

/** smtps://user:password@smtp.example.com:465 (or smtp://… with STARTTLS). */
export function smtpMailer(url: string, from?: string): Mailer {
  const transport = createTransport(url, { connectionTimeout: 15_000, greetingTimeout: 15_000 } as never);
  const sender = from ?? decodeURIComponent(new URL(url).username);
  return {
    async send(to, subject, text) {
      await transport.sendMail({ from: sender, to, subject, text });
    },
    async verify() {
      await transport.verify();
    },
  };
}

/** The mailer for the current SMTP URL, rebuilt when the URL changes (it's set at runtime). */
export function mailerFor(current: () => string | undefined, make: (url: string) => Mailer = (u) => smtpMailer(u, process.env.HIVEPAL_WAKE_FROM)) {
  let url: string | undefined;
  let mailer: Mailer | undefined;
  return (): Mailer | undefined => {
    const now = current();
    if (now !== url) {
      url = now;
      mailer = now ? make(now) : undefined;
    }
    return mailer;
  };
}

/**
 * Several agents may share one address (Grok gives one per account, not per bot): the relay sends one
 * email per address, and its subject lists the agents it is for ("Hivepal: casa nubi"), so each agent
 * checks whether it's meant and the others stop at once.
 */
export class Wake {
  private readonly last = new Map<string, number>();
  private readonly pending = new Map<string, NodeJS.Timeout>();
  /** Agents waiting for the next email to each address. */
  private readonly queued = new Map<string, Set<string>>();

  constructor(
    private readonly agents: Agents,
    /** The mailer now, or undefined when the relay has no mail account (then nothing is sent). */
    private readonly mailer: () => Mailer | undefined,
    private readonly log: FastifyBaseLogger,
    private readonly gapMs = 60_000,
    private readonly now: () => number = Date.now,
  ) {}

  /** A new message from the user: email its agents, except `later` (rung by `ringAgents` once their mailbox is written). */
  handle(e: Event, later: Iterable<string> = []): void {
    if (e.type !== "msg.new" || e.from !== "user") return;
    const c = parseConv(e.conv ?? "agent:main");
    const skip = new Set(later);
    const targets = (c?.kind === "agent" ? [c.id] : c?.kind === "group" ? (this.agents.group(c.id)?.members ?? []) : []).filter((id) => !skip.has(id));
    this.ringAgents(targets);
  }

  /** Emails these agents (those with an address), one email per address and gap. */
  ringAgents(targets: string[]): void {
    if (!this.mailer()) return;
    const touched = new Set<string>();
    for (const id of targets) {
      const to = this.agents.wakeEmail(id);
      if (!to) continue;
      const q = this.queued.get(to) ?? new Set<string>();
      q.add(id);
      this.queued.set(to, q);
      touched.add(to);
    }
    for (const to of touched) this.ring(to);
  }

  private ring(to: string): void {
    if (this.pending.has(to)) return;
    const wait = (this.last.get(to) ?? -Infinity) + this.gapMs - this.now();
    if (wait <= 0) {
      this.send(to);
      return;
    }
    const t = setTimeout(() => {
      this.pending.delete(to);
      this.send(to);
    }, wait);
    t.unref?.();
    this.pending.set(to, t);
  }

  /** Sends the agent's wake-up email now (a test from the panel or the CLI); throws what the mail server said. */
  async test(agentId: string): Promise<void> {
    const to = this.agents.wakeEmail(agentId);
    if (!to) throw new Error("no address");
    const mailer = this.mailer();
    if (!mailer) throw new Error("no mail account");
    await mailer.send(to, `Hivepal: ${agentId}`, `Test from Hivepal: the wake-up email for ${agentId} works. Read new messages with: hivepal replies`);
  }

  private send(to: string): void {
    const ids = [...(this.queued.get(to) ?? [])].sort();
    this.queued.delete(to);
    if (ids.length === 0) return;
    this.last.set(to, this.now());
    const mailer = this.mailer();
    if (!mailer) return;
    mailer
      .send(to, `Hivepal: ${ids.join(" ")}`, `Mensajes nuevos en Hivepal para: ${ids.join(", ")}. Léelos con: hivepal replies`)
      .catch((err: unknown) => this.log.warn({ err, to }, "wake email failed"));
  }

  stop(): void {
    for (const t of this.pending.values()) clearTimeout(t);
    this.pending.clear();
  }
}
