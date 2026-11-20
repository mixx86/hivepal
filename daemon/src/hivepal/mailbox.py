"""Mailbox files: the agent's new messages written as Markdown files in a folder it reads.

For agents that work from files rather than from `hivepal replies`: a notes repository where each
agent has a mailbox folder, read on a schedule (docs/guide/hive.md). `hivepal mailbox DIR` writes
every new message for this daemon's agent (the user's, and in its groups and agent-to-agent
conversations the other agents') to `DIR/<agent id>/<date>-<time>-<sender>-<msg id>.md`, one file
per message so two writers never touch the same file. With `--git` it commits those files and
pushes them.

This is a second way to reach an agent, next to wake-up emails (the relay emails a doorbell; the
agent then runs `hivepal replies`). It doesn't mark messages as seen for `hivepal replies`, and
doesn't send read receipts: an agent uses one way or the other.
"""

from __future__ import annotations

import json
import re
import subprocess
from collections.abc import Callable, Iterable
from dataclasses import dataclass
from datetime import datetime
from pathlib import Path
from typing import Any

from .store import Store

#: Where the store keeps, per mailbox folder, the last message written there.
_CURSOR = "mailbox_cursor:"
_SAFE = re.compile(r"[^A-Za-z0-9_.-]+")


@dataclass
class Written:
    msg_id: str
    path: Path


def _cursor_key(root: Path) -> str:
    return _CURSOR + str(root.resolve())


def _yaml_str(s: str) -> str:
    """A YAML scalar that can't be misread: JSON strings are valid YAML."""
    return json.dumps(s, ensure_ascii=False)


def _local(ms: int) -> datetime:
    return datetime.fromtimestamp(ms / 1000).astimezone()


def file_name(row: dict[str, Any]) -> str:
    sender = "user" if row["sender"] == "user" else (row["agent_id"] or "main")
    return f"{_local(row['created_at']):%Y%m%d-%H%M}-{_SAFE.sub('_', sender)}-{_SAFE.sub('_', row['msg_id'])}.md"


def render(row: dict[str, Any], me: str, names: dict[str, str], quoted: str | None = None,
           transcripts: dict[str, str] | None = None) -> str:
    """One message as Markdown with a small YAML header (who, where, when, what it answers)."""
    conv = row["conv"] or "agent:main"
    if row["sender"] == "user":
        sender, sender_name = "user", "user"
    else:
        sender = row["agent_id"] or "main"
        sender_name = names.get(f"agent:{sender}", sender)
    head = [
        "---",
        f"msg_id: {row['msg_id']}",
        f"from: {_yaml_str(sender)}",
        f"from_name: {_yaml_str(sender_name)}",
        f"to: {_yaml_str(me)}",
        f"conv: {_yaml_str(conv)}",
    ]
    if conv.startswith("group:"):
        head.append(f"group: {_yaml_str(names.get(conv, conv[6:]))}")
    head.append(f"date: {_local(row['created_at']).isoformat(timespec='seconds')}")
    if row.get("reply_to"):
        head.append(f"reply_to: {row['reply_to']}")
    head.append("---")
    body = [row["text"]]
    if quoted is not None:
        q = quoted if len(quoted) <= 200 else quoted[:200] + "…"
        body.append("\n".join("> " + line for line in f"In reply to: {q}".splitlines()))
    options = json.loads(row["options"]) if row.get("options") else []
    if options:
        body.append("Quick replies offered: " + " · ".join(str(o) for o in options))
    attachments = json.loads(row["attachments"]) if row.get("attachments") else []
    if attachments:
        lines = ["Attachments (download with `hivepal file <file_id>`):"]
        for a in attachments:
            kind = str(a.get("mime", "file")).split("/")[0]
            line = f"- {kind}: `{a.get('file_id', '?')}`"
            t = (transcripts or {}).get(a.get("file_id", ""))
            if t:
                line += f" — transcript: {t}"
            lines.append(line)
        body.append("\n".join(lines))
    return "\n".join(head) + "\n\n" + "\n\n".join(b for b in body if b) + "\n"


def pending(store: Store, root: Path, since_ms: int) -> list[dict[str, Any]]:
    """Messages for this agent not yet written to `root`: after the folder's cursor, or on the first
    run, from `since_ms` on (a new mailbox doesn't get the whole history)."""
    cursor = store.get_state(_cursor_key(root))
    return store.replies(since=None if cursor else since_ms, after=cursor, limit=500)


def write(store: Store, root: Path, rows: Iterable[dict[str, Any]], names: dict[str, str],
          transcript: Callable[[str], str | None] | None = None) -> list[Written]:
    """Writes each message once; a file already there (even moved to a subfolder such as `read/`)
    is left alone. Moves the folder's cursor past every message handled."""
    me = store.self_agent_id
    box = root / me
    box.mkdir(parents=True, exist_ok=True)
    existing = {p.name for p in box.rglob("*.md")}
    out: list[Written] = []
    last = store.get_state(_cursor_key(root))
    for r in rows:
        name = file_name(r)
        if name not in existing:
            quoted = None
            if r.get("reply_to"):
                orig = store.message(r["reply_to"])
                quoted = orig["text"] if orig else None
            transcripts: dict[str, str] = {}
            if transcript and r["sender"] == "user" and r.get("attachments"):
                for a in json.loads(r["attachments"]):
                    if str(a.get("mime", "")).startswith("audio/"):
                        t = transcript(a["file_id"])
                        if t:
                            transcripts[a["file_id"]] = t
            path = box / name
            tmp = path.with_suffix(".md.tmp")
            tmp.write_text(render(r, me, names, quoted, transcripts), encoding="utf-8")
            tmp.replace(path)
            out.append(Written(r["msg_id"], path))
        if last is None or r["msg_id"] > last:
            last = r["msg_id"]
    if last is not None:
        store.set_state(_cursor_key(root), last)
    return out


class GitError(Exception):
    pass


def git_publish(root: Path, files: list[Path], message: str) -> None:
    """Commits exactly `files` in the repository holding `root` and pushes (rebasing on what other
    writers pushed meanwhile). Other changes in that checkout are left as they are."""
    def git(*args: str) -> str:
        r = subprocess.run(["git", "-C", str(root), *args], capture_output=True, text=True)
        if r.returncode != 0:
            raise GitError(f"git {' '.join(args)}: {(r.stderr or r.stdout).strip()}")
        return r.stdout

    if not files:
        return
    rel = [str(f.resolve()) for f in files]
    git("add", "--", *rel)
    git("commit", "-q", "-m", message, "--", *rel)
    git("pull", "-q", "--rebase", "--autostash")
    git("push", "-q")


# ---------- running it every minute (crontab) ----------

_MARK = "# hivepal mailbox: "


def cron_line(hivepal_bin: str, home: Path, root: Path, git: bool) -> str:
    """The crontab line that runs `hivepal mailbox` every minute for this agent and folder, logging
    to the agent's home. Its trailing marker names the home and folder, so installing again replaces it."""
    import shlex

    q = shlex.quote
    cmd = (f"HIVEPAL_HOME={q(str(home))} {q(hivepal_bin)} mailbox {q(str(root))}{' --git' if git else ''}"
           f" >> {q(str(home / 'mailbox.log'))} 2>&1")
    # cron reads % as a newline unless escaped.
    return f"* * * * * {cmd.replace('%', chr(92) + '%')} {_MARK}{home} -> {root}"


def with_line(crontab: str, line: str | None, home: Path, root: Path) -> str:
    """The crontab with this agent and folder's line replaced by `line` (removed when None); the rest kept."""
    mark = f"{_MARK}{home} -> {root}"
    kept = [ln for ln in crontab.splitlines() if not ln.endswith(mark)]
    if line:
        kept.append(line)
    return "\n".join(kept) + ("\n" if kept else "")


def _crontab() -> str:
    r = subprocess.run(["crontab", "-l"], capture_output=True, text=True)
    # No crontab yet: `crontab -l` fails with "no crontab for <user>".
    return r.stdout if r.returncode == 0 else ""


def set_cron(line: str | None, home: Path, root: Path) -> None:
    """Installs (or, with None, removes) the line in the user's crontab. Raises OSError without cron."""
    import shutil

    if not shutil.which("crontab"):
        raise OSError("there's no crontab command here: install cron, or run the line above from a timer")
    new = with_line(_crontab(), line, home, root)
    r = subprocess.run(["crontab", "-"], input=new, capture_output=True, text=True)
    if r.returncode != 0:
        raise OSError(r.stderr.strip() or "crontab refused the new table")

