import request from "supertest";
import { expect, it } from "vitest";
import { createApp } from "../src/app.js";
import { Banking } from "../src/core/banking.js";
import { Evidence } from "../src/modules/ekyc/ekyc.service.js";
import { Payments } from "../src/modules/payments/payment.service.js";
import { MemoryStore } from "./support/memory-store.js";

async function passwordResetFixture() {
  const s = new MemoryStore();
  let updatedPassword: string | null = null;
  await s.run(async (t) => {
    t.set("users/alice", {
      role: "CUSTOMER",
      active: true,
      status: "ACTIVE",
      contactEmail: "alice@example.com",
      phoneMasked: "***4125",
    });
    t.set("users/disabled", {
      role: "CUSTOMER",
      active: false,
      status: "SUSPENDED",
      contactEmail: "disabled@example.com",
    });
  });

  const bank = new Banking(s, { otpSecret: "reset-secret", deliver: async () => {} });
  const app = createApp({
    bank,
    evidence: new Evidence(bank, undefined),
    payments: new Payments(bank, undefined),
    authenticate: async () => {
      throw new Error("unauthenticated");
    },
    lookupUserByEmail: async (email) => {
      if (email === "alice@example.com") return { uid: "alice", disabled: false };
      if (email === "disabled@example.com") return { uid: "disabled", disabled: true };
      return null;
    },
    updateUserPassword: async (uid, password) => {
      if (uid === "alice") updatedPassword = password;
    },
  });

  return { app, getUpdatedPassword: () => updatedPassword };
}

it("rejects unknown or disabled emails on password reset request", async () => {
  const { app } = await passwordResetFixture();

  await request(app)
    .post("/auth/password-reset/request")
    .send({ email: "nobody@example.com" })
    .expect(404)
    .expect(({ body }) => expect(body.error).toBe("EMAIL_NOT_FOUND"));

  await request(app)
    .post("/auth/password-reset/request")
    .send({ email: "disabled@example.com" })
    .expect(404)
    .expect(({ body }) => expect(body.error).toBe("EMAIL_NOT_FOUND"));
});

it("completes the full OTP password reset flow and prevents token replay", async () => {
  const { app, getUpdatedPassword } = await passwordResetFixture();

  const reqRes = await request(app)
    .post("/auth/password-reset/request")
    .send({ email: "Alice@Example.com" })
    .expect(200);

  expect(reqRes.body.method).toBe("DEMO_DISPLAY_CODE");
  expect(reqRes.body.code).toMatch(/^\d{6}$/);
  expect(reqRes.body.phoneMasked).toBe("***4125");
  const { challengeId, code } = reqRes.body;

  const wrongCode = code === "000000" ? "111111" : "000000";
  await request(app)
    .post("/auth/password-reset/verify-otp")
    .send({ challengeId, code: wrongCode })
    .expect(400)
    .expect(({ body }) => expect(body.error).toBe("INVALID_OTP"));

  const verifyRes = await request(app)
    .post("/auth/password-reset/verify-otp")
    .send({ challengeId, code })
    .expect(200);

  expect(verifyRes.body.verified).toBe(true);
  expect(typeof verifyRes.body.resetToken).toBe("string");
  const { resetToken } = verifyRes.body;

  await request(app)
    .post("/auth/password-reset/confirm")
    .send({ challengeId, resetToken, newPassword: "short" })
    .expect(400)
    .expect(({ body }) => expect(body.error).toBe("WEAK_PASSWORD"));

  await request(app)
    .post("/auth/password-reset/confirm")
    .send({ challengeId, resetToken, newPassword: "NewSecurePassword123!" })
    .expect(200)
    .expect(({ body }) => expect(body.updated).toBe(true));

  expect(getUpdatedPassword()).toBe("NewSecurePassword123!");

  await request(app)
    .post("/auth/password-reset/confirm")
    .send({ challengeId, resetToken, newPassword: "AnotherPassword123!" })
    .expect(403)
    .expect(({ body }) => expect(body.error).toBe("RESET_TOKEN_INVALID"));
});
