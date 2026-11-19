"""Fresh demo relay + fresh app on the emulator, paired, with the demo agents and history."""
import os, re, subprocess, time, shutil, json
from datetime import datetime, timedelta
import demo
S = demo.S
R = os.path.expanduser("~/Docs/dev/hivepal")
subprocess.run(["docker", "compose", "--project-directory", f"{R}/deploy", "-f", f"{R}/deploy/docker-compose.local.yml",
                "--env-file", f"{R}/deploy/.env.local", "down", "-v"], capture_output=True)
for d in S.glob("demo-*"):
    if d.is_dir() and d.name not in ("demo-bin", "demo-uv"):
        shutil.rmtree(d)
(S / "demo-home").mkdir()
env = {**os.environ, "HIVEPAL_HOME": str(S / "demo-home"), "UV_TOOL_DIR": str(S / "demo-uv"), "UV_TOOL_BIN_DIR": str(S / "demo-bin")}
# Voice notes need a Gemini key on the demo relay: a file outside the repo (DEMO_GEMINI_KEY_FILE), never committed.
if os.environ.get("DEMO_GEMINI_KEY_FILE"):
    env["HIVEPAL_GEMINI_API_KEY"] = open(os.environ["DEMO_GEMINI_KEY_FILE"]).read().strip()
r = subprocess.run([f"{R}/install.sh", "--local", "--yes"], env=env, capture_output=True, text=True)
print("install:", "ok" if "The relay answers" in r.stdout else r.stdout[-500:] + r.stderr[-500:])
demo.setup_agents()
# No on-screen keyboard: `input text` types anyway, and the keyboard's suggestion strip would show
# whatever the emulator synced from the computer's clipboard.
for ime in demo.adb("shell", "ime", "list", "-s").split():
    demo.adb("shell", "ime", "disable", ime)
demo.adb("shell", "pm", "clear", "dev.agentring.app")
for perm in ("POST_NOTIFICATIONS", "RECORD_AUDIO"):
    demo.adb("shell", "pm", "grant", "dev.agentring.app", f"android.permission.{perm}")
demo.adb("shell", "am", "start", "-n", "dev.agentring.app/.ui.MainActivity"); time.sleep(4)
p = subprocess.Popen([demo.BIN, "pair"], stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True,
                     env={**os.environ, "HIVEPAL_HOME": str(S / "demo-home"), "COLUMNS": "400"})
code = None
while code is None:
    m = re.search(r'(\{"v":\s*1.*?\})', p.stdout.readline())
    if m: code = m.group(1)
demo.tap("Get started"); time.sleep(2)
demo.tap("Paste the code", exact=False); time.sleep(1.5)
f = next(n for n in demo.nodes() if n["cls"].endswith("EditText"))
demo.adb("shell", "input", "tap", str(f["x"]), str(f["y"])); time.sleep(0.5)
demo.adb("shell", "input text '" + code + "'"); time.sleep(1)
demo.adb("shell", "input", "keyevent", "4"); time.sleep(1)
demo.tap("Pair"); time.sleep(6)
demo.adb("shell", "input", "swipe", "540", "1900", "540", "500", "300"); time.sleep(1)
lab = [(n["text"] or n["desc"]) for n in demo.nodes()]
demo.tap(next(l for l in ("Continue", "Skip for now", "Next") if l in lab)); time.sleep(2.5)
demo.adb("shell", "input", "swipe", "540", "1900", "540", "500", "300"); time.sleep(1)
demo.tap("Open Hivepal"); time.sleep(2)
p.terminate()
demo.hp("penny", "msg", "May so far: €1,240 of €1,600. Groceries are under budget 👍")
demo.hp("hearth", "msg", "Boiler service done. Next one in November.")
d = lambda n: (datetime.now() + timedelta(days=n)).strftime("%Y-%m-%d")
demo.hp("main", "subs", "set", "claude", "--name", "Claude", "--plan", "Max 5x", "--until", d(12), "--price", "€108.90 / month")
demo.hp("main", "subs", "set", "openrouter", "--name", "OpenRouter", "--plan", "Credits", "--until", d(3), "--price", "$20.00")
demo.hp("main", "subs", "set", "hetzner", "--name", "Hetzner", "--plan", "CX22", "--until", d(24), "--price", "€4.59 / month")
time.sleep(2)
print([ (n['text'] or n['desc'])[:20] for n in demo.nodes() if n['text'] or n['desc']][:8])
