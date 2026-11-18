/**
 * Hivepal: speech services from Gemini, used by the relay when GEMINI_API_KEY is set.
 * Voice notes get a transcript (so agents that can't hear audio can read them) and agents can
 * turn text into a voice note. Models are overridable: HIVEPAL_TRANSCRIBE_MODEL, HIVEPAL_TTS_MODEL,
 * HIVEPAL_TTS_VOICE.
 */
const API = "https://generativelanguage.googleapis.com/v1beta/models";

export interface Speech {
  transcribe(audio: Buffer, mime: string): Promise<string>;
  tts(text: string, voice?: string): Promise<{ data: Buffer; mime: string }>;
}

export class GeminiSpeech implements Speech {
  constructor(
    private readonly key: string,
    private readonly transcribeModel = process.env.HIVEPAL_TRANSCRIBE_MODEL || "gemini-flash-latest",
    private readonly ttsModel = process.env.HIVEPAL_TTS_MODEL || "gemini-3.8-flash-tts",
    private readonly defaultVoice = process.env.HIVEPAL_TTS_VOICE || "Kore",
  ) {}

  private async generate(model: string, body: unknown): Promise<Record<string, unknown>> {
    const res = await fetch(`${API}/${model}:generateContent`, {
      method: "POST",
      headers: { "x-goog-api-key": this.key, "content-type": "application/json" },
      body: JSON.stringify(body),
      signal: AbortSignal.timeout(60_000),
    });
    const json = (await res.json()) as Record<string, unknown>;
    if (!res.ok) throw new Error(`Gemini ${res.status}: ${(json.error as { message?: string } | undefined)?.message ?? ""}`);
    return json;
  }

  private static parts(json: Record<string, unknown>): Array<Record<string, unknown>> {
    const c = (json.candidates as Array<{ content?: { parts?: Array<Record<string, unknown>> } }> | undefined)?.[0];
    return c?.content?.parts ?? [];
  }

  async transcribe(audio: Buffer, mime: string): Promise<string> {
    const json = await this.generate(this.transcribeModel, {
      contents: [
        {
          parts: [
            { text: "Transcribe exactly what is said in this voice note, in its own language. Reply with the transcript only." },
            { inlineData: { mimeType: mime, data: audio.toString("base64") } },
          ],
        },
      ],
    });
    return GeminiSpeech.parts(json)
      .map((p) => (typeof p.text === "string" ? p.text : ""))
      .join("")
      .trim();
  }

  async tts(text: string, voice?: string): Promise<{ data: Buffer; mime: string }> {
    const json = await this.generate(this.ttsModel, {
      contents: [{ parts: [{ text }] }],
      generationConfig: {
        responseModalities: ["AUDIO"],
        speechConfig: { voiceConfig: { prebuiltVoiceConfig: { voiceName: voice || this.defaultVoice } } },
      },
    });
    const inline = GeminiSpeech.parts(json).find((p) => p.inlineData)?.inlineData as { mimeType: string; data: string } | undefined;
    if (!inline) throw new Error("Gemini returned no audio");
    const raw = Buffer.from(inline.data, "base64");
    if (raw.subarray(0, 4).toString("ascii") === "RIFF") return { data: raw, mime: "audio/wav" };
    // Raw PCM (audio/L16;rate=24000): wrap it in a WAV header so phones can play it.
    const rate = Number(/rate=(\d+)/.exec(inline.mimeType)?.[1] ?? 24000);
    return { data: wav(raw, rate), mime: "audio/wav" };
  }
}

/** 16-bit mono PCM → WAV. */
export function wav(pcm: Buffer, rate: number): Buffer {
  const h = Buffer.alloc(44);
  h.write("RIFF", 0);
  h.writeUInt32LE(36 + pcm.length, 4);
  h.write("WAVEfmt ", 8);
  h.writeUInt32LE(16, 16);
  h.writeUInt16LE(1, 20);
  h.writeUInt16LE(1, 22);
  h.writeUInt32LE(rate, 24);
  h.writeUInt32LE(rate * 2, 28);
  h.writeUInt16LE(2, 32);
  h.writeUInt16LE(16, 34);
  h.write("data", 36);
  h.writeUInt32LE(pcm.length, 40);
  return Buffer.concat([h, pcm]);
}

/** WAV duration in ms (16-bit mono), for the voice note's length. */
export function wavDurationMs(data: Buffer): number | undefined {
  if (data.length < 44 || data.subarray(0, 4).toString("ascii") !== "RIFF") return undefined;
  const byteRate = data.readUInt32LE(28);
  return byteRate > 0 ? Math.round(((data.length - 44) / byteRate) * 1000) : undefined;
}
