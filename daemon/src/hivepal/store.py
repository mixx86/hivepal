"""Local SQLite mirror of the relay's messages (and, from M2, calls)."""

from __future__ import annotations

import logging
import os
import sqlite3
from pathlib import Path
from typing import Any

log = logging.getLogger("hivepal.store")

SCHEMA = """
CREATE TABLE IF NOT EXISTS state (key TEXT PRIMARY KEY, value TEXT NOT NULL);
CREATE TABLE IF NOT EXISTS messages (
    msg_id TEXT PRIMARY KEY,
    sender TEXT NOT NULL,
    text TEXT NOT NULL,
    reply_to TEXT,
    created_at INTEGER NOT NULL,
    read_at INTEGER,
    -- when `hivepal replies` / get_replies last handed this message to the agent
    delivered_at INTEGER,
    -- multi-agent fork: the conversation (NULL = agent:main) and the sending agent
    conv TEXT,
    agent_id TEXT,
    -- Hivepal: JSON arrays of quick replies and attachments
    options TEXT,
    attachments TEXT
);
CREATE INDEX IF NOT EXISTS messages_created ON messages(created_at);
"""

#: Columns added after the first release, for mirrors created by an older daemon.
_ADDED_COLUMNS = {"conv": "TEXT", "agent_id": "TEXT", "options": "TEXT", "attachments": "TEXT"}


def _make_private(path: Path) -> None:
    """The mirror holds the user's messages, however the daemon was configured: only the owner
    reads it. A new directory is created 0700 (an existing one may be shared and is left alone)
    and the database 0600 before SQLite opens it; SQLite gives its -wal and -shm files the
    database's mode. Files from an earlier version are tightened."""
    path.parent.mkdir(parents=True, exist_ok=True, mode=0o700)
    os.close(os.open(path, os.O_CREAT | os.O_RDWR, 0o600))
    for f in (path, path.with_name(path.name + "-wal"), path.with_name(path.name + "-shm")):
        try:
            f.chmod(0o600)
        except FileNotFoundError:
            pass
        except OSError as e:
            log.warning("cannot make %s private: %s", f, e)


class Store:
    def __init__(self, path: Path | str):
        if str(path) != ":memory:":
            _make_private(Path(path))
        self.db = sqlite3.connect(str(path), check_same_thread=False, isolation_level=None)
        self.db.row_factory = sqlite3.Row
        self.db.execute("PRAGMA journal_mode=WAL")
        self.db.execute("PRAGMA busy_timeout=5000")
        self.db.executescript(SCHEMA)
        have = {r["name"] for r in self.db.execute("PRAGMA table_info(messages)")}
        for col, typ in _ADDED_COLUMNS.items():
            if col not in have:
                self.db.execute(f"ALTER TABLE messages ADD COLUMN {col} {typ}")

    def close(self) -> None:
        self.db.close()

    # state
    def get_state(self, key: str, default: str | None = None) -> str | None:
        row = self.db.execute("SELECT value FROM state WHERE key = ?", (key,)).fetchone()
        return row["value"] if row else default

    def set_state(self, key: str, value: str) -> None:
        self.db.execute(
            "INSERT INTO state (key, value) VALUES (?, ?) ON CONFLICT(key) DO UPDATE SET value = excluded.value",
            (key, value),
        )

    @property
    def last_seq(self) -> int:
        return int(self.get_state("last_seq", "0") or 0)

    def advance_seq(self, seq: int) -> None:
        if seq > self.last_seq:
            self.set_state("last_seq", str(seq))

    def reset_if_relay_changed(self, relay_id: str) -> bool:
        """A different relay (or a wiped one) means our cursor is meaningless."""
        known = self.get_state("relay_id")
        if known == relay_id:
            return False
        self.set_state("relay_id", relay_id)
        if known is not None:
            self.set_state("last_seq", "0")
            return True
        return False

    # messages
    def upsert_message(self, msg_id: str, sender: str, text: str, created_at: int, reply_to: str | None = None,
                       conv: str | None = None, agent_id: str | None = None, options: list | None = None,
                       attachments: list | None = None) -> bool:
        import json

        cur = self.db.execute(
            "INSERT OR IGNORE INTO messages (msg_id, sender, text, reply_to, created_at, conv, agent_id, options, attachments)"
            " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
            (msg_id, sender, text, reply_to, created_at, conv, agent_id,
             json.dumps(options) if options else None, json.dumps(attachments) if attachments else None),
        )
        return cur.rowcount == 1

    @property
    def self_agent_id(self) -> str:
        """The agent this daemon's key belongs to (multi-agent fork); `main` for the relay API key."""
        return self.get_state("self_agent_id", "main") or "main"

    def mark_read(self, msg_id: str, ts: int) -> None:
        self.db.execute("UPDATE messages SET read_at = COALESCE(read_at, ?) WHERE msg_id = ?", (ts, msg_id))

    def replies(self, *, since: int | None = None, undelivered_only: bool = False, limit: int = 100,
                conv: str | None = None, after: str | None = None) -> list[dict[str, Any]]:
        """Messages for this agent: the user's, and in a group also the other agents'.
        `after`: only messages with a later id (ids sort by time)."""
        me = self.self_agent_id
        # Not our own messages, and not another agent's conversation: a mirror that was filled with
        # the relay's shared key (before this agent had its own) holds those, as agent:main.
        q = ("SELECT * FROM messages WHERE NOT (sender = 'agent' AND COALESCE(agent_id, 'main') = ?)"
             " AND (COALESCE(conv, 'agent:main') LIKE 'group:%' OR COALESCE(conv, 'agent:main') = ?"
             " OR conv LIKE ? OR conv LIKE ?)")
        args: list[Any] = [me, f"agent:{me}", f"bots:{me}.%", f"bots:%.{me}"]
        if conv is not None:
            q += " AND COALESCE(conv, 'agent:main') = ?"
            args.append(conv)
        if since is not None:
            q += " AND created_at >= ?"
            args.append(since)
        if after is not None:
            q += " AND msg_id > ?"
            args.append(after)
        if undelivered_only:
            q += " AND delivered_at IS NULL"
        q += " ORDER BY msg_id LIMIT ?"
        args.append(limit)
        return [dict(r) for r in self.db.execute(q, args).fetchall()]

    def delete_message(self, msg_id: str) -> None:
        self.db.execute("DELETE FROM messages WHERE msg_id = ?", (msg_id,))

    def message(self, msg_id: str) -> dict[str, Any] | None:
        row = self.db.execute("SELECT * FROM messages WHERE msg_id = ?", (msg_id,)).fetchone()
        return dict(row) if row else None

    def mark_delivered(self, msg_ids: list[str], ts: int) -> None:
        self.db.executemany("UPDATE messages SET delivered_at = ? WHERE msg_id = ?", [(ts, m) for m in msg_ids])
