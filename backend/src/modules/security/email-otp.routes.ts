import { createHmac, randomInt, randomUUID, timingSafeEqual } from "node:crypto";
import type { Express, Request } from "express";
import { z } from "zod";
import type { AppDependencies } from "../../core/http.js";
import { check, Fault } from "../../shared/errors.js";
import { rate } from "./intent.js";

export type EmailOtpPurpose = "REGISTRATION" | "PASSWORD_RESET";

const lifetime = 300_000;
const cooldown = 30_000;
const maxAttempts = 5;
const emailInput = z.object({ email: z.string().trim().email().max(254).toLowerCase() }).strict();
const idInput = z.uuid();
const codeInput = z.string().regex(/^\d{6}$/);
const challengeInput = z.object({ challengeId: idInput, code: codeInput }).strict();
const passwordInput = z.object({ resetToken: idInput, password: z.string().min(8).max(128) }).strict();

export async function registrationIdentity(req: Request, d: AppDependencies) {
  const token = req.get("authorization")?.match(/^Bearer (.+)$/)?.[1];
  check(token, "UNAUTHENTICATED", 401);
  check(d.verifyRegistrationIdentity, "REGISTRATION_UNAVAILABLE", 503);
  return d.verifyRegistrationIdentity(token).catch(() => {
    throw new Fault("UNAUTHENTICATED", 401);
  });
}

export function registerEmailOtpRoutes(app: Express, d: AppDependencies) {
  const hash = (parts: string[]) => createHmac("sha256", d.bank.options.otpSecret)
    .update(JSON.stringify(parts)).digest("hex");
  const pathFor = (id: string) => "authEmailChallenges/" + id;

  async function issue(req: Request, purpose: EmailOtpPurpose, email: string, uid: string | null) {
    const sender = d.sendAuthEmail;
    check(sender, "EMAIL_OTP_UNAVAILABLE", 503);
    const id = randomUUID();
    const code = randomInt(1_000_000).toString().padStart(6, "0");
    const now = d.bank.now();
    const emailKey = hash(["auth-email", email]);
    const currentPath = "authEmailCurrent/" + hash([purpose, emailKey]);

    await d.bank.store.run(async (t) => {
      const current = await t.get(currentPath);
      check(!current || now >= current.resendAt, "OTP_RESEND_TOO_SOON", 429);
      await rate(t, "auth-email-ip", req.ip ?? "unknown", now, 20, lifetime);
      await rate(t, "auth-email-address", emailKey, now, 5, lifetime);
      t.set(currentPath, { id, resendAt: now + cooldown });
      t.set(pathFor(id), {
        purpose, uid, emailKey, currentPath, codeHash: hash([purpose, id, code]),
        expiresAt: now + lifetime, attempts: 0, consumed: false,
      });
    });
    if (uid !== null) {
      try {
        await sender({ to: email, purpose, code, id });
      } catch {
        await d.bank.store.run(async (t) => {
          const challenge = await t.get(pathFor(id));
          if (challenge) t.set(pathFor(id), { ...challenge, consumed: true });
        });
        console.warn("Authentication email delivery failed", { purpose, challengeId: id });
        if (purpose === "REGISTRATION") throw new Fault("EMAIL_OTP_DELIVERY_FAILED", 503);
      }
    }
    return { challengeId: id, resendAfterSeconds: cooldown / 1000 };
  }

  async function consume(purpose: EmailOtpPurpose, id: string, code: string, identity?: { uid: string; email: string }) {
    const resetToken = randomUUID();
    const outcome = await d.bank.store.run(async (t) => {
      const challenge = await t.get(pathFor(id));
      if (!challenge || challenge.purpose !== purpose || challenge.consumed ||
          (identity && (challenge.uid !== identity.uid || challenge.emailKey !== hash(["auth-email", identity.email])))) {
        return { error: "INVALID_OTP" };
      }
      if (d.bank.now() >= challenge.expiresAt) return { error: "OTP_EXPIRED" };
      if (challenge.attempts >= maxAttempts) return { error: "OTP_LOCKED" };
      const current = await t.get(challenge.currentPath);
      if (current?.id !== id) return { error: "OTP_EXPIRED" };
      const attempts = challenge.attempts + 1;
      const matches = timingSafeEqual(Buffer.from(challenge.codeHash, "hex"), Buffer.from(hash([purpose, id, code]), "hex"));
      if (!matches || !challenge.uid) {
        t.set(pathFor(id), { ...challenge, attempts });
        return { error: attempts >= maxAttempts ? "OTP_LOCKED" : "INVALID_OTP" };
      }
      t.set(pathFor(id), { ...challenge, attempts, consumed: true });
      if (purpose === "PASSWORD_RESET") {
        t.set("authPasswordResetProofs/" + hash(["reset-proof", resetToken]), {
          uid: challenge.uid, emailKey: challenge.emailKey, currentPath: challenge.currentPath,
          challengeId: id, expiresAt: d.bank.now() + lifetime, consumed: false,
        });
      }
      return resetToken;
    });
    if (typeof outcome !== "string") {
      const error = outcome.error ?? "INVALID_OTP";
      throw new Fault(error, error === "OTP_LOCKED" ? 429 : error === "OTP_EXPIRED" ? 410 : 400);
    }
    return outcome;
  }

  app.post("/auth/register/email-otp", async (req, res) => {
    const identity = await registrationIdentity(req, d);
    check(identity.email && identity.role === undefined, "FORBIDDEN", 403);
    if (identity.emailVerified) return res.json({ verified: true });
    return res.json(await issue(req, "REGISTRATION", identity.email.toLowerCase(), identity.uid));
  });

  app.post("/auth/register/email-otp/verify", async (req, res) => {
    const identity = await registrationIdentity(req, d);
    check(identity.email && identity.role === undefined, "FORBIDDEN", 403);
    check(d.markRegistrationEmailVerified, "REGISTRATION_UNAVAILABLE", 503);
    const { challengeId, code } = challengeInput.parse(req.body);
    if (!identity.emailVerified) {
      await consume("REGISTRATION", challengeId, code, { uid: identity.uid, email: identity.email.toLowerCase() });
      await d.markRegistrationEmailVerified(identity.uid, identity.email);
    }
    return res.json({ verified: true });
  });

  app.post("/auth/password-reset/email-otp", async (req, res) => {
    const { email } = emailInput.parse(req.body);
    check(d.sendAuthEmail && d.lookupPasswordResetUser, "EMAIL_OTP_UNAVAILABLE", 503);
    const uid = await d.lookupPasswordResetUser(email);
    return res.json(await issue(req, "PASSWORD_RESET", email, uid));
  });

  app.post("/auth/password-reset/email-otp/verify", async (req, res) => {
    const { challengeId, code } = challengeInput.parse(req.body);
    const resetToken = await consume("PASSWORD_RESET", challengeId, code);
    return res.json({ resetToken });
  });

  app.post("/auth/password-reset/email-otp/confirm", async (req, res) => {
    check(d.updatePasswordAfterEmailOtp, "EMAIL_OTP_UNAVAILABLE", 503);
    const { resetToken, password } = passwordInput.parse(req.body);
    const path = "authPasswordResetProofs/" + hash(["reset-proof", resetToken]);
    const { uid, emailKey } = await d.bank.store.run(async (t) => {
      const proof = await t.get(path);
      check(proof && !proof.consumed && d.bank.now() < proof.expiresAt, "RESET_TOKEN_EXPIRED", 410);
      const current = await t.get(proof.currentPath);
      check(current?.id === proof.challengeId, "RESET_TOKEN_EXPIRED", 410);
      t.set(path, { ...proof, consumed: true });
      return { uid: String(proof.uid), emailKey: String(proof.emailKey) };
    });
    await d.updatePasswordAfterEmailOtp(uid, password, (currentEmail) =>
      hash(["auth-email", currentEmail.toLowerCase()]) === emailKey);
    return res.json({ updated: true });
  });
}
