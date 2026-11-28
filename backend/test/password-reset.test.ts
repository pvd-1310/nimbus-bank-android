import request from "supertest";
import { it } from "vitest";
import { createApp } from "../src/app.js";
import { Banking } from "../src/core/banking.js";
import { Evidence } from "../src/modules/ekyc/ekyc.service.js";
import { Payments } from "../src/modules/payments/payment.service.js";
import { MemoryStore } from "./support/memory-store.js";

it("does not expose the legacy authentication OTP endpoints", async () => {
  const bank = new Banking(new MemoryStore(), { otpSecret: "test" });
  const app = createApp({
    bank,
    evidence: new Evidence(bank),
    payments: new Payments(bank),
    authenticate: async () => ({ uid: "alice", role: "CUSTOMER", permissions: [] }),
  });
  for (const route of [
    "/auth/password-reset/request", "/auth/password-reset/verify-otp", "/auth/password-reset/confirm",
    "/auth/register/request", "/auth/register/confirm",
  ]) {
    await request(app).post(route).send({ email: "alice@example.com" }).expect(401);
  }
});
