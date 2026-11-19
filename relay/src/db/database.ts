import { mkdirSync } from "node:fs";
import { dirname } from "node:path";
import { DatabaseSync } from "node:sqlite";

const MIGRATIONS: string[] = [
  `
  CREATE TABLE meta (key TEXT PRIMARY KEY, value TEXT NOT NULL);

  CREATE TABLE pairing_tokens (
    token_hash TEXT PRIMARY KEY,
    expires_at INTEGER NOT NULL,
    used_at INTEGER,
    created_at INTEGER NOT NULL
  );

  CREATE TABLE devices (
    device_id TEXT PRIMARY KEY,
    name TEXT NOT NULL,
    platform TEXT NOT NULL,
    app_version TEXT,
    push_secret TEXT NOT NULL,
    fcm_token TEXT,
    created_at INTEGER NOT NULL,
    last_seen_at INTEGER,
    revoked_at INTEGER
  );

  CREATE TABLE messages (
    msg_id TEXT PRIMARY KEY,
    sender TEXT NOT NULL CHECK (sender IN ('agent','user')),
    text TEXT NOT NULL,
    reply_to TEXT,
    client_msg_id TEXT,
    device_id TEXT,
    created_at INTEGER NOT NULL,
    read_at INTEGER
  );
  CREATE UNIQUE INDEX messages_client_id ON messages(sender, client_msg_id) WHERE client_msg_id IS NOT NULL;
  CREATE INDEX messages_created ON messages(created_at);

  CREATE TABLE events (
    seq INTEGER PRIMARY KEY AUTOINCREMENT,
    id TEXT NOT NULL UNIQUE,
    type TEXT NOT NULL,
    ts INTEGER NOT NULL,
    body TEXT NOT NULL
  );
  `,
  `
  CREATE TABLE calls (
    call_id TEXT PRIMARY KEY,
    direction TEXT NOT NULL CHECK (direction IN ('agent_to_user','user_to_agent')),
    state TEXT NOT NULL CHECK (state IN ('ringing','active','ended','missed','declined','canceled')),
    reason TEXT NOT NULL DEFAULT '',
    priority TEXT NOT NULL CHECK (priority IN ('normal','urgent')),
    agent_name TEXT NOT NULL,
    created_at INTEGER NOT NULL,
    expires_at INTEGER NOT NULL,
    answered_at INTEGER,
    ended_at INTEGER,
    ended_by TEXT,
    end_reason TEXT,
    device_id TEXT
  );
  CREATE INDEX calls_open ON calls(state) WHERE state IN ('ringing','active');
  `,
  `
  -- Group calls: every paired phone rings and any of them may answer while it rings.
  CREATE TABLE call_participants (
    call_id TEXT NOT NULL REFERENCES calls(call_id),
    device_id TEXT NOT NULL,
    state TEXT NOT NULL CHECK (state IN ('ringing','joined','declined','missed','left')),
    updated_at INTEGER NOT NULL,
    PRIMARY KEY (call_id, device_id)
  );
  `,
  `
  -- Targeted calls: JSON array of the device ids the call rings; NULL = every paired phone.
  ALTER TABLE calls ADD COLUMN device_ids TEXT;
  `,
  `
  -- Multi-agent fork: agents with their own API keys, group conversations, and the
  -- conversation of every message (NULL = 'agent:main', from before the fork).
  CREATE TABLE agents (
    agent_id TEXT PRIMARY KEY,
    name TEXT NOT NULL,
    key_hash TEXT,
    created_at INTEGER NOT NULL,
    revoked_at INTEGER
  );
  CREATE UNIQUE INDEX agents_key ON agents(key_hash) WHERE key_hash IS NOT NULL;
  CREATE TABLE agent_groups (
    group_id TEXT PRIMARY KEY,
    name TEXT NOT NULL,
    created_at INTEGER NOT NULL
  );
  CREATE TABLE agent_group_members (
    group_id TEXT NOT NULL REFERENCES agent_groups(group_id),
    agent_id TEXT NOT NULL,
    PRIMARY KEY (group_id, agent_id)
  );
  ALTER TABLE messages ADD COLUMN conv TEXT;
  ALTER TABLE messages ADD COLUMN agent_id TEXT;
  CREATE INDEX messages_conv ON messages(conv, msg_id);
  ALTER TABLE calls ADD COLUMN agent_id TEXT;
  `,
  `
  -- Hivepal: quick replies and attachments on messages, uploaded files, scheduled reminders.
  ALTER TABLE messages ADD COLUMN options TEXT;
  ALTER TABLE messages ADD COLUMN attachments TEXT;
  CREATE TABLE files (
    file_id TEXT PRIMARY KEY,
    mime TEXT NOT NULL,
    size INTEGER NOT NULL,
    data BLOB NOT NULL,
    agent_id TEXT,
    device_id TEXT,
    duration_ms INTEGER,
    created_at INTEGER NOT NULL
  );
  CREATE TABLE reminders (
    rem_id TEXT PRIMARY KEY,
    agent_id TEXT NOT NULL,
    conv TEXT NOT NULL,
    text TEXT NOT NULL,
    options TEXT,
    at INTEGER NOT NULL,
    created_at INTEGER NOT NULL,
    sent_at INTEGER,
    canceled_at INTEGER
  );
  CREATE INDEX reminders_due ON reminders(at) WHERE sent_at IS NULL AND canceled_at IS NULL;
  `,
  `
  -- Hivepal: when each agent last used its key (is its routine still running?).
  CREATE TABLE agent_seen (agent_id TEXT PRIMARY KEY, at INTEGER NOT NULL);
  `,
  `
  -- Hivepal: voice-note transcripts; reminders that insist (follow up, then call) until answered.
  ALTER TABLE files ADD COLUMN transcript TEXT;
  ALTER TABLE reminders ADD COLUMN insist_s INTEGER;
  ALTER TABLE reminders ADD COLUMN call INTEGER NOT NULL DEFAULT 0;
  ALTER TABLE reminders ADD COLUMN msg_id TEXT;
  ALTER TABLE reminders ADD COLUMN followups INTEGER NOT NULL DEFAULT 0;
  ALTER TABLE reminders ADD COLUMN done_at INTEGER;
  `,
  `
  -- Hivepal: where to email an agent that only runs on a trigger when the user writes to it.
  CREATE TABLE agent_wake (agent_id TEXT PRIMARY KEY, email TEXT NOT NULL);
  `,
  `
  -- Hivepal: subscriptions and when each renews or ends (read by the user's scraper).
  CREATE TABLE subscriptions (
    sub_id TEXT PRIMARY KEY,
    name TEXT NOT NULL,
    plan TEXT,
    status TEXT NOT NULL,
    until INTEGER,
    price TEXT,
    url TEXT,
    error TEXT,
    checked_at INTEGER NOT NULL
  );
  `,
  `
  -- Hivepal: a device woken through UnifiedPush (its endpoint) instead of FCM.
  ALTER TABLE devices ADD COLUMN up_endpoint TEXT;
  `,
  `
  -- Hivepal: the app's Web Push keys, to encrypt what goes through the UnifiedPush distributor.
  ALTER TABLE devices ADD COLUMN up_p256dh TEXT;
  ALTER TABLE devices ADD COLUMN up_auth TEXT;
  `,
];

/** For upgrade tests: a database at an older schema. */
export const MIGRATIONS_FOR_TEST: readonly string[] = MIGRATIONS;

export type Db = DatabaseSync;

/** Opens (creating if needed) a SQLite database and runs `migrations` it hasn't run yet. */
export function openDatabase(path: string, migrations: string[] = MIGRATIONS): Db {
  if (path !== ":memory:") mkdirSync(dirname(path), { recursive: true });
  const db = new DatabaseSync(path);
  db.exec("PRAGMA journal_mode = WAL; PRAGMA foreign_keys = ON; PRAGMA busy_timeout = 5000;");
  db.exec("CREATE TABLE IF NOT EXISTS schema_version (version INTEGER NOT NULL)");
  const row = db.prepare("SELECT version FROM schema_version").get() as { version: number } | undefined;
  let version = row?.version ?? 0;
  if (!row) db.prepare("INSERT INTO schema_version (version) VALUES (0)").run();
  for (; version < migrations.length; version++) {
    db.exec("BEGIN");
    try {
      db.exec(migrations[version]!);
      db.prepare("UPDATE schema_version SET version = ?").run(version + 1);
      db.exec("COMMIT");
    } catch (e) {
      db.exec("ROLLBACK");
      throw e;
    }
  }
  return db;
}

export function getMeta(db: Db, key: string): string | undefined {
  const row = db.prepare("SELECT value FROM meta WHERE key = ?").get(key) as { value: string } | undefined;
  return row?.value;
}

export function setMeta(db: Db, key: string, value: string): void {
  db.prepare("INSERT INTO meta (key, value) VALUES (?, ?) ON CONFLICT(key) DO UPDATE SET value = excluded.value").run(
    key,
    value,
  );
}

/** Returns the stored value, creating it with `make()` on first use. */
export function metaOrInit(db: Db, key: string, make: () => string): string {
  const existing = getMeta(db, key);
  if (existing !== undefined) return existing;
  const v = make();
  setMeta(db, key, v);
  return v;
}
