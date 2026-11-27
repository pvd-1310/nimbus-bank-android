import {
  createHmac,
  randomInt,
  randomUUID,
  timingSafeEqual,
} from "node:crypto";
import type { Express } from "express";
import { z } from "zod";
import type { AppDependencies } from "../../core/http.js";
import { digest, generateAccountNumber } from "../../shared/crypto.js";
import { check, Fault } from "../../shared/errors.js";
import { normalizeSearchText, phoneSearchTokens } from "../../shared/search.js";
import { releaseContactPhone, reserveContactPhone } from "../profile/contact-phone-reservations.js";
import { rate } from "./intent.js";

const requestSchema = z
  .object({
    email: z.string().trim().email().max(254),
    password: z.string().min(8).max(128),
    fullName: z.string().trim().min(2).max(120),
    phoneNumber: z
      .string()
      .trim()
      .regex(/^(?:0[0-9]{9}|\+84[0-9]{9})$/)
      .transform((phone) => (phone.startsWith("0") ? "+84" + phone.slice(1) : phone)),
    nationalIdNumber: z.string().trim().regex(/^\d{12}$/),
  })
  .strict();

const confirmSchema = z
  .object({
    challengeId: z.string().trim().min(1).max(100),
    code: z.string().trim().regex(/^\d{6}$/),
    password: z.string().min(8).max(128),
  })
  .strict();

const OTP_LIFETIME_MS = 180_000;
const RATE_WINDOW_MS = 300_000;
const challengePath = (id: string) => "registrationChallenges/" + id;

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

export function registerSelfRegistrationRoutes(app: Express, d: AppDependencies) {
  app.post("/auth/register/request", async (req, res, next) => {
    try {
      check(
        d.lookupUserByEmail && d.createCustomerIdentity,
        "REGISTRATION_UNAVAILABLE",
        503,
      );
      const input = requestSchema.parse(req.body);
      const normalizedEmail = input.email.toLowerCase();
      const now = d.bank.now();

      await d.bank.store.run(async (t) => {
        await rate(t, "register-ip", req.ip || "unknown", now, 20, RATE_WINDOW_MS);
        await rate(t, "register-email", digest(normalizedEmail), now, 5, RATE_WINDOW_MS);
      });

      const existingAuth = await d.lookupUserByEmail(normalizedEmail);
      check(!existingAuth, "EMAIL_ALREADY_EXISTS", 409);

      const challengeId = randomUUID();
      const nonce = randomUUID();
      const code = String(randomInt(1_000_000)).padStart(6, "0");
      const expiresAt = now + OTP_LIFETIME_MS;
      const phoneMasked = "••••" + input.phoneNumber.slice(-4);

      await d.bank.store.run(async (t) => {
        const phoneReservation = await t.get("contactPhoneReservations/" + digest(input.phoneNumber));
        check(!phoneReservation || phoneReservation.status === "RELEASED", "PHONE_ALREADY_EXISTS", 409);
        const existingPhoneUsers = await t.list("users", { contactPhone: input.phoneNumber }, { limit: 1 });
        check(existingPhoneUsers.length === 0, "PHONE_ALREADY_EXISTS", 409);

        const existingNationalId = await t.list(
          "customers",
          { nationalIdNumber: input.nationalIdNumber },
          { limit: 1 },
        );
        check(existingNationalId.length === 0, "NATIONAL_ID_EXISTS", 409);

        t.set(challengePath(challengeId), {
          id: challengeId,
          email: normalizedEmail,
          fullName: input.fullName,
          phoneNumber: input.phoneNumber,
          phoneMasked,
          nationalIdNumber: input.nationalIdNumber,
          method: "DEMO_DISPLAY_CODE",
          nonce,
          otpHash: hashOtp(d.bank.options.otpSecret, challengeId, nonce, code),
          attempts: 0,
          status: "AWAITING_CODE",
          createdAt: now,
          expiresAt,
        });
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

  app.post("/auth/register/confirm", async (req, res, next) => {
    try {
      check(
        d.lookupUserByEmail && d.createCustomerIdentity,
        "REGISTRATION_UNAVAILABLE",
        503,
      );
      const { challengeId, code, password } = confirmSchema.parse(req.body);
      const now = d.bank.now();

      const verifiedChallenge = await d.bank.store.run(async (t) => {
        const c = await t.get(challengePath(challengeId));
        check(c, "CHALLENGE_NOT_FOUND", 404);
        check(Number(c.attempts ?? 0) < 5 && c.status !== "LOCKED", "OTP_LOCKED", 429);
        check(now < Number(c.expiresAt ?? 0), "OTP_EXPIRED", 410);
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
          return { ok: false as const, status };
        }

        const uid = "customer_" + digest(["self", challengeId]).slice(0, 40);
        await reserveContactPhone(t, uid, String(c.phoneNumber), now);
        const existingNationalId = await t.list(
          "customers",
          { nationalIdNumber: String(c.nationalIdNumber) },
          { limit: 1 },
        );
        if (existingNationalId.length > 0) {
          await releaseContactPhone(t, uid, String(c.phoneNumber), now);
          throw new Fault("NATIONAL_ID_EXISTS", 409);
        }

        t.set(challengePath(challengeId), {
          ...c,
          attempts,
          status: "COMPLETING",
          verifiedAt: now,
          uid,
        });
        return {
          ok: true as const,
          uid,
          email: String(c.email),
          fullName: String(c.fullName),
          phoneNumber: String(c.phoneNumber),
          phoneMasked: String(c.phoneMasked ?? ""),
          nationalIdNumber: String(c.nationalIdNumber),
        };
      });

      if (!verifiedChallenge.ok) {
        check(verifiedChallenge.status !== "LOCKED", "OTP_LOCKED", 429);
        throw new Fault("INVALID_OTP", 400);
      }

      const { uid, email, fullName, phoneNumber, phoneMasked, nationalIdNumber } = verifiedChallenge;
      const existingAuth = await d.lookupUserByEmail(email);
      if (existingAuth) {
        await d.bank.store.run(async (t) => {
          const c = await t.get(challengePath(challengeId));
          if (c) t.set(challengePath(challengeId), { ...c, status: "FAILED" });
          await releaseContactPhone(t, uid, phoneNumber, d.bank.now());
        });
        throw new Fault("EMAIL_ALREADY_EXISTS", 409);
      }

      try {
        await d.createCustomerIdentity(uid, email, password);
      } catch {
        await d.bank.store.run(async (t) => {
          const c = await t.get(challengePath(challengeId));
          if (c && c.status === "COMPLETING") {
            t.set(challengePath(challengeId), { ...c, status: "AWAITING_CODE" });
          }
          await releaseContactPhone(t, uid, phoneNumber, d.bank.now());
        });
        throw new Fault("REGISTRATION_IDENTITY_FAILED", 409);
      }

      const accountId = "acct_" + digest(["checking", uid]).slice(0, 24);
      const accountNumber = generateAccountNumber(accountId);
      const completedAt = d.bank.now();

      await d.bank.store.run(async (t) => {
        t.set("users/" + uid, {
          uid,
          role: "CUSTOMER",
          active: false,
          status: "ONBOARDING",
          selfRegistered: true,
          fullName,
          emailMasked: email.replace(/^(.).*(@.*)$/, "$1***$2"),
          contactEmail: email,
          phoneMasked,
          contactPhone: phoneNumber,
          contactPhoneSearchTokens: phoneSearchTokens(phoneNumber),
          preferences: { notificationsEnabled: true, language: "vi" },
          primaryCheckingAccountId: accountId,
          createdAt: completedAt,
          createdBy: uid,
        });
        t.set("customers/" + uid, {
          uid,
          uidRef: uid,
          customerNo: null,
          nationalIdNumber,
          dateOfBirth: null,
          gender: null,
          nationality: null,
          identityDocumentExpiry: null,
          address: null,
          kycStatus: "NOT_SUBMITTED",
          status: "ONBOARDING",
          createdBy: uid,
        });
        t.set("accounts/" + accountId, {
          accountId,
          accountNumber,
          ownerUid: uid,
          type: "CHECKING",
          ownerNameSearch: normalizeSearchText(fullName),
          currency: "VND",
          status: "INACTIVE",
          availableMinor: 0,
          bookedMinor: 0,
          balanceMinor: 0,
          version: 0,
          createdAt: completedAt,
          createdBy: uid,
        });
        t.set("checkingDetails/" + accountId, {
          accountId,
          overdraftPolicy: "NONE",
        });
        const c = await t.get(challengePath(challengeId));
        if (c) {
          t.set(challengePath(challengeId), {
            ...c,
            status: "COMPLETED",
            completedAt,
          });
        }
        d.bank.audit(
          t,
          { uid, role: "CUSTOMER", permissions: [] },
          "CUSTOMER_SELF_REGISTER",
          uid,
          challengeId,
        );
      });

      res.json({
        uid,
        accountId,
        accountNumber,
        status: "ONBOARDING",
      });
    } catch (error) {
      next(error);
    }
  });
}
