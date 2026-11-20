/**
 * Hivepal: the relay's delivery settings, changed at runtime (installer, web panel, app) and kept in the
 * `meta` table: the email account wake-up emails are sent from, and the git repository the relay writes
 * agents' mailboxes to. `HIVEPAL_SMTP_URL` stays as the initial value until one is saved here.
 * Secrets (the email password) are never returned.
 */
import type { Db } from "./db/database.js";

const SMTP = "setting:smtp_url";
const REPO = "setting:mailbox_repo";
const BRANCH = "setting:mailbox_branch";
const MAILBOXES = "setting:mailboxes_all";
const MEMORY = "setting:memory";

/** How the bots remember their Hivepal conversations: Markdown history in their mailbox, a SQLite database, both or neither. */
export interface Memory {
  markdown: boolean;
  sqlite: boolean;
}

/** The SMTP server of well-known providers (the domain after @). */
const KNOWN: Record<string, string> = {
  "gmail.com": "smtps://smtp.gmail.com:465",
  "googlemail.com": "smtps://smtp.gmail.com:465",
  "icloud.com": "smtp://smtp.mail.me.com:587",
  "me.com": "smtp://smtp.mail.me.com:587",
  "mac.com": "smtp://smtp.mail.me.com:587",
  "yahoo.com": "smtps://smtp.mail.yahoo.com:465",
  "yahoo.es": "smtps://smtp.mail.yahoo.com:465",
  "ymail.com": "smtps://smtp.mail.yahoo.com:465",
  "fastmail.com": "smtps://smtp.fastmail.com:465",
  "fastmail.fm": "smtps://smtp.fastmail.com:465",
  "gmx.com": "smtps://mail.gmx.net:465",
  "gmx.net": "smtps://mail.gmx.net:465",
  "gmx.es": "smtps://mail.gmx.net:465",
  "gmx.de": "smtps://mail.gmx.net:465",
  "zoho.com": "smtps://smtp.zoho.com:465",
  "zohomail.com": "smtps://smtp.zoho.com:465",
};

export const EMAIL_RE = /^[^\s@<>,;"]+@[^\s@<>,;"]+\.[^\s@<>,;"]+$/;

/** smtps://host:465 or smtp://host:587 for an address, from `server` ("host:port") or the provider's domain. */
export function smtpServerFor(address: string, server?: string): string | undefined {
  if (server?.trim()) {
    const hp = server.trim().replace(/^smtps?:\/\//, "");
    const withPort = /:\d+$/.test(hp) ? hp : `${hp}:465`;
    return `${withPort.endsWith(":465") ? "smtps" : "smtp"}://${withPort}`;
  }
  return KNOWN[address.split("@").pop()!.toLowerCase()];
}

/** The URL nodemailer takes, every part percent-encoded. */
export function smtpUrl(address: string, password: string, server: string): string {
  const [scheme, rest] = server.split("://") as [string, string];
  return `${scheme}://${encodeURIComponent(address)}:${encodeURIComponent(password)}@${rest}`;
}

export interface EmailView {
  configured: boolean;
  address?: string;
  server?: string;
  /** From the environment (HIVEPAL_SMTP_URL) rather than saved in the relay. */
  from_env?: boolean;
}

export class Settings {
  constructor(private readonly db: Db) {}

  private get(key: string): string | undefined {
    return (this.db.prepare("SELECT value FROM meta WHERE key = ?").get(key) as { value: string } | undefined)?.value;
  }

  private set(key: string, value: string | null): void {
    if (value === null) this.db.prepare("DELETE FROM meta WHERE key = ?").run(key);
    else this.db.prepare("INSERT INTO meta (key, value) VALUES (?, ?) ON CONFLICT(key) DO UPDATE SET value = excluded.value").run(key, value);
  }

  /** The SMTP URL in use: saved here ("" = turned off here), else the environment's. */
  smtpUrl(): string | undefined {
    const saved = this.get(SMTP);
    if (saved !== undefined) return saved || undefined;
    return process.env.HIVEPAL_SMTP_URL || undefined;
  }

  setSmtpUrl(url: string | null): void {
    // "" rather than deleting: turning it off here must win over the environment.
    this.set(SMTP, url ?? "");
  }

  email(): EmailView {
    const url = this.smtpUrl();
    if (!url) return { configured: false };
    try {
      const u = new URL(url);
      return {
        configured: true,
        address: decodeURIComponent(u.username),
        server: `${u.hostname}:${u.port || (u.protocol === "smtps:" ? "465" : "587")}`,
        ...(this.get(SMTP) === undefined ? { from_env: true } : {}),
      };
    } catch {
      return { configured: true };
    }
  }

  repo(): { url: string; branch?: string } | undefined {
    const url = this.get(REPO);
    if (!url) return undefined;
    const branch = this.get(BRANCH);
    return { url, ...(branch ? { branch } : {}) };
  }

  /** Every agent has a mailbox unless turned off for it. */
  mailboxesForAll(): boolean {
    return this.get(MAILBOXES) === "1";
  }

  setMailboxesForAll(on: boolean): void {
    this.set(MAILBOXES, on ? "1" : null);
  }

  memory(): Memory {
    const v = this.get(MEMORY) ?? "";
    return { markdown: v.includes("markdown"), sqlite: v.includes("sqlite") };
  }

  setMemory(m: Memory): void {
    const v = [m.markdown && "markdown", m.sqlite && "sqlite"].filter(Boolean).join(",");
    this.set(MEMORY, v || null);
  }

  setRepo(url: string | null, branch?: string): void {
    this.set(REPO, url);
    this.set(BRANCH, url && branch ? branch : null);
  }
}
