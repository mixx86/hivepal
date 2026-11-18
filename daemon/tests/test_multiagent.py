"""Multi-agent fork: agents with their own keys, their own conversations and groups, from the CLI."""

from __future__ import annotations

import asyncio
import json
import threading
from pathlib import Path

import httpx
import pytest
from typer.testing import CliRunner

from hivepal.cli import app
from hivepal.relay_client import RelayClient
from hivepal.store import Store
from hivepal.sync import run_ws

from .conftest import API_KEY
from .test_e2e import simulate_phone_pairing

runner = CliRunner()


def cli(home: Path, *args: str) -> str:
    r = runner.invoke(app, list(args), env={"HIVEPAL_HOME": str(home)})
    assert r.exit_code == 0, r.output
    return r.output


def test_bots_and_groups(relay: str, tmp_path: Path, monkeypatch: pytest.MonkeyPatch):
    monkeypatch.delenv("HIVEPAL_RELAY_URL", raising=False)
    monkeypatch.delenv("HIVEPAL_API_KEY", raising=False)
    admin, nubi_home, rutina_home = tmp_path / "admin", tmp_path / "nubi", tmp_path / "rutina"
    cli(admin, "init", "--relay", relay, "--api-key", API_KEY)
    phone = simulate_phone_pairing(json.loads(cli(admin, "pair", "--no-wait", "--json"))["qr_payload"])

    nubi_key = json.loads(cli(admin, "bots", "add", "nubi", "Nubi", "--json"))["api_key"]
    rutina_key = json.loads(cli(admin, "bots", "add", "rutina", "Rutina", "--json"))["api_key"]
    assert "Nubi" in cli(admin, "bots")
    cli(nubi_home, "init", "--relay", relay, "--api-key", nubi_key)
    cli(rutina_home, "init", "--relay", relay, "--api-key", rutina_key)
    assert "(id nubi)" in cli(nubi_home, "status")

    # Own conversations: each bot only gets what the user writes to it.
    cli(nubi_home, "msg", "Hola, soy Nubi")
    httpx.post(f"{relay}/v1/messages", headers=phone["headers"], json={"text": "para Nubi", "conv": "agent:nubi"}).raise_for_status()
    httpx.post(f"{relay}/v1/messages", headers=phone["headers"], json={"text": "para Rutina", "conv": "agent:rutina"}).raise_for_status()
    assert [r["text"] for r in json.loads(cli(nubi_home, "replies", "--json"))["replies"]] == ["para Nubi"]
    assert [r["text"] for r in json.loads(cli(rutina_home, "replies", "--json"))["replies"]] == ["para Rutina"]
    # The admin (main) sees neither.
    assert json.loads(cli(admin, "replies", "--json"))["replies"] == []

    # A group: everyone reads the user and the other members.
    cli(admin, "groups", "add", "todos", "Todos", "--members", "nubi,rutina")
    assert "nubi, rutina" in cli(nubi_home, "groups")
    httpx.post(f"{relay}/v1/messages", headers=phone["headers"], json={"text": "¿Plan para hoy?", "conv": "group:todos"}).raise_for_status()
    cli(nubi_home, "msg", "Gym a las 18:00", "--group", "todos")
    out = cli(rutina_home, "replies")
    assert "[Todos] user: ¿Plan para hoy?" in out
    assert "[Todos] Nubi: Gym a las 18:00" in out
    # Nubi doesn't get its own message back, only the user's.
    replies = json.loads(cli(nubi_home, "replies", "--json"))["replies"]
    assert [(r["author"], r["text"]) for r in replies] == [("user", "¿Plan para hoy?")]
    assert replies[0]["conv"] == "group:todos" and replies[0]["group"] == "Todos"
    # --group filters.
    cli(rutina_home, "msg", "Apuntado", "--group", "todos")
    assert [r["text"] for r in json.loads(cli(nubi_home, "replies", "--group", "todos", "--json"))["replies"]] == ["Apuntado"]

    # A bot can't post to a group it isn't in, nor manage agents.
    r = runner.invoke(app, ["msg", "x", "--group", "otro"], env={"HIVEPAL_HOME": str(nubi_home)})
    assert r.exit_code != 0
    r = runner.invoke(app, ["bots", "add", "x", "X"], env={"HIVEPAL_HOME": str(nubi_home)})
    assert r.exit_code != 0 and "403" in r.output

    # The phone sees every conversation.
    convs = {m["conv"] for m in httpx.get(f"{relay}/v1/messages", headers=phone["headers"]).json()["messages"]}
    assert convs == {"agent:nubi", "agent:rutina", "group:todos"}

    # Rotating a key locks the old one out.
    cli(admin, "bots", "key", "rutina")
    r = runner.invoke(app, ["replies"], env={"HIVEPAL_HOME": str(rutina_home)})
    assert r.exit_code != 0


def test_two_agents_hold_websockets_at_once(relay: str):
    """Before the fork one daemon socket replaced the other (4409); now each agent has its own."""
    with RelayClient(relay, API_KEY) as admin:
        key = admin.create_agent("nubi", "Nubi")["api_key"]

    async def main() -> list[str]:
        stop = asyncio.Event()
        stores = [Store(":memory:"), Store(":memory:")]
        clients = [RelayClient(relay, API_KEY), RelayClient(relay, key)]
        tasks = [asyncio.create_task(run_ws(c, k, s, stop=stop)) for c, k, s in zip(clients, [API_KEY, key], stores)]
        await asyncio.sleep(1.5)
        with RelayClient(relay, API_KEY) as c:
            online = [a["agent_id"] for a in c.list_agents() if a.get("online")]
        stop.set()
        await asyncio.gather(*tasks)
        for c in clients:
            c.close()
        return online

    assert sorted(asyncio.run(main())) == ["main", "nubi"]


def test_switching_to_an_own_key_does_not_replay_the_shared_conversation(tmp_path: Path):
    s = Store(tmp_path / "m.db")
    # Filled with the shared (main) key: the user's and other agents' messages, all agent:main.
    s.upsert_message("msg_1", "user", "para todos", 1)
    s.upsert_message("msg_2", "agent", "de otro bot", 2)
    s.set_state("self_agent_id", "nubi")
    s.upsert_message("msg_3", "user", "para Nubi", 3, conv="agent:nubi")
    s.upsert_message("msg_4", "agent", "en el grupo", 4, conv="group:todos", agent_id="rutina")
    s.upsert_message("msg_5", "user", "para Rutina", 5, conv="agent:rutina")
    assert [r["text"] for r in s.replies(undelivered_only=True)] == ["para Nubi", "en el grupo"]
    s.close()


def test_old_mirror_gets_the_new_columns(tmp_path: Path):
    import sqlite3

    path = tmp_path / "old.db"
    db = sqlite3.connect(path)
    db.executescript(
        "CREATE TABLE state (key TEXT PRIMARY KEY, value TEXT NOT NULL);"
        "CREATE TABLE messages (msg_id TEXT PRIMARY KEY, sender TEXT NOT NULL, text TEXT NOT NULL, reply_to TEXT,"
        " created_at INTEGER NOT NULL, read_at INTEGER, delivered_at INTEGER);"
        "INSERT INTO messages (msg_id, sender, text, created_at) VALUES ('msg_1', 'user', 'viejo', 1);"
    )
    db.close()
    s = Store(path)
    assert [r["text"] for r in s.replies()] == ["viejo"]
    s.upsert_message("msg_2", "agent", "de otro", 2, conv="group:todos", agent_id="nubi")
    assert [r["text"] for r in s.replies()] == ["viejo", "de otro"]
    threading.Thread(target=s.close).start()
