import type { FastifyInstance } from "fastify";
import { type Ctx, principal, requireAuth } from "../context.js";
import { ApiError, notFound } from "../errors.js";
import { newId } from "../ids.js";

export const MAX_FILE_BYTES = 10 * 1024 * 1024;
const TYPES = /^(image\/(jpeg|png|webp)|audio\/(mp4|aac|ogg|mpeg|webm))$/;

/** Photos and voice clips attached to messages. Stored in the database, so a backup has them too. */
export function fileRoutes(app: FastifyInstance, ctx: Ctx): void {
  const { db } = ctx;
  const auth = requireAuth(ctx, "agent", "device");

  app.register(async (scope) => {
    scope.addContentTypeParser(/^(image|audio)\//, { parseAs: "buffer", bodyLimit: MAX_FILE_BYTES }, (_req, body, done) =>
      done(null, body),
    );
    scope.post<{ Querystring: { duration_ms?: number } }>(
      "/v1/files",
      {
        preHandler: auth,
        bodyLimit: MAX_FILE_BYTES,
        schema: { querystring: { type: "object", properties: { duration_ms: { type: "integer", minimum: 0 } } } },
      },
      async (req, reply) => {
        const mime = String(req.headers["content-type"] ?? "").split(";")[0]!.trim().toLowerCase();
        if (!TYPES.test(mime)) throw new ApiError(400, "invalid_request", `Unsupported file type ${mime || "(none)"}`);
        const data = req.body as Buffer;
        if (!Buffer.isBuffer(data) || data.length === 0) throw new ApiError(400, "invalid_request", "Empty file");
        const p = principal(req);
        const file = { file_id: newId("file"), mime, size: data.length };
        db.prepare(
          "INSERT INTO files (file_id, mime, size, data, agent_id, device_id, duration_ms, created_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
        ).run(
          file.file_id, mime, data.length, data,
          p.kind === "agent" ? p.agentId : null, p.kind === "device" ? p.deviceId : null,
          req.query.duration_ms ?? null, Date.now(),
        );
        return reply.code(201).send({ ...file, ...(req.query.duration_ms ? { duration_ms: req.query.duration_ms } : {}) });
      },
    );
  });

  app.get<{ Params: { file_id: string } }>(
    "/v1/files/:file_id",
    { preHandler: auth, schema: { params: { type: "object", properties: { file_id: { type: "string", pattern: "^file_[0-9A-HJKMNP-TV-Z]{26}$" } } } } },
    async (req, reply) => {
      const f = db.prepare("SELECT mime, data, agent_id FROM files WHERE file_id = ?").get(req.params.file_id) as
        | { mime: string; data: Uint8Array; agent_id: string | null }
        | undefined;
      if (!f) throw notFound("File");
      const p = principal(req);
      if (p.kind === "agent" && f.agent_id !== p.agentId) {
        // An agent gets a file attached to a message it can see.
        const convs = (
          db.prepare("SELECT conv FROM messages WHERE attachments LIKE ?").all(`%"file_id":"${req.params.file_id}"%`) as Array<{
            conv: string | null;
          }>
        ).map((r) => r.conv);
        if (!convs.some((c) => ctx.agents.canSee(p.agentId, c))) throw notFound("File");
      }
      return reply.header("content-type", f.mime).header("cache-control", "private, max-age=31536000, immutable").send(Buffer.from(f.data));
    },
  );
}
