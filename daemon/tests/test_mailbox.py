"""Mailbox files: `hivepal mailbox DIR` writes an agent's new messages as Markdown files."""

from __future__ import annotations

import json
import subprocess
import time
from pathlib import Path

import httpx
import pytest
from typer.testing import CliRunner

from hivepal import mailbox as mb
from hivepal.cli import app
from hivepal.store import Store

from .conftest import API_KEY
from .test_e2e import simulate_phone_pairing

runner = CliRunner()
NOW = int(time.time() * 1000)


def cli(home: Path, *args: str) -> str:
    r = runner.invoke(app, list(args), env={"HIVEPAL_HOME": str(home)})
    assert r.exit_code == 0, r.output
    return r.output


def _store(me: str = "nubi") -> Store:
    s = Store(":memory:")
    s.set_state("self_agent_id", me)
    return s


def test_writes_each_message_once_and_keeps_a_cursor(tmp_path: Path):
    s = _store()
    s.upsert_message("msg_01", "user", "old one", NOW - 3 * 86_400_000, conv="agent:nubi")
    s.upsert_message("msg_02", "user", "Hola Nubi", NOW, conv="agent:nubi")
    s.upsert_message("msg_03", "agent", "my own", NOW, conv="agent:nubi", agent_id="nubi")
    s.upsert_message("msg_04", "user", "for Rutina", NOW, conv="agent:rutina")
    s.upsert_message("msg_05", "agent", "Gym a las 18:00", NOW, conv="group:todos", agent_id="rutina")

    # First run: from --since on, only this agent's messages, never its own.
    written = mb.write(s, tmp_path, mb.pending(s, tmp_path, NOW - 86_400_000), {"group:todos": "Todos",
                                                                                "agent:rutina": "Rutina"})
    assert [w.msg_id for w in written] == ["msg_02", "msg_05"]
    assert all(w.path.parent == tmp_path / "nubi" for w in written)
    first = written[0].path.read_text()
    assert first.startswith("---\nmsg_id: msg_02\nfrom: \"user\"\n")
    assert 'to: "nubi"' in first and 'conv: "agent:nubi"' in first and first.endswith("\n\nHola Nubi\n")
    group = written[1].path.read_text()
    assert 'from: "rutina"' in group and 'from_name: "Rutina"' in group and 'group: "Todos"' in group
    assert written[1].path.name.endswith("-rutina-msg_05.md")

    # Nothing new: nothing written. A new message: only that one.
    assert mb.write(s, tmp_path, mb.pending(s, tmp_path, 0), {}) == []
    s.upsert_message("msg_06", "user", "¿Y mañana?", NOW + 1000, conv="agent:nubi", reply_to="msg_05",
                     options=["Sí", "No"])
    [w] = mb.write(s, tmp_path, mb.pending(s, tmp_path, 0), {})
    text = w.path.read_text()
    assert "reply_to: msg_05" in text and "> In reply to: Gym a las 18:00" in text
    assert "Quick replies offered: Sí · No" in text


def test_a_file_moved_to_a_subfolder_is_not_written_again(tmp_path: Path):
    s = _store()
    s.upsert_message("msg_10", "user", "léeme", NOW, conv="agent:nubi")
    [w] = mb.write(s, tmp_path, mb.pending(s, tmp_path, 0), {})
    read = tmp_path / "nubi" / "read"
    read.mkdir()
    w.path.rename(read / w.path.name)
    # Even without the cursor (a fresh mirror), the file already in the mailbox counts.
    s2 = _store()
    s2.upsert_message("msg_10", "user", "léeme", NOW, conv="agent:nubi")
    assert mb.write(s2, tmp_path, mb.pending(s2, tmp_path, 0), {}) == []
    assert [p.name for p in (tmp_path / "nubi").glob("*.md")] == []


def test_attachments_and_transcripts(tmp_path: Path):
    s = _store()
    s.upsert_message("msg_20", "user", "", NOW, conv="agent:nubi",
                     attachments=[{"file_id": "file_a", "mime": "audio/ogg"}, {"file_id": "file_b", "mime": "image/jpeg"}])
    [w] = mb.write(s, tmp_path, mb.pending(s, tmp_path, 0), {}, transcript=lambda f: "voy al gym" if f == "file_a" else None)
    text = w.path.read_text()
    assert "- audio: `file_a` — transcript: voy al gym" in text
    assert "- image: `file_b`" in text


def test_cursor_is_applied_before_the_limit(tmp_path: Path):
    s = _store()
    for i in range(520):
        s.upsert_message(f"msg_{i:04d}", "user", f"m{i}", NOW, conv="agent:nubi")
    assert len(mb.write(s, tmp_path, mb.pending(s, tmp_path, 0), {})) == 500
    assert [w.msg_id for w in mb.write(s, tmp_path, mb.pending(s, tmp_path, 0), {})] == [f"msg_{i:04d}" for i in range(500, 520)]


def _git(cwd: Path, *args: str) -> str:
    return subprocess.run(["git", "-C", str(cwd), *args], check=True, capture_output=True, text=True).stdout


def test_git_publish_commits_only_the_new_files(tmp_path: Path):
    remote, work = tmp_path / "remote.git", tmp_path / "work"
    subprocess.run(["git", "init", "-q", "--bare", "-b", "main", str(remote)], check=True)
    subprocess.run(["git", "clone", "-q", str(remote), str(work)], check=True)
    for k, v in {"user.name": "t", "user.email": "t@example.com"}.items():
        _git(work, "config", k, v)
    (work / "notes.md").write_text("x")
    _git(work, "add", "notes.md")
    _git(work, "commit", "-qm", "init")
    _git(work, "push", "-q", "origin", "HEAD:main")
    _git(work, "branch", "-q", "--set-upstream-to=origin/main")
    (work / "notes.md").write_text("an unrelated edit, not committed")

    s = _store()
    s.upsert_message("msg_30", "user", "hola", NOW, conv="agent:nubi")
    root = work / "mailbox"
    written = mb.write(s, root, mb.pending(s, root, 0), {})
    mb.git_publish(root, [w.path for w in written], "mailbox/nubi: 1 message")
    assert _git(remote, "log", "-1", "--format=%s", "main").strip() == "mailbox/nubi: 1 message"
    assert _git(remote, "show", "--name-only", "--format=", "main").split() == [f"mailbox/nubi/{written[0].path.name}"]
    assert (work / "notes.md").read_text() == "an unrelated edit, not committed"


def test_mailbox_command_against_the_relay(relay: str, tmp_path: Path, monkeypatch: pytest.MonkeyPatch):
    monkeypatch.delenv("HIVEPAL_RELAY_URL", raising=False)
    monkeypatch.delenv("HIVEPAL_API_KEY", raising=False)
    admin, nubi = tmp_path / "admin", tmp_path / "nubi"
    cli(admin, "init", "--relay", relay, "--api-key", API_KEY)
    phone = simulate_phone_pairing(json.loads(cli(admin, "pair", "--no-wait", "--json"))["qr_payload"])
    key = json.loads(cli(admin, "bots", "add", "nubi", "Nubi", "--json"))["api_key"]
    cli(nubi, "init", "--relay", relay, "--api-key", key)
    httpx.post(f"{relay}/v1/messages", headers=phone["headers"], json={"text": "para Nubi", "conv": "agent:nubi"}).raise_for_status()

    box = tmp_path / "buzon"
    out = json.loads(cli(nubi, "mailbox", str(box), "--json"))["written"]
    assert len(out) == 1 and Path(out[0]["path"]).parent == box / "nubi"
    assert Path(out[0]["path"]).read_text().endswith("\n\npara Nubi\n")
    assert "No new messages." in cli(nubi, "mailbox", str(box))
    # The mailbox doesn't take the message away from `hivepal replies`.
    assert [r["text"] for r in json.loads(cli(nubi, "replies", "--json"))["replies"]] == ["para Nubi"]
