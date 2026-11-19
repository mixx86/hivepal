"""hivepal command-line interface."""

from __future__ import annotations

import asyncio
import inspect
import json
import logging
import re
import signal
import sys
import time
from datetime import datetime, timezone
from typing import Annotated, Any, Optional

import segno
import typer

from . import __version__
from .config import ConfigError, Settings, home_dir, load_settings, write_settings
from .relay_client import InsecureRelayError, RelayClient, RelayError
from .store import Store
from .sync import FatalSyncError, catch_up, run_ws

app = typer.Typer(
    help="Hivepal: your agents call and message your phone, each with its own chat, plus group chats. (Also installed as `hivepal`.)",
    no_args_is_help=True,
    add_completion=False,
    pretty_exceptions_enable=False,
)
devices_app = typer.Typer(help="List or revoke paired phones.", no_args_is_help=False)
app.add_typer(devices_app, name="devices")
agents_app = typer.Typer(help="Your agents the voice AI can consult (agents.yaml).", no_args_is_help=False)
app.add_typer(agents_app, name="agents")
voice_app = typer.Typer(help="Voice AI tools.", no_args_is_help=True)
app.add_typer(voice_app, name="voice")
bots_app = typer.Typer(help="Agents with their own key and conversation on the relay.",
                       no_args_is_help=False)
app.add_typer(bots_app, name="bots")
groups_app = typer.Typer(help="Group conversations: the user and several agents, who all read them.", no_args_is_help=False)
app.add_typer(groups_app, name="groups")
subs_app = typer.Typer(help="Your subscriptions and when each renews (the app reads each service's billing page).",
                       no_args_is_help=False, invoke_without_command=True)
app.add_typer(subs_app, name="subs")

JsonOpt = Annotated[bool, typer.Option("--json", help="Machine-readable JSON output.")]
AiOpt = Annotated[bool, typer.Option("--ai", help="A voice AI talks on the call (default Gemini Live; see --provider).")]
ProviderOpt = Annotated[
    Optional[str],
    typer.Option("--provider", help="--ai: voice provider: gemini, openai, grok, phonic, openrouter or local (default "
                 "HIVEPAL_VOICE_PROVIDER, else gemini). Each needs its key: GEMINI_API_KEY, OPENAI_API_KEY, "
                 "XAI_API_KEY, PHONIC_API_KEY, OPENROUTER_API_KEY; local needs a server at HIVEPAL_VOICE_URL "
                 "(docs/voice.md)."),
]
InstructionsOpt = Annotated[
    Optional[str], typer.Option("--instructions", help="--ai: system prompt, or @file to read it from a file.")
]
McpOpt = Annotated[Optional[list[str]], typer.Option("--mcp", help="--ai: MCP server URL whose tools the AI may use (repeatable).")]
ToolsOpt = Annotated[Optional[str], typer.Option("--tools", help="--ai: comma-separated allowlist of MCP tools.")]
ModelOpt = Annotated[Optional[str], typer.Option("--model", help="--ai: the provider's model (default HIVEPAL_VOICE_MODEL, "
                                                 "else e.g. gemini-3.8-live, gpt-realtime-2.1).")]
AgentsFileOpt = Annotated[
    Optional[str],
    typer.Option("--agents", help="--ai: agents.yaml with your agents and MCP servers (default ~/.agentring/agents.yaml "
                 "if it exists; 'none' to skip it)."),
]


def _agents_config(path: str | None, *, required: bool = False) -> Any:
    from .voice.agents import AgentsConfigError, load_agents

    if path == "none":
        return None
    file = path or str(home_dir() / "agents.yaml")
    if path is None and not (home_dir() / "agents.yaml").exists():
        if required:
            raise _fail(f"no agents configured: create {file} (see docs/agents.md)")
        return None
    try:
        return load_agents(file)
    except AgentsConfigError as e:
        raise _fail(str(e)) from None


async def _build_ai(instructions: str | None, mcp: list[str] | None, tools: str | None, model: str | None,
                    voice: str | None, agents_file: str | None = None, provider: str | None = None) -> Any:
    try:
        from .voice.quick import build_agent
    except ImportError:
        raise _fail("voice support is not installed: install hivepal with the [voice] extra") from None
    from .voice.providers import VoiceConfigError, resolve_voice

    try:
        choice = resolve_voice(provider, model, voice)
    except VoiceConfigError as e:
        raise _fail(f"--ai: {e}") from None
    logging.getLogger("hivepal").info("voice AI: %s (%s)", choice.provider.label, choice.model)
    allow = [t.strip() for t in tools.split(",") if t.strip()] if tools else None
    agents = _agents_config(agents_file)
    try:
        return await build_agent(choice, instructions=instructions, mcp=mcp, allow=allow, agents=agents)
    except OSError as e:
        raise _fail(f"cannot read the instructions: {e}") from None


def _print_result(r: Any) -> None:
    for role, text in r.transcript:
        typer.echo(f"  {'you' if role == 'user' else 'agent'}: {text}")
    for t in r.tools:
        typer.echo(f"  [tool] {t['name']} {json.dumps(t['args'], ensure_ascii=False)} → {t['output'][:120]}")
    typer.echo(f"Call {r.end_reason}, {r.duration_s} s" if r.answered else f"Call {r.end_reason}")
    if r.fallback_msg_id:
        typer.echo(f"Sent the reason as a message instead ({r.fallback_msg_id})")


def _signals(stop: asyncio.Event) -> None:
    loop = asyncio.get_running_loop()
    for sig in (signal.SIGINT, signal.SIGTERM):
        try:
            loop.add_signal_handler(sig, stop.set)
        except NotImplementedError:  # Windows
            pass


def _now_ms() -> int:
    return int(time.time() * 1000)


def _fail(msg: str, code: int = 1) -> typer.Exit:
    typer.secho(f"error: {msg}", fg=typer.colors.RED, err=True)
    return typer.Exit(code)


def _settings() -> Settings:
    try:
        return load_settings()
    except ConfigError as e:
        raise _fail(str(e)) from None


def _client(s: Settings) -> RelayClient:
    return RelayClient(s.relay_url, s.api_key)


def _emit_json(obj: Any) -> None:
    typer.echo(json.dumps(obj, ensure_ascii=False, indent=2))


def _fmt_ts(ms: int) -> str:
    return datetime.fromtimestamp(ms / 1000).strftime("%Y-%m-%d %H:%M:%S")


_DURATION = re.compile(r"^(\d+)\s*([smhd])$")


def parse_since(value: str, now_ms: int | None = None) -> int:
    """Accepts a relative duration (30s, 10m, 2h, 1d), epoch milliseconds, or an ISO-8601 datetime."""
    now_ms = now_ms if now_ms is not None else _now_ms()
    v = value.strip()
    m = _DURATION.match(v)
    if m:
        mult = {"s": 1, "m": 60, "h": 3600, "d": 86400}[m.group(2)]
        return now_ms - int(m.group(1)) * mult * 1000
    if v.isdigit():
        return int(v)
    try:
        dt = datetime.fromisoformat(v.replace("Z", "+00:00"))
    except ValueError:
        raise typer.BadParameter("use e.g. 10m, 2h, 1d, epoch ms, or 2026-10-23T10:00:00") from None
    if dt.tzinfo is None:
        dt = dt.astimezone()  # local time
    return int(dt.timestamp() * 1000)


@app.callback()
def _root(
    verbose: Annotated[bool, typer.Option("--verbose", "-v", help="Debug logging.")] = False,
) -> None:
    logging.basicConfig(
        level=logging.DEBUG if verbose else logging.INFO,
        format="%(asctime)s %(levelname)s %(name)s: %(message)s",
        stream=sys.stderr,
    )
    logging.getLogger("httpx").setLevel(logging.WARNING)
    # At DEBUG websockets logs every handshake header, API keys included.
    logging.getLogger("websockets").setLevel(logging.INFO)


@app.command()
def version() -> None:
    """Print the version."""
    typer.echo(__version__)


@app.command()
def init(
    relay: Annotated[str, typer.Option("--relay", help="Relay base URL, e.g. https://relay.example.com")],
    api_key: Annotated[str, typer.Option("--api-key", help="Relay API key (ak_...)")],
) -> None:
    """Configure the relay URL and API key (stored in ~/.agentring)."""
    if not relay.startswith(("https://", "http://")):
        raise _fail("--relay must be an http(s) URL")
    if not api_key.startswith("ak_"):
        raise _fail("--api-key must start with ak_")
    try:
        with RelayClient(relay, api_key) as c:
            st = c.status()
    except (RelayError, InsecureRelayError) as e:
        raise _fail(str(e)) from None
    if not isinstance(st, dict) or not st.get("relay_id"):
        raise _fail(f"{relay} did not answer like a Hivepal relay; nothing was saved")
    home = write_settings(relay, api_key)
    typer.echo(f"Connected to relay {st['relay_id']} (agent name: {st.get('agent_name')}). Config saved in {home}")


@app.command()
def pair(
    wait: Annotated[bool, typer.Option("--wait/--no-wait", help="Wait until the phone pairs.")] = True,
    json_out: JsonOpt = False,
) -> None:
    """Show a QR code to pair the Android app (single use, valid 10 minutes)."""
    s = _settings()
    with _client(s) as c:
        try:
            known = {d["device_id"] for d in c.list_devices()}
            tok = c.create_pairing_token()
        except RelayError as e:
            raise _fail(str(e)) from None

        if json_out:
            _emit_json(tok)
        else:
            # The link form (newer relays) also works from the phone's camera: it offers to install the app.
            qr = segno.make(tok.get("qr_url") or tok["qr_payload"], error="m")
            qr.terminal(compact=True, border=2)
            typer.echo("\nScan this QR code with the phone's camera or the Hivepal app (Get started → Scan).")
            typer.echo("No camera? Copy this pairing code into the app (Pair → Paste code):\n")
            typer.echo(f"  {tok['qr_payload']}\n")
            typer.echo(f"Valid until {_fmt_ts(tok['expires_at'])}, single use.")
        if not wait:
            return

        if not json_out:
            typer.echo("Waiting for the phone… (Ctrl+C to stop)")
        try:
            while _now_ms() < tok["expires_at"]:
                time.sleep(2)
                new = [d for d in c.list_devices() if d["device_id"] not in known]
                if new:
                    d = new[0]
                    if json_out:
                        _emit_json({"paired": True, "device": d})
                    else:
                        typer.secho(f"Paired with {d['name']} ({d['device_id']}) ✓", fg=typer.colors.GREEN)
                    return
        except KeyboardInterrupt:
            raise typer.Exit(130) from None
        except RelayError as e:
            raise _fail(str(e)) from None
        raise _fail("pairing token expired; run `hivepal pair` again")


_TERMINAL = {"ended", "missed", "declined", "canceled"}


@app.command()
def call(
    reason: Annotated[str, typer.Argument(help="Why you're calling; shown on the phone (max 500 chars).")],
    urgent: Annotated[bool, typer.Option("--urgent", help="Ring like an alarm, bypassing Do Not Disturb if allowed.")] = False,
    agent_name: Annotated[Optional[str], typer.Option("--agent-name", help="Caller name shown for this call.")] = None,
    greeting: Annotated[
        Optional[str], typer.Option("--greeting", help="What the agent says when the user answers (default: the reason).")
    ] = None,
    voice: Annotated[
        Optional[str], typer.Option("--voice", help="With --ai the model's voice (e.g. Charon, marin); otherwise the macOS `say` voice.")
    ] = None,
    ai: AiOpt = False,
    provider: ProviderOpt = None,
    instructions: InstructionsOpt = None,
    mcp: McpOpt = None,
    tools: ToolsOpt = None,
    model: ModelOpt = None,
    agents_file: AgentsFileOpt = None,
    fallback: Annotated[bool, typer.Option("--fallback/--no-fallback",
                                           help="--ai: if nobody answers, send the reason as a message.")] = True,
    json_out: JsonOpt = False,
) -> None:
    """Call the phone. Rings until answered (30 s). With --ai a voice AI talks to you; otherwise the agent greets and echoes you (audio test)."""
    if ai:
        return _ai_call(reason, urgent, agent_name, instructions, mcp, tools, model, voice, agents_file, fallback,
                        json_out, provider)
    try:
        from .voice.echo import EchoWorker, synthesize
    except ImportError:
        raise _fail("voice support is not installed: install hivepal with the [voice] extra") from None

    s = _settings()
    log = logging.getLogger("hivepal")
    client = _client(s)

    async def run() -> dict:
        after = (await asyncio.to_thread(client.list_events, 0, 1))["last_seq"]
        try:
            created = await asyncio.to_thread(
                client.create_call, reason, priority="urgent" if urgent else "normal", agent_name=agent_name
            )
        except RelayError as e:
            raise _fail(str(e)) from None
        c, join = created["call"], created["join"]
        t0 = c["created_at"]
        call_id = c["call_id"]
        if not json_out:
            typer.echo(f"Calling… {call_id} (Ctrl+C to hang up)")

        text = greeting if greeting is not None else f"{c['agent_name']}. {reason}"
        pcm = await asyncio.to_thread(synthesize, text, voice)
        user_left = asyncio.Event()
        worker = EchoWorker(pcm, user_left)
        stop = asyncio.Event()
        loop = asyncio.get_running_loop()
        for sig in (signal.SIGINT, signal.SIGTERM):
            try:
                loop.add_signal_handler(sig, stop.set)
            except NotImplementedError:
                pass

        def say(line: str) -> None:
            if not json_out:
                typer.echo(f"  +{(_now_ms() - t0) / 1000:5.1f}s  {line}")

        # Join the room while the phone rings; the user shouldn't wait for us after answering.
        joining = asyncio.create_task(worker.start(join["livekit_url"], join["livekit_token"]))
        joined = False
        try:
            state = c["state"]
            while state not in _TERMINAL:
                if joining.done() and not joined:
                    joined = True
                    if joining.exception():
                        log.error("could not join the call's room: %s", joining.exception())
                        typer.secho("error: the agent could not join the call (LiveKit unreachable?)", fg=typer.colors.RED, err=True)
                        stop.set()
                    else:
                        say("agent in the room")
                if user_left.is_set() and not stop.is_set():
                    # The room emptied. In a group call another phone may still answer
                    # while it rings: the relay decides when the call is over.
                    user_left.clear()
                    c = await asyncio.to_thread(client.get_call, call_id)
                    if c["state"] == "active":
                        say("everyone left; waiting while the other phones still ring")
                    state = c["state"]
                    continue
                if stop.is_set():
                    c = await asyncio.to_thread(client.end_call, call_id)
                    state = c["state"]
                    break
                page = await asyncio.to_thread(client.list_events, after, 100)
                for e in page["events"]:
                    after = e.get("seq", after)
                    if e.get("call_id") != call_id:
                        continue
                    t = e["type"]
                    if t == "call.ringing":
                        say(f"ringing on {e['device_id']}")
                    elif t == "call.accept":
                        # Group calls: later phones join while the call is active.
                        verb = "joined" if state == "active" else "answered"
                        say(f"{verb} on {e.get('device_id', 'the phone')}")
                        state = "active"
                    elif t == "call.decline":
                        # Group calls: one phone declining doesn't end the call.
                        say(f"declined on {e.get('device_id', 'the phone')}")
                        state = (await asyncio.to_thread(client.get_call, call_id))["state"]
                    elif t == "call.missed":
                        say("no answer")
                        state = "missed"
                    elif t == "call.end":
                        say(f"ended by {e['by']} ({e['reason']})")
                        state = "ended"
                try:
                    await asyncio.wait_for(asyncio.shield(stop.wait()), 0.3)
                except asyncio.TimeoutError:
                    pass
        finally:
            if not joining.done():
                joining.cancel()
            await asyncio.gather(joining, return_exceptions=True)
            await worker.stop()
        return await asyncio.to_thread(client.get_call, call_id)

    try:
        final = asyncio.run(run())
    finally:
        client.close()
    if json_out:
        _emit_json(final)
    else:
        dur = f", {final['duration_ms'] / 1000:.0f} s" if final.get("duration_ms") is not None else ""
        typer.echo(f"Call {final['state']}{dur}")
    log.debug("call finished: %s", final)


@app.command()
def msg(
    text: Annotated[str, typer.Argument(help="Message text (max 4000 chars).")],
    reply_to: Annotated[Optional[str], typer.Option("--reply-to", help="msg_id or call_id this answers.")] = None,
    group: Annotated[Optional[str], typer.Option("--group", "-g", help="Write in this group (see `hivepal groups`) "
                                                 "instead of your own conversation.")] = None,
    to: Annotated[Optional[str], typer.Option("--to", help="Write to another agent (a bots: conversation the user can "
                                              "read but that doesn't wake the phone).")] = None,
    option: Annotated[Optional[list[str]], typer.Option("--option", "-o", help="A quick reply the user can tap "
                                                        "(repeatable, up to 10). The answer comes back as a reply.")] = None,
    file: Annotated[Optional[list[str]], typer.Option("--file", "-f", help="Attach a photo (jpg/png/webp) or audio "
                                                      "(m4a/aac/ogg/mp3/wav) (repeatable, up to 4).")] = None,
    voice: Annotated[bool, typer.Option("--voice", help="Also send the text as a voice note (spoken by Gemini).")] = False,
    voice_name: Annotated[Optional[str], typer.Option("--voice-name", help="--voice: a Gemini voice (default Kore).")] = None,
    json_out: JsonOpt = False,
) -> None:
    """Send a message to the phone. Prints the msg_id."""
    if group and to:
        raise _fail("use --group or --to, not both")
    s = _settings()
    with _client(s) as c:
        try:
            conv = f"group:{group}" if group else None
            if to:
                me = (c.status().get("self") or {}).get("agent_id") or "main"
                if to == me:
                    raise _fail("--to: that's you")
                conv = "bots:" + ".".join(sorted([me, to]))
            ids = [_upload(c, f)["file_id"] for f in file or []]
            if voice:
                ids.append(c.tts(text, voice_name)["file_id"])
            m = c.post_message(text, reply_to=reply_to, conv=conv, options=option or None, attachments=ids or None)
        except RelayError as e:
            raise _fail(str(e)) from None
    store = Store(s.db_path)
    store.upsert_message(m["msg_id"], "agent", m["text"], m["created_at"], m.get("reply_to"), m.get("conv"),
                         m.get("agent_id"), m.get("options"), m.get("attachments"))
    store.close()
    if json_out:
        _emit_json({"msg_id": m["msg_id"]})
    else:
        typer.echo(m["msg_id"])


_MIME = {".jpg": "image/jpeg", ".jpeg": "image/jpeg", ".png": "image/png", ".webp": "image/webp",
         ".m4a": "audio/mp4", ".mp4": "audio/mp4", ".aac": "audio/aac", ".ogg": "audio/ogg", ".opus": "audio/ogg",
         ".mp3": "audio/mpeg", ".webm": "audio/webm", ".wav": "audio/wav"}
_EXT = {v: k for k, v in reversed(list(_MIME.items()))}


def _upload(c: RelayClient, path: str) -> dict:
    from pathlib import Path

    p = Path(path).expanduser()
    mime = _MIME.get(p.suffix.lower())
    if mime is None:
        raise _fail(f"{path}: only {', '.join(sorted(_MIME))}")
    try:
        data = p.read_bytes()
    except OSError as e:
        raise _fail(f"{path}: {e.strerror}") from None
    return c.upload_file(data, mime)


def _when(at: str | None, in_: str | None, now_ms: int | None = None) -> int:
    """--at 19:00 (today, or tomorrow if past), --at 2026-11-20T09:30, or --in 2h / 30m / 1d."""
    now_ms = now_ms if now_ms is not None else _now_ms()
    if bool(at) == bool(in_):
        raise _fail("give --at or --in")
    if in_:
        m = _DURATION.match(in_.strip())
        if not m:
            raise typer.BadParameter("--in: e.g. 30m, 2h, 1d")
        return now_ms + int(m.group(1)) * {"s": 1, "m": 60, "h": 3600, "d": 86400}[m.group(2)] * 1000
    v = at.strip()
    hm = re.fullmatch(r"(\d{1,2}):(\d{2})", v)
    if hm:
        now = datetime.fromtimestamp(now_ms / 1000).astimezone()
        t = now.replace(hour=int(hm.group(1)), minute=int(hm.group(2)), second=0, microsecond=0)
        if t.timestamp() * 1000 <= now_ms:
            from datetime import timedelta

            t += timedelta(days=1)
        return int(t.timestamp() * 1000)
    return parse_since(v, now_ms)


@app.command()
def remind(
    text: Annotated[str, typer.Argument(help="What to say then.")],
    at: Annotated[Optional[str], typer.Option("--at", help="19:00 (next time it's 19:00), or a date: 2026-11-20T09:30.")] = None,
    in_: Annotated[Optional[str], typer.Option("--in", help="From now: 30m, 2h, 1d.")] = None,
    group: Annotated[Optional[str], typer.Option("--group", "-g", help="Send it in this group.")] = None,
    option: Annotated[Optional[list[str]], typer.Option("--option", "-o", help="Quick reply (repeatable).")] = None,
    insist: Annotated[Optional[str], typer.Option("--insist", help="If the user hasn't answered after this long (e.g. 15m), "
                                                  "send it again.")] = None,
    call: Annotated[bool, typer.Option("--call", help="--insist: and if still no answer after as long again, ring "
                                       "the phone.")] = False,
    json_out: JsonOpt = False,
) -> None:
    """Schedule a message: the relay sends it at that time as you, with a push, even if you aren't running then."""
    when = _when(at, in_)
    insist_s = None
    if insist:
        m = _DURATION.match(insist.strip())
        if not m:
            raise typer.BadParameter("--insist: e.g. 10m, 1h")
        insist_s = int(m.group(1)) * {"s": 1, "m": 60, "h": 3600, "d": 86400}[m.group(2)]
    elif call:
        raise _fail("--call needs --insist")
    s = _settings()
    with _client(s) as c:
        try:
            r = c.create_reminder(text, when, conv=f"group:{group}" if group else None, options=option or None,
                                  insist_s=insist_s, call=call)
        except RelayError as e:
            raise _fail(str(e)) from None
    if json_out:
        _emit_json(r)
    else:
        typer.echo(f"{r['rem_id']}  {_fmt_ts(r['at'])}")


@app.command()
def reminders(
    cancel: Annotated[Optional[str], typer.Option("--cancel", help="Cancel this reminder (rem_...).")] = None,
    json_out: JsonOpt = False,
) -> None:
    """List your pending reminders, or cancel one."""
    s = _settings()
    with _client(s) as c:
        try:
            if cancel:
                c.cancel_reminder(cancel)
                typer.echo(f"Canceled {cancel}")
                return
            rows = c.list_reminders()
        except RelayError as e:
            raise _fail(str(e)) from None
    if json_out:
        _emit_json({"reminders": rows})
        return
    if not rows:
        typer.echo("No pending reminders.")
    for r in rows:
        where = "" if r["conv"].startswith("agent:") else f" [{r['conv']}]"
        typer.echo(f"{r['rem_id']}  {_fmt_ts(r['at'])}{where}  {r['text']}")


@app.command()
def file(
    file_id: Annotated[str, typer.Argument(help="file_... from a message's attachments (see replies).")],
    out: Annotated[Optional[str], typer.Option("--out", "-o", help="Where to save it (default: ./<file_id>.<ext>).")] = None,
) -> None:
    """Download a photo or audio the user (or another agent) sent you. Prints the path."""
    from pathlib import Path

    s = _settings()
    with _client(s) as c:
        try:
            data, mime = c.download_file(file_id)
        except RelayError as e:
            raise _fail(str(e)) from None
    path = Path(out).expanduser() if out else Path(f"{file_id}{_EXT.get(mime.split(';')[0], '')}")
    path.write_bytes(data)
    typer.echo(str(path))


@app.command()
def replies(
    since: Annotated[
        Optional[str], typer.Option("--since", help="Show all replies since: 10m, 2h, 1d, epoch ms or ISO date.")
    ] = None,
    all_: Annotated[bool, typer.Option("--all", help="Show all replies, not just new ones.")] = False,
    peek: Annotated[bool, typer.Option("--peek", help="Don't mark the replies as seen.")] = False,
    limit: Annotated[int, typer.Option("--limit", min=1, max=500)] = 100,
    group: Annotated[Optional[str], typer.Option("--group", "-g", help="Only this group's messages.")] = None,
    json_out: JsonOpt = False,
) -> None:
    """Show the user's replies. By default only new ones (not shown before), which are then marked as seen.

    In a group you also get what the other agents write there; those lines say the group and who wrote."""
    s = _settings()
    store = Store(s.db_path)
    names: dict[str, str] = {}
    with _client(s) as c:
        try:
            catch_up(c, store)
        except RelayError as e:
            raise _fail(str(e)) from None

        since_ms = parse_since(since) if since else None
        undelivered_only = since is None and not all_
        rows = store.replies(since=since_ms, undelivered_only=undelivered_only, limit=limit,
                             conv=f"group:{group}" if group else None)
        if rows and not peek:
            store.mark_delivered([r["msg_id"] for r in rows], _now_ms())
            for r in rows:
                if r["read_at"] is None and r["sender"] == "user":
                    try:
                        c.mark_read(r["msg_id"])
                    except RelayError:
                        pass  # best effort; the read receipt is cosmetic
        if any((r["conv"] or "").startswith(("group:", "bots:")) for r in rows):
            try:
                names = {f"agent:{a['agent_id']}": a["name"] for a in c.list_agents()}
                names.update({f"group:{g['group_id']}": g["name"] for g in c.list_groups()})
            except RelayError:
                pass  # names are cosmetic; ids are shown instead

    def who(r: dict[str, Any]) -> str:
        if r["sender"] == "user":
            return "user"
        aid = r["agent_id"] or "main"
        return names.get(f"agent:{aid}", aid)

    out = []
    tc = _client(s)
    out = []
    for r in rows:
        item: dict[str, Any] = {"msg_id": r["msg_id"], "text": r["text"], "created_at": r["created_at"],
                                "reply_to": r["reply_to"]}
        conv = r["conv"] or "agent:main"
        if conv.startswith("group:"):
            item.update(conv=conv, group=names.get(conv, conv[6:]), from_=r["sender"], author=who(r))
            item["from"] = item.pop("from_")
            if r["sender"] == "agent":
                item["agent_id"] = r["agent_id"]
        elif conv.startswith("bots:"):
            item.update(conv=conv, author=who(r) if r["sender"] == "agent" else "user", agent_id=r["agent_id"])
        if r["attachments"]:
            item["attachments"] = json.loads(r["attachments"])
            for a in item["attachments"]:
                # Voice notes as text, for agents that can't listen (transcribed once by the relay).
                if a.get("mime", "").startswith("audio/") and r["sender"] == "user":
                    try:
                        a["transcript"] = tc.transcript(a["file_id"])
                    except RelayError:
                        pass
        if r["options"]:
            item["options"] = json.loads(r["options"])
        if r["reply_to"]:
            # A tapped quick reply only says "Sí": show what it answers.
            orig = store.message(r["reply_to"])
            if orig is not None:
                item["reply_to_text"] = orig["text"]
        out.append(item)
    tc.close()
    store.close()
    if json_out:
        _emit_json({"replies": out})
        return
    if not out:
        typer.echo("No new replies." if undelivered_only else "No replies.")
        return
    for r in out:
        if "group" in r:
            prefix = f"[{r['group']}] {r['author']}: "
        elif r.get("conv", "").startswith("bots:"):
            prefix = f"[{r['author']} → you] "
        else:
            prefix = ""
        files = "".join(
            f" [{a['mime'].split('/')[0]}: {a['file_id']}]" + (f" «{a['transcript']}»" if a.get("transcript") else "")
            for a in r.get("attachments", [])
        )
        quoted = r.get("reply_to_text")
        answer = f" (reply to: «{quoted[:80] + ('…' if len(quoted) > 80 else '')}»)" if quoted else ""
        typer.echo(f"[{_fmt_ts(r['created_at'])}] {prefix}{r['text']}{files}{answer}")


@app.command()
def mailbox(
    folder: Annotated[str, typer.Argument(help="The mailboxes folder: each agent's files go in <folder>/<agent id>/.")],
    since: Annotated[
        str, typer.Option("--since", help="On the first run in this folder, start from: 10m, 2h, 1d, epoch ms or ISO date.")
    ] = "1d",
    git: Annotated[bool, typer.Option("--git", help="Commit the new files in the folder's git repository and push.")] = False,
    json_out: JsonOpt = False,
) -> None:
    """Write your new messages as Markdown files, one per message, in your mailbox folder.

    For agents that read a folder (a notes repo, say) instead of running `hivepal replies`. Run it
    from cron or a timer. It doesn't mark messages as seen for `replies`."""
    from pathlib import Path

    from . import mailbox as mb

    s = _settings()
    root = Path(folder).expanduser()
    store = Store(s.db_path)
    names: dict[str, str] = {}
    try:
        with _client(s) as c:
            try:
                catch_up(c, store)
            except RelayError as e:
                raise _fail(str(e)) from None
            rows = mb.pending(store, root, parse_since(since))
            if any((r["conv"] or "").startswith(("group:", "bots:")) for r in rows):
                try:
                    names = {f"agent:{a['agent_id']}": a["name"] for a in c.list_agents()}
                    names.update({f"group:{g['group_id']}": g["name"] for g in c.list_groups()})
                except RelayError:
                    pass  # names are cosmetic; ids are shown instead

            def transcript(file_id: str) -> str | None:
                try:
                    return c.transcript(file_id)
                except RelayError:
                    return None

            written = mb.write(store, root, rows, names, transcript)
        me = store.self_agent_id
    finally:
        store.close()
    if git and written:
        try:
            mb.git_publish(root, [w.path for w in written],
                           f"mailbox/{me}: {len(written)} message{'s' if len(written) != 1 else ''}")
        except (mb.GitError, OSError) as e:
            raise _fail(f"the files are written, but publishing them failed: {e}") from None
    if json_out:
        _emit_json({"written": [{"msg_id": w.msg_id, "path": str(w.path)} for w in written]})
        return
    for w in written:
        typer.echo(str(w.path))
    if not written:
        typer.echo("No new messages.")


@app.command()
def doctor(json_out: JsonOpt = False) -> None:
    """Check that everything works (relay, HTTPS, key, push, calls, voice, phones) and say how to fix what doesn't."""
    from . import doctor as dr

    checks = dr.run()
    if json_out:
        _emit_json(dr.as_json(checks))
    else:
        mark = {dr.OK: ("✓", typer.colors.GREEN), dr.WARN: ("!", typer.colors.YELLOW), dr.FAIL: ("✗", typer.colors.RED)}
        for c in checks:
            sym, color = mark[c.status]
            typer.secho(f"{sym} ", fg=color, nl=False)
            typer.echo(f"{c.name}: {c.detail}")
            if c.fix and c.status != dr.OK:
                typer.echo(f"    → {c.fix}")
    if any(c.status == dr.FAIL for c in checks):
        raise typer.Exit(1)


@app.command()
def status(json_out: JsonOpt = False) -> None:
    """Relay connection and paired devices."""
    s = _settings()
    with _client(s) as c:
        try:
            st = c.status()
            try:
                devs = c.list_devices()
            except RelayError as e:
                if e.status != 403:  # an agent's own key (multi-agent fork) can't list phones
                    raise
                devs = None
        except RelayError as e:
            if json_out:
                _emit_json({"relay_url": s.relay_url, "reachable": False, "error": str(e)})
            raise _fail(str(e)) from None
    if json_out:
        _emit_json({"relay_url": s.relay_url, "reachable": True, **st, "devices": devs})
        return
    typer.echo(f"Relay:       {s.relay_url} ({st['relay_id']}, v{st['version']})")
    me = st.get("self")
    typer.echo(f"Agent name:  {st['agent_name']}" + (f" (id {me['agent_id']})" if me else ""))
    typer.echo(f"Daemon:      {'connected (serve running)' if st['agent_online'] else 'not connected'}")
    typer.echo(f"Push:        {st['push_provider']}")
    if devs is None:
        typer.echo(f"Devices:     {st.get('device_count', '?')} paired")
        return
    if not devs:
        typer.echo("Devices:     none — run `hivepal pair`")
    for d in devs:
        seen = _fmt_ts(d["last_seen_at"]) if d.get("last_seen_at") else "never"
        push = "push ok" if d["has_push_token"] else "NO push token"
        typer.echo(f"Device:      {d['name']} ({d['device_id']}) — {push}, last seen {seen}")


@devices_app.callback(invoke_without_command=True)
def devices_list(
    ctx: typer.Context,
    include_revoked: Annotated[bool, typer.Option("--include-revoked")] = False,
    json_out: JsonOpt = False,
) -> None:
    """List paired devices."""
    if ctx.invoked_subcommand:
        return
    s = _settings()
    with _client(s) as c:
        try:
            devs = c.list_devices(include_revoked=include_revoked)
        except RelayError as e:
            raise _fail(str(e)) from None
    if json_out:
        _emit_json({"devices": devs})
        return
    if not devs:
        typer.echo("No devices paired.")
    for d in devs:
        state = f"revoked {_fmt_ts(d['revoked_at'])}" if d.get("revoked_at") else "active"
        typer.echo(f"{d['device_id']}  {d['name']:<24} paired {_fmt_ts(d['created_at'])}  {state}")


def _ai_call(reason: str, urgent: bool, agent_name: str | None, instructions: str | None, mcp: list[str] | None,
             tools: str | None, model: str | None, voice: str | None, agents_file: str | None, fallback: bool,
             json_out: bool, provider: str | None = None) -> None:
    from .voice import CallInfo

    s = _settings()
    client = _client(s)

    async def run() -> Any:
        built = await _build_ai(instructions, mcp, tools, model, voice, agents_file, provider)
        agent = built.agent
        try:
            created = await asyncio.to_thread(
                client.create_call, reason, priority="urgent" if urgent else "normal", agent_name=agent_name
            )
        except RelayError as e:
            raise _fail(str(e)) from None
        if not json_out:
            typer.echo(f"Calling… {created['call']['call_id']} (Ctrl+C to hang up)")
        stop = asyncio.Event()
        _signals(stop)
        try:
            result = await agent.run(CallInfo(created["call"]["call_id"], reason), created["join"], relay=client, stop=stop)
            if fallback and not result.answered and not stop.is_set():
                from .voice.quick import missed_call_message

                try:
                    result.fallback_msg_id = await asyncio.to_thread(missed_call_message, client, result.call_id, reason)
                except RelayError as e:
                    logging.getLogger("hivepal").warning("could not send the missed-call message: %s", e)
            if built.consultations and built.consultations.pending and not stop.is_set():
                if not json_out:
                    typer.echo("Waiting for your agents' answers; they will arrive as messages (Ctrl+C to stop)…")
                stop_wait = asyncio.create_task(stop.wait())
                drain = asyncio.create_task(built.consultations.drain())
                await asyncio.wait({stop_wait, drain}, return_when=asyncio.FIRST_COMPLETED)
                stop_wait.cancel()
                drain.cancel()
            return result
        finally:
            await built.aclose()

    try:
        result = asyncio.run(run())
    finally:
        client.close()
    if json_out:
        from dataclasses import asdict

        _emit_json(asdict(result))
    else:
        _print_result(result)


@devices_app.command("revoke")
def devices_revoke(device_id: Annotated[str, typer.Argument(help="Device id (dev_...).")]) -> None:
    """Revoke a device: its credentials stop working immediately."""
    s = _settings()
    with _client(s) as c:
        try:
            c.revoke_device(device_id)
        except RelayError as e:
            raise _fail(str(e)) from None
    typer.echo(f"Revoked {device_id}")


@agents_app.callback(invoke_without_command=True)
def agents_list(
    ctx: typer.Context,
    agents_file: Annotated[Optional[str], typer.Option("--agents", help="agents.yaml (default ~/.agentring/agents.yaml).")] = None,
    json_out: JsonOpt = False,
) -> None:
    """List the agents and MCP servers in agents.yaml (and check the file)."""
    if ctx.invoked_subcommand is not None:
        ctx.obj = agents_file  # `hivepal agents --agents X ask ...`
        return
    cfg = _agents_config(agents_file, required=True)
    if json_out:
        from dataclasses import asdict

        _emit_json(asdict(cfg))
        return
    for a in cfg.agents:
        where = f"  (in {a.cwd})" if a.cwd else ""
        typer.echo(f"{a.name}: {a.description}\n  $ {' '.join(a.command)}{where}")
    for m in cfg.mcp:
        typer.echo(f"MCP {m.url}" + (f"  tools: {', '.join(m.tools)}" if m.tools is not None else "  (all tools)"))
    if not cfg.agents and not cfg.mcp:
        typer.echo("agents.yaml is empty.")


@agents_app.command("ask")
def agents_ask(
    ctx: typer.Context,
    name: Annotated[str, typer.Argument(help="The agent's name in agents.yaml.")],
    task: Annotated[str, typer.Argument(help="What to ask it, as the voice AI would.")],
    agents_file: Annotated[Optional[str], typer.Option("--agents", help="agents.yaml (default ~/.agentring/agents.yaml).")] = None,
) -> None:
    """Ask one agent something without a call: checks that consult_agent will work."""
    cfg = _agents_config(agents_file or ctx.obj, required=True)
    agent = next((a for a in cfg.agents if a.name == name), None)
    if agent is None:
        raise _fail(f"no agent {name!r}; agents: {', '.join(a.name for a in cfg.agents) or 'none'}")
    try:
        typer.echo(asyncio.run(agent.ask(task)))
    except (OSError, RuntimeError, TimeoutError) as e:
        raise _fail(str(e)) from None


@bots_app.callback(invoke_without_command=True)
def bots_list(ctx: typer.Context, json_out: JsonOpt = False) -> None:
    """List the agents on the relay (`main` is the relay's own API key)."""
    if ctx.invoked_subcommand:
        return
    s = _settings()
    with _client(s) as c:
        try:
            agents = c.list_agents()
        except RelayError as e:
            raise _fail(str(e)) from None
    if json_out:
        _emit_json({"agents": agents})
        return
    for a in agents:
        seen = "online" if a.get("online") else (f"seen {_fmt_ts(a['last_seen_at'])}" if a.get("last_seen_at") else "")
        typer.echo(f"{a['agent_id']:<16} {a['name']:<24} {seen}" + ("  ✉" if a.get("wakes") else ""))


def _bot_key_out(r: dict, json_out: bool) -> None:
    if json_out:
        _emit_json(r)
        return
    typer.echo(f"Agent {r['agent']['agent_id']} ({r['agent']['name']}). Its API key (shown only now):")
    typer.echo(r["api_key"])
    typer.echo("Give it to that agent: HIVEPAL_API_KEY, or `hivepal init --api-key` in its own HIVEPAL_HOME.")


@bots_app.command("add")
def bots_add(
    agent_id: Annotated[str, typer.Argument(help="Short id: lowercase letters, digits, - and _ (e.g. nubi).")],
    name: Annotated[str, typer.Argument(help="Name shown on the phone.")],
    json_out: JsonOpt = False,
) -> None:
    """Add an agent with its own API key and conversation. Needs the relay's API key."""
    s = _settings()
    with _client(s) as c:
        try:
            r = c.create_agent(agent_id, name)
        except RelayError as e:
            raise _fail(str(e)) from None
    _bot_key_out(r, json_out)


@bots_app.command("rename")
def bots_rename(
    agent_id: Annotated[str, typer.Argument()],
    name: Annotated[str, typer.Argument(help="New name shown on the phone.")],
) -> None:
    """Rename an agent (main too). The user can also do it from the app."""
    s = _settings()
    with _client(s) as c:
        try:
            a = c.rename_agent(agent_id, name)
        except RelayError as e:
            raise _fail(str(e)) from None
    typer.echo(f"{a['agent_id']} is now {a['name']}")


@bots_app.command("wake")
def bots_wake(
    agent_id: Annotated[str, typer.Argument()],
    email: Annotated[str, typer.Argument(help="Address to email when the user writes to this agent, or 'off'.")],
) -> None:
    """Wake an agent by email when the user writes to it (for agents that only run on a trigger).

    The email never carries the message, only the agent ids in its subject ("Hivepal: casa nubi"), since
    several agents may share an address; each reads its messages from the relay as usual. At most one
    a minute. Needs the relay's API key and HIVEPAL_SMTP_URL on the relay."""
    s = _settings()
    with _client(s) as c:
        try:
            a = c.set_agent_wake(agent_id, None if email.lower() == "off" else email)
        except RelayError as e:
            raise _fail(str(e)) from None
    typer.echo(f"{a['agent_id']}: " + ("woken by email" if a.get("wakes") else "no wake-up email"))


@bots_app.command("key")
def bots_key(agent_id: Annotated[str, typer.Argument()], json_out: JsonOpt = False) -> None:
    """Give an agent a new API key; the old one stops working at once."""
    s = _settings()
    with _client(s) as c:
        try:
            r = c.rotate_agent_key(agent_id)
        except RelayError as e:
            raise _fail(str(e)) from None
    _bot_key_out(r, json_out)


@bots_app.command("remove")
def bots_remove(agent_id: Annotated[str, typer.Argument()]) -> None:
    """Revoke an agent's key and take it out of its groups. Its messages stay."""
    s = _settings()
    with _client(s) as c:
        try:
            c.remove_agent(agent_id)
        except RelayError as e:
            raise _fail(str(e)) from None
    typer.echo(f"Removed {agent_id}")


def _members(value: str) -> list[str]:
    return [m.strip() for m in value.split(",") if m.strip()]


@groups_app.callback(invoke_without_command=True)
def groups_list(ctx: typer.Context, json_out: JsonOpt = False) -> None:
    """List the groups (an agent sees the ones it is in)."""
    if ctx.invoked_subcommand:
        return
    s = _settings()
    with _client(s) as c:
        try:
            groups = c.list_groups()
        except RelayError as e:
            raise _fail(str(e)) from None
    if json_out:
        _emit_json({"groups": groups})
        return
    if not groups:
        typer.echo("No groups.")
    for g in groups:
        typer.echo(f"{g['group_id']:<16} {g['name']:<24} {', '.join(g['members'])}")


@groups_app.command("add")
def groups_add(
    group_id: Annotated[str, typer.Argument(help="Short id (e.g. todos). Agents write there with --group <id>.")],
    name: Annotated[str, typer.Argument(help="Name shown on the phone.")],
    members: Annotated[str, typer.Option("--members", "-m", help="Comma-separated agent ids.")],
) -> None:
    """Create a group. Needs the relay's API key (the user can also create groups from the app)."""
    s = _settings()
    with _client(s) as c:
        try:
            g = c.create_group(group_id, name, _members(members))
        except RelayError as e:
            raise _fail(str(e)) from None
    typer.echo(f"Group {g['group_id']} ({g['name']}): {', '.join(g['members'])}")


@groups_app.command("set")
def groups_set(
    group_id: Annotated[str, typer.Argument()],
    name: Annotated[Optional[str], typer.Option("--name", help="New name.")] = None,
    members: Annotated[Optional[str], typer.Option("--members", "-m", help="The new member list, comma-separated.")] = None,
) -> None:
    """Rename a group or replace its members."""
    s = _settings()
    with _client(s) as c:
        try:
            g = c.update_group(group_id, name=name, members=_members(members) if members is not None else None)
        except RelayError as e:
            raise _fail(str(e)) from None
    typer.echo(f"Group {g['group_id']} ({g['name']}): {', '.join(g['members'])}")


@groups_app.command("remove")
def groups_remove(group_id: Annotated[str, typer.Argument()]) -> None:
    """Delete a group. Its messages stay in the relay's log."""
    s = _settings()
    with _client(s) as c:
        try:
            c.remove_group(group_id)
        except RelayError as e:
            raise _fail(str(e)) from None
    typer.echo(f"Removed group {group_id}")


@voice_app.command("bench")
def voice_bench(
    provider: Annotated[Optional[str], typer.Option("--provider", help="gemini, openai, grok, phonic, openrouter "
                                                    "or local (default HIVEPAL_VOICE_PROVIDER, else gemini).")] = None,
    model: Annotated[Optional[str], typer.Option("--model", help="The provider's model (default HIVEPAL_VOICE_MODEL, "
                                                 "else the provider's default).")] = None,
    voice: Annotated[Optional[str], typer.Option("--voice", help="The provider's voice.")] = None,
    runs: Annotated[int, typer.Option("--runs", min=1, max=20, help="Sessions per prompt.")] = 3,
    lang: Annotated[str, typer.Option("--lang", help="Language of the spoken prompts: en or es.")] = "en",
    prompts: Annotated[Optional[str], typer.Option("--prompts", help="Directory with your own recordings "
                                                   "(chat.wav, time.wav, chain.wav, consult.wav; 16-bit PCM) "
                                                   "instead of the system's text-to-speech.")] = None,
    out: Annotated[Optional[str], typer.Option("--out", help="Keep each session's audio (WAV) and results.jsonl here.")] = None,
    instructions: Annotated[Optional[str], typer.Option("--instructions", help="System prompt instead of the one "
                                                        "`call --ai` uses, or @file.")] = None,
    json_out: JsonOpt = False,
) -> None:
    """Time a voice provider on the hivepal phone-call test: 4 spoken prompts, the same demo tools.

    Reports, per prompt, the median time from the end of speech to the first word, how often the right tools were called, and how often it spoke before a tool answered. Uses the provider's real API (and its minutes): about 12 short sessions with --runs 3.
    """
    from pathlib import Path

    try:
        from .voice import bench
    except ImportError:
        raise _fail("voice support is not installed: install hivepal with the [voice] extra") from None
    from .voice.providers import VoiceConfigError, resolve_voice

    if lang not in bench.PROMPTS:
        raise _fail(f"--lang: one of {', '.join(bench.PROMPTS)}")
    try:
        choice = resolve_voice(provider, model, voice)
    except VoiceConfigError as e:
        raise _fail(str(e)) from None
    out_dir = Path(out).expanduser() if out else None
    if out_dir:
        out_dir.mkdir(parents=True, exist_ok=True)
    try:
        files = bench.prompt_files(Path(prompts).expanduser()) if prompts else bench.speak_prompts(lang, bench.temp_dir())
    except (OSError, RuntimeError, ValueError) as e:
        raise _fail(str(e)) from None
    where = f" at {choice.url}" if choice.url else ""
    if not json_out:
        typer.echo(f"{choice.provider.label} ({choice.model}){where}: {len(bench.SCENARIOS)} prompts x {runs}")

    def progress(s: Any) -> None:
        if json_out:
            return
        took = f"{s.first_voice_s:.2f} s" if s.first_voice_s is not None else "no voice"
        tools = ", ".join(s.tools) or "no tools"
        typer.echo(f"  {s.scenario:<8} #{s.run + 1}  {took:<9} {tools}{'  ' + s.error if s.error else ''}")

    from .voice.quick import read_instructions

    try:
        text = read_instructions(instructions) if instructions else None
    except OSError as e:
        raise _fail(f"cannot read the instructions: {e}") from None
    sessions = asyncio.run(bench.run_bench(choice.factory(), files, runs=runs, out=out_dir, instructions=text,
                                           on_session=progress))
    summ = bench.summary(sessions)
    if json_out:
        typer.echo(json.dumps({"provider": choice.provider.name, "model": choice.model, "url": choice.url,
                               "runs": runs, "lang": lang, "summary": summ}, ensure_ascii=False, indent=2))
        return
    typer.echo("")
    typer.echo(f"{'prompt':<9}{'first voice':>12}{'right tools':>13}{'spoke before tool':>19}")
    for name, r in summ.items():
        fv = f"{r['first_voice_s']:.2f} s" if r["first_voice_s"] is not None else "-"
        typer.echo(f"{name:<9}{fv:>12}{r['tools_ok']:>13}{(r['spoke_before_tool'] or '-'):>19}")
    if out_dir:
        typer.echo(f"\nAudio and results.jsonl in {out_dir}")


@app.command()
def mcp(
    agent_name: Annotated[Optional[str], typer.Option("--agent-name", help="Caller name the phone shows for this agent's calls.")] = None,
    device: Annotated[Optional[list[str]], typer.Option("--device", help="Ring only this phone (device id); repeat for several.")] = None,
) -> None:
    """Run as an MCP server over stdio, for agents: `claude mcp add hivepal -- hivepal mcp`."""
    from .mcp_server import run_stdio

    try:
        settings = load_settings()
    except ConfigError as e:
        # Start anyway: the agent sees the tools and learns from the first call what is missing.
        typer.echo(f"hivepal mcp: {e}", err=True)
        run_stdio(None, not_configured=str(e))
        return
    run_stdio(settings, agent_name=agent_name, device_ids=device or None)


@app.command("mcp-http")
def mcp_http(
    host: Annotated[Optional[str], typer.Option("--host", help="Address to bind (default HIVEPAL_MCP_HOST, else 127.0.0.1).")] = None,
    port: Annotated[Optional[int], typer.Option("--port", help="Port (default HIVEPAL_MCP_PORT, else 8790).")] = None,
    relay_url: Annotated[Optional[str], typer.Option("--relay-url", help="The relay every API key is used against "
                                                     "(default HIVEPAL_RELAY_URL). With a hub (HIVEPAL_HUB_ADMIN_URL), "
                                                     "the relay's internal address.")] = None,
    data_dir: Annotated[Optional[str], typer.Option("--data-dir", help="Per-user state (default HIVEPAL_MCP_DATA_DIR, "
                                                    "else ~/.agentring/mcp-http).")] = None,
    max_calls: Annotated[Optional[int], typer.Option("--max-calls", help="Voice calls at once, all users together "
                                                     "(default HIVEPAL_MCP_MAX_CALLS, else 4).")] = None,
    max_body: Annotated[Optional[int], typer.Option("--max-body", help="Largest request body in bytes "
                                                    "(default HIVEPAL_MCP_MAX_BODY, else 1 MiB).")] = None,
    allow_origin: Annotated[Optional[list[str]], typer.Option("--allow-origin", help="Browser Origin allowed to use "
                                                              "/mcp, repeatable; '*' = any (default "
                                                              "HIVEPAL_MCP_ALLOWED_ORIGINS, comma-separated; else none).")] = None,
) -> None:
    """Run as a remote MCP server (Streamable HTTP at /mcp) for many users; each sends its relay API key."""
    from pathlib import Path

    from .mcp_http import HttpConfig, run_http

    try:
        cfg = HttpConfig.from_env(host=host, port=port, relay_url=relay_url, data_dir=Path(data_dir) if data_dir else None,
                                  max_calls=max_calls, max_body=max_body, allowed_origins=allow_origin or None)
    except ConfigError as e:
        raise _fail(str(e)) from None
    run_http(cfg)


def with_personas(base: Any, home: Any) -> Any:
    """Hivepal: when the user calls a specific agent, the voice AI speaks as that agent. Its persona
    comes from `personas/<agent_id>.md` in the hivepal home (if there is one), on top of the usual
    instructions; without the file, a short default says who it is."""
    from pathlib import Path

    async def instructions(info: Any) -> str:
        text = base(info) if callable(base) else base
        if inspect.isawaitable(text):
            text = await text
        aid = getattr(info, "agent_id", None)
        if not aid or aid == "main":
            return text
        name = getattr(info, "agent_name", None) or aid
        f = Path(home) / "personas" / f"{aid}.md"
        persona = f.read_text(encoding="utf-8").strip() if f.is_file() else (
            f"En esta llamada eres {name}, uno de los agentes del usuario, y hablas como {name}. "
            f"Si hay que apuntar o hacer algo, déjaselo como nota a {name} con su herramienta."
        )
        return f"{persona}\n\n{text}"

    return instructions


def _describe(event: dict[str, Any]) -> str | None:
    t = event.get("type")
    if t == "msg.new":
        who = "you" if event["from"] == "agent" else "user"
        return f"msg.new {event.get('msg_id', '')} from {who} ({len(event.get('text') or '')} chars)"
    if t == "msg.read":
        return f"msg.read {event['msg_id']} by {event['by']}"
    if t == "ws.hello":
        return None
    return t


@app.command()
def serve(
    ai: Annotated[bool, typer.Option("--ai", help="Answer calls from the phone with a voice AI (see --provider).")] = False,
    provider: ProviderOpt = None,
    instructions: InstructionsOpt = None,
    mcp: McpOpt = None,
    tools: ToolsOpt = None,
    model: ModelOpt = None,
    voice: Annotated[Optional[str], typer.Option("--voice", help="--ai: the model's voice (e.g. Charon, marin).")] = None,
    agents_file: AgentsFileOpt = None,
) -> None:
    """Run the daemon: keeps a live connection to the relay. With --ai, answers the phone's calls with a voice AI."""
    s = _settings()
    store = Store(s.db_path)
    client = _client(s)
    log = logging.getLogger("hivepal")

    calls: set[asyncio.Task] = set()
    voice_agent: Any = None

    async def answer(e: dict[str, Any], stop: asyncio.Event) -> None:
        from .voice import accept_incoming

        if calls - {asyncio.current_task()}:
            log.info("already on a call; not answering %s", e["call_id"])
            return
        accepted = await asyncio.to_thread(accept_incoming, client, e)
        if not accepted:
            return
        info, join = accepted
        log.info("answering %s", info.call_id)
        r = await voice_agent.run(info, join, relay=client, stop=stop)
        log.info("call %s: %s, %d s, %d turns", info.call_id, r.end_reason, r.duration_s, len(r.transcript))

    def on_event(e: dict[str, Any], stop: asyncio.Event | None = None) -> None:
        d = _describe(e)
        if d:
            log.info(d)
        if e.get("type") == "msg.new":
            log.debug("msg.new text: %s", e.get("text"))  # message text stays out of production logs
        if voice_agent is not None and stop is not None and e.get("type") == "call.invite":
            from .voice import is_incoming_call

            if is_incoming_call(e):
                t = asyncio.get_running_loop().create_task(answer(e, stop))
                calls.add(t)
                t.add_done_callback(calls.discard)

    async def main() -> None:
        nonlocal voice_agent
        stop = asyncio.Event()
        _signals(stop)
        built: Any = None
        if ai:
            built = await _build_ai(instructions, mcp, tools, model, voice, agents_file, provider)
            voice_agent = built.agent
            voice_agent.instructions = with_personas(voice_agent.instructions, home_dir())
            log.info("answering calls with %d tools", len(voice_agent.tools))
        log.info("hivepal %s serving; relay %s", __version__, s.relay_url)
        try:
            await run_ws(client, s.api_key, store, lambda e: on_event(e, stop), stop=stop)
            await asyncio.gather(*calls, return_exceptions=True)
        finally:
            if built is not None:
                await built.aclose()

    try:
        asyncio.run(main())
    except FatalSyncError as e:
        raise _fail(str(e)) from None
    finally:
        client.close()
        store.close()
    log.info("stopped")


@app.command()
def connect(
    harness: Annotated[str, typer.Argument(help="The agent harness to connect: claude-code, codex, cursor, openclaw or hermes.")],
    url: Annotated[Optional[str], typer.Option(
        "--url", help="Use a remote MCP server instead of the local one, e.g. https://agentring.io/mcp. "
        "The API key comes from HIVEPAL_API_KEY (or this machine's hivepal config).")] = None,
    skill: Annotated[bool, typer.Option("--skill/--no-skill", help="Install the hivepal skill.")] = True,
    consult: Annotated[bool, typer.Option(
        "--consult/--no-consult", help="Add the harness to agents.yaml, so the voice AI can consult it on a call "
        "(local MCP only).")] = True,
    dry_run: Annotated[bool, typer.Option("--dry-run", help="Show what would change; change nothing.")] = False,
    json_out: JsonOpt = False,
) -> None:
    """Connect Claude Code, Codex, Cursor, OpenClaw or Hermes: MCP server, skill and agents.yaml. Safe to run again."""
    from dataclasses import asdict

    from .config import secret
    from .connect import ConnectError, connect as do_connect

    api_key = None
    if url:
        api_key = secret("HIVEPAL_API_KEY")
        if not api_key:
            raise _fail("--url needs the API key: set HIVEPAL_API_KEY (ak_…), e.g. "
                        "`HIVEPAL_API_KEY=ak_... hivepal connect hermes --url …`")
    try:
        steps = do_connect(harness.lower(), url=url, api_key=api_key, skill=skill, consult=consult, dry_run=dry_run)
    except ConnectError as e:
        raise _fail(str(e)) from None
    if json_out:
        _emit_json({"harness": harness.lower(), "dry_run": dry_run, "steps": [asdict(s) for s in steps]})
        return
    for s in steps:
        typer.echo(f"{s.what:<20} {s.status:<12} {s.detail}".rstrip())
    if dry_run:
        return
    typer.echo("\n" + {
        "openclaw": "OpenClaw picks it up on the next turn. Check: openclaw mcp probe hivepal",
        "hermes": "In Hermes, run /reload-mcp or start a new session. Check: hermes mcp test hivepal",
        "claude-code": "Start a new Claude Code session (or /mcp). Check: claude mcp get hivepal",
        "codex": "Start a new Codex session. Check: codex mcp list",
        "cursor": "Restart Cursor (or reload MCP servers in its settings).",
    }.get(harness.lower(), ""))


def main() -> None:
    app()


if __name__ == "__main__":
    main()


@subs_app.callback()
def subs_list(ctx: typer.Context, json_out: JsonOpt = False) -> None:
    """List the subscriptions, soonest renewal first."""
    if ctx.invoked_subcommand:
        return
    s = _settings()
    with _client(s) as c:
        try:
            subs = c.list_subscriptions()
        except RelayError as e:
            raise _fail(str(e)) from None
    if json_out:
        typer.echo(json.dumps({"subscriptions": subs}))
        return
    if not subs:
        typer.echo("No subscriptions yet.")
    now = time.time() * 1000
    for x in subs:
        when = ""
        if x.get("until"):
            days = max(0, -int(-(x["until"] - now) // 86_400_000))  # rounded up, like the app
            when = f"{'ends' if x['status'] == 'ending' else 'renews'} {_fmt_ts(x['until'])[:10]} ({days} d)"
        extra = f"  ! {x['error']}" if x.get("error") else ""
        typer.echo(f"{x['sub_id']:<12} {x['name']:<14} {x.get('plan', ''):<14} {x['status']:<8} {when:<30} {x.get('price', '')}{extra}")


@subs_app.command("set")
def subs_set(
    sub_id: Annotated[str, typer.Argument(help="Short id, e.g. claude.")],
    name: Annotated[str, typer.Option("--name", help="Name shown on the phone.")],
    status: Annotated[str, typer.Option("--status", help="active, ending, ended, unknown or error.")] = "active",
    until: Annotated[Optional[str], typer.Option("--until", help="When it renews or ends: 2026-12-05 or 2026-12-05T10:00.")] = None,
    plan: Annotated[Optional[str], typer.Option("--plan")] = None,
    price: Annotated[Optional[str], typer.Option("--price", help="As the service shows it, e.g. '21,78 € / mes'.")] = None,
    url: Annotated[Optional[str], typer.Option("--url", help="The billing page.")] = None,
    error: Annotated[Optional[str], typer.Option("--error", help="With --status error: why the read failed.")] = None,
) -> None:
    """Record a subscription (relay API key only). Meant for the scraper that reads the billing pages."""
    body: dict[str, Any] = {"name": name, "status": status}
    if until:
        try:
            body["until"] = int(datetime.fromisoformat(until).timestamp() * 1000)
        except ValueError:
            raise _fail("--until must be a date like 2026-12-05") from None
    for k, v in (("plan", plan), ("price", price), ("url", url), ("error", error)):
        if v:
            body[k] = v
    s = _settings()
    with _client(s) as c:
        try:
            r = c.put_subscription(sub_id, body)
        except RelayError as e:
            raise _fail(str(e)) from None
    typer.echo(f"{r['sub_id']}: {r['status']}" + (f", until {_fmt_ts(r['until'])[:10]}" if r.get("until") else ""))


@subs_app.command("rm")
def subs_rm(sub_id: Annotated[str, typer.Argument()]) -> None:
    """Forget a subscription."""
    s = _settings()
    with _client(s) as c:
        try:
            c.delete_subscription(sub_id)
        except RelayError as e:
            raise _fail(str(e)) from None
    typer.echo(f"{sub_id} removed")
