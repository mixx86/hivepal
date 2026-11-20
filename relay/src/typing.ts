/**
 * Hivepal: "typing…" (issue #48). Which agents are writing in which conversation, live only: each change
 * is an ephemeral `agent.typing` event for the phones, never stored or pushed, and it ends by itself.
 *
 * An agent says it's writing (`POST /v1/typing`, 20 s unless repeated); the relay also assumes it when an
 * agent marks a recent message from the user as read (60 s), since that's what `hivepal replies` does
 * before answering. Either way it ends when the agent writes in that conversation.
 */
import type { EventLog } from "./events.js";
import type { Event } from "./protocol.js";

/** How long one `POST /v1/typing` lasts. */
export const TYPING_MS = 20_000;
/** How long reading the user's message counts as writing an answer. */
export const READ_TYPING_MS = 60_000;
/** A message the user sent longer ago than this doesn't start "typing" when read. */
export const RECENT_MS = 10 * 60_000;

interface State {
  agentId: string;
  conv: string;
  until: number;
  timer: NodeJS.Timeout;
}

export class Typing {
  private readonly now = new Map<string, State>();

  constructor(private readonly events: EventLog) {}

  private emit(agentId: string, conv: string, typing: boolean, until?: number): void {
    this.events.emit({ type: "agent.typing", agent_id: agentId, conv, typing, ...(until ? { until } : {}) }, "devices");
  }

  /** The agent writes in `conv` for `ms` from now (or stops, with `ms` = 0). */
  set(agentId: string, conv: string, ms: number): void {
    const key = `${agentId}\u0000${conv}`;
    const old = this.now.get(key);
    if (old) clearTimeout(old.timer);
    if (ms <= 0) {
      if (!old) return;
      this.now.delete(key);
      this.emit(agentId, conv, false);
      return;
    }
    const until = Date.now() + ms;
    const timer = setTimeout(() => this.set(agentId, conv, 0), ms);
    timer.unref?.();
    this.now.set(key, { agentId, conv, until, timer });
    this.emit(agentId, conv, true, until);
  }

  /** It wrote, or it's gone: no longer typing anywhere ([conv] given: only there). */
  stop(agentId: string, conv?: string): void {
    for (const s of [...this.now.values()]) if (s.agentId === agentId && (conv === undefined || s.conv === conv)) this.set(s.agentId, s.conv, 0);
  }

  /** Who is writing right now, for a phone that just connected. */
  current(): Array<Omit<Extract<Event, { type: "agent.typing" }>, "id" | "ts" | "seq">> {
    return [...this.now.values()].map((s) => ({ type: "agent.typing", agent_id: s.agentId, conv: s.conv, typing: true, until: s.until }));
  }

  close(): void {
    for (const s of this.now.values()) clearTimeout(s.timer);
    this.now.clear();
  }
}
