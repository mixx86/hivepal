"""Plays the demo on the emulator while it records the screen; logs every terminal step with its time.

The story: a day of small errands. Atlas (planner) moves a dentist appointment and asks which time,
tells Hearth (the house) so the plumber can come; Hearth sends the plumber's quote to the Home group;
Penny (money) fits it in the budget and sets the rent reminder; Atlas leaves a voice note; you ask
Hearth something and it lands in Hearth's mailbox folder as a Markdown file; subscriptions; and at the
end Hearth calls because the plumber can't come.
"""
import json
import os
import re
import subprocess
import threading
import time

import demo

events = []
T0 = None
MAILBOX = demo.S / "notes" / "mailbox"


def now() -> float:
    return time.time() - T0


def tidy(out: str) -> str:
    """Local paths as an agent would see them."""
    return out.replace(str(demo.S / "notes"), "~/notes").replace(str(demo.S) + "/", "")


def term(who: str, *args: str, typing: float = 2.2, show: str | None = None) -> str:
    """A command typed in the terminal (as `who`), then run for real; its output goes in the video."""
    shown = show or "hivepal " + " ".join(a if " " not in a and "'" not in a else json.dumps(a, ensure_ascii=False) for a in args)
    events.append({"t": now(), "type": "cmd", "who": who, "text": shown, "typing": typing})
    time.sleep(typing + 0.3)
    out = tidy(demo.hp(who, *args).rstrip())
    events.append({"t": now(), "type": "out", "text": out})
    return out


def shell(who: str, shown: str, out: str, typing: float = 1.6) -> None:
    """A plain shell command (not hivepal) whose output is already known."""
    events.append({"t": now(), "type": "cmd", "who": who, "text": shown, "typing": typing})
    time.sleep(typing + 0.3)
    events.append({"t": now(), "type": "out", "text": out})


def back() -> None:
    demo.adb("shell", "input", "keyevent", "4")
    time.sleep(1.2)


def open_chat(name: str, expect: str) -> None:
    """Taps a chat row until the conversation shows `expect` (list rows animate when they reorder)."""
    for _ in range(4):
        demo.tap(name)
        time.sleep(1.6)
        if any(expect in (n["text"] or n["desc"]) for n in demo.nodes()):
            return
    raise RuntimeError(f"{name}: {expect!r} not on screen")


def no_keyboard() -> None:
    """No on-screen keyboard: `input text` types anyway, and its suggestion strip would show whatever the
    emulator synced from the computer's clipboard. Android re-enables it after package changes, so
    this runs right before typing."""
    for ime in demo.adb("shell", "ime", "list", "-s").split():
        demo.adb("shell", "ime", "disable", ime)


def to_list() -> None:
    """Back to the chat list, wherever the app is (and back into the app if a Back left it)."""
    for _ in range(4):
        if any((n["text"] or n["desc"]) == "Open menu" for n in demo.nodes()):
            return
        if "dev.agentring.app" not in demo.adb("shell", "dumpsys", "activity", "activities").split("topResumedActivity", 1)[-1][:300]:
            demo.adb("shell", "am", "start", "-n", "dev.agentring.app/.ui.MainActivity")
            time.sleep(2.0)
            continue
        back()
    raise RuntimeError("can't get back to the chat list")


def menu(item: str) -> None:
    demo.tap("Open menu")
    time.sleep(1.3)
    demo.tap(item)
    time.sleep(2.5)


rec = subprocess.Popen(["adb", "-s", demo.EMU, "shell", "screenrecord", "--size", "1080x2400", "--bit-rate", "20000000",
                        "--time-limit", "180", "/sdcard/demo.mp4"])
time.sleep(1.0)
T0 = time.time()
time.sleep(2.5)

# 1. Atlas asks a question with two quick replies; one tap answers it
term("atlas", "msg", "The dentist moved your check-up. Tomorrow at 10:00 or 16:00?", "-o", "10:00", "-o", "16:00")
time.sleep(1.5)
open_chat("Atlas", "16:00")
demo.tap("16:00")
time.sleep(2.2)
term("atlas", "replies", typing=1.0)
time.sleep(1.5)

# 2. Agent to agent: Atlas tells Hearth, without asking you again
term("atlas", "msg", "--to", "hearth", "Dentist at 16:00 tomorrow, so the plumber can come at 10:00.")
back()
time.sleep(1.5)

# 3. The Home group: Hearth sends the plumber's quote, Penny fits it in the budget
to_list()
term("hearth", "msg", "--group", "home", "Plumber booked for tomorrow at 10:00. Here's the quote:", "-f", "quote.jpg",
     show='hivepal msg --group home "Plumber booked for tomorrow at 10:00. Here\'s the quote:" -f quote.jpg')
time.sleep(1.5)
open_chat("Home", "Here's the quote")
time.sleep(1.5)
term("penny", "msg", "--group", "home", "€85 fits in May's budget. Rent is due on Friday: shall I remind you?",
     "-o", "Yes, Friday 9:00", "-o", "I'll do it now")
time.sleep(2.0)
demo.tap("Yes, Friday 9:00")
time.sleep(1.5)

# 4. A reminder the relay sends on time, insisting until answered, and ringing in the end
from datetime import date, timedelta
friday = date.today() + timedelta(days=(4 - date.today().weekday()) % 7 or 7)
term("penny", "remind", "--at", f"{friday}T09:00", "Rent: transfer €750 to the landlord", "-o", "Done", "--insist", "10m",
     "--call")
time.sleep(1.0)
to_list()
menu("Reminders")
time.sleep(1.5)

# 5. A voice note (spoken by the relay, transcribed for the other agents)
term("atlas", "msg", "--voice", "Morning! Plumber at ten, dentist at four, and Penny has the rent covered for Friday.")
time.sleep(1.0)
menu("Chats")
open_chat("Atlas", "Morning!")
time.sleep(1.5)

# 6. You write to Hearth; Hearth reads its mailbox folder, one Markdown file per message
to_list()
open_chat("Hearth", "Boiler service done")
no_keyboard()
field = next(n for n in demo.nodes() if n["cls"].endswith("EditText"))
demo.adb("shell", "input", "tap", str(field["x"]), str(field["y"]))
time.sleep(0.6)
demo.adb("shell", "input", "text", "Can%sthe%splumber%salso%slook%sat%sthe%sbathroom%stap?")
time.sleep(0.8)
demo.tap("Send")
time.sleep(2.0)
out = term("hearth", "mailbox", str(MAILBOX), show="hivepal mailbox ~/notes/mailbox", typing=1.6)
newest = sorted((MAILBOX / "hearth").glob("*.md"))[-1]
body = "\n".join(l for l in newest.read_text().splitlines() if not l.startswith(("from_name:", "conv:")))
shell("hearth", f"cat ~/notes/mailbox/hearth/{newest.name[:14]}…", body)
time.sleep(2.0)

# 7. Subscriptions: what you pay to run your agents, days left on each
to_list()
menu("Subscriptions")
term("main", "subs", typing=1.0)
time.sleep(2.5)

# 8. Hearth calls: it rings like a real call; answer, listen, hang up
menu("Chats")
reason = "The plumber can't come tomorrow. Got a minute?"
events.append({"t": now(), "type": "cmd", "who": "hearth", "text": f'hivepal call "{reason}"', "typing": 2.0})
time.sleep(2.3)
call = subprocess.Popen([demo.BIN, "call", reason], stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True,
                        env={**os.environ, "HIVEPAL_HOME": str(demo.S / "demo-hearth")})


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
demo.tap("Answer")
time.sleep(11.0)  # Hearth's line (hearth.wav, about 10 s) plays over this in the video
demo.tap("Hang up")
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
