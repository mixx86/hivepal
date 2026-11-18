"""Hivepal CLI: quick replies, files, reminders, bot conversations, personas."""

from __future__ import annotations

import asyncio
import json
from pathlib import Path

import httpx
import pytest
from typer.testing import CliRunner

from agentring.cli import _when, app, with_personas
from agentring.voice.session import CallInfo

from .conftest import API_KEY
from .test_e2e import simulate_phone_pairing

runner = CliRunner()


def cli(home: Path, *args: str) -> str:
    r = runner.invoke(app, list(args), env={"AGENTRING_HOME": str(home)})
    assert r.exit_code == 0, r.output
    return r.output


@pytest.fixture()
def hive(relay: str, tmp_path: Path, monkeypatch: pytest.MonkeyPatch):
    monkeypatch.delenv("AGENTRING_RELAY_URL", raising=False)
    monkeypatch.delenv("AGENTRING_API_KEY", raising=False)
    admin, nubi, rutina = tmp_path / "admin", tmp_path / "nubi", tmp_path / "rutina"
    cli(admin, "init", "--relay", relay, "--api-key", API_KEY)
    phone = simulate_phone_pairing(json.loads(cli(admin, "pair", "--no-wait", "--json"))["qr_payload"])
    for home, aid, name in ((nubi, "nubi", "Nubi"), (rutina, "rutina", "Rutina")):
        key = json.loads(cli(admin, "bots", "add", aid, name, "--json"))["api_key"]
        cli(home, "init", "--relay", relay, "--api-key", key)
    return {"relay": relay, "phone": phone, "admin": admin, "nubi": nubi, "rutina": rutina, "tmp": tmp_path}


def test_quick_replies_and_files(hive):
    relay, phone, nubi = hive["relay"], hive["phone"], hive["nubi"]
    photo = hive["tmp"] / "foto.png"
    photo.write_bytes(bytes.fromhex("89504e470d0a1a0a0000000d49484452"))
    mid = json.loads(cli(nubi, "msg", "¿Vas al gym?", "-o", "Sí", "-o", "No", "-f", str(photo), "--json"))["msg_id"]
    m = httpx.get(f"{relay}/v1/messages/{mid}", headers=phone["headers"]).json()
    assert m["options"] == ["Sí", "No"] and m["attachments"][0]["mime"] == "image/png"

    # The user taps "Sí" (a reply) and sends a voice clip.
    httpx.post(f"{relay}/v1/messages", headers=phone["headers"],
               json={"text": "Sí", "reply_to": mid, "conv": "agent:nubi"}).raise_for_status()
    up = httpx.post(f"{relay}/v1/files", headers={**phone["headers"], "content-type": "audio/mp4"}, content=b"\x00" * 64).json()
    httpx.post(f"{relay}/v1/messages", headers=phone["headers"],
               json={"text": "", "conv": "agent:nubi", "attachments": [up["file_id"]]}).raise_for_status()
    out = cli(nubi, "replies")
    assert "Sí (reply to: «¿Vas al gym?»)" in out and f"[audio: {up['file_id']}]" in out
    path = Path(cli(nubi, "file", up["file_id"], "-o", str(hive["tmp"] / "nota.m4a")).strip())
    assert path.read_bytes() == b"\x00" * 64


def test_bots_talk_to_each_other(hive):
    cli(hive["nubi"], "msg", "¿Movemos el gym?", "--to", "rutina")
    out = cli(hive["rutina"], "replies")
    assert "[Nubi → you] ¿Movemos el gym?" in out
    cli(hive["rutina"], "msg", "Vale, al jueves", "--to", "nubi")
    replies = json.loads(cli(hive["nubi"], "replies", "--json"))["replies"]
    assert [(r["author"], r["text"]) for r in replies] == [("Rutina", "Vale, al jueves")]


def test_reminders(hive):
    out = cli(hive["nubi"], "remind", "Hora de IPE", "--in", "2h", "-o", "Voy")
    rem = out.split()[0]
    assert rem.startswith("rem_")
    assert "Hora de IPE" in cli(hive["nubi"], "reminders")
    assert "No pending" in cli(hive["rutina"], "reminders")
    cli(hive["nubi"], "reminders", "--cancel", rem)
    assert "No pending" in cli(hive["nubi"], "reminders")


def test_when():
    from datetime import datetime

    base = int(datetime(2026, 11, 18, 20, 0).astimezone().timestamp() * 1000)
    assert _when(None, "30m", base) == base + 30 * 60_000
    at = _when("19:00", None, base)  # already past today → tomorrow
    assert datetime.fromtimestamp(at / 1000).day == 19 and datetime.fromtimestamp(at / 1000).hour == 19
    assert datetime.fromtimestamp(_when("21:15", None, base) / 1000).day == 18


def test_personas(tmp_path: Path):
    ins = with_personas("BASE", tmp_path)
    run = lambda info: asyncio.run(ins(info))  # noqa: E731
    assert run(CallInfo("c1", direction="user_to_agent")) == "BASE"
    assert run(CallInfo("c1", direction="user_to_agent", agent_id="main")) == "BASE"
    out = run(CallInfo("c1", direction="user_to_agent", agent_id="nubi", agent_name="Nubi"))
    assert out.startswith("En esta llamada eres Nubi") and out.endswith("BASE")
    (tmp_path / "personas").mkdir()
    (tmp_path / "personas" / "nubi.md").write_text("Soy Nubi, directa y breve.")
    assert run(CallInfo("c1", direction="user_to_agent", agent_id="nubi")) == "Soy Nubi, directa y breve.\n\nBASE"


def test_insisting_reminder_and_last_seen(hive):
    out = cli(hive["rutina"], "remind", "A dormir", "--in", "1h", "--insist", "15m", "--call", "--json")
    r = json.loads(out)
    assert r["insist_s"] == 900 and r["call"] is True
    r = runner.invoke(app, ["remind", "x", "--in", "1h", "--call"], env={"AGENTRING_HOME": str(hive["rutina"])})
    assert r.exit_code != 0
    assert "seen" in cli(hive["admin"], "bots")
