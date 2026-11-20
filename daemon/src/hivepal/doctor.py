"""`hivepal doctor`: checks every piece a working setup needs and says how to fix what isn't right.

Each check is a `Check(status, name, detail, fix)`: ok, warn (works, but something is missing or
odd) or fail (it won't work). Checks only read: nothing is sent to the phone, and no WebSocket is
opened (the relay keeps one per agent, so a test connection would push the running daemon off).
"""

from __future__ import annotations

import os
import time
from dataclasses import asdict, dataclass
from email.utils import parsedate_to_datetime
from pathlib import Path
from typing import Any

import httpx

from . import __version__
from .config import ConfigError, Settings, load_settings
from .relay_client import RelayClient, RelayError

OK, WARN, FAIL = "ok", "warn", "fail"
DAY_MS = 86_400_000


@dataclass
class Check:
    status: str
    name: str
    detail: str
    fix: str = ""


def _ago(ms: int, now_ms: int) -> str:
    s = max(0, (now_ms - ms) // 1000)
    for unit, n in (("d", 86_400), ("h", 3600), ("min", 60)):
        if s >= n:
            return f"{s // n} {unit} ago"
    return "just now"


def check_relay(url: str, transport: httpx.BaseTransport | None = None) -> tuple[list[Check], bool]:
    """Reachability, HTTPS and clock. Returns the checks and whether the relay answered."""
    out: list[Check] = []
    t0 = time.monotonic()
    try:
        with httpx.Client(timeout=10, transport=transport) as c:
            r = c.get(f"{url.rstrip('/')}/healthz")
    except httpx.ConnectError as e:
        msg = str(e)
        if "CERTIFICATE" in msg.upper() or "SSL" in msg.upper():
            return [Check(FAIL, "Relay", f"{url}: the HTTPS certificate isn't valid ({msg[:120]})",
                          "On a new server Caddy may still be getting it (a few minutes). Check that the domain "
                          "points at the server and ports 80 and 443 are open: docker compose logs caddy")], False
        return [Check(FAIL, "Relay", f"{url} doesn't answer ({msg[:120]})",
                      "Is it running? docker compose ps (in deploy/). From another machine: is the address "
                      "reachable (firewall, Tailscale, the IP in HIVEPAL_HOST_IP)?")], False
    except httpx.HTTPError as e:
        return [Check(FAIL, "Relay", f"{url}: {e.__class__.__name__}: {str(e)[:120]}",
                      "Check the address in ~/.agentring/config.yaml (hivepal init --relay …)")], False
    ms = int((time.monotonic() - t0) * 1000)
    if r.status_code != 200:
        return [Check(FAIL, "Relay", f"{url}/healthz answered {r.status_code}",
                      "Something else answers at that address: is the reverse proxy sending /v1 and /healthz "
                      "to the relay? (docs/guide/server.md)")], False
    out.append(Check(OK, "Relay", f"{url} answers in {ms} ms"))
    date = r.headers.get("date")
    if date:
        skew = abs(parsedate_to_datetime(date).timestamp() - time.time())
        if skew > 60:
            out.append(Check(WARN, "Clock", f"this computer and the relay differ by {int(skew)} s",
                             "Reminders and 'last seen' use the clock: turn on time sync (timedatectl set-ntp true)"))
    return out, True


def check_status(c: RelayClient, now_ms: int) -> list[Check]:
    out: list[Check] = []
    try:
        st = c.status()
    except RelayError as e:
        if e.status == 401:
            return [Check(FAIL, "Key", "the relay doesn't accept this API key",
                          "It was rotated or the agent removed: get a new one (hivepal bots key <id>, with the "
                          "relay's key) and run hivepal init --relay … --api-key ak_…")]
        return [Check(FAIL, "Key", str(e))]
    me = (st.get("self") or {}).get("agent_id", "main")
    out.append(Check(OK, "Key", f"accepted: agent {me} ({st.get('agent_name', me)})"))

    version = st.get("version", "?")
    if version != __version__ and not __version__.startswith("0.0.0"):
        out.append(Check(WARN, "Versions", f"relay {version}, this CLI {__version__}",
                         "Update the older one (the one-line install or ./install.sh updates both)"))
    else:
        out.append(Check(OK, "Versions", f"relay and CLI {version}"))

    # Push is per phone: Firebase when the relay has it, or UnifiedPush (a distributor app) without it.
    if st.get("push_provider", "none") == "none":
        out.append(Check(OK, "Push", "no Firebase on the relay: phones are woken through UnifiedPush (each phone below)"))
    else:
        out.append(Check(OK, "Push", f"{st['push_provider']} configured, and UnifiedPush for phones that choose it"))

    features = st.get("features")
    if isinstance(features, dict):
        if features.get("calls"):
            out.append(Check(OK, "Calls", "LiveKit configured. Audio needs UDP 50000-50100 and TCP 7881 open to the phone",
                             "Try one: hivepal call \"test\""))
        else:
            out.append(Check(WARN, "Calls", "LiveKit isn't configured on the relay: no calls",
                             "Use the docker compose files from deploy/ (they include LiveKit)"))
        if not features.get("voice"):
            out.append(Check(WARN, "Voice", "no Gemini key on the relay: no voice notes or transcripts",
                             "Add HIVEPAL_GEMINI_API_KEY to deploy/.env (https://aistudio.google.com/apikey) and restart"))
        else:
            out.append(Check(OK, "Voice", "voice notes and transcripts available"))
        if features.get("wake_email"):
            out.append(Check(OK, "Wake-up emails", "SMTP configured"))

    if me != "main":
        return out  # devices are the relay key's business
    try:
        devices = c.list_devices()
    except RelayError as e:
        out.append(Check(WARN, "Phones", f"can't list them: {e}"))
        return out
    if not devices:
        out.append(Check(FAIL, "Phones", "no phone paired", "hivepal pair, then scan the QR with the phone's camera"))
        return out
    for d in devices:
        seen = d.get("last_seen_at")
        via = d.get("push_via") or ("fcm" if d.get("has_push_token") else "none")
        detail = f"{d.get('name', d['device_id'])}: last seen {_ago(seen, now_ms) if seen else 'never'}"
        if via == "none":
            fix = ("Open the app once with notifications allowed; Status in the app says why if it stays"
                   if st.get("push_provider", "none") != "none" else
                   "In the app: Settings → Notifications → Use UnifiedPush (with ntfy installed), or set up "
                   "Firebase (docs/firebase.md)")
            out.append(Check(WARN, "Phone", detail + ", no push channel: messages only while the app is open, "
                             "and calls don't ring it when locked", fix))
        elif seen and now_ms - seen > 7 * DAY_MS:
            out.append(Check(WARN, "Phone", detail, "Open the app: if it can't connect, Status in the app says why"))
        else:
            out.append(Check(OK, "Phone", f"{detail}, woken through {'UnifiedPush' if via == 'unifiedpush' else 'Firebase'}"))
    return out


def check_local(s: Settings) -> list[Check]:
    """What this machine needs for `serve --ai` (the voice AI answering calls)."""
    key = os.environ.get("GEMINI_API_KEY") or _dotenv_has(s.home / ".env", "GEMINI_API_KEY")
    if key:
        return [Check(OK, "Voice AI here", "a Gemini key for hivepal serve --ai")]
    return [Check(WARN, "Voice AI here", "no GEMINI_API_KEY for hivepal serve --ai on this machine",
                  f"Only needed where the voice AI runs: add GEMINI_API_KEY=… to {s.home / '.env'}")]


def _dotenv_has(path: Path, name: str) -> bool:
    try:
        return any(line.split("=", 1)[0].strip() == name and line.split("=", 1)[1].strip()
                   for line in path.read_text().splitlines() if "=" in line)
    except OSError:
        return False


def run(now_ms: int | None = None) -> list[Check]:
    now_ms = now_ms if now_ms is not None else int(time.time() * 1000)
    try:
        s = load_settings()
    except (ConfigError, ValueError) as e:
        return [Check(FAIL, "Config", str(e), "hivepal init --relay https://… --api-key ak_…")]
    out = [Check(OK, "Config", f"{s.home} → {s.relay_url}")]
    relay, up = check_relay(s.relay_url)
    out += relay
    if not up:
        return out
    with RelayClient(s.relay_url, s.api_key) as c:
        out += check_status(c, now_ms)
    out += check_local(s)
    return out


def as_json(checks: list[Check]) -> dict[str, Any]:
    return {"ok": all(c.status != FAIL for c in checks), "checks": [asdict(c) for c in checks]}
