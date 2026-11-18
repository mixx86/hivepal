import type { Config } from "./config.js";
import type { Db } from "./db/database.js";
import { randomToken } from "./ids.js";
import type { Schemas } from "./protocol.js";
import { sha256 } from "./auth.js";

/** The agent behind the relay's own API key (and hub agent tokens). It always exists. */
export const MAIN = "main";

export type Agent = Schemas["Agent"];
export type Group = Schemas["Group"];

export const AGENT_ID_RE = /^[a-z0-9][a-z0-9_-]{0,31}$/;
const CONV_RE = /^(agent|group):([a-z0-9][a-z0-9_-]{0,31})$/;
const BOTS_RE = /^bots:([a-z0-9][a-z0-9_-]{0,31})\.([a-z0-9][a-z0-9_-]{0,31})$/;

/** `agent:<id>` or `group:<id>`; anything older than the multi-agent fork belongs to `agent:main`. */
export const dmOf = (agentId: string): string => `agent:${agentId}`;
export const convOf = (conv: string | null | undefined): string => conv ?? dmOf(MAIN);

/** The conversation between two agents: `bots:<a>.<b>`, ids sorted. */
export const botsOf = (a: string, b: string): string => (a < b ? `bots:${a}.${b}` : `bots:${b}.${a}`);

export type ParsedConv =
  | { kind: "agent" | "group"; id: string }
  | { kind: "bots"; id: string; pair: [string, string] };

export function parseConv(conv: string): ParsedConv | undefined {
  const m = CONV_RE.exec(conv);
  if (m) return { kind: m[1] as "agent" | "group", id: m[2]! };
  const b = BOTS_RE.exec(conv);
  // Only the canonical (sorted, two different agents) form is a conversation.
  if (b && b[1]! < b[2]!) return { kind: "bots", id: `${b[1]}.${b[2]}`, pair: [b[1]!, b[2]!] };
  return undefined;
}

interface AgentRow {
  agent_id: string;
  name: string;
  key_hash: string | null;
  created_at: number;
  revoked_at: number | null;
}

interface GroupRow {
  group_id: string;
  name: string;
  created_at: number;
}

/**
 * Agents with their own API keys, and group conversations (multi-agent fork).
 * `main` is the relay API key's agent; its name defaults to AGENTRING_AGENT_NAME until renamed.
 */
export class Agents {
  constructor(
    private readonly db: Db,
    private readonly cfg: Config,
  ) {}

  private row(id: string): AgentRow | undefined {
    return this.db.prepare("SELECT * FROM agents WHERE agent_id = ? AND revoked_at IS NULL").get(id) as unknown as
      | AgentRow
      | undefined;
  }

  private view(r: AgentRow): Agent {
    const seen = this.lastSeen(r.agent_id);
    return { agent_id: r.agent_id, name: r.name, created_at: r.created_at, ...(seen ? { last_seen_at: seen } : {}) };
  }

  private seenCache = new Map<string, number>();

  /** Records an agent's request, at most once a minute per agent (it's on every request). */
  seen(agentId: string, now = Date.now()): void {
    const last = this.seenCache.get(agentId) ?? 0;
    if (now - last < 60_000) return;
    this.seenCache.set(agentId, now);
    this.db
      .prepare("INSERT INTO agent_seen (agent_id, at) VALUES (?, ?) ON CONFLICT(agent_id) DO UPDATE SET at = excluded.at")
      .run(agentId, now);
  }

  lastSeen(agentId: string): number | undefined {
    const r = this.db.prepare("SELECT at FROM agent_seen WHERE agent_id = ?").get(agentId) as { at: number } | undefined;
    return r?.at;
  }

  private mainAgent(): Agent {
    const r = this.row(MAIN);
    if (r) return this.view(r);
    const seen = this.lastSeen(MAIN);
    return { agent_id: MAIN, name: this.cfg.agentName, created_at: 0, ...(seen ? { last_seen_at: seen } : {}) };
  }

  exists(id: string): boolean {
    return id === MAIN || this.row(id) !== undefined;
  }

  get(id: string): Agent | undefined {
    if (id === MAIN) return this.mainAgent();
    const r = this.row(id);
    return r && this.view(r);
  }

  nameOf(id: string): string {
    return this.get(id)?.name ?? id;
  }

  /** `main` first, then the others in the order they were added. */
  list(): Agent[] {
    const rows = this.db
      .prepare("SELECT * FROM agents WHERE revoked_at IS NULL AND agent_id <> ? ORDER BY created_at, agent_id")
      .all(MAIN) as unknown as AgentRow[];
    return [this.mainAgent(), ...rows.map((r) => this.view(r))];
  }

  /** The agent a per-agent API key belongs to. */
  byKeyHash(hash: Buffer): string | undefined {
    const r = this.db
      .prepare("SELECT agent_id FROM agents WHERE key_hash = ? AND revoked_at IS NULL")
      .get(hash.toString("hex")) as { agent_id: string } | undefined;
    return r?.agent_id;
  }

  /** A revoked agent's id can be reused: the old row is replaced. */
  create(id: string, name: string): { agent: Agent; apiKey: string } | undefined {
    if (id === MAIN || this.row(id)) return undefined;
    const apiKey = randomToken("ak");
    const now = Date.now();
    this.db.prepare("DELETE FROM agents WHERE agent_id = ?").run(id);
    this.db
      .prepare("INSERT INTO agents (agent_id, name, key_hash, created_at) VALUES (?, ?, ?, ?)")
      .run(id, name, sha256(apiKey).toString("hex"), now);
    return { agent: { agent_id: id, name, created_at: now }, apiKey };
  }

  rename(id: string, name: string): Agent | undefined {
    if (id === MAIN) {
      this.db
        .prepare(
          `INSERT INTO agents (agent_id, name, key_hash, created_at) VALUES (?, ?, NULL, ?)
           ON CONFLICT(agent_id) DO UPDATE SET name = excluded.name`,
        )
        .run(MAIN, name, Date.now());
      return this.mainAgent();
    }
    const res = this.db.prepare("UPDATE agents SET name = ? WHERE agent_id = ? AND revoked_at IS NULL").run(name, id);
    return res.changes === 1 ? this.get(id) : undefined;
  }

  rotateKey(id: string): { agent: Agent; apiKey: string } | undefined {
    if (id === MAIN || !this.row(id)) return undefined;
    const apiKey = randomToken("ak");
    this.db.prepare("UPDATE agents SET key_hash = ? WHERE agent_id = ?").run(sha256(apiKey).toString("hex"), id);
    return { agent: this.get(id)!, apiKey };
  }

  /** Revokes the key and leaves every group. Returns the groups that changed. */
  remove(id: string): Group[] | undefined {
    if (id === MAIN || !this.row(id)) return undefined;
    const groups = this.groupsOf(id);
    this.db.prepare("UPDATE agents SET revoked_at = ?, key_hash = NULL WHERE agent_id = ?").run(Date.now(), id);
    this.db.prepare("DELETE FROM agent_group_members WHERE agent_id = ?").run(id);
    return groups.map((g) => this.group(g)!).filter(Boolean);
  }

  // ---------- groups ----------

  private members(groupId: string): string[] {
    return (
      this.db
        .prepare("SELECT agent_id FROM agent_group_members WHERE group_id = ? ORDER BY rowid")
        .all(groupId) as Array<{ agent_id: string }>
    ).map((r) => r.agent_id);
  }

  group(id: string): Group | undefined {
    const r = this.db.prepare("SELECT * FROM agent_groups WHERE group_id = ?").get(id) as unknown as GroupRow | undefined;
    return r && { group_id: r.group_id, name: r.name, members: this.members(r.group_id), created_at: r.created_at };
  }

  /** All groups, or only those `agentId` belongs to. */
  groups(agentId?: string): Group[] {
    const rows = (
      agentId === undefined
        ? this.db.prepare("SELECT group_id FROM agent_groups ORDER BY created_at, group_id").all()
        : this.db
            .prepare(
              `SELECT g.group_id FROM agent_groups g JOIN agent_group_members m ON m.group_id = g.group_id
               WHERE m.agent_id = ? ORDER BY g.created_at, g.group_id`,
            )
            .all(agentId)
    ) as Array<{ group_id: string }>;
    return rows.map((r) => this.group(r.group_id)!);
  }

  groupsOf(agentId: string): string[] {
    return (
      this.db.prepare("SELECT group_id FROM agent_group_members WHERE agent_id = ?").all(agentId) as Array<{
        group_id: string;
      }>
    ).map((r) => r.group_id);
  }

  /** Members that aren't agents (or are listed twice) are an error: returns the bad id. */
  unknownMember(members: string[]): string | undefined {
    const seen = new Set<string>();
    for (const m of members) {
      if (seen.has(m) || !this.exists(m)) return m;
      seen.add(m);
    }
    return undefined;
  }

  createGroup(id: string, name: string, members: string[]): Group | undefined {
    if (this.group(id)) return undefined;
    this.db.prepare("INSERT INTO agent_groups (group_id, name, created_at) VALUES (?, ?, ?)").run(id, name, Date.now());
    this.setMembers(id, members);
    return this.group(id);
  }

  updateGroup(id: string, patch: { name?: string; members?: string[] }): Group | undefined {
    if (!this.group(id)) return undefined;
    if (patch.name !== undefined) this.db.prepare("UPDATE agent_groups SET name = ? WHERE group_id = ?").run(patch.name, id);
    if (patch.members !== undefined) this.setMembers(id, patch.members);
    return this.group(id);
  }

  removeGroup(id: string): Group | undefined {
    const g = this.group(id);
    if (!g) return undefined;
    this.db.prepare("DELETE FROM agent_group_members WHERE group_id = ?").run(id);
    this.db.prepare("DELETE FROM agent_groups WHERE group_id = ?").run(id);
    return g;
  }

  private setMembers(id: string, members: string[]): void {
    this.db.prepare("DELETE FROM agent_group_members WHERE group_id = ?").run(id);
    const ins = this.db.prepare("INSERT INTO agent_group_members (group_id, agent_id) VALUES (?, ?)");
    for (const m of members) ins.run(id, m);
  }

  isMember(groupId: string, agentId: string): boolean {
    return (
      this.db.prepare("SELECT 1 FROM agent_group_members WHERE group_id = ? AND agent_id = ?").get(groupId, agentId) !==
      undefined
    );
  }

  /** Whether an agent takes part in a conversation: its own, or a group it belongs to. */
  canSee(agentId: string, conv: string | null | undefined): boolean {
    const c = parseConv(convOf(conv));
    if (!c) return false;
    if (c.kind === "bots") return c.pair.includes(agentId);
    return c.kind === "agent" ? c.id === agentId : this.isMember(c.id, agentId);
  }

  /** Every conversation an agent takes part in (for SQL filters). Its bots: ones are matched by pattern. */
  convsOf(agentId: string): { exact: string[]; botsLike: [string, string] } {
    return {
      exact: [dmOf(agentId), ...this.groupsOf(agentId).map((g) => `group:${g}`)],
      botsLike: [`bots:${agentId}.%`, `bots:%.${agentId}`],
    };
  }

  /** Whether a conversation exists (an agent's, or a group). */
  convExists(conv: string): boolean {
    const c = parseConv(conv);
    if (!c) return false;
    if (c.kind === "bots") return c.pair.every((a) => this.exists(a));
    return c.kind === "agent" ? this.exists(c.id) : this.group(c.id) !== undefined;
  }
}
