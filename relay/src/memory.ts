/**
 * Hivepal: bots' memory as SQLite (issue #41). The relay already keeps every message; with this memory
 * on, an agent can search its own conversations (`GET /v1/agents/{id}/memory?q=…`, full-text through
 * the `messages_fts` index) or download them as a SQLite file of its own
 * (`GET /v1/agents/{id}/memory.sqlite`) to query however it likes. Only the conversations the agent
 * takes part in: its chat with the user, its groups and its agent-to-agent chats.
 */
import { mkdtempSync, rmSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { DatabaseSync } from "node:sqlite";
import type { Agents } from "./agents.js";
import type { Db } from "./db/database.js";

interface Row {
  rowid: number;
  msg_id: string;
  sender: "agent" | "user";
  agent_id: string | null;
  text: string;
  conv: string | null;
  reply_to: string | null;
  created_at: number;
}

export interface MemoryMessage {
  msg_id: string;
  conv: string;
  from: string;
  from_name: string;
  text: string;
  reply_to?: string;
  created_at: number;
}

/** A user's words as an FTS5 query: each word quoted, so nothing in them is query syntax. */
export function ftsQuery(q: string): string {
  return q
    .split(/\s+/)
    .filter(Boolean)
    .slice(0, 20)
    .map((w) => `"${w.replace(/"/g, '""')}"`)
    .join(" ");
}

export class Memory {
  constructor(
    private readonly db: Db,
    private readonly agents: Agents,
  ) {}

  private view(r: Row): MemoryMessage {
    const from = r.sender === "user" ? "user" : (r.agent_id ?? "main");
    return {
      msg_id: r.msg_id,
      conv: r.conv ?? "agent:main",
      from,
      from_name: from === "user" ? "User" : (this.agents.get(from)?.name ?? from),
      text: r.text,
      ...(r.reply_to ? { reply_to: r.reply_to } : {}),
      created_at: r.created_at,
    };
  }

  /** Newest first: the agent's messages matching `q` (all of them without it), in `conv` if given, before `before`. */
  search(agentId: string, o: { q?: string; conv?: string; before?: number; limit?: number }): MemoryMessage[] {
    const limit = Math.min(Math.max(o.limit ?? 50, 1), 500);
    const where: string[] = [];
    const args: Array<string | number> = [];
    if (o.conv === "agent:main") where.push("(m.conv IS NULL OR m.conv = 'agent:main')");
    else if (o.conv) {
      where.push("m.conv = ?");
      args.push(o.conv);
    }
    if (o.before) {
      where.push("m.created_at < ?");
      args.push(o.before);
    }
    const q = o.q?.trim() ? ftsQuery(o.q) : "";
    const sql = q
      ? `SELECT m.rowid, m.* FROM messages_fts f JOIN messages m ON m.rowid = f.rowid WHERE messages_fts MATCH ? ${where.map((w) => `AND ${w}`).join(" ")} ORDER BY m.created_at DESC, m.rowid DESC LIMIT ?`
      : `SELECT m.rowid, m.* FROM messages m ${where.length ? `WHERE ${where.join(" AND ")}` : ""} ORDER BY m.created_at DESC, m.rowid DESC LIMIT ?`;
    // Visibility is checked per row; read a few pages ahead so a busy relay still fills the page.
    const rows = this.db.prepare(sql).all(...(q ? [q] : []), ...args, limit * 20) as unknown as Row[];
    return rows.filter((r) => this.agents.canSee(agentId, r.conv)).slice(0, limit).map((r) => this.view(r));
  }

  /** The agent's conversations as a SQLite file of their own (the caller deletes the folder). */
  exportFile(agentId: string): { dir: string; file: string } {
    const dir = mkdtempSync(join(tmpdir(), "hivepal-memory-"));
    const file = join(dir, `${agentId}-memory.sqlite`);
    try {
      const out = new DatabaseSync(file);
      out.exec(`
        CREATE TABLE messages (
          msg_id TEXT PRIMARY KEY, conv TEXT NOT NULL, sender TEXT NOT NULL, sender_name TEXT NOT NULL,
          text TEXT NOT NULL, reply_to TEXT, created_at INTEGER NOT NULL, date TEXT NOT NULL
        );
        CREATE INDEX messages_conv ON messages(conv, created_at);
        CREATE VIRTUAL TABLE messages_fts USING fts5(text, content='messages', content_rowid='rowid');
        CREATE TABLE about (key TEXT PRIMARY KEY, value TEXT NOT NULL);
      `);
      const ins = out.prepare("INSERT INTO messages (msg_id, conv, sender, sender_name, text, reply_to, created_at, date) VALUES (?, ?, ?, ?, ?, ?, ?, ?)");
      out.exec("BEGIN");
      for (const r of this.db.prepare("SELECT rowid, * FROM messages ORDER BY created_at, rowid").iterate() as Iterable<Row>) {
        if (!this.agents.canSee(agentId, r.conv)) continue;
        const m = this.view(r);
        ins.run(m.msg_id, m.conv, m.from, m.from_name, m.text, m.reply_to ?? null, m.created_at, new Date(m.created_at).toISOString());
      }
      out.exec("INSERT INTO messages_fts(messages_fts) VALUES('rebuild')");
      out.prepare("INSERT INTO about (key, value) VALUES (?, ?), (?, ?)").run("agent_id", agentId, "exported_at", new Date().toISOString());
      out.exec("COMMIT");
      out.close();
      return { dir, file };
    } catch (e) {
      rmSync(dir, { recursive: true, force: true });
      throw e;
    }
  }
}
