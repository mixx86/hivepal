/**
 * Hivepal: the user's subscriptions and when each renews or ends. The phone reads every service's own
 * billing page (in the app, with the user's login there) and PUTs what it found; the relay API key may too.
 */
import type { FastifyInstance, FastifyRequest } from "fastify";
import { type Ctx, requireAuth } from "../context.js";
import { forbidden, notFound } from "../errors.js";
import type { Schemas } from "../protocol.js";

type Subscription = Schemas["Subscription"];
type Put = Schemas["PutSubscriptionRequest"];

interface Row {
  sub_id: string;
  name: string;
  plan: string | null;
  status: Subscription["status"];
  until: number | null;
  price: string | null;
  url: string | null;
  error: string | null;
  checked_at: number;
}

const view = (r: Row): Subscription => ({
  sub_id: r.sub_id,
  name: r.name,
  status: r.status,
  checked_at: r.checked_at,
  ...(r.plan ? { plan: r.plan } : {}),
  ...(r.until ? { until: r.until } : {}),
  ...(r.price ? { price: r.price } : {}),
  ...(r.url ? { url: r.url } : {}),
  ...(r.error ? { error: r.error } : {}),
});

const ID = { type: "object", required: ["sub_id"], properties: { sub_id: { type: "string", pattern: "^[a-z0-9][a-z0-9_-]{0,31}$" } } };
const STATUS = ["active", "ending", "ended", "unknown", "error"];

export function subscriptionRoutes(app: FastifyInstance, ctx: Ctx): void {
  const { db } = ctx;
  const anyone = requireAuth(ctx, "agent", "device");
  /** The user's phone, or the relay API key: not the other agents. */
  const userOrMain = async (req: FastifyRequest) => {
    await anyone(req);
    const p = req.principal!;
    if (p.kind === "agent" && p.agentId !== "main") throw forbidden("Only the user's phone or the relay API key");
  };
  const get = (id: string) => db.prepare("SELECT * FROM subscriptions WHERE sub_id = ?").get(id) as unknown as Row | undefined;

  app.get("/v1/subscriptions", { preHandler: requireAuth(ctx, "agent", "device") }, async (req) => {
    const p = req.principal!;
    // Agents other than main have no business with the user's billing.
    if (p.kind === "agent" && p.agentId !== "main") return { subscriptions: [] };
    const rows = db.prepare("SELECT * FROM subscriptions ORDER BY until IS NULL, until, name").all() as unknown as Row[];
    return { subscriptions: rows.map(view) };
  });

  app.put<{ Params: { sub_id: string }; Body: Put }>(
    "/v1/subscriptions/:sub_id",
    {
      preHandler: userOrMain,
      schema: {
        params: ID,
        body: {
          type: "object",
          additionalProperties: false,
          required: ["name", "status"],
          properties: {
            name: { type: "string", minLength: 1, maxLength: 64 },
            plan: { type: "string", maxLength: 64 },
            status: { type: "string", enum: STATUS },
            until: { type: "integer", minimum: 0 },
            price: { type: "string", maxLength: 64 },
            url: { type: "string", maxLength: 512 },
            error: { type: "string", maxLength: 500 },
          },
        },
      },
    },
    async (req) => {
      const b = req.body;
      const prev = get(req.params.sub_id);
      // A failed read keeps what was known before: the date is still the best guess.
      const keep = b.status === "error" && prev;
      const row: Row = {
        sub_id: req.params.sub_id,
        name: b.name.trim(),
        plan: b.plan?.trim() || (keep ? prev.plan : null),
        status: b.status,
        until: b.until ?? (keep ? prev.until : null),
        price: b.price?.trim() || (keep ? prev.price : null),
        url: b.url ?? prev?.url ?? null,
        error: b.status === "error" ? (b.error ?? "read failed") : null,
        checked_at: Date.now(),
      };
      db.prepare(
        `INSERT INTO subscriptions (sub_id, name, plan, status, until, price, url, error, checked_at)
         VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
         ON CONFLICT(sub_id) DO UPDATE SET name = excluded.name, plan = excluded.plan, status = excluded.status,
           until = excluded.until, price = excluded.price, url = excluded.url, error = excluded.error, checked_at = excluded.checked_at`,
      ).run(row.sub_id, row.name, row.plan, row.status, row.until, row.price, row.url, row.error, row.checked_at);
      return view(row);
    },
  );

  app.delete<{ Params: { sub_id: string } }>(
    "/v1/subscriptions/:sub_id",
    { preHandler: userOrMain, schema: { params: ID } },
    async (req, reply) => {
      const r = db.prepare("DELETE FROM subscriptions WHERE sub_id = ?").run(req.params.sub_id);
      if (r.changes === 0) throw notFound("Subscription");
      return reply.code(204).send();
    },
  );
}
