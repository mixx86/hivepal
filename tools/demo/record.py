"""Plays the demo on the emulator while it records the screen; logs every terminal step with its time."""
import json
import subprocess
import time

import demo

events = []
T0 = None


def now() -> float:
    return time.time() - T0


def term(who: str, *args: str, typing: float = 2.2, show: str | None = None) -> str:
    """A command typed in the terminal (as `who`), then run for real; its output goes in the video."""
    shown = show or "hivepal " + " ".join(a if " " not in a and "'" not in a else json.dumps(a, ensure_ascii=False) for a in args)
    events.append({"t": now(), "type": "cmd", "who": who, "text": shown, "typing": typing})
    time.sleep(typing + 0.3)
    out = demo.hp(who, *args).rstrip()
    events.append({"t": now(), "type": "out", "text": out})
    return out


def phone_tap(label: str, **kw) -> None:
    demo.tap(label, **kw)


rec = subprocess.Popen(["adb", "-s", demo.EMU, "shell", "screenrecord", "--size", "1080x2400", "--bit-rate", "20000000",
                        "--time-limit", "175", "/sdcard/demo.mp4"])
time.sleep(1.0)
T0 = time.time()
time.sleep(2.5)

# 1. Nubi writes the plan, with quick replies
term("nubi", "msg", "Plan for today: 09:00 class · 13:00 lunch · 18:00 gym", "-o", "Let's go", "-o", "Move the gym")
time.sleep(2.8)
# 2. Open Nubi's chat and answer with one tap
phone_tap("Nubi")
time.sleep(2.0)
if not any(n["text"] == "Let's go" for n in demo.nodes()):
    phone_tap("Nubi")
    time.sleep(1.5)
phone_tap("Let's go")
time.sleep(2.5)
# 3. Nubi reads the answer
term("nubi", "replies", typing=1.2)
time.sleep(2.5)
# 4. A group: Coach and Ink coordinate
demo.adb("shell", "input", "keyevent", "4")
time.sleep(1.2)
term("coach", "msg", "--group", "hive", "Gym at 18:00. I'll check in at 17:45 🏋️")
term("ink", "msg", "--group", "hive", "Then 20 minutes of chapter 3 after dinner?", "-o", "Deal", "-o", "Tomorrow")
time.sleep(2.5)  # let the list settle (rows animate when they reorder)
for _ in range(3):
    phone_tap("Hive")
    time.sleep(1.5)
    if any(n["text"] == "Then 20 minutes of chapter 3 after dinner?" for n in demo.nodes()):
        break
time.sleep(2.5)
# 5. A reminder the relay sends, insisting until answered (then it rings)
term("coach", "remind", "--at", "17:45", "Gym in 15 minutes", "-o", "On my way", "--insist", "10m", "--call")
time.sleep(2.0)
demo.adb("shell", "input", "keyevent", "4")
time.sleep(1.2)
# 6. Subscriptions
phone_tap("Open menu")
time.sleep(1.4)
phone_tap("Subscriptions")
time.sleep(3.5)
term("main", "subs", typing=1.0)
time.sleep(4.0)

# 7. An agent calls: it rings like a real call; open it, answer, hang up
import os
import re
import threading
demo.adb("shell", "input", "keyevent", "4")
time.sleep(1.2)
reason = "You skipped the gym twice this week. Two minutes?"
events.append({"t": now(), "type": "cmd", "who": "coach", "text": f'hivepal call "{reason}"', "typing": 2.0})
time.sleep(2.3)
call = subprocess.Popen([demo.BIN, "call", reason], stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True,
                        env={**os.environ, "HIVEPAL_HOME": str(demo.S / "demo-coach")})


def follow() -> None:
    # The CLI's own progress lines, as they happen (minus the media library's logging).
    for line in call.stdout:
        line = line.rstrip()
        if re.search(r"ringing on|answered on|Call ended|Call missed|declined", line):
            line = re.sub(r"dev_[0-9A-Z]+", "your phone", line).strip()
            events.append({"t": now(), "type": "out", "text": line})


th = threading.Thread(target=follow)
th.start()
for _ in range(30):
    if "Decline" in demo.adb("exec-out", "uiautomator", "dump", "--windows", "/dev/tty"):
        break
    time.sleep(0.4)
time.sleep(2.5)
demo.adb("shell", "input", "tap", "540", "300")  # open the incoming call screen from the notification
time.sleep(3.0)
phone_tap("Answer")
time.sleep(5.5)
phone_tap("Hang up")
call.wait(timeout=30)
th.join(timeout=5)
time.sleep(3.0)

events.append({"t": now(), "type": "end"})
rec.terminate()
time.sleep(2)
subprocess.run(["adb", "-s", demo.EMU, "shell", "pkill", "-INT", "screenrecord"])
time.sleep(3)
subprocess.run(["adb", "-s", demo.EMU, "pull", "/sdcard/demo.mp4", str(demo.S / "phone.mp4")], capture_output=True)
events.sort(key=lambda e: (e["type"] == "end", e["t"]))
(demo.S / "events.json").write_text(json.dumps({"lead": 1.0, "events": events}, ensure_ascii=False, indent=1))
print("events:", len(events), "duration:", round(events[-1]["t"], 1))
