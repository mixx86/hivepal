"""`hivepal connect`: set up an agent harness (OpenClaw, Hermes, Claude Code, Codex, Cursor) to use hivepal.

Three steps, each one idempotent (running it again changes nothing that is already right):

1. **MCP server.** Register hivepal in the harness: the local `hivepal mcp` (stdio), or a
   remote MCP URL such as hivepal cloud's. The API key of a remote server goes to the
   harness's own `.env` and the config refers to it as `${HIVEPAL_API_KEY}`, so it is not
   written into the config file. The tool timeout is raised to 30 minutes: `call` lasts as
   long as the phone call, and the harness defaults (60 s in OpenClaw, 300 s in Hermes) would
   cut it.
2. **Skill.** Install `skills/hivepal` where the harness reads skills, so the agent knows
   when to message and when to call.
3. **Consult from a call.** Add the harness to `~/.agentring/agents.yaml`, so the voice AI
   can ask it things during a call (local setup only: a remote MCP server never runs commands).
"""

from __future__ import annotations

import json
import os
import shutil
import subprocess
import sys
import tempfile
from collections.abc import Callable
from dataclasses import dataclass
from importlib import resources
from pathlib import Path
from typing import Any

import yaml

from .config import home_dir

SERVER_NAME = "hivepal"
MCP_TIMEOUT_S = 1800
KEY_VAR = "HIVEPAL_API_KEY"
SKILL_NAME = "hivepal"

Runner = Callable[[list[str]], subprocess.CompletedProcess]


class ConnectError(Exception):
    pass


@dataclass
class Step:
    what: str
    status: str  # "added", "updated", "unchanged", "skipped", "would add", "would update"
    detail: str = ""


@dataclass(frozen=True)
class Harness:
    name: str
    label: str  # caller name the phone shows for this agent's calls
    binary: str


HARNESSES = {
    "openclaw": Harness("openclaw", "OpenClaw", "openclaw"),
    "hermes": Harness("hermes", "Hermes", "hermes"),
    "claude-code": Harness("claude-code", "Claude Code", "claude"),
    "codex": Harness("codex", "Codex", "codex"),
    "cursor": Harness("cursor", "Cursor", "cursor-agent"),
}
#: Harnesses whose skills folder hivepal knows; the others get the MCP server's own instructions.
SKILL_HOMES = {"openclaw", "hermes", "claude-code"}
#: Harnesses the voice AI can consult on a call (a command that answers one question and exits).
CONSULTABLE = {"openclaw", "hermes", "claude-code", "codex"}


def _run(argv: list[str]) -> subprocess.CompletedProcess:
    return subprocess.run(argv, capture_output=True, text=True, timeout=120)


def hivepal_executable() -> str:
    """Absolute path of this `hivepal` command, so the harness doesn't depend on its PATH.

    The symlink is kept as is (`~/.local/bin/hivepal` of `uv tool install` survives upgrades).
    """
    argv0 = Path(sys.argv[0])
    if argv0.name == "hivepal" and argv0.exists():
        return os.path.abspath(argv0)
    return shutil.which("hivepal") or "hivepal"


def _find_binary(h: Harness) -> str:
    path = shutil.which(h.binary)
    if not path:
        raise ConnectError(f"`{h.binary}` is not on PATH: install {h.label} first, or run this where it is installed")
    return path


def mcp_entry(h: Harness, *, url: str | None = None, exe: str | None = None) -> dict[str, Any]:
    """The MCP server entry in the harness's own config format."""
    if url:
        entry: dict[str, Any] = {"url": url, "headers": {"Authorization": f"Bearer ${{{KEY_VAR}}}"}}
        if h.name == "openclaw":
            entry["transport"] = "streamable-http"
    else:
        entry = {"command": exe or hivepal_executable(), "args": ["mcp", "--agent-name", h.label]}
        if os.environ.get("HIVEPAL_HOME"):
            entry["env"] = {"HIVEPAL_HOME": str(home_dir())}
    if h.name == "openclaw":
        entry["requestTimeoutMs"] = MCP_TIMEOUT_S * 1000
    elif h.name == "hermes":
        entry["timeout"] = MCP_TIMEOUT_S
    elif h.name == "codex":
        entry["tool_timeout_sec"] = MCP_TIMEOUT_S
    elif url:
        entry["type"] = "http"  # Claude Code and Cursor tell remote servers apart this way
    return entry


# --- harness homes -------------------------------------------------------------------------


def hermes_home() -> Path:
    return Path(os.environ.get("HERMES_HOME") or Path.home() / ".hermes").expanduser()


def openclaw_state_dir() -> Path:
    if os.environ.get("OPENCLAW_STATE_DIR"):
        return Path(os.environ["OPENCLAW_STATE_DIR"]).expanduser()
    return Path(os.environ.get("OPENCLAW_HOME") or Path.home()).expanduser() / ".openclaw"


def _env_file(h: Harness) -> Path:
    return (openclaw_state_dir() if h.name == "openclaw" else hermes_home()) / ".env"


# --- small file helpers --------------------------------------------------------------------


def _write_private(path: Path, text: str) -> None:
    """Write atomically; the file keeps its mode, or is created 0600."""
    path.parent.mkdir(parents=True, exist_ok=True)
    mode = path.stat().st_mode & 0o777 if path.exists() else 0o600
    fd, tmp = tempfile.mkstemp(dir=path.parent, prefix=f".{path.name}.")
    try:
        with os.fdopen(fd, "w") as f:
            f.write(text)
        os.chmod(tmp, mode)
        os.replace(tmp, path)
    except BaseException:
        Path(tmp).unlink(missing_ok=True)
        raise


def set_env_var(path: Path, name: str, value: str, *, dry_run: bool) -> str:
    """Set NAME=value in a dotenv file, keeping every other line. Returns the step status."""
    lines = path.read_text().splitlines() if path.exists() else []
    current = [line for line in lines if line.startswith(f"{name}=")]
    if current == [f"{name}={value}"]:
        return "unchanged"
    status = "updated" if current else "added"
    if dry_run:
        return _would(status, dry_run)
    kept = [line for line in lines if not line.startswith(f"{name}=")]
    _write_private(path, "\n".join([*kept, f"{name}={value}"]) + "\n")
    return status


def _would(status: str, dry_run: bool) -> str:
    return {"added": "would add", "updated": "would update"}.get(status, status) if dry_run else status


# --- step 1: MCP server --------------------------------------------------------------------


def connect_mcp(h: Harness, entry: dict[str, Any], *, dry_run: bool, run: Runner = _run) -> Step:
    if h.name == "openclaw":
        return _openclaw_mcp(h, entry, dry_run=dry_run, run=run)
    if h.name == "claude-code":
        return _claude_mcp(h, entry, dry_run=dry_run, run=run)
    if h.name == "codex":
        return _codex_mcp(entry, dry_run=dry_run)
    if h.name == "cursor":
        return _json_mcp(Path.home() / ".cursor" / "mcp.json", entry, dry_run=dry_run)
    return _hermes_mcp(entry, dry_run=dry_run)


def _claude_mcp(h: Harness, entry: dict[str, Any], *, dry_run: bool, run: Runner) -> Step:
    """User scope (every project), through Claude Code's own CLI; ~/.claude.json tells what's there."""
    binary = _find_binary(h)
    try:
        current = (json.loads((Path.home() / ".claude.json").read_text()).get("mcpServers") or {}).get(SERVER_NAME)
    except (OSError, ValueError, AttributeError):
        current = None
    want = {**entry, "type": entry.get("type", "stdio")}
    if current is not None and {k: v for k, v in current.items() if k in want} == want:
        return Step("MCP server", "unchanged", f"{h.label}: {SERVER_NAME} (user scope)")
    status = "updated" if current is not None else "added"
    if not dry_run:
        if current is not None:
            run([binary, "mcp", "remove", SERVER_NAME, "--scope", "user"])
        r = run([binary, "mcp", "add-json", SERVER_NAME, json.dumps(want), "--scope", "user"])
        if r.returncode != 0:
            raise ConnectError(f"`claude mcp add-json` failed: {(r.stderr or r.stdout).strip()}")
    return Step("MCP server", _would(status, dry_run), f"`claude mcp add-json {SERVER_NAME} --scope user`")


def _json_mcp(path: Path, entry: dict[str, Any], *, dry_run: bool) -> Step:
    """A `{"mcpServers": {...}}` file (Cursor)."""
    try:
        cfg = json.loads(path.read_text()) if path.exists() else {}
    except ValueError as e:
        raise ConnectError(f"cannot read {path}: {e}") from None
    servers = cfg.get("mcpServers") or {}
    if not isinstance(cfg, dict) or not isinstance(servers, dict):
        raise ConnectError(f"{path}: expected an object with an `mcpServers` object")
    if servers.get(SERVER_NAME) == entry:
        return Step("MCP server", "unchanged", str(path))
    status = "updated" if SERVER_NAME in servers else "added"
    if not dry_run:
        if path.exists():
            shutil.copy2(path, path.with_name(path.name + ".bak-hivepal"))
        cfg["mcpServers"] = {**servers, SERVER_NAME: entry}
        _write_private(path, json.dumps(cfg, indent=2) + "\n")
    return Step("MCP server", _would(status, dry_run), str(path))


def _toml_value(v: Any) -> str:
    if isinstance(v, bool):
        return "true" if v else "false"
    if isinstance(v, (int, float)):
        return str(v)
    if isinstance(v, list):
        return "[" + ", ".join(_toml_value(x) for x in v) + "]"
    if isinstance(v, dict):
        return "{ " + ", ".join(f"{json.dumps(k)} = {_toml_value(x)}" for k, x in v.items()) + " }"
    return json.dumps(str(v))  # a JSON string is a valid TOML basic string


def _codex_mcp(entry: dict[str, Any], *, dry_run: bool) -> Step:
    """[mcp_servers.hivepal] in ~/.codex/config.toml; the rest of the file is left as it is."""
    import re
    import tomllib

    path = Path(os.environ.get("CODEX_HOME") or Path.home() / ".codex").expanduser() / "config.toml"
    text = path.read_text() if path.exists() else ""
    try:
        cfg = tomllib.loads(text)
    except tomllib.TOMLDecodeError as e:
        raise ConnectError(f"cannot read {path}: {e}") from None
    current = (cfg.get("mcp_servers") or {}).get(SERVER_NAME)
    if current == entry:
        return Step("MCP server", "unchanged", str(path))
    block = f"[mcp_servers.{SERVER_NAME}]\n" + "".join(f"{k} = {_toml_value(v)}\n" for k, v in entry.items())
    header = re.compile(rf"^\[mcp_servers\.{SERVER_NAME}(\.[^\]]*)?\]\s*$", re.M)
    if current is not None:
        # Drop the old table (and any sub-tables such as .env) up to the next table header.
        lines, out, skipping = text.splitlines(keepends=True), [], False
        for line in lines:
            if line.lstrip().startswith("["):
                skipping = bool(header.match(line.strip()))
            if not skipping:
                out.append(line)
        text = "".join(out)
    new = text + ("" if not text or text.endswith("\n") else "\n") + ("\n" if text.strip() else "") + block
    if (tomllib.loads(new).get("mcp_servers") or {}).get(SERVER_NAME) != entry:
        raise ConnectError(f"couldn't write [mcp_servers.{SERVER_NAME}] in {path}: add it by hand:\n{block}")
    if not dry_run:
        if path.exists():
            shutil.copy2(path, path.with_name(path.name + ".bak-hivepal"))
        _write_private(path, new)
    return Step("MCP server", _would("updated" if current is not None else "added", dry_run), str(path))


def _openclaw_mcp(h: Harness, entry: dict[str, Any], *, dry_run: bool, run: Runner) -> Step:
    binary = _find_binary(h)
    show = run([binary, "mcp", "show", SERVER_NAME, "--json"])
    current = None
    if show.returncode == 0:
        try:
            current = json.loads(show.stdout)
        except ValueError:
            current = None
    if isinstance(current, dict) and isinstance(current.get("server"), dict):
        current = current["server"]  # tolerate a wrapped answer
    if current == entry:
        return Step("MCP server", "unchanged", f"{h.label}: {SERVER_NAME}")
    status = "updated" if show.returncode == 0 else "added"
    if not dry_run:
        r = run([binary, "mcp", "set", SERVER_NAME, json.dumps(entry)])
        if r.returncode != 0:
            raise ConnectError(f"`openclaw mcp set` failed: {(r.stderr or r.stdout).strip()}")
    return Step("MCP server", _would(status, dry_run), f"`openclaw mcp set {SERVER_NAME}`")


def _hermes_mcp(entry: dict[str, Any], *, dry_run: bool) -> Step:
    path = hermes_home() / "config.yaml"
    try:
        cfg = (yaml.safe_load(path.read_text()) or {}) if path.exists() else {}
    except yaml.YAMLError as e:
        raise ConnectError(f"cannot read {path}: {e}") from None
    if not isinstance(cfg, dict):
        raise ConnectError(f"{path} must contain a mapping")
    servers = cfg.get("mcp_servers") or {}
    if not isinstance(servers, dict):
        raise ConnectError(f"{path}: `mcp_servers` must be a mapping")
    if servers.get(SERVER_NAME) == entry:
        return Step("MCP server", "unchanged", str(path))
    status = "updated" if SERVER_NAME in servers else "added"
    if not dry_run:
        if path.exists():
            shutil.copy2(path, path.with_name(path.name + ".bak-hivepal"))
        servers[SERVER_NAME] = entry
        cfg["mcp_servers"] = servers
        _write_private(path, yaml.safe_dump(cfg, sort_keys=False, allow_unicode=True))
    return Step("MCP server", _would(status, dry_run), str(path))


# --- step 2: skill -------------------------------------------------------------------------


def skill_files() -> dict[str, bytes]:
    """The hivepal skill shipped in this package: {relative path: content}."""
    root = resources.files("hivepal") / "skill"
    out: dict[str, bytes] = {}

    def walk(node: Any, prefix: str) -> None:
        for child in node.iterdir():
            rel = f"{prefix}{child.name}"
            if child.is_dir():
                walk(child, rel + "/")
            elif not child.name.startswith("."):
                out[rel] = child.read_bytes()

    walk(root, "")
    return out


def _same_tree(dest: Path, files: dict[str, bytes]) -> bool:
    if not dest.is_dir():
        return False
    # Skip hidden files and folders: the harness keeps its own metadata there (OpenClaw: .openclaw/).
    rels = [p.relative_to(dest) for p in dest.rglob("*") if p.is_file()]
    present = {r.as_posix() for r in rels if not any(part.startswith(".") for part in r.parts)}
    return present == set(files) and all((dest / rel).read_bytes() == data for rel, data in files.items())


def _materialize(files: dict[str, bytes], dest: Path) -> None:
    for rel, data in files.items():
        p = dest / rel
        p.parent.mkdir(parents=True, exist_ok=True)
        p.write_bytes(data)


def install_skill(h: Harness, *, dry_run: bool, run: Runner = _run) -> Step:
    if h.name not in SKILL_HOMES:
        return Step("skill", "skipped", f"{h.label} reads the MCP server's own instructions")
    files = skill_files()
    if h.name == "openclaw":
        dest = openclaw_state_dir() / "skills" / SKILL_NAME
        if _same_tree(dest, files):
            return Step("skill", "unchanged", str(dest))
        status = "updated" if dest.exists() else "added"
        if not dry_run:
            binary = _find_binary(h)
            with tempfile.TemporaryDirectory() as tmp:
                src = Path(tmp) / SKILL_NAME
                _materialize(files, src)
                r = run([binary, "skills", "install", str(src), "--as", SKILL_NAME, "--global", "--force"])
            if r.returncode != 0:
                raise ConnectError(f"`openclaw skills install` failed: {(r.stderr or r.stdout).strip()}")
        return Step("skill", _would(status, dry_run), str(dest))

    dest = (Path.home() / ".claude" if h.name == "claude-code" else hermes_home()) / "skills" / SKILL_NAME
    if _same_tree(dest, files):
        return Step("skill", "unchanged", str(dest))
    status = "updated" if dest.exists() else "added"
    if not dry_run:
        with tempfile.TemporaryDirectory(dir=dest.parent if dest.parent.exists() else None) as tmp:
            src = Path(tmp) / SKILL_NAME
            _materialize(files, src)
            dest.parent.mkdir(parents=True, exist_ok=True)
            if dest.exists():
                shutil.rmtree(dest)
            shutil.move(str(src), dest)
    return Step("skill", _would(status, dry_run), str(dest))


# --- step 3: agents.yaml -------------------------------------------------------------------


def consult_spec(h: Harness, binary: str) -> dict[str, Any]:
    """The agents.yaml entry that lets the voice AI ask this harness something on a call."""
    if h.name == "openclaw":
        # `openclaw agent` runs one turn through the running gateway. The script is fixed text:
        # the binary arrives as $0 and the task as $1, never parsed by the shell. The call id
        # gives each call its own session, so a follow-up question keeps the context.
        return {
            "description": "My OpenClaw assistant. Messages, calendar, reminders, home.",
            "command": ["sh", "-c",
                        'exec "$0" agent --agent main --session-id "hivepal-${HIVEPAL_CALL_ID:-test}" --message="$1"',
                        binary, "{task}"],
            "timeout_s": 600,
        }
    if h.name == "claude-code":
        return {
            "description": "My Claude Code agent. Code, files, the projects on this computer.",
            "command": [binary, "-p", "Task: {task}"],  # -p answers once and exits
            "timeout_s": 600,
        }
    if h.name == "codex":
        return {
            "description": "My Codex agent. Code and the projects on this computer.",
            "command": [binary, "exec", "Task: {task}"],
            "timeout_s": 600,
        }
    return {
        "description": "My Hermes agent. Email, calendar, research.",
        "command": [binary, "-z", "Task: {task}"],  # -z prints only the final answer
        "timeout_s": 600,
    }


def add_consult_agent(h: Harness, *, dry_run: bool) -> Step:
    path = home_dir() / "agents.yaml"
    try:
        cfg = (yaml.safe_load(path.read_text()) or {}) if path.exists() else {}
    except yaml.YAMLError as e:
        raise ConnectError(f"cannot read {path}: {e}") from None
    if not isinstance(cfg, dict) or not isinstance(cfg.get("agents") or {}, dict):
        raise ConnectError(f"{path}: expected a mapping with an `agents` mapping")
    agents = cfg.get("agents") or {}
    if h.name in agents:
        return Step("consult from a call", "unchanged", f"{path} already has `{h.name}`")
    spec = consult_spec(h, _find_binary(h))
    if not dry_run:
        block = yaml.safe_dump({h.name: spec}, sort_keys=False, allow_unicode=True, width=1000)
        text = path.read_text() if path.exists() else ""
        if set(cfg) <= {"agents"} and agents:
            # Append under the existing `agents:` so the file keeps its comments.
            indented = "".join(f"  {line}\n" for line in block.splitlines())
            new = text + ("" if text.endswith("\n") else "\n") + indented
        elif not cfg:
            new = text + ("" if not text or text.endswith("\n") else "\n") + "agents:\n" + \
                "".join(f"  {line}\n" for line in block.splitlines())
        else:
            cfg["agents"] = {**agents, h.name: spec}
            new = yaml.safe_dump(cfg, sort_keys=False, allow_unicode=True, width=1000)
        try:
            check = yaml.safe_load(new) or {}
        except yaml.YAMLError:
            check = {}
        if not isinstance(check, dict) or (check.get("agents") or {}).get(h.name) != spec:
            # Appending didn't give the right file (e.g. a flow-style `agents: {…}`): rewrite it.
            cfg["agents"] = {**agents, h.name: spec}
            new = yaml.safe_dump(cfg, sort_keys=False, allow_unicode=True, width=1000)
        if path.exists():
            shutil.copy2(path, path.with_name(path.name + ".bak-hivepal"))
        _write_private(path, new)
    return Step("consult from a call", "would add" if dry_run else "added", f"`{h.name}` in {path}")


# --- all together --------------------------------------------------------------------------


def connect(harness: str, *, url: str | None = None, api_key: str | None = None, skill: bool = True,
            consult: bool = True, dry_run: bool = False, run: Runner = _run) -> list[Step]:
    h = HARNESSES.get(harness)
    if h is None:
        raise ConnectError(f"unknown harness {harness!r}: one of {', '.join(HARNESSES)}")
    consult = consult and h.name in CONSULTABLE
    if h.name in ("openclaw", "claude-code") or (consult and not url):
        _find_binary(h)  # fail before changing anything
    steps: list[Step] = []
    if url and h.name not in ("openclaw", "hermes"):
        raise ConnectError(f"--url (a remote MCP server) is for OpenClaw and Hermes; {h.label} uses the local one")
    if url:
        if not url.startswith(("https://", "http://localhost", "http://127.0.0.1")):
            raise ConnectError("--url must be https (http only for localhost)")
        if not api_key or not api_key.startswith(("ak_", "at_")):
            raise ConnectError(f"a remote MCP server needs the API key (ak_…) in {KEY_VAR}")
        env_path = _env_file(h)
        steps.append(Step("API key", set_env_var(env_path, KEY_VAR, api_key, dry_run=dry_run),
                          f"{KEY_VAR} in {env_path}"))
        steps.append(connect_mcp(h, mcp_entry(h, url=url), dry_run=dry_run, run=run))
    else:
        steps.append(connect_mcp(h, mcp_entry(h), dry_run=dry_run, run=run))
    steps.append(install_skill(h, dry_run=dry_run, run=run) if skill else Step("skill", "skipped"))
    if consult and not url:
        steps.append(add_consult_agent(h, dry_run=dry_run))
    else:
        steps.append(Step("consult from a call", "skipped",
                          "remote MCP: the server never runs commands" if url
                          else "" if h.name in CONSULTABLE else f"{h.label} has no one-shot command for it"))
    return steps
