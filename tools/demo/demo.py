"""Demo helpers: the local demo relay, demo agents (fictional) and the emulator."""
import json
import os
import subprocess
import time
import xml.etree.ElementTree as ET
from pathlib import Path

S = Path(__file__).parent
BIN = str(S / "demo-bin/hivepal")
EMU = "emulator-5554"


def hp(who: str, *args: str, check: bool = True) -> str:
    """Runs the hivepal CLI as `who` ('main' or a demo agent id)."""
    home = S / ("demo-home" if who == "main" else f"demo-{who}")
    p = subprocess.run([BIN, *args], capture_output=True, text=True, env={**os.environ, "HIVEPAL_HOME": str(home)})
    if check and p.returncode != 0:
        raise RuntimeError(p.stderr or p.stdout)
    return p.stdout


def relay_url() -> str:
    for line in (S / "demo-home/config.yaml").read_text().splitlines():
        if line.startswith("relay_url"):
            return line.split(":", 1)[1].strip()
    raise RuntimeError("no relay_url")


def setup_agents() -> None:
    existing = {a["agent_id"] for a in json.loads(hp("main", "bots", "--json"))["agents"]}
    hp("main", "bots", "rename", "main", "Switchboard")
    for aid, name in (("nubi", "Nubi"), ("coach", "Coach"), ("ink", "Ink")):
        if aid not in existing:
            key = json.loads(hp("main", "bots", "add", aid, name, "--json"))["api_key"]
            (S / f"demo-{aid}").mkdir(exist_ok=True)
            hp(aid, "init", "--relay", relay_url(), "--api-key", key)
    groups = {g["group_id"] for g in json.loads(hp("main", "groups", "--json"))["groups"]}
    if "hive" not in groups:
        hp("main", "groups", "add", "hive", "Hive", "--members", "nubi,coach,ink")


def adb(*args: str) -> str:
    return subprocess.run(["adb", "-s", EMU, *args], capture_output=True, text=True).stdout


def nodes() -> list[dict]:
    adb("shell", "uiautomator", "dump", "/sdcard/ui.xml")
    xml = adb("shell", "cat", "/sdcard/ui.xml")
    out = []
    for n in ET.fromstring(xml[xml.index("<"):]).iter("node"):
        b = [int(x) for x in n.get("bounds").replace("][", ",").strip("[]").split(",")]
        out.append({"text": n.get("text") or "", "desc": n.get("content-desc") or "", "cls": n.get("class") or "",
                    "x": (b[0] + b[2]) // 2, "y": (b[1] + b[3]) // 2})
    return out


def tap(label: str, timeout: float = 10, exact: bool = True) -> None:
    end = time.time() + timeout
    while time.time() < end:
        for n in nodes():
            v = n["text"] or n["desc"]
            if (v == label) if exact else (label in v):
                adb("shell", "input", "tap", str(n["x"]), str(n["y"]))
                return
        time.sleep(0.5)
    raise RuntimeError(f"no {label!r} on screen")
