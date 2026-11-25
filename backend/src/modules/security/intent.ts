import {
  createHmac,
  randomInt,
  randomUUID,
  timingSafeEqual,
} from "node:crypto";
import type { Banking } from "../../core/banking.js";
import type { Doc, Tx } from "../../core/store.js";
import { digest } from "../../shared/crypto.js";
import { check } from "../../shared/errors.js";
import type { Actor } from "../auth/auth.types.js";

const method = "DEMO_DISPLAY_CODE";
const lifetime = 300_000;
const challengePath = (id: string) => "otpChallenges/" + id;
const proofPath = (id: string) => "faceProofs/" + id;

export function intentDigest(i: Doc) {
  return digest([
    i.id,
    i.actorUid,
    i.kind,
    i.sourceAccountId ?? i.sourceId,
    i.destinationAccountId ?? i.billId,
    i.amountMinor,
    i.feeMinor,
    i.currency,
    i.policyDigest ?? null,
    i.faceRequired,
  ]);
}

export async function rate(
  t: Tx,
  scope: string,
  subject: string,
  now: number,
  limit: number,
  window: number,
) {
  const path = "securityRateLimits/" + digest([scope, subject]);
  const old = await t.get(path);
  const current =
    old && now < old.resetAt ? old : { count: 0, resetAt: now + window };
  check(current.count < limit, "RATE_LIMITED", 429);
  t.set(path, { ...current, count: current.count + 1 });
}

export class IntentSecurity {
  constructor(private bank: Banking) {}

  requiresFace(currency: string, amount: number) {
    return (
      amount >= (this.bank.options.faceThresholds?.[currency] ?? 100_000_000)
    );
  }

  assertCurrent(i: Doc) {
    check(
      i.securityVersion === 1 && i.digest === intentDigest(i),
      "INTENT_CHANGED",
      409,
    );
    check(i.status === "CREATED", "INVALID_INTENT_STATE", 409);
    check(this.bank.now() < i.expiresAt, "EXPIRED", 410);
  }

  private hash(i: Doc, nonce: string, code: string) {
    return createHmac("sha256", this.bank.options.otpSecret)
      .update(JSON.stringify([i.id, i.actorUid, i.digest, nonce, code]))
      .digest("hex");
  }

  async challenge(a: Actor, id: string) {
    const code = String(randomInt(1_000_000)).padStart(6, "0");
    const nonce = randomUUID();
    return this.bank.store.run(async (t) => {
      await this.bank.authorize(t, a);
      const i = await this.bank.transfers.getIntent(t, a, id);
      this.assertCurrent(i);
      const old = await t.get(challengePath(id));
      const now = this.bank.now();
      check(
        old?.status !== "VERIFIED" || now >= old.expiresAt,
        "OTP_USED",
        409,
      );
      check(!old || old.attempts < 5, "OTP_LOCKED", 429);
      check(!old || now >= old.sentAt + 30_000, "RATE_LIMITED", 429);
      await rate(t, "otp", a.uid, now, 8, lifetime);
      const expiresAt = Math.min(i.expiresAt, now + 120_000);
      t.set(challengePath(id), {
        actorUid: a.uid,
        intentDigest: i.digest,
        nonce,
        method,
        hash: this.hash(i, nonce, code),
        expiresAt,
        sentAt: now,
        attempts: old?.attempts ?? 0,
        status: "AWAITING_CODE",
      });
      return {
        method,
        challengeId: nonce,
        code,
        expiresAt,
        status: "AWAITING_CODE",
      };
    });
  }

  async verify(a: Actor, id: string, challengeId: string, code: string) {
    const outcome = await this.bank.store.run(async (t) => {
      await this.bank.authorize(t, a);
      const i = await this.bank.transfers.getIntent(t, a, id);
      this.assertCurrent(i);
      const c = await t.get(challengePath(id));
      check(
        c?.method === method && c.nonce === challengeId && c.actorUid === a.uid,
        "OTP_REQUIRED",
      );
      check(c.intentDigest === i.digest, "INTENT_CHANGED", 409);
      check(c.attempts < 5, "OTP_LOCKED", 429);
      check(this.bank.now() < c.expiresAt, "OTP_EXPIRED", 410);
      check(c.status === "AWAITING_CODE", "OTP_USED", 409);
      const match = timingSafeEqual(
        Buffer.from(this.hash(i, c.nonce, code)),
        Buffer.from(c.hash),
      );
      const attempts = c.attempts + 1;
      const status = match
        ? "VERIFIED"
        : attempts >= 5
          ? "LOCKED"
          : "AWAITING_CODE";
      t.set(challengePath(id), { ...c, attempts, status });
      return status;
    });
    check(outcome !== "LOCKED", "OTP_LOCKED", 429);
    check(outcome === "VERIFIED", "INVALID_OTP");
    return { authorized: true, status: "VERIFIED" };
  }

  async consume(t: Tx, i: Doc) {
    this.assertCurrent(i);
    const c = await t.get(challengePath(i.id));
    check(
      c?.method === method &&
        c.status === "VERIFIED" &&
        c.actorUid === i.actorUid &&
        c.intentDigest === i.digest,
      "OTP_REQUIRED",
    );
    check(this.bank.now() < c.expiresAt, "OTP_EXPIRED", 410);
    if (i.faceRequired || this.requiresFace(i.currency, i.amountMinor)) {
      const face = this.bank.options.face;
      check(face, "STEP_UP_UNAVAILABLE", 503);
      const proof = await t.get(proofPath(i.id));
      const template = await face.activeTemplate(t, i.actorUid);
      check(
        template &&
          proof &&
          proof.actorUid === i.actorUid &&
          proof.digest === i.digest &&
          proof.templateId === template.enrollmentId &&
          !proof.used &&
          this.bank.now() < proof.expiresAt,
        "FACE_REQUIRED",
        403,
      );
      t.set(proofPath(i.id), { ...proof, used: true });
    }
    t.set(challengePath(i.id), {
      ...c,
      status: "USED",
      usedAt: this.bank.now(),
    });
  }

  async stepUp(a: Actor, id: string, mime: string, image: Buffer) {
    const face = this.bank.options.face;
    check(face, "STEP_UP_UNAVAILABLE", 503);
    const { i, template } = await this.bank.store.run(async (t) => {
      await this.bank.authorize(t, a);
      const i = await this.bank.transfers.getIntent(t, a, id);
      this.assertCurrent(i);
      check(
        i.faceRequired || this.requiresFace(i.currency, i.amountMinor),
        "FACE_NOT_REQUIRED",
      );
      const template = await face.activeTemplate(t, a.uid);
      check(template, "FACE_REQUIRED", 403);
      await rate(t, "face-step-up", a.uid, this.bank.now(), 5, lifetime);
      return { i, template };
    });
    await face.matchOwner(template, mime, image);
    return this.bank.store.run(async (t) => {
      await this.bank.authorize(t, a);
      const current = await this.bank.transfers.getIntent(t, a, id);
      this.assertCurrent(current);
      const latest = await face.activeTemplate(t, a.uid);
      check(
        current.digest === i.digest &&
          latest?.enrollmentId === template.enrollmentId,
        "INTENT_CHANGED",
        409,
      );
      const expiresAt = Math.min(i.expiresAt, this.bank.now() + 120_000);
      t.set(proofPath(id), {
        actorUid: a.uid,
        digest: i.digest,
        templateId: template.enrollmentId,
        expiresAt,
        used: false,
      });
      return { status: "VERIFIED", expiresAt };
    });
  }

  async describe(a: Actor, id: string) {
    return this.bank.store.run(async (t) => {
      await this.bank.authorize(t, a);
      const i = await this.bank.transfers.getIntent(t, a, id);
      const c = await t.get(challengePath(id));
      const proof = await t.get(proofPath(id));
      const payment =
        i.kind === "PAYMENT" ? await t.get("payments/" + id) : undefined;
      const now = this.bank.now();
      const faceTemplate =
        proof && !proof.used && now < proof.expiresAt
          ? await this.bank.options.face?.activeTemplate(t, a.uid, now)
          : undefined;
      const status = payment
        ? payment.status === "SUCCEEDED"
          ? "COMMITTED"
          : payment.status === "CANCELLED"
            ? "FAILED"
            : "COMMIT_PENDING"
        : i.status === "CREATED" && now >= i.expiresAt
          ? "EXPIRED"
          : i.status;
      return {
        ...(i.failureCode && { failureCode: i.failureCode }),
        id,
        intentId: id,
        kind: i.kind ?? "TRANSFER",
        ...(i.provider && { provider: i.provider }),
        ...(i.sourceId === "SIMULATOR" && { demo: true }),
        ...(i.kind === "DEMO_MOVEMENT" && {
          demo: true, providerReference: i.providerReference ?? null,
          ...(i.bankCode ? { bankCode: i.bankCode, beneficiaryAccount: i.beneficiaryAccount } : {}),
        }),
        status,
        expiresAt: i.expiresAt,
        sourceId: i.sourceId ?? i.sourceAccountId,
        target:
          i.target ??
          (i.billId
            ? { kind: "BILL", id: i.billId }
            : { kind: "ACCOUNT", id: i.destinationAccountId }),
        amountMinor: i.amountMinor,
        feeMinor: i.feeMinor ?? 0,
        currency: i.currency,
        faceRequired:
          i.faceRequired || this.requiresFace(i.currency, i.amountMinor),
        faceStatus:
          faceTemplate &&
          faceTemplate.enrollmentId === proof?.templateId &&
          proof.digest === i.digest
            ? "VERIFIED"
            : "REQUIRED",
        challengeStatus:
          c?.method === method && c.actorUid === i.actorUid && c.intentDigest === i.digest
            ? ["AWAITING_CODE", "VERIFIED"].includes(c.status) &&
              now >= c.expiresAt
              ? "EXPIRED"
              : c.status
            : "REQUIRED",
      };
    });
  }

  async cancel(a: Actor, id: string) {
    return this.bank.store.run(async (t) => {
      await this.bank.authorize(t, a);
      const i = await this.bank.transfers.getIntent(t, a, id);
      if (i.status === "FAILED" || i.status === "CANCELLED")
        return { status: i.status };
      check(i.status === "CREATED", "INVALID_INTENT_STATE", 409);
      const c = await t.get(challengePath(id));
      if (i.kind === "DEMO_MOVEMENT" && i.movement !== "EXTERNAL_TRANSFER") {
        const request = await t.get("moneyRequests/" + id);
        if (request?.demo === true && request.ownerUid === a.uid)
          t.set("moneyRequests/" + id, { ...request, status: "CANCELLED" });
      }
      t.set("transactionIntents/" + id, { ...i, status: "CANCELLED" });
      if (c) t.set(challengePath(id), { ...c, status: "CANCELLED" });
      return { status: "CANCELLED" };
    });
  }
}
