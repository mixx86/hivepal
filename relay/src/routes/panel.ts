/**
 * Hivepal: GET /admin, a web panel for the relay's owner.
 *
 * A static page: it holds no data and adds no API. You paste the relay's API key (kept in this tab's
 * sessionStorage only) and the page uses the same REST API as the CLI with it: the status of each
 * piece, the paired phones and a pairing QR, the agents with ready-to-copy setup for each harness,
 * how agents hear from the user and remember (email account, mailboxes in git, external repository with
 * the relay's deploy key, memory), per agent its wake-up email and mailbox, and a test message and a
 * test call. Everything is drawn with textContent; the CSP allows only this
 * page's own script (by hash) and data: images (the QR).
 */
import { createHash } from "node:crypto";
import { readFileSync } from "node:fs";
import { createRequire } from "node:module";
import type { FastifyInstance } from "fastify";

const require = createRequire(import.meta.url);
/** qrcode-generator (MIT, Kazuhiko Arase): sets the global `qrcode`. */
const QR_LIB = readFileSync(require.resolve("qrcode-generator/qrcode.js"), "utf8");

const APP = String.raw`
const $ = (id) => document.getElementById(id);
const el = (tag, props = {}, ...kids) => {
  const e = document.createElement(tag);
  for (const [k, v] of Object.entries(props)) if (k === "class") e.className = v; else e[k] = v;
  for (const k of kids) e.append(k);
  return e;
};
const store = { get: () => { try { return sessionStorage.getItem("hivepal_key"); } catch (e) { return null; } },
                set: (v) => { try { v ? sessionStorage.setItem("hivepal_key", v) : sessionStorage.removeItem("hivepal_key"); } catch (e) {} } };
let key = store.get();

async function api(method, path, body) {
  const r = await fetch(path, { method, headers: { authorization: "Bearer " + key, ...(body ? { "content-type": "application/json" } : {}) },
                                body: body ? JSON.stringify(body) : undefined });
  const data = r.status === 204 ? null : await r.json().catch(() => null);
  if (!r.ok) throw new Error((data && data.error && data.error.message) || ("HTTP " + r.status));
  return data;
}

function row(status, title, detail) {
  return el("div", { class: "row " + status }, el("span", { class: "dot" }), el("div", {}, el("b", { textContent: title }), el("small", { textContent: detail })));
}
function ago(ms) {
  if (!ms) return "never";
  const s = Math.max(0, (Date.now() - ms) / 1000);
  return s < 120 ? "just now" : s < 7200 ? Math.round(s / 60) + " min ago" : s < 172800 ? Math.round(s / 3600) + " h ago" : Math.round(s / 86400) + " days ago";
}
function snippet(text) {
  const pre = el("pre", { textContent: text });
  const b = el("button", { class: "ghost small", type: "button", textContent: "Copy" });
  b.onclick = () => navigator.clipboard.writeText(text).then(() => { b.textContent = "Copied"; });
  return el("div", { class: "snip" }, pre, b);
}

async function load() {
  $("login").hidden = true; $("panel").hidden = false;
  try {
    const st = await api("GET", "/v1/status");
    if (st.self && st.self.agent_id !== "main") throw new Error("this is an agent's key: use the relay's own key (deploy/.env, HIVEPAL_API_KEY)");
    const f = st.features || {};
    $("status").replaceChildren(
      row("ok", "Relay " + st.version, location.origin),
      row("ok", "Push", st.push_provider === "none" ? "No Firebase: phones are woken through UnifiedPush (the ntfy app), set up by the app" : "Firebase configured; phones may use UnifiedPush (ntfy) instead"),
      row(f.calls ? "ok" : "warn", "Calls", f.calls ? "LiveKit configured (UDP 50000-50100 open to the phone)" : "LiveKit isn't configured"),
      row(f.voice ? "ok" : "warn", "Voice", f.voice ? "Voice notes and transcripts" : "No Gemini key: no voice notes or transcripts"),
      row(f.wake_email ? "ok" : "off", "Wake-up emails", f.wake_email ? "Email account set: give agents an address below" : "Optional: set an email account below to email agents that run on a trigger"));
    wakeOn = !!f.wake_email;
    delivery = await api("GET", "/v1/settings/delivery");
    drawDelivery();
    const devices = await api("GET", "/v1/devices");
    $("devices").replaceChildren(...(devices.devices || devices).map((d) =>
      row(d.push_via === "none" ? "warn" : "ok", d.name, "seen " + ago(d.last_seen_at) + (d.app_version ? " · app " + d.app_version : "") +
          (d.push_via === "unifiedpush" ? " · UnifiedPush" : d.push_via === "fcm" ? " · Firebase" : d.push_via === "none" ? " · no push yet: open the app, Settings → Notifications" : ""))));
    if (!$("devices").children.length) $("devices").append(row("fail", "No phone paired", "Pair one below"));
    const agents = await api("GET", "/v1/agents");
    $("agents").replaceChildren(...(agents.agents || agents).map((a) => {
      const r = row(a.last_seen_at && Date.now() - a.last_seen_at < 86400000 ? "ok" : "warn", a.name + " (" + a.agent_id + ")",
          "last seen " + ago(a.last_seen_at) + (a.wakes ? " · wake-up email on" : ""));
      if (wakeOn) r.lastChild.append(wakeForm(a));
      r.lastChild.append(mailboxForm(a));
      return r;
    }));
  } catch (e) {
    if (/401|key|Unauthorized/i.test(String(e.message))) { store.set(null); key = null; $("panel").hidden = true; $("login").hidden = false; }
    $("error").textContent = e.message;
  }
}

let wakeOn = false;
let delivery = null;

/** The relay-wide card: email account, mailboxes, external repository, memory. */
function drawDelivery() {
  const d = delivery, e = d.email || {}, r = d.mailbox_repo || {}, m = d.memory || {};
  const out = el("small");
  const say = (t) => { out.textContent = t; };
  const act = async (fn, ok) => { say("…"); try { delivery = await fn(); drawDelivery(); load(); } catch (err) { say(err.message); return; } };

  // Email account.
  const addr = el("input", { type: "email", placeholder: "account to send from (a spare Gmail works)", autocomplete: "off", value: e.address || "" });
  const pass = el("input", { type: "password", placeholder: "its password (Gmail: an app password)", autocomplete: "new-password" });
  const server = el("input", { placeholder: "server host:port (only if not Gmail, iCloud, Yahoo, Fastmail, GMX, Zoho)" });
  const emailForm = el("form", { class: "wake" }, addr, pass, server, el("button", { class: "ghost small", textContent: "Save" }));
  emailForm.onsubmit = (ev) => { ev.preventDefault(); act(() => api("PUT", "/v1/settings/email", { address: addr.value.trim(), password: pass.value, ...(server.value.trim() ? { server: server.value.trim() } : {}) })); };
  const emailOff = el("button", { class: "ghost small", type: "button", textContent: "Turn off", hidden: !e.configured });
  emailOff.onclick = () => act(() => api("PUT", "/v1/settings/email", { address: null }));

  // Mailboxes and memory.
  const all = el("input", { type: "checkbox", checked: !!(d.mailboxes && d.mailboxes.all_agents) });
  all.onchange = () => act(() => api("PUT", "/v1/settings/mailboxes", { all_agents: all.checked }));
  const mem = el("select", {}, ...[["off", "Off: each run starts from the bot's own instructions"], ["markdown", "Markdown: each day's conversation in its mailbox"], ["sqlite", "SQLite: the bot searches it (or downloads it)"], ["both", "Both"]]
    .map(([v, t]) => el("option", { value: v, textContent: t })));
  mem.value = m.markdown && m.sqlite ? "both" : m.markdown ? "markdown" : m.sqlite ? "sqlite" : "off";
  mem.onchange = () => act(() => api("PUT", "/v1/settings/memory", { markdown: mem.value === "markdown" || mem.value === "both", sqlite: mem.value === "sqlite" || mem.value === "both" }));

  // External repository.
  const url = el("input", { placeholder: "git@github.com:you/notes.git (empty: mailboxes stay in the relay)", value: r.url || "" });
  const branch = el("input", { placeholder: "branch (optional)", value: r.branch || "" });
  const repoForm = el("form", { class: "wake" }, url, branch, el("button", { class: "ghost small", textContent: "Save" }));
  repoForm.onsubmit = (ev) => { ev.preventDefault(); act(() => api("PUT", "/v1/settings/mailbox-repo", { url: url.value.trim() || null, ...(branch.value.trim() ? { branch: branch.value.trim() } : {}) })); };
  const check = el("button", { class: "ghost small", type: "button", textContent: "Check access", hidden: !r.url });
  check.onclick = async () => { say("Cloning…"); try { await api("POST", "/v1/settings/mailbox-repo/test"); say("The relay can clone and push to it."); } catch (err) { say(err.message); } };
  const repoState = r.url ? (r.last_error ? "Last error: " + r.last_error : r.last_push_at ? "Last push " + ago(r.last_push_at) : "Nothing pushed yet") : "";

  $("delivery").replaceChildren(
    el("h4", { textContent: "Email account for wake-up emails" }),
    el("small", { textContent: e.configured ? "Sending from " + e.address + " via " + e.server + (e.from_env ? " (from deploy/.env)" : "") + ". The relay logs in before saving." : "Off. The relay logs in before saving, so a wrong password shows up here." }),
    emailForm, emailOff,
    el("h4", { textContent: "Mailboxes (Markdown files in git the agents read)" }),
    el("label", {}, all, document.createTextNode(" Every agent has a mailbox (or turn it on per agent below)")),
    el("small", { textContent: "Each agent's mailbox is a git repository in the relay; optionally also pushed to an external repository (mailbox/<agent>/):" }),
    repoForm,
    ...(r.url ? [el("small", { textContent: repoState }), el("p", { textContent: "Add the relay's deploy key to the repository, with write access:" }), snippet(r.public_key || "?"), check] : []),
    el("h4", { textContent: "Memory for bots" }),
    mem,
    out);
}

/** An agent's mailbox: default / on / off, and how it clones it. */
function mailboxForm(a) {
  const sel = el("select", {}, el("option", { value: "default", textContent: "Mailbox: default" }), el("option", { value: "on", textContent: "Mailbox: on" }), el("option", { value: "off", textContent: "Mailbox: off" }));
  sel.value = "default";
  const out = el("div");
  const draw = (on) => out.replaceChildren(...(on ? [el("small", { textContent: "Clone it with the agent's own key:" }), snippet("git clone https://x:<" + a.agent_id + "'s key>@" + location.host + "/v1/agents/" + a.agent_id + "/mailbox.git")] : []));
  sel.onchange = async () => {
    try { const r = await api("PUT", "/v1/agents/" + a.agent_id + "/mailbox", { enabled: sel.value === "default" ? null : sel.value === "on" }); draw(!!r.mailbox); }
    catch (e) { out.replaceChildren(el("small", { textContent: e.message })); }
  };
  draw(!!a.mailbox);
  return el("div", { class: "wake" }, sel, out);
}
/** An agent's wake-up email: set or change it (the relay never shows it back), turn it off, send a test. */
function wakeForm(a) {
  const input = el("input", { type: "email", placeholder: a.wakes ? "on · new address to change it" : "address that starts it (optional)", autocomplete: "off" });
  const out = el("small");
  const say = (t) => { out.textContent = t; };
  const save = el("button", { class: "ghost small", type: "submit", textContent: "Save" });
  const test = el("button", { class: "ghost small", type: "button", textContent: "Send a test", hidden: !a.wakes });
  const off = el("button", { class: "ghost small", type: "button", textContent: "Turn off", hidden: !a.wakes });
  const f = el("form", { class: "wake" }, input, save, test, off);
  f.onsubmit = async (ev) => {
    ev.preventDefault();
    try { await api("PUT", "/v1/agents/" + a.agent_id + "/wake", { email: input.value.trim() }); input.value = ""; test.hidden = off.hidden = false; say("Saved. Send a test to check it arrives."); }
    catch (e) { say(e.message); }
  };
  test.onclick = async () => {
    say("Sending…");
    try { await api("POST", "/v1/agents/" + a.agent_id + "/wake/test"); say("Sent: the agent's routine should run now (check its inbox, and spam)."); }
    catch (e) { say(e.message); }
  };
  off.onclick = async () => {
    try { await api("PUT", "/v1/agents/" + a.agent_id + "/wake", { email: null }); test.hidden = off.hidden = true; say("Off."); }
    catch (e) { say(e.message); }
  };
  return el("div", {}, f, out);
}

$("loginForm").onsubmit = (ev) => { ev.preventDefault(); key = $("key").value.trim(); store.set(key); $("error").textContent = ""; load(); };
$("logout").onclick = () => { store.set(null); location.reload(); };

$("pair").onclick = async () => {
  try {
    const t = await api("POST", "/v1/pairing-tokens");
    const qr = qrcode(0, "M"); qr.addData(t.qr_url || t.qr_payload); qr.make();
    $("pairBox").replaceChildren(
      el("img", { src: qr.createDataURL(6, 12), alt: "Pairing QR" }),
      el("p", { textContent: "Scan it with the phone's camera: it offers to install the app, then opens it. Valid 10 minutes, once." }),
      snippet(t.qr_payload));
    const known = $("devices").children.length;
    const until = Date.now() + 600000;
    const poll = setInterval(async () => { await load(); if ($("devices").children.length > known || Date.now() > until) clearInterval(poll); }, 3000);
  } catch (e) { $("error").textContent = e.message; }
};

$("addForm").onsubmit = async (ev) => {
  ev.preventDefault();
  const id = $("agentId").value.trim(), name = $("agentName").value.trim();
  try {
    const a = await api("POST", "/v1/agents", { agent_id: id, name });
    const relay = location.origin, home = "~/.hivepal-" + id;
    $("newAgent").replaceChildren(
      el("p", { textContent: name + "'s key is shown only now. On the machine where " + name + " runs:" }),
      el("h4", { textContent: "CLI (scripts, cron, any harness)" }),
      snippet("HIVEPAL_HOME=" + home + " hivepal init --relay " + relay + " --api-key " + a.api_key + "\nHIVEPAL_HOME=" + home + " hivepal msg \"Hello, I'm " + name + "\""),
      el("h4", { textContent: "Claude Code (or codex, cursor, openclaw, hermes)" }),
      snippet("HIVEPAL_HOME=" + home + " hivepal init --relay " + relay + " --api-key " + a.api_key + "\nHIVEPAL_HOME=" + home + " hivepal connect claude-code"),
      el("h4", { textContent: "HTTP (n8n, anything)" }),
      snippet("curl -X POST " + relay + "/v1/messages -H 'authorization: Bearer " + a.api_key + "' -H 'content-type: application/json' -d '{\"text\":\"Hello from " + name + "\"}'"),
      el("h4", { textContent: "Its mailbox (Markdown files, with its memory when it's kept as Markdown)" }),
      snippet("git clone https://x:" + a.api_key + "@" + location.host + "/v1/agents/" + id + "/mailbox.git"),
      el("h4", { textContent: "Its memory as SQLite (when on)" }),
      snippet("curl -H 'authorization: Bearer " + a.api_key + "' '" + relay + "/v1/agents/" + id + "/memory?q=gym'"));
    $("agentId").value = ""; $("agentName").value = "";
    load();
  } catch (e) { $("error").textContent = e.message; }
};

$("testMsg").onclick = async () => {
  try { await api("POST", "/v1/messages", { text: "Test message from the Hivepal panel 👋", options: ["It works"] }); $("testOut").textContent = "Sent: a notification should appear on the phone."; }
  catch (e) { $("testOut").textContent = e.message; }
};
$("testCall").onclick = async () => {
  try { await api("POST", "/v1/calls", { reason: "Test call from the Hivepal panel" }); $("testOut").textContent = "Ringing: lock the phone first to see it ring like a call."; }
  catch (e) { $("testOut").textContent = e.message; }
};

if (key) load();
`;

const SCRIPT = QR_LIB + "\n" + APP;
const SCRIPT_HASH = createHash("sha256").update(SCRIPT).digest("base64");

const PAGE = `<!doctype html>
<html lang="en">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<meta name="referrer" content="no-referrer">
<meta name="robots" content="noindex">
<title>Hivepal relay</title>
<style>
  :root { color-scheme: dark; }
  body { margin: 0; background: #000814; color: #E6F4FF; font: 15px/1.5 system-ui, sans-serif; }
  main { max-width: 46rem; margin: 0 auto; padding: 1.5rem 1rem 3rem; }
  header { display: flex; align-items: center; justify-content: space-between; gap: 1rem; }
  h1 { font-size: 1.5rem; margin: 0; color: #00A6FB; }
  h2 { font-size: 1.05rem; margin: 2rem 0 .6rem; color: #7DF9FF; }
  h4 { margin: 1rem 0 .3rem; font-size: .9rem; color: #8FB3D9; }
  p, small { color: #8FB3D9; }
  .card { background: #001D3D; border: 1px solid #123456; border-radius: 16px; padding: .9rem 1rem; }
  .row { display: flex; gap: .75rem; align-items: flex-start; padding: .45rem 0; }
  .row small { display: block; }
  .dot { width: .7rem; height: .7rem; border-radius: 50%; margin-top: .4rem; flex: none; background: #8FB3D9; }
  .ok .dot { background: #3DFFA8; } .warn .dot { background: #FFD60A; } .fail .dot { background: #FF6B8B; } .off .dot { background: #34506E; }
  button { background: #00A6FB; color: #000814; border: 0; border-radius: 999px; padding: .55rem 1.1rem; font: 600 .95rem system-ui, sans-serif; cursor: pointer; }
  button.ghost { background: transparent; color: #7DF9FF; border: 1px solid #1F4E7A; }
  button.small { padding: .25rem .7rem; font-size: .8rem; }
  input { background: #050B16; color: #E6F4FF; border: 1px solid #1F4E7A; border-radius: 10px; padding: .55rem .7rem; font: inherit; min-width: 0; }
  form { display: flex; gap: .5rem; flex-wrap: wrap; }
  form input { flex: 1 1 10rem; }
  .snip { display: flex; gap: .5rem; align-items: flex-start; }
  pre { flex: 1; min-width: 0; white-space: pre-wrap; word-break: break-all; background: #050B16; border: 1px solid #123456; border-radius: 10px; padding: .6rem; margin: 0; font-size: .78rem; color: #CFE6FF; }
  img { background: #fff; border-radius: 12px; padding: 8px; max-width: 100%; }
  #error { color: #FF6B8B; }
  .actions { display: flex; gap: .5rem; flex-wrap: wrap; }
  select { background: #050B16; color: #E6F4FF; border: 1px solid #1F4E7A; border-radius: 10px; padding: .4rem .6rem; font: inherit; max-width: 100%; }
  label { color: #CFE6FF; display: block; margin: .3rem 0; }
  form.wake { margin-top: .35rem; } form.wake input { flex: 1 1 12rem; padding: .3rem .6rem; font-size: .85rem; }
  .row > div { flex: 1; min-width: 0; }
</style>
</head>
<body>
<main>
  <header><h1>Hivepal relay</h1><button class="ghost small" id="logout" type="button">Lock</button></header>
  <p id="error"></p>
  <section id="login">
    <p>Paste the relay's API key (<code>HIVEPAL_API_KEY</code> in <code>deploy/.env</code>). It stays in this tab only.</p>
    <form id="loginForm"><input id="key" type="password" placeholder="ak_…" autocomplete="off" required><button>Open</button></form>
  </section>
  <section id="panel" hidden>
    <h2>Status</h2><div class="card" id="status"></div>
    <h2>Phones</h2><div class="card"><div id="devices"></div><div class="actions"><button id="pair" type="button">Pair a phone</button></div><div id="pairBox"></div></div>
    <h2>Agents</h2><div class="card"><div id="agents"></div>
      <h4>Add an agent</h4>
      <form id="addForm"><input id="agentId" placeholder="id (planner)" pattern="[a-z0-9][a-z0-9_-]{0,31}" required><input id="agentName" placeholder="Name (Planner)" maxlength="64" required><button>Add</button></form>
      <div id="newAgent"></div></div>
    <h2>How agents hear from you, and remember</h2><div class="card" id="delivery"></div>
    <h2>Try it</h2><div class="card"><div class="actions"><button class="ghost" id="testMsg" type="button">Send a test message</button><button class="ghost" id="testCall" type="button">Ring my phone</button></div><p id="testOut"></p></div>
  </section>
</main>
<script>${SCRIPT}</script>
</body>
</html>
`;

export function panelRoutes(app: FastifyInstance): void {
  app.get("/admin", async (_req, reply) =>
    reply
      .header("content-type", "text/html; charset=utf-8")
      .header(
        "content-security-policy",
        `default-src 'none'; style-src 'unsafe-inline'; img-src data:; connect-src 'self'; script-src 'sha256-${SCRIPT_HASH}'; base-uri 'none'; form-action 'none'; frame-ancestors 'none'`,
      )
      .header("referrer-policy", "no-referrer")
      .header("x-robots-tag", "noindex")
      .header("cache-control", "no-store")
      .send(PAGE),
  );
}
