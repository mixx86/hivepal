import type { FastifyInstance } from "fastify";
import { type Ctx, principal, requireAuth } from "../context.js";
import { ApiError, notFound } from "../errors.js";
import type { Principal } from "../auth.js";
import { wavDurationMs } from "../gemini.js";
import { newId, wellFormed } from "../ids.js";

export const MAX_FILE_BYTES = 10 * 1024 * 1024;
const TYPES = /^(image\/(jpeg|png|webp)|audio\/(mp4|aac|ogg|mpeg|webm|wav))$/;

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

  /** The file, if `p` may read it: its uploader, the user, or an agent that sees a message with it. */
  const readable = (fileId: string, p: Principal) => {
    const f = db.prepare("SELECT mime, data, agent_id, transcript FROM files WHERE file_id = ?").get(fileId) as
      | { mime: string; data: Uint8Array; agent_id: string | null; transcript: string | null }
      | undefined;
    if (!f) throw notFound("File");
    if (p.kind === "agent" && f.agent_id !== p.agentId) {
      const convs = (
        db.prepare("SELECT conv FROM messages WHERE attachments LIKE ?").all(`%"file_id":"${fileId}"%`) as Array<{ conv: string | null }>
      ).map((r) => r.conv);
      if (!convs.some((c) => ctx.agents.canSee(p.agentId, c))) throw notFound("File");
    }
    return f;
  };
  const FILE_PARAMS = { type: "object", properties: { file_id: { type: "string", pattern: "^file_[0-9A-HJKMNP-TV-Z]{26}$" } } } as const;

  // Hivepal: a voice note's transcript (Gemini), made on first request and kept.
  app.get<{ Params: { file_id: string } }>(
    "/v1/files/:file_id/transcript",
    { preHandler: auth, schema: { params: FILE_PARAMS } },
    async (req) => {
      const f = readable(req.params.file_id, principal(req));
      if (!f.mime.startsWith("audio/")) throw new ApiError(400, "invalid_request", "Not an audio file");
      if (f.transcript !== null) return { text: f.transcript };
      if (!ctx.speech) throw new ApiError(503, "calls_unavailable", "Transcription is not configured on this relay");
      const text = wellFormed(await ctx.speech.transcribe(Buffer.from(f.data), f.mime));
      db.prepare("UPDATE files SET transcript = ? WHERE file_id = ?").run(text, req.params.file_id);
      return { text };
    },
  );

  // Hivepal: text to a voice note (Gemini TTS). The caller gets a file to attach to a message.
  app.post<{ Body: { text: string; voice?: string } }>(
    "/v1/tts",
    {
      preHandler: auth,
      schema: {
        body: {
          type: "object",
          additionalProperties: false,
          required: ["text"],
          properties: { text: { type: "string", minLength: 1, maxLength: 2000 }, voice: { type: "string", maxLength: 40 } },
        },
      },
    },
    async (req, reply) => {
      if (!ctx.speech) throw new ApiError(503, "calls_unavailable", "Text-to-speech is not configured on this relay");
      const p = principal(req);
      const text = wellFormed(req.body.text);
      const audio = await ctx.speech.tts(text, req.body.voice);
      const duration = wavDurationMs(audio.data) ?? null;
      const file = { file_id: newId("file"), mime: audio.mime, size: audio.data.length };
      db.prepare(
        "INSERT INTO files (file_id, mime, size, data, agent_id, device_id, duration_ms, created_at, transcript) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
      ).run(
        file.file_id, audio.mime, audio.data.length, audio.data,
        p.kind === "agent" ? p.agentId : null, p.kind === "device" ? p.deviceId : null,
        duration, Date.now(), text,
      );
      return reply.code(201).send({ ...file, ...(duration ? { duration_ms: duration } : {}) });
    },
  );

  app.get<{ Params: { file_id: string } }>(
    "/v1/files/:file_id",
    { preHandler: auth, schema: { params: FILE_PARAMS } },
    async (req, reply) => {
      const f = readable(req.params.file_id, principal(req));
      return reply.header("content-type", f.mime).header("cache-control", "private, max-age=31536000, immutable").send(Buffer.from(f.data));
    },
  );
}
