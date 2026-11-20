/**
 * Hivepal: how agents hear from the user (issue #41). The relay's mail account for wake-up emails, the
 * external repository for mailboxes (with the relay's deploy key), each agent's mailbox on or off, and
 * each agent's own mailbox repository served read-only over git's dumb HTTP protocol, so an agent can
 * `git clone https://x:<its key>@relay/v1/agents/<id>/mailbox.git`.
 *
 * Changed by the relay's API key or the user's phones; an agent's own key only reads its own mailbox.
 */
import { createReadStream, existsSync, rmSync, statSync } from "node:fs";
import { join, normalize, sep } from "node:path";
import type { FastifyInstance, FastifyRequest } from "fastify";
import { AGENT_ID_RE, MAIN } from "../agents.js";
import { type Ctx, principal, requireAuth } from "../context.js";
import { ApiError, forbidden, notFound } from "../errors.js";
import { repoUrlProblem } from "../mailbox.js";
import { Memory } from "../memory.js";
import { EMAIL_RE, smtpServerFor, smtpUrl } from "../settings.js";
import { smtpMailer } from "../wake.js";

const AGENT_PARAMS = { type: "object", properties: { agent_id: { type: "string", pattern: AGENT_ID_RE.source } } } as const;

/** What git's dumb HTTP protocol reads from a repository. */
const GIT_PATH = /^(HEAD|info\/refs|objects\/info\/(packs|alternates|http-alternates)|objects\/[0-9a-f]{2}\/[0-9a-f]{38,62}|objects\/pack\/pack-[0-9a-f]{40,64}\.(pack|idx))$/;

export function deliveryRoutes(app: FastifyInstance, ctx: Ctx): void {
  const { agents, events, hub } = ctx;
  const settings = ctx.settings!;
  const mailbox = ctx.mailbox!;
  const anyone = requireAuth(ctx, "agent", "device");
  /** The user (any phone) or the relay's API key. */
  const userOrMain = async (req: FastifyRequest) => {
    await anyone(req);
    const p = principal(req);
    if (p.kind === "agent" && p.agentId !== MAIN) throw forbidden("Only the user or the relay's API key can do this");
  };

  const memory = new Memory(ctx.db, agents);

  const view = async () => ({
    email: settings.email(),
    mailboxes: { all_agents: settings.mailboxesForAll() },
    memory: settings.memory(),
    mailbox_repo: {
      ...(settings.repo() ?? {}),
      public_key: await mailbox.publicKey().catch(() => undefined),
      ...mailbox.repoStatus(),
    },
  });

  app.get("/v1/settings/delivery", { preHandler: userOrMain }, async () => view());

  app.put<{ Body: { address: string | null; password?: string; server?: string } }>(
    "/v1/settings/email",
    {
      preHandler: userOrMain,
      schema: {
        body: {
          type: "object", additionalProperties: false, required: ["address"],
          properties: {
            address: { type: ["string", "null"], maxLength: 254 },
            password: { type: "string", maxLength: 512 },
            server: { type: "string", maxLength: 255 },
          },
        },
      },
    },
    async (req) => {
      const { address, password, server } = req.body;
      if (address === null || !address.trim()) {
        settings.setSmtpUrl(null);
        return view();
      }
      const a = address.trim();
      if (!EMAIL_RE.test(a)) throw new ApiError(400, "invalid_request", "Not an email address");
      if (!password) throw new ApiError(400, "invalid_request", "The account's password is needed (an app password for Gmail)");
      const host = smtpServerFor(a, server);
      if (!host) throw new ApiError(400, "invalid_request", `Which mail server does ${a.split("@")[1]} use? Give it as server: "smtp.example.com:465"`);
      const url = smtpUrl(a, password, host);
      // Log in before saving, so a wrong password shows up now and not at the first wake-up email.
      const m = (ctx.mailerFor ?? ((u: string) => smtpMailer(u, process.env.HIVEPAL_WAKE_FROM)))(url);
      try {
        await m.verify?.();
      } catch (e) {
        throw new ApiError(502, "email_failed", `The mail server said: ${e instanceof Error ? e.message : String(e)}`);
      }
      settings.setSmtpUrl(url);
      return view();
    },
  );

  app.put<{ Body: { url: string | null; branch?: string } }>(
    "/v1/settings/mailbox-repo",
    {
      preHandler: userOrMain,
      schema: {
        body: {
          type: "object", additionalProperties: false, required: ["url"],
          properties: {
            url: { type: ["string", "null"], maxLength: 512 },
            branch: { type: "string", maxLength: 100, pattern: "^[A-Za-z0-9._/-]+$" },
          },
        },
      },
    },
    async (req) => {
      const url = req.body.url?.trim() || null;
      if (url) {
        const problem = repoUrlProblem(url, mailbox.allowsFileRepos);
        if (problem) throw new ApiError(400, "invalid_request", `Repository: ${problem}`);
        if (req.body.branch?.startsWith("-")) throw new ApiError(400, "invalid_request", "Not a branch name");
      }
      settings.setRepo(url, req.body.branch);
      return view();
    },
  );

  app.put<{ Body: { all_agents: boolean } }>(
    "/v1/settings/mailboxes",
    {
      preHandler: userOrMain,
      schema: { body: { type: "object", additionalProperties: false, required: ["all_agents"], properties: { all_agents: { type: "boolean" } } } },
    },
    async (req) => {
      const before = new Set(agents.list().filter((a) => agents.hasMailbox(a.agent_id)).map((a) => a.agent_id));
      settings.setMailboxesForAll(req.body.all_agents);
      for (const a of agents.list()) if (!before.has(a.agent_id) && agents.hasMailbox(a.agent_id)) mailbox.backfill(a.agent_id);
      return view();
    },
  );

  app.put<{ Body: { markdown: boolean; sqlite: boolean } }>(
    "/v1/settings/memory",
    {
      preHandler: userOrMain,
      schema: {
        body: {
          type: "object", additionalProperties: false, required: ["markdown", "sqlite"],
          properties: { markdown: { type: "boolean" }, sqlite: { type: "boolean" } },
        },
      },
    },
    async (req) => {
      const was = settings.memory().markdown;
      settings.setMemory(req.body);
      // Markdown memory turned on: the agents with a mailbox get their history so far.
      if (req.body.markdown && !was) for (const a of agents.list()) if (agents.hasMailbox(a.agent_id)) mailbox.backfill(a.agent_id);
      return view();
    },
  );

  app.post("/v1/settings/mailbox-repo/test", { preHandler: userOrMain }, async (_req, reply) => {
    if (!settings.repo()) throw new ApiError(400, "invalid_request", "No repository set");
    try {
      await mailbox.testRepo();
    } catch (e) {
      throw new ApiError(502, "repo_failed", `git said: ${e instanceof Error ? e.message : String(e)}`);
    }
    return reply.code(204).send();
  });

  app.put<{ Params: { agent_id: string }; Body: { enabled: boolean | null } }>(
    "/v1/agents/:agent_id/mailbox",
    {
      preHandler: userOrMain,
      schema: {
        params: AGENT_PARAMS,
        body: { type: "object", additionalProperties: false, required: ["enabled"], properties: { enabled: { type: ["boolean", "null"] } } },
      },
    },
    async (req) => {
      const { agent_id } = req.params;
      if (!agents.exists(agent_id)) throw notFound("Agent");
      const was = agents.hasMailbox(agent_id);
      agents.setMailbox(agent_id, req.body.enabled);
      // Its memory starts with what it already said and heard.
      if (agents.hasMailbox(agent_id) && !was) mailbox.backfill(agent_id);
      const a = agents.get(agent_id)!;
      events.emit({ type: "agent.updated", agent_id: a.agent_id, name: a.name });
      return { ...a, online: hub.isAgentOnline(a.agent_id) };
    },
  );

  /** The agent itself, the relay key or a phone; and memory as SQLite turned on. */
  const memoryReader = async (req: FastifyRequest<{ Params: { agent_id: string } }>) => {
    await anyone(req);
    const p = principal(req);
    const { agent_id } = req.params;
    if (!agents.exists(agent_id)) throw notFound("Agent");
    if (p.kind === "agent" && p.agentId !== agent_id && p.agentId !== MAIN) throw forbidden("Another agent's memory");
    if (!settings.memory().sqlite) throw new ApiError(404, "not_found", "Memory as SQLite is off on this relay (turn it on in the app's Settings or the web panel)");
  };

  app.get<{ Params: { agent_id: string }; Querystring: { q?: string; conv?: string; before?: number; limit?: number } }>(
    "/v1/agents/:agent_id/memory",
    {
      preHandler: memoryReader,
      schema: {
        params: AGENT_PARAMS,
        querystring: {
          type: "object", additionalProperties: false,
          properties: {
            q: { type: "string", maxLength: 500 }, conv: { type: "string", maxLength: 80 },
            before: { type: "integer", minimum: 0 }, limit: { type: "integer", minimum: 1, maximum: 500 },
          },
        },
      },
    },
    async (req) => ({ messages: memory.search(req.params.agent_id, req.query) }),
  );

  app.get<{ Params: { agent_id: string } }>(
    "/v1/agents/:agent_id/memory.sqlite",
    { preHandler: memoryReader, schema: { params: AGENT_PARAMS } },
    async (req, reply) => {
      const { dir, file } = memory.exportFile(req.params.agent_id);
      const stream = createReadStream(file);
      stream.on("close", () => rmSync(dir, { recursive: true, force: true }));
      return reply
        .header("content-type", "application/vnd.sqlite3")
        .header("content-disposition", `attachment; filename="${req.params.agent_id}-memory.sqlite"`)
        .header("cache-control", "no-store")
        .send(stream);
    },
  );

  // git's dumb HTTP protocol, read-only. git first asks without credentials; the 401 makes it send the
  // ones in the URL (any user name, the key as the password).
  app.get<{ Params: { agent_id: string; "*": string } }>("/v1/agents/:agent_id/mailbox.git/*", async (req, reply) => {
    const basic = /^Basic\s+(.+)$/i.exec(req.headers.authorization ?? "");
    if (basic) {
      const pass = Buffer.from(basic[1]!, "base64").toString("utf8").split(":").slice(1).join(":");
      req.headers.authorization = `Bearer ${pass}`;
    }
    if (!req.headers.authorization) {
      return reply.code(401).header("www-authenticate", 'Basic realm="Hivepal mailbox"').send({ error: { code: "unauthorized", message: "Use the agent's key as the password" } });
    }
    await anyone(req);
    const p = principal(req);
    const { agent_id } = req.params;
    if (!AGENT_ID_RE.test(agent_id) || !agents.exists(agent_id)) throw notFound("Agent");
    if (p.kind === "agent" && p.agentId !== agent_id && p.agentId !== MAIN) throw forbidden("Another agent's mailbox");
    const rel = req.params["*"];
    if (!GIT_PATH.test(rel)) throw notFound("File");
    const dir = mailbox.gitDir(agent_id);
    if (!dir) throw notFound("Mailbox (it's created with the agent's first message once its mailbox is on)");
    const file = normalize(join(dir, rel));
    if (!file.startsWith(dir + sep) || !existsSync(file) || !statSync(file).isFile()) throw notFound("File");
    return reply
      .header("content-type", rel.endsWith(".pack") || rel.startsWith("objects/") ? "application/octet-stream" : "text/plain; charset=utf-8")
      .header("cache-control", "no-store")
      .send(createReadStream(file));
  });
}
