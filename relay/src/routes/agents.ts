import type { FastifyInstance, FastifyRequest } from "fastify";
import { AGENT_ID_RE, type Agent, type Group, MAIN } from "../agents.js";
import { type Ctx, principal, requireAgentKey, requireAuth } from "../context.js";
import { ApiError, forbidden, notFound } from "../errors.js";
import { wellFormed } from "../ids.js";

const ID = { type: "string", pattern: AGENT_ID_RE.source } as const;
const NAME = { type: "string", minLength: 1, maxLength: 64 } as const;
const MEMBERS = { type: "array", maxItems: 64, items: ID } as const;
const AGENT_PARAMS = { type: "object", properties: { agent_id: ID } } as const;
const GROUP_PARAMS = { type: "object", properties: { group_id: ID } } as const;

const cleanName = (s: string): string => {
  const name = wellFormed(s).trim();
  if (!name) throw new ApiError(400, "invalid_request", "name must not be blank");
  return name;
};

/** Agents and group conversations (multi-agent fork). */
export function agentRoutes(app: FastifyInstance, ctx: Ctx): void {
  const { agents, events, hub } = ctx;
  const anyone = requireAuth(ctx, "agent", "device");
  const mainKey = requireAgentKey(ctx);
  /** The user (any device) or the relay API key. */
  const userOrMain = async (req: FastifyRequest) => {
    await anyone(req);
    const p = principal(req);
    if (p.kind === "agent" && p.agentId !== MAIN) throw forbidden("Only the user or the relay's API key can do this");
  };

  const withOnline = (a: Agent): Agent => ({ ...a, online: hub.isAgentOnline(a.agent_id) });
  const announceAgent = (a: Agent, removed = false) =>
    events.emit({ type: "agent.updated", agent_id: a.agent_id, name: a.name, ...(removed ? { removed: true } : {}) });
  const announceGroup = (g: Group, removed = false) =>
    events.emit({
      type: "group.updated",
      group_id: g.group_id,
      name: g.name,
      members: g.members,
      ...(removed ? { removed: true } : {}),
    });
  const checkMembers = (members: string[]) => {
    const bad = agents.unknownMember(members);
    if (bad !== undefined) throw new ApiError(400, "invalid_request", `Unknown or repeated agent in members: ${bad}`);
  };

  app.get("/v1/agents", { preHandler: anyone }, async () => ({ agents: agents.list().map(withOnline) }));

  app.post<{ Body: { agent_id: string; name: string } }>(
    "/v1/agents",
    {
      preHandler: mainKey,
      schema: {
        body: { type: "object", additionalProperties: false, required: ["agent_id", "name"], properties: { agent_id: ID, name: NAME } },
      },
    },
    async (req, reply) => {
      const created = agents.create(req.body.agent_id, cleanName(req.body.name));
      if (!created) throw new ApiError(409, "exists", `Agent ${req.body.agent_id} already exists`);
      announceAgent(created.agent);
      return reply.code(201).send({ agent: withOnline(created.agent), api_key: created.apiKey });
    },
  );

  app.patch<{ Params: { agent_id: string }; Body: { name: string } }>(
    "/v1/agents/:agent_id",
    {
      preHandler: userOrMain,
      schema: {
        params: AGENT_PARAMS,
        body: { type: "object", additionalProperties: false, required: ["name"], properties: { name: NAME } },
      },
    },
    async (req) => {
      const a = agents.rename(req.params.agent_id, cleanName(req.body.name));
      if (!a) throw notFound("Agent");
      announceAgent(a);
      return withOnline(a);
    },
  );

  app.delete<{ Params: { agent_id: string } }>(
    "/v1/agents/:agent_id",
    { preHandler: mainKey, schema: { params: AGENT_PARAMS } },
    async (req, reply) => {
      const { agent_id } = req.params;
      if (agent_id === MAIN) throw forbidden("The main agent can't be removed");
      const a = agents.get(agent_id);
      const changed = agents.remove(agent_id);
      if (!a || !changed) throw notFound("Agent");
      hub.disconnectAgent(agent_id);
      announceAgent(a, true);
      changed.forEach((g) => announceGroup(g));
      return reply.code(204).send();
    },
  );

  app.put<{ Params: { agent_id: string }; Body: { email: string | null } }>(
    "/v1/agents/:agent_id/wake",
    {
      preHandler: userOrMain,
      schema: {
        params: AGENT_PARAMS,
        body: {
          type: "object", additionalProperties: false, required: ["email"],
          properties: { email: { type: ["string", "null"], maxLength: 254 } },
        },
      },
    },
    async (req) => {
      const { agent_id } = req.params;
      if (!agents.exists(agent_id)) throw notFound("Agent");
      const email = req.body.email?.trim() || null;
      if (email !== null && !/^[^\s@<>,;"]+@[^\s@<>,;"]+\.[^\s@<>,;"]+$/.test(email)) {
        throw new ApiError(400, "invalid_request", "Not an email address");
      }
      if (email !== null && !ctx.settings?.smtpUrl()) {
        throw new ApiError(400, "invalid_request", "This relay has no email account to send from: set one first (Settings, the web panel or ./install.sh --email)");
      }
      agents.setWake(agent_id, email);
      const a = agents.get(agent_id)!;
      announceAgent(a);
      return withOnline(a);
    },
  );

  app.post<{ Params: { agent_id: string } }>(
    "/v1/agents/:agent_id/wake/test",
    { preHandler: userOrMain, schema: { params: AGENT_PARAMS } },
    async (req, reply) => {
      const { agent_id } = req.params;
      if (!agents.exists(agent_id)) throw notFound("Agent");
      if (!ctx.wake || !ctx.settings?.smtpUrl()) throw new ApiError(400, "invalid_request", "This relay has no email account to send from: set one first (Settings, the web panel or ./install.sh --email)");
      if (!agents.wakeEmail(agent_id)) throw new ApiError(400, "invalid_request", "This agent has no wake-up address");
      try {
        await ctx.wake.test(agent_id);
      } catch (e) {
        req.log.warn({ err: e, agent_id }, "test wake email failed");
        throw new ApiError(502, "email_failed", `The mail server said: ${e instanceof Error ? e.message : String(e)}`);
      }
      return reply.code(204).send();
    },
  );

  app.post<{ Params: { agent_id: string } }>(
    "/v1/agents/:agent_id/key",
    { preHandler: mainKey, schema: { params: AGENT_PARAMS } },
    async (req) => {
      if (req.params.agent_id === MAIN) throw forbidden("The main agent uses the relay's API key");
      const r = agents.rotateKey(req.params.agent_id);
      if (!r) throw notFound("Agent");
      hub.disconnectAgent(req.params.agent_id);
      return { agent: withOnline(r.agent), api_key: r.apiKey };
    },
  );

  app.get("/v1/groups", { preHandler: anyone }, async (req) => {
    const p = principal(req);
    // The relay's own key (main) manages groups, so it sees all of them.
    return { groups: agents.groups(p.kind === "agent" && p.agentId !== MAIN ? p.agentId : undefined) };
  });

  app.post<{ Body: { group_id: string; name: string; members: string[] } }>(
    "/v1/groups",
    {
      preHandler: userOrMain,
      schema: {
        body: {
          type: "object",
          additionalProperties: false,
          required: ["group_id", "name", "members"],
          properties: { group_id: ID, name: NAME, members: MEMBERS },
        },
      },
    },
    async (req, reply) => {
      checkMembers(req.body.members);
      const g = agents.createGroup(req.body.group_id, cleanName(req.body.name), req.body.members);
      if (!g) throw new ApiError(409, "exists", `Group ${req.body.group_id} already exists`);
      announceGroup(g);
      return reply.code(201).send(g);
    },
  );

  app.patch<{ Params: { group_id: string }; Body: { name?: string; members?: string[] } }>(
    "/v1/groups/:group_id",
    {
      preHandler: userOrMain,
      schema: {
        params: GROUP_PARAMS,
        body: { type: "object", additionalProperties: false, properties: { name: NAME, members: MEMBERS } },
      },
    },
    async (req) => {
      if (req.body.members) checkMembers(req.body.members);
      const g = agents.updateGroup(req.params.group_id, {
        ...(req.body.name !== undefined ? { name: cleanName(req.body.name) } : {}),
        ...(req.body.members !== undefined ? { members: req.body.members } : {}),
      });
      if (!g) throw notFound("Group");
      announceGroup(g);
      return g;
    },
  );

  app.delete<{ Params: { group_id: string } }>(
    "/v1/groups/:group_id",
    { preHandler: userOrMain, schema: { params: GROUP_PARAMS } },
    async (req, reply) => {
      const g = agents.removeGroup(req.params.group_id);
      if (!g) throw notFound("Group");
      announceGroup(g, true);
      return reply.code(204).send();
    },
  );
}
