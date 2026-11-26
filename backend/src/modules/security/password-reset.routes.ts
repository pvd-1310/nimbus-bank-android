import {
  createHmac,
  randomInt,
  randomUUID,
  timingSafeEqual,
} from "node:crypto";
import type { Express } from "express";
import { z } from "zod";
import type { AppDependencies } from "../../core/http.js";
import { digest } from "../../shared/crypto.js";
import { check, Fault } from "../../shared/errors.js";
import { rate } from "./intent.js";

const requestSchema = z.object({
  email: z.string().trim().email().max(254),
});

const verifySchema = z.object({
  challengeId: z.string().trim().min(1).max(100),
  code: z.string().trim().regex(/^\d{6}$/),
});

const confirmSchema = z.object({
  challengeId: z.string().trim().min(1).max(100),
  resetToken: z.string().trim().min(1).max(200),
  newPassword: z.string(),
});

const OTP_LIFETIME_MS = 180_000;
const RESET_LIFETIME_MS = 300_000;
const RATE_WINDOW_MS = 300_000;
const challengePath = (id: string) => "passwordResetChallenges/" + id;

function hashOtp(secret: string, challengeId: string, nonce: string, code: string) {
  return createHmac("sha256", secret)
    .update(`${challengeId}:${nonce}:${code}`)
    .digest("hex");
}

function safeEqualHex(a: string, b: string) {
  const bufA = Buffer.from(a);
  const bufB = Buffer.from(b);
  return bufA.length === bufB.length && timingSafeEqual(bufA, bufB);
}

export function registerPasswordResetRoutes(app: Express, d: AppDependencies) {
  app.post("/auth/password-reset/request", async (req, res, next) => {
    try {
      check(
        d.lookupUserByEmail && d.updateUserPassword,
        "PASSWORD_RESET_UNAVAILABLE",
        503,
      );
      const { email } = requestSchema.parse(req.body);
      const normalizedEmail = email.toLowerCase();
      const now = d.bank.now();
      await d.bank.store.run(async (t) => {
        await rate(
          t,
          "password-reset-ip",
          req.ip || "unknown",
          now,
          20,
          RATE_WINDOW_MS,
        );
        await rate(
          t,
          "password-reset-email",
          digest(normalizedEmail),
          now,
          5,
          RATE_WINDOW_MS,
        );
      });

      const authUser = await d.lookupUserByEmail(normalizedEmail);
      check(authUser && !authUser.disabled, "EMAIL_NOT_FOUND", 404);

      const challengeId = randomUUID();
      const nonce = randomUUID();
      const code = String(randomInt(1_000_000)).padStart(6, "0");
      const expiresAt = now + OTP_LIFETIME_MS;

      const phoneMasked = await d.bank.store.run(async (t) => {
        const user = await t.get("users/" + authUser.uid);
        check(
          user && (user.status === "ACTIVE" || user.active === true),
          "EMAIL_NOT_FOUND",
          404,
        );
        t.set(challengePath(challengeId), {
          id: challengeId,
          uid: authUser.uid,
          email: normalizedEmail,
          method: "DEMO_DISPLAY_CODE",
          nonce,
          otpHash: hashOtp(d.bank.options.otpSecret, challengeId, nonce, code),
          attempts: 0,
          status: "AWAITING_CODE",
          createdAt: now,
          expiresAt,
        });
        return typeof user.phoneMasked === "string" ? user.phoneMasked : "";
      });

      res.json({
        challengeId,
        method: "DEMO_DISPLAY_CODE",
        code,
        phoneMasked,
        expiresAt,
      });
    } catch (error) {
      next(error);
    }
  });

  app.post("/auth/password-reset/verify-otp", async (req, res, next) => {
    try {
      const { challengeId, code } = verifySchema.parse(req.body);
      const now = d.bank.now();
      const result = await d.bank.store.run(async (t) => {
        const c = await t.get(challengePath(challengeId));
        check(c, "CHALLENGE_NOT_FOUND", 404);
        check(c.attempts < 5 && c.status !== "LOCKED", "OTP_LOCKED", 429);
        check(now < c.expiresAt, "OTP_EXPIRED", 410);
        check(c.status === "AWAITING_CODE", "OTP_USED", 409);

        const expected = hashOtp(
          d.bank.options.otpSecret,
          challengeId,
          String(c.nonce),
          code,
        );
        const match = safeEqualHex(expected, String(c.otpHash));
        const attempts = Number(c.attempts ?? 0) + 1;

        if (!match) {
          const status = attempts >= 5 ? "LOCKED" : "AWAITING_CODE";
          t.set(challengePath(challengeId), { ...c, attempts, status });
          return { status, resetToken: null, resetExpiresAt: 0 };
        }

        const resetToken = randomUUID();
        const resetExpiresAt = now + RESET_LIFETIME_MS;
        t.set(challengePath(challengeId), {
          ...c,
          attempts,
          status: "OTP_VERIFIED",
          resetTokenHash: digest(resetToken),
          verifiedAt: now,
          resetExpiresAt,
        });
        return { status: "OTP_VERIFIED", resetToken, resetExpiresAt };
      });

      check(result.status !== "LOCKED", "OTP_LOCKED", 429);
      check(result.status === "OTP_VERIFIED" && result.resetToken, "INVALID_OTP");

      res.json({
        verified: true,
        resetToken: result.resetToken,
        expiresAt: result.resetExpiresAt,
      });
    } catch (error) {
      next(error);
    }
  });

  app.post("/auth/password-reset/confirm", async (req, res, next) => {
    try {
      check(
        d.lookupUserByEmail && d.updateUserPassword,
        "PASSWORD_RESET_UNAVAILABLE",
        503,
      );
      const { challengeId, resetToken, newPassword } = confirmSchema.parse(
        req.body,
      );
      check(
        newPassword.length >= 8 && newPassword.length <= 128,
        "WEAK_PASSWORD",
        400,
      );

      const now = d.bank.now();
      const uid = await d.bank.store.run(async (t) => {
        const c = await t.get(challengePath(challengeId));
        check(c, "CHALLENGE_NOT_FOUND", 404);
        check(c.status === "OTP_VERIFIED", "RESET_TOKEN_INVALID", 403);
        check(now < Number(c.resetExpiresAt ?? 0), "RESET_TOKEN_EXPIRED", 410);
        check(
          typeof c.resetTokenHash === "string" &&
            safeEqualHex(digest(resetToken), c.resetTokenHash),
          "RESET_TOKEN_INVALID",
          403,
        );
        t.set(challengePath(challengeId), {
          ...c,
          status: "COMPLETING",
        });
        return String(c.uid);
      });

      try {
        await d.updateUserPassword(uid, newPassword);
      } catch (err) {
        await d.bank.store.run(async (t) => {
          const c = await t.get(challengePath(challengeId));
          if (c && c.status === "COMPLETING") {
            t.set(challengePath(challengeId), { ...c, status: "OTP_VERIFIED" });
          }
        });
        throw err instanceof Fault ? err : new Fault("PASSWORD_UPDATE_FAILED", 500);
      }

      await d.bank.store.run(async (t) => {
        const c = await t.get(challengePath(challengeId));
        if (c) {
          t.set(challengePath(challengeId), {
            ...c,
            status: "COMPLETED",
            completedAt: d.bank.now(),
          });
        }
        const user = await t.get("users/" + uid);
        const actorRole = user?.role === "OFFICER" ? "OFFICER" : "CUSTOMER";
        d.bank.audit(
          t,
          { uid, role: actorRole, permissions: [] },
          "PASSWORD_RESET_COMPLETED",
          challengeId,
          String(res.locals.requestId ?? ""),
        );
        d.bank.notifications.emit(t, {
          id: `pwd_reset_${challengeId}`,
          ownerUid: uid,
          type: "SECURITY",
          title: "Mật khẩu vừa được thay đổi",
          body: "Mật khẩu tài khoản Nimbus Bank của bạn vừa được đặt lại. Nếu không phải bạn, hãy liên hệ hỗ trợ ngay.",
          refId: challengeId,
        });
      });

      res.json({ updated: true });
    } catch (error) {
      next(error);
    }
  });
}
