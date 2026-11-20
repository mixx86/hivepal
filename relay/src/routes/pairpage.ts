/**
 * Hivepal: GET /pair, the page a pairing link opens (PairingToken.qr_url = <relay>/pair#<code>).
 *
 * Scanned with the phone's camera (or opened without the app), the link lands here. The page is
 * static: the code stays in the URL fragment, which browsers never send, and a small script reads it
 * there. It offers to install the app and to open it with the code (hivepal://pair?c=<code>; the app
 * asks before pairing), and shows the code to paste by hand.
 */
import { createHash } from "node:crypto";
import type { FastifyInstance } from "fastify";

/** Where to get the app; HIVEPAL_APP_URL for a build of your own. */
const APP_URL = process.env.HIVEPAL_APP_URL || "https://github.com/mixx86/hivepal/releases/latest";

const SCRIPT = `
const code = location.hash.slice(1);
const $ = (id) => document.getElementById(id);
if (!/^[A-Za-z0-9_-]{20,2000}$/.test(code)) {
  $("ok").hidden = true;
  $("missing").hidden = false;
} else {
  let json = "";
  try { json = atob(code.replace(/-/g, "+").replace(/_/g, "/")); } catch (e) {}
  $("code").textContent = json || code;
  const open = "hivepal://pair?c=" + code;
  // On Android, an intent link opens the app, or the download page when it isn't installed.
  const fallback = encodeURIComponent($("install").href);
  $("open").href = /Android/i.test(navigator.userAgent)
    ? "intent://pair?c=" + code + "#Intent;scheme=hivepal;S.browser_fallback_url=" + fallback + ";end"
    : open;
  $("copy").onclick = () => navigator.clipboard.writeText($("code").textContent)
    .then(() => { $("copy").textContent = "Copied"; });
}
`;
const SCRIPT_HASH = createHash("sha256").update(SCRIPT).digest("base64");

const esc = (s: string) => s.replace(/[&<>"]/g, (c) => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;" })[c]!);

const PAGE = `<!doctype html>
<html lang="en">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<meta name="referrer" content="no-referrer">
<title>Pair your phone · Hivepal</title>
<style>
  :root { color-scheme: dark; }
  body { margin: 0; background: #000814; color: #E6F4FF; font: 16px/1.5 system-ui, sans-serif; }
  main { max-width: 30rem; margin: 0 auto; padding: 2rem 1.25rem 3rem; }
  h1 { font-size: 1.6rem; margin: 0 0 .25rem; color: #00A6FB; }
  p { color: #8FB3D9; margin: .25rem 0 1rem; }
  ol { padding: 0; list-style: none; counter-reset: s; margin: 1.5rem 0; }
  li { counter-increment: s; background: #001D3D; border: 1px solid #123456; border-radius: 16px; padding: 1rem 1rem 1rem 3.25rem; margin-bottom: .75rem; position: relative; }
  li::before { content: counter(s); position: absolute; left: 1rem; top: 1rem; width: 1.6rem; height: 1.6rem; border-radius: 50%; background: #00A6FB; color: #000814; font-weight: 700; text-align: center; line-height: 1.6rem; }
  li b { display: block; margin-bottom: .5rem; }
  a.btn, button { display: inline-block; background: #00A6FB; color: #000814; border: 0; border-radius: 999px; padding: .65rem 1.25rem; font: 600 1rem system-ui, sans-serif; text-decoration: none; cursor: pointer; }
  a.btn.ghost, button.ghost { background: transparent; color: #7DF9FF; border: 1px solid #1F4E7A; }
  pre { white-space: pre-wrap; word-break: break-all; background: #050B16; border: 1px solid #123456; border-radius: 12px; padding: .75rem; font-size: .8rem; color: #8FB3D9; }
  small { color: #8FB3D9; }
</style>
</head>
<body>
<main>
  <h1>Pair your phone</h1>
  <p>Connect the Hivepal app to this relay. The code below works once and expires in a few minutes.</p>
  <div id="ok">
    <ol>
      <li><b>Install Hivepal</b><small>Skip this if you have it.</small><br><br>
        <a class="btn ghost" id="install" href="${esc(APP_URL)}">Download the app</a></li>
      <li><b>Open it with this code</b><small>The app shows the relay and asks before pairing.</small><br><br>
        <a class="btn" id="open" href="#">Open Hivepal</a></li>
    </ol>
    <p>Or in the app: <b>Get started → Paste the code</b>.</p>
    <pre id="code"></pre>
    <button class="ghost" id="copy" type="button">Copy the code</button>
  </div>
  <div id="missing" hidden>
    <p>This link has no pairing code. Run <code>hivepal pair</code> again and scan the new QR.</p>
  </div>
</main>
<script>${SCRIPT}</script>
</body>
</html>
`;

export function pairPageRoutes(app: FastifyInstance): void {
  app.get("/pair", async (_req, reply) =>
    reply
      .header("content-type", "text/html; charset=utf-8")
      .header(
        "content-security-policy",
        `default-src 'none'; style-src 'unsafe-inline'; script-src 'sha256-${SCRIPT_HASH}'; base-uri 'none'; form-action 'none'; frame-ancestors 'none'`,
      )
      .header("referrer-policy", "no-referrer")
      .header("cache-control", "no-store")
      .send(PAGE),
  );
}

/** The pairing link for a QR payload: <relay>/pair#<base64url(payload)>. */
export function pairUrl(publicUrl: string, qrPayload: string): string {
  return `${publicUrl}/pair#${Buffer.from(qrPayload).toString("base64url")}`;
}
