"""`hivepal connect`: MCP entry, skill and agents.yaml for OpenClaw and Hermes."""

from __future__ import annotations

import json
import os
import stat
import subprocess
from pathlib import Path

import pytest
import yaml
from typer.testing import CliRunner

from hivepal import connect as c
from hivepal.cli import app

ROOT = Path(__file__).resolve().parents[2]


@pytest.fixture()
def homes(tmp_path: Path, monkeypatch: pytest.MonkeyPatch):
    """Isolated hivepal / Hermes / OpenClaw homes and fake harness binaries on PATH."""
    bindir = tmp_path / "bin"
    bindir.mkdir()
    for name in ("openclaw", "hermes"):
        p = bindir / name
        p.write_text("#!/bin/sh\nexit 0\n")
        p.chmod(0o755)
    monkeypatch.setenv("PATH", f"{bindir}{os.pathsep}{os.environ['PATH']}")
    monkeypatch.setenv("HIVEPAL_HOME", str(tmp_path / "hivepal"))
    monkeypatch.setenv("HERMES_HOME", str(tmp_path / "hermes"))
    monkeypatch.setenv("OPENCLAW_STATE_DIR", str(tmp_path / "openclaw"))
    monkeypatch.delenv("HIVEPAL_API_KEY", raising=False)
    return tmp_path


class FakeOpenClaw:
    """Records `openclaw` invocations and keeps the saved MCP server like the real CLI."""

    def __init__(self, state: Path) -> None:
        self.state = state
        self.calls: list[list[str]] = []
        self.server: dict | None = None

    def __call__(self, argv: list[str]) -> subprocess.CompletedProcess:
        self.calls.append(argv)
        args = argv[1:]
        if args[:2] == ["mcp", "show"]:
            if self.server is None:
                return subprocess.CompletedProcess(argv, 1, "", "not found")
            return subprocess.CompletedProcess(argv, 0, json.dumps(self.server), "")
        if args[:2] == ["mcp", "set"]:
            self.server = json.loads(args[3])
            return subprocess.CompletedProcess(argv, 0, "", "")
        if args[:2] == ["skills", "install"]:
            src = Path(args[2])
            dest = self.state / "skills" / args[args.index("--as") + 1]
            for f in src.rglob("*"):
                if f.is_file():
                    (dest / f.relative_to(src)).parent.mkdir(parents=True, exist_ok=True)
                    (dest / f.relative_to(src)).write_bytes(f.read_bytes())
            (dest / ".openclaw").mkdir(exist_ok=True)  # the real CLI records where the skill came from
            (dest / ".openclaw" / "source-origin.json").write_text("{}")
            return subprocess.CompletedProcess(argv, 0, "", "")
        return subprocess.CompletedProcess(argv, 2, "", "unexpected")


def test_packaged_skill_matches_repo_skill() -> None:
    """skills/hivepal (published to ClawHub, GitHub installs) and the copy `connect` installs."""
    repo = {p.relative_to(ROOT / "skills/hivepal").as_posix(): p.read_bytes()
            for p in (ROOT / "skills/hivepal").rglob("*") if p.is_file()}
    assert repo == c.skill_files()
    assert "SKILL.md" in repo and "references/setup.md" in repo


def test_grok_plugin_carries_the_skill_and_hosted_server() -> None:
    """plugins/hivepal (Grok Build marketplace): same skill, and only the hosted MCP
    server with the key taken from the environment (plugins must not carry secrets)."""
    plugin = ROOT / "plugins/hivepal"
    copy = {p.relative_to(plugin / "skills/hivepal").as_posix(): p.read_bytes()
            for p in (plugin / "skills/hivepal").rglob("*") if p.is_file()}
    assert copy == c.skill_files()
    servers = json.loads((plugin / ".mcp.json").read_text())["mcpServers"]
    assert servers == {"hivepal": {"type": "http", "url": "https://agentring.io/mcp",
                                     "headers": {"Authorization": "Bearer ${HIVEPAL_API_KEY}"}}}
    manifest = json.loads((plugin / ".grok-plugin/plugin.json").read_text())
    assert manifest["name"] == "hivepal" and manifest["license"] == "Apache-2.0"


def test_skill_text_passes_skill_scanners() -> None:
    """Hermes' skills guard blocks a community skill that points the agent at a harness's
    config or .env files (it reads like exfiltration), so the skill sends the agent to
    `hivepal connect` and to the web pages for manual steps instead."""
    banned = [".hermes/", ".openclaw/", "hermes config set", "config.yaml", "openclaw.json", "AGENTS.md",
              "agents.md"]
    for rel, data in c.skill_files().items():
        text = data.decode().lower()
        for word in banned:
            assert word.lower() not in text, f"{rel} mentions {word!r}"


def test_mcp_entry_has_long_timeout_and_absolute_command(homes: Path) -> None:
    oc = c.mcp_entry(c.HARNESSES["openclaw"], exe="/opt/bin/hivepal")
    assert oc == {"command": "/opt/bin/hivepal", "args": ["mcp", "--agent-name", "OpenClaw"],
                  "env": {"HIVEPAL_HOME": str(homes / "hivepal")}, "requestTimeoutMs": 1_800_000}
    he = c.mcp_entry(c.HARNESSES["hermes"], url="https://agentring.io/mcp")
    assert he == {"url": "https://agentring.io/mcp", "headers": {"Authorization": "Bearer ${HIVEPAL_API_KEY}"},
                  "timeout": 1800}
    assert c.mcp_entry(c.HARNESSES["openclaw"], url="https://x.test/mcp")["transport"] == "streamable-http"


def test_hermes_local_is_idempotent_and_keeps_config(homes: Path) -> None:
    cfg = homes / "hermes" / "config.yaml"
    cfg.parent.mkdir()
    cfg.write_text("model: {default: nous/hermes-5}\nmcp_servers:\n  github: {command: npx}\n")
    agents = homes / "hivepal" / "agents.yaml"
    agents.parent.mkdir()
    agents.write_text("# my agents\nagents:\n  claude:\n    description: Claude\n    command: [claude, -p, \"{task}\"]\n")

    steps = c.connect("hermes")
    assert [s.status for s in steps] == ["added", "added", "added"]
    data = yaml.safe_load(cfg.read_text())
    assert data["model"] == {"default": "nous/hermes-5"}
    assert data["mcp_servers"]["github"] == {"command": "npx"}
    assert data["mcp_servers"]["hivepal"]["timeout"] == 1800
    assert (homes / "hermes" / "config.yaml.bak-hivepal").exists()
    assert (homes / "hermes" / "skills" / "hivepal" / "references" / "setup.md").exists()

    text = agents.read_text()
    assert text.startswith("# my agents\n")  # appended, comments kept
    loaded = yaml.safe_load(text)["agents"]
    assert set(loaded) == {"claude", "hermes"}
    assert loaded["hermes"]["command"][0].endswith("/hermes")
    assert loaded["hermes"]["command"][1:] == ["-z", "Task: {task}"]

    again = c.connect("hermes")
    assert [s.status for s in again] == ["unchanged", "unchanged", "unchanged"]


def test_agents_yaml_created_and_loadable(homes: Path) -> None:
    from hivepal.voice.agents import load_agents

    c.connect("hermes", skill=False)
    cfg = load_agents(str(homes / "hivepal" / "agents.yaml"))
    assert [a.name for a in cfg.agents] == ["hermes"]


def test_openclaw_local_uses_its_cli(homes: Path) -> None:
    fake = FakeOpenClaw(homes / "openclaw")
    steps = c.connect("openclaw", run=fake)
    assert [s.status for s in steps] == ["added", "added", "added"]
    assert fake.server["requestTimeoutMs"] == 1_800_000
    assert fake.server["args"] == ["mcp", "--agent-name", "OpenClaw"]
    install = next(a for a in fake.calls if a[1:3] == ["skills", "install"])
    assert install[-4:] == ["--as", "hivepal", "--global", "--force"]
    assert (homes / "openclaw" / "skills" / "hivepal" / "SKILL.md").exists()

    spec = yaml.safe_load((homes / "hivepal" / "agents.yaml").read_text())["agents"]["openclaw"]
    script = spec["command"]
    assert script[:2] == ["sh", "-c"] and script[3].endswith("/openclaw") and script[4] == "{task}"
    assert "$1" in script[2] and "{task}" not in script[2]  # the task never enters the shell script

    fake.calls.clear()
    assert [s.status for s in c.connect("openclaw", run=fake)] == ["unchanged", "unchanged", "unchanged"]
    assert not [a for a in fake.calls if a[1:3] in (["mcp", "set"], ["skills", "install"])]


def test_remote_puts_key_in_harness_env_not_config(homes: Path) -> None:
    steps = c.connect("hermes", url="https://agentring.io/mcp", api_key="ak_secret")
    assert [s.what for s in steps] == ["API key", "MCP server", "skill", "consult from a call"]
    assert steps[-1].status == "skipped"
    env = homes / "hermes" / ".env"
    assert "HIVEPAL_API_KEY=ak_secret" in env.read_text()
    assert stat.S_IMODE(env.stat().st_mode) == 0o600
    assert "ak_secret" not in (homes / "hermes" / "config.yaml").read_text()
    assert not (homes / "hivepal" / "agents.yaml").exists()

    fake = FakeOpenClaw(homes / "openclaw")
    c.connect("openclaw", url="https://agentring.io/mcp", api_key="ak_secret", run=fake)
    assert "ak_secret" not in json.dumps(fake.server)
    assert (homes / "openclaw" / ".env").read_text() == "HIVEPAL_API_KEY=ak_secret\n"


def test_env_file_keeps_other_lines(homes: Path) -> None:
    env = homes / "hermes" / ".env"
    env.parent.mkdir()
    env.write_text("OPENROUTER_API_KEY=sk-or\nHIVEPAL_API_KEY=ak_old\n")
    assert c.set_env_var(env, "HIVEPAL_API_KEY", "ak_new", dry_run=False) == "updated"
    assert env.read_text() == "OPENROUTER_API_KEY=sk-or\nHIVEPAL_API_KEY=ak_new\n"


def test_dry_run_changes_nothing(homes: Path) -> None:
    fake = FakeOpenClaw(homes / "openclaw")
    steps = c.connect("openclaw", dry_run=True, run=fake)
    assert [s.status for s in steps] == ["would add", "would add", "would add"]
    assert fake.server is None
    assert not (homes / "openclaw").exists() and not (homes / "hivepal").exists()
    c.connect("hermes", url="https://agentring.io/mcp", api_key="ak_x", dry_run=True)
    assert not (homes / "hermes").exists()


def test_flow_style_agents_yaml_is_rewritten_not_broken(homes: Path) -> None:
    agents = homes / "hivepal" / "agents.yaml"
    agents.parent.mkdir()
    agents.write_text('agents: {claude: {description: C, command: [claude, -p, "{task}"]}}\n')
    c.connect("hermes", skill=False)
    assert set(yaml.safe_load(agents.read_text())["agents"]) == {"claude", "hermes"}


def test_missing_binary_changes_nothing(homes: Path, monkeypatch: pytest.MonkeyPatch) -> None:
    monkeypatch.setenv("PATH", "/nonexistent")
    with pytest.raises(c.ConnectError, match="not on PATH"):
        c.connect("hermes")
    assert not (homes / "hermes").exists()


def test_errors(homes: Path, monkeypatch: pytest.MonkeyPatch) -> None:
    with pytest.raises(c.ConnectError, match="unknown harness"):
        c.connect("nanobot")
    with pytest.raises(c.ConnectError, match="https"):
        c.connect("hermes", url="http://agentring.io/mcp", api_key="ak_x")
    with pytest.raises(c.ConnectError, match="API key"):
        c.connect("hermes", url="https://agentring.io/mcp", api_key=None)
    monkeypatch.setenv("PATH", "/nonexistent")
    with pytest.raises(c.ConnectError, match="not on PATH"):
        c.connect("openclaw")


def test_cli(homes: Path, monkeypatch: pytest.MonkeyPatch) -> None:
    r = CliRunner().invoke(app, ["connect", "hermes", "--url", "https://agentring.io/mcp"])
    assert r.exit_code == 1 and "HIVEPAL_API_KEY" in r.output
    monkeypatch.setenv("HIVEPAL_API_KEY", "ak_cli")
    r = CliRunner().invoke(app, ["connect", "hermes", "--url", "https://agentring.io/mcp", "--json"])
    assert r.exit_code == 0, r.output
    out = json.loads(r.output)
    assert [s["status"] for s in out["steps"]] == ["added", "added", "added", "skipped"]


@pytest.fixture()
def more_homes(homes: Path, monkeypatch: pytest.MonkeyPatch) -> Path:
    """Claude Code, Codex and Cursor keep their settings under HOME (Codex: CODEX_HOME)."""
    monkeypatch.setenv("HOME", str(homes / "home"))
    monkeypatch.setenv("CODEX_HOME", str(homes / "home" / ".codex"))
    (homes / "home").mkdir()
    for name in ("claude", "codex"):
        p = homes / "bin" / name
        p.write_text("#!/bin/sh\nexit 0\n")
        p.chmod(0o755)
    return homes / "home"


class FakeClaude:
    """`claude mcp add-json/remove --scope user`, saved in ~/.claude.json like the real CLI."""

    def __init__(self, home: Path) -> None:
        self.file = home / ".claude.json"
        self.calls: list[list[str]] = []

    def __call__(self, argv: list[str]) -> subprocess.CompletedProcess:
        self.calls.append(argv)
        cfg = json.loads(self.file.read_text()) if self.file.exists() else {"projects": {}}
        servers = cfg.setdefault("mcpServers", {})
        if argv[1:3] == ["mcp", "add-json"]:
            servers[argv[3]] = json.loads(argv[4])
        elif argv[1:3] == ["mcp", "remove"]:
            servers.pop(argv[3], None)
        self.file.write_text(json.dumps(cfg))
        return subprocess.CompletedProcess(argv, 0, "", "")


def test_claude_code(more_homes: Path) -> None:
    fake = FakeClaude(more_homes)
    steps = c.connect("claude-code", run=fake)
    assert [s.status for s in steps] == ["added", "added", "added"]
    server = json.loads(fake.file.read_text())["mcpServers"]["hivepal"]
    assert server["type"] == "stdio" and server["args"] == ["mcp", "--agent-name", "Claude Code"]
    assert (more_homes / ".claude" / "skills" / "hivepal" / "SKILL.md").exists()
    spec = yaml.safe_load((more_homes.parent / "hivepal" / "agents.yaml").read_text())["agents"]["claude-code"]
    assert spec["command"][1:] == ["-p", "Task: {task}"]
    fake.calls.clear()
    assert [s.status for s in c.connect("claude-code", run=fake)] == ["unchanged", "unchanged", "unchanged"]
    assert fake.calls == []


def test_codex_keeps_the_rest_of_its_config(more_homes: Path) -> None:
    import tomllib

    cfg = more_homes / ".codex" / "config.toml"
    cfg.parent.mkdir()
    cfg.write_text('model = "gpt-5"\n\n[mcp_servers.hivepal]\ncommand = "old"\n\n[mcp_servers.hivepal.env]\nX = "1"\n\n'
                   '[mcp_servers.other]\ncommand = "other"\n')
    steps = c.connect("codex")
    assert [s.status for s in steps] == ["updated", "skipped", "added"]
    data = tomllib.loads(cfg.read_text())
    assert data["model"] == "gpt-5" and data["mcp_servers"]["other"] == {"command": "other"}
    assert data["mcp_servers"]["hivepal"]["tool_timeout_sec"] == 1800
    assert data["mcp_servers"]["hivepal"]["args"] == ["mcp", "--agent-name", "Codex"]
    assert [s.status for s in c.connect("codex")][0] == "unchanged"


def test_cursor_mcp_json(more_homes: Path) -> None:
    path = more_homes / ".cursor" / "mcp.json"
    path.parent.mkdir()
    path.write_text(json.dumps({"mcpServers": {"other": {"command": "x"}}}))
    steps = c.connect("cursor")
    assert [s.status for s in steps] == ["added", "skipped", "skipped"]  # no skill folder, no one-shot command
    servers = json.loads(path.read_text())["mcpServers"]
    assert servers["other"] == {"command": "x"} and servers["hivepal"]["args"] == ["mcp", "--agent-name", "Cursor"]
    with pytest.raises(c.ConnectError, match="local one"):
        c.connect("cursor", url="https://x/mcp", api_key="ak_x")
