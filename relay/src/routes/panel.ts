/**
 * Hivepal: GET /admin, a web panel for the relay's owner.
 *
 * A static page: it holds no data and adds no API. You paste the relay's API key (kept in this tab's
 * sessionStorage only) and the page uses the same REST API as the CLI with it: the status of each
 * piece, the paired phones and a pairing QR, the agents with ready-to-copy setup for each harness,
 * and a test message and a test call. Everything is drawn with textContent; the CSP allows only this
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
      row(st.push_provider === "none" ? "warn" : "ok", "Push", st.push_provider === "none" ? "No Firebase: the phone gets messages only while the app is open" : st.push_provider + " configured"),
      row(f.calls ? "ok" : "warn", "Calls", f.calls ? "LiveKit configured (UDP 50000-50100 open to the phone)" : "LiveKit isn't configured"),
      row(f.voice ? "ok" : "warn", "Voice", f.voice ? "Voice notes and transcripts" : "No Gemini key: no voice notes or transcripts"),
      row(f.wake_email ? "ok" : "off", "Wake-up emails", f.wake_email ? "SMTP configured" : "Not set up (optional)"));
    const devices = await api("GET", "/v1/devices");
    $("devices").replaceChildren(...(devices.devices || devices).map((d) =>
      row(d.has_push_token || st.push_provider === "none" ? "ok" : "warn", d.name, "seen " + ago(d.last_seen_at) + (d.app_version ? " · app " + d.app_version : ""))));
    if (!$("devices").children.length) $("devices").append(row("fail", "No phone paired", "Pair one below"));
    const agents = await api("GET", "/v1/agents");
    $("agents").replaceChildren(...(agents.agents || agents).map((a) =>
      row(a.last_seen_at && Date.now() - a.last_seen_at < 86400000 ? "ok" : "warn", a.name + " (" + a.agent_id + ")",
          "last seen " + ago(a.last_seen_at) + (a.wakes ? " · wake-up email" : ""))));
  } catch (e) {
    if (/401|key|Unauthorized/i.test(String(e.message))) { store.set(null); key = null; $("panel").hidden = true; $("login").hidden = false; }
    $("error").textContent = e.message;
  }
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
      snippet("curl -X POST " + relay + "/v1/messages -H 'authorization: Bearer " + a.api_key + "' -H 'content-type: application/json' -d '{\"text\":\"Hello from " + name + "\"}'"));
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
