import {
  createCipheriv,
  createDecipheriv,
  randomBytes,
  randomUUID,
} from "node:crypto";
import { z } from "zod";
import type { Doc, Store, Tx } from "../../core/store.js";
import { check } from "../../shared/errors.js";
import { rate } from "./intent.js";

export const maxImageBytes = 5_000_000;
export const faceTemplatePath = (uid: string) => "faceTemplates/" + uid;
const model = "buffalo_l";
const version = z.string().regex(/^v1-[a-f0-9]{64}$/);
const imageErrors = [
  "INVALID_IMAGE", "FACE_NOT_FOUND", "MULTIPLE_FACES", "FACE_LOW_QUALITY",
  "FACE_DETECTION_UNCERTAIN", "FACE_TOO_SMALL", "FACE_CROPPED",
  "FACE_LANDMARKS_INVALID", "FACE_EXPOSURE", "FACE_BLURRY",
  "FACE_EMBEDDING_INVALID",
];
const result = z.object({
  model: z.literal(model),
  version,
  embedding: z.array(z.number().finite()).length(512),
});
type Embedding = z.infer<typeof result>;
type Template = {
  uid: string;
  enrollmentId: string;
  requestId: string;
  status: "VERIFIED";
  model: string;
  version: string;
  createdAt: number;
  expiresAt: number;
  encrypted: string;
};

export class FaceService {
  private running = 0;
  constructor(
    private baseUrl: string,
    private encryptionKey: Buffer,
    private similarity: number,
    private margin: number,
    private retentionMs: number,
  ) {}

  async ready() {
    try {
      const res = await fetch(this.baseUrl + "/health", {
        signal: AbortSignal.timeout(1500),
        redirect: "error",
      });
      const data = res.ok ? await res.json() : null;
      return (
        data?.status === "ready" &&
        data.model === model &&
        version.safeParse(data.version).success
      );
    } catch {
      return false;
    }
  }

  private async embedding(mime: string, image: Buffer): Promise<Embedding> {
    check(["image/jpeg", "image/png"].includes(mime), "INVALID_MIME", 415);
    check(
      image.length > 0 && image.length <= maxImageBytes,
      "INVALID_BYTES",
      413,
    );
    check(this.running < 2, "FACE_BUSY", 429);
    this.running++;
    try {
      const res = await fetch(this.baseUrl + "/embedding", {
        method: "POST",
        headers: { "Content-Type": mime },
        body: new Uint8Array(image),
        signal: AbortSignal.timeout(8000),
        redirect: "error",
      }).catch(() => null);
      check(res, "FACE_SETUP_REQUIRED", 503);
      const data = await res.json().catch(() => null);
      if (res.status === 422) {
        const code = data?.detail;
        check(
          false,
          imageErrors.includes(code)
            ? code
            : "INVALID_IMAGE",
          422,
        );
      }
      check(res.status !== 429, "FACE_BUSY", 429);
      const parsed = result.safeParse(data);
      check(res.ok && parsed.success, "FACE_SETUP_REQUIRED", 503);
      const norm = Math.hypot(...parsed.data.embedding);
      check(Math.abs(norm - 1) < 0.01, "FACE_SETUP_REQUIRED", 503);
      return parsed.data;
    } finally {
      this.running--;
    }
  }

  private associated(template: Omit<Template, "encrypted">) {
    return Buffer.from(
      JSON.stringify([
        template.uid,
        template.enrollmentId,
        template.requestId,
        template.model,
        template.version,
        template.createdAt,
        template.expiresAt,
      ]),
    );
  }

  async enroll(uid: string, requestId: string, mime: string, image: Buffer, now: number): Promise<Template> {
    return this.seal(uid, requestId, await this.embedding(mime, image), now);
  }

  private seal(uid: string, requestId: string, result: Embedding, now: number): Template {
    const metadata = {
      uid, requestId, enrollmentId: randomUUID(), status: "VERIFIED" as const,
      model: result.model, version: result.version, createdAt: now,
      expiresAt: now + this.retentionMs,
    };
    const iv = randomBytes(12);
    const cipher = createCipheriv("aes-256-gcm", this.encryptionKey, iv);
    cipher.setAAD(this.associated(metadata));
    const ciphertext = Buffer.concat([cipher.update(JSON.stringify(result.embedding)), cipher.final()]);
    return { ...metadata, encrypted: Buffer.concat([iv, cipher.getAuthTag(), ciphertext]).toString("base64") };
  }

  private score(template: Template, embedding: Embedding) {
    const encrypted = Buffer.from(template.encrypted, "base64");
    const decipher = createDecipheriv(
      "aes-256-gcm",
      this.encryptionKey,
      encrypted.subarray(0, 12),
    );
    decipher.setAAD(this.associated(template));
    decipher.setAuthTag(encrypted.subarray(12, 28));
    const vector: number[] = JSON.parse(
      Buffer.concat([
        decipher.update(encrypted.subarray(28)),
        decipher.final(),
      ]).toString(),
    );
    return embedding.embedding.reduce(
      (sum, n, index) => sum + n * vector[index],
      0,
    );
  }

  private async eligible(t: Tx, value: Doc, now: number) {
    const template = value as Template;
    if (template.status !== "VERIFIED" || template.expiresAt <= now)
      return undefined;
    const user = await t.get("users/" + template.uid);
    if (
      user?.role !== "CUSTOMER" ||
      user.status !== "ACTIVE" ||
      user.active !== true
    )
      return undefined;
    const request = await t.get("ekycRequests/" + template.requestId);
    return request?.reviewStatus === "VERIFIED" &&
      request.customerUid === template.uid
      ? template
      : undefined;
  }

  async activeTemplate(t: Tx, uid: string, now = Date.now()) {
    const value = await t.get(faceTemplatePath(uid));
    return value?.uid === uid ? this.eligible(t, value, now) : undefined;
  }

  async matchOwner(template: Template, mime: string, image: Buffer) {
    const vector = await this.embedding(mime, image);
    check(template.version === vector.version, "FACE_TEMPLATE_OUTDATED", 409);
    check(
      this.score(template, vector) >= this.similarity,
      "FACE_MISMATCH",
      403,
    );
  }

  async signIn(
    store: Store,
    ip: string,
    mime: string,
    image: Buffer,
    customToken: (uid: string) => Promise<string>,
  ) {
    await store.run(async (t) => {
      await rate(t, "face-login-ip", ip, Date.now(), 5, 300_000);
      await rate(t, "face-login-global", "all", Date.now(), 60, 60_000);
    });
    const vector = await this.embedding(mime, image);
    const best = await store.run(async (t) => {
      const rows = await t.list(
        "faceTemplates",
        { version: vector.version },
        { limit: 1001 },
      );
      check(rows.length <= 1000, "FACE_SETUP_REQUIRED", 503);
      const candidates: { uid: string; score: number }[] = [];
      const now = Date.now();
      for (const row of rows) {
        if (
          row.id !== row.uid ||
          row.status !== "VERIFIED" ||
          row.expiresAt <= now
        )
          continue;
        const score = this.score(row as unknown as Template, vector);
        if (score < this.similarity - this.margin) continue;
        if (await this.eligible(t, row, now))
          candidates.push({ uid: row.uid, score });
      }
      candidates.sort((a, b) => b.score - a.score);
      const [first, second] = candidates;
      check(
        first &&
          first.score >= this.similarity &&
          (!second || first.score - second.score >= this.margin),
        "FACE_LOGIN_FAILED",
        401,
      );
      return first.uid;
    });
    return { customToken: await customToken(best) };
  }
}

