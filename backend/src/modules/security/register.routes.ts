import type { Express } from "express";
import { z } from "zod";
import type { AppDependencies } from "../../core/http.js";
import { digest, generateAccountNumber } from "../../shared/crypto.js";
import { check, key } from "../../shared/errors.js";
import { normalizeSearchText, phoneSearchTokens } from "../../shared/search.js";
import { registrationIdentity } from "./email-otp.routes.js";
import { reserveContactPhone } from "../profile/contact-phone-reservations.js";

const profileSchema = z.object({
  fullName: z.string().trim().min(2).max(120),
  phoneNumber: z.string().trim().regex(/^(?:0[0-9]{9}|\+84[0-9]{9})$/)
    .transform((phone) => phone.startsWith("0") ? "+84" + phone.slice(1) : phone),
  nationalIdNumber: z.string().trim().regex(/^\d{12}$/),
}).strict();

export function registerSelfRegistrationRoutes(app: Express, d: AppDependencies) {
  app.post("/auth/register/complete", async (req, res) => {
    check(d.grantCustomerRole, "REGISTRATION_UNAVAILABLE", 503);
    const identity = await registrationIdentity(req, d);
    check(identity.emailVerified && identity.email, "EMAIL_NOT_VERIFIED", 403);
    check(identity.role === undefined || identity.role === "CUSTOMER", "FORBIDDEN", 403);
    const { uid } = identity;
    key(uid);
    const email = identity.email.toLowerCase();
    const { fullName, phoneNumber, nationalIdNumber } = profileSchema.parse(req.body);
    const phoneMasked = "••••" + phoneNumber.slice(-4);
    const accountId = "acct_" + digest(["checking", uid]).slice(0, 24);
    const accountNumber = generateAccountNumber(accountId);
    const completedAt = d.bank.now();

    const status = await d.bank.store.run(async (t) => {
      const user = await t.get("users/" + uid);
      if (user) {
        check(user.role === "CUSTOMER" && user.registrationMethod === "FIREBASE_EMAIL" &&
          ["ONBOARDING", "ACTIVE"].includes(user.status) && !(user.status === "ACTIVE" && user.active !== true),
          "REGISTRATION_ALREADY_COMPLETE", 409);
        return user.status as string;
      }
      check(identity.role === undefined, "FORBIDDEN", 403);
      const idPath = "registrationNationalIds/" + digest(nationalIdNumber);
      const nationalId = await t.get(idPath);
      const existing = await t.list("customers", { nationalIdNumber }, { limit: 1 });
      check(!nationalId && existing.length === 0, "NATIONAL_ID_EXISTS", 409);
      await reserveContactPhone(t, uid, phoneNumber, completedAt);
      t.set(idPath, { uid, createdAt: completedAt });
        t.set("users/" + uid, {
          uid,
          role: "CUSTOMER",
          active: false,
          status: "ONBOARDING",
          selfRegistered: true,
          registrationMethod: "FIREBASE_EMAIL",
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

      d.bank.audit(t, { uid, role: "CUSTOMER", permissions: [] }, "CUSTOMER_SELF_REGISTER", uid);
      return "ONBOARDING";
    });
    // A failed claim update can be retried against the same committed profile.
    if (identity.role === undefined) await d.grantCustomerRole(uid);
    res.json({ uid, accountId, accountNumber, status });
  });
}
