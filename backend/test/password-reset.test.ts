import request from "supertest";
import { expect, it } from "vitest";
import { createApp } from "../src/app.js";
import { authEmailContent, type AuthEmail } from "../src/modules/security/auth-email.js";
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

function otpFixture() {
  const store = new MemoryStore();
  let now = 1_000_000;
  const bank = new Banking(store, { otpSecret: "email-otp-test", now: () => now });
  const mail: AuthEmail[] = [];
  const updatedPasswords: string[] = [];
  let verified = false;
  const app = createApp({
    bank, evidence: new Evidence(bank), payments: new Payments(bank),
    authenticate: async () => ({ uid: "alice", role: "CUSTOMER", permissions: [] }),
    sendAuthEmail: async (email) => { mail.push(email); },
    verifyRegistrationIdentity: async (token) => ({
      uid: token, email: "alice@example.com", emailVerified: verified,
    }),
    markRegistrationEmailVerified: async (uid, email) => {
      expect(uid).toBe("alice");
      expect(email).toBe("alice@example.com");
      verified = true;
    },
    lookupPasswordResetUser: async (email) => email === "alice@example.com" ? "alice" : null,
    updatePasswordAfterEmailOtp: async (uid, password, matchesEmail) => {
      expect(uid).toBe("alice");
      expect(matchesEmail("alice@example.com")).toBe(true);
      expect(matchesEmail("someone-else@example.com")).toBe(false);
      updatedPasswords.push(password);
    },
  });
  return { app, store, mail, updatedPasswords, advance: (ms: number) => { now += ms; } };
}

it("sends the registration OTP by email and binds it to the Firebase identity", async () => {
  const { app, mail, store, advance } = otpFixture();
  const send = () => request(app).post("/auth/register/email-otp").set("Authorization", "Bearer alice");
  const verify = (uid: string, challengeId: string, code: string) => request(app)
    .post("/auth/register/email-otp/verify").set("Authorization", "Bearer " + uid)
    .send({ challengeId, code });
  const { body } = await send().expect(200);
  expect(body).toEqual({ challengeId: mail[0].id, resendAfterSeconds: 30 });
  expect(mail[0]).toMatchObject({ purpose: "REGISTRATION", code: /^\d{6}$/ });
  const challenge = await store.run((tx) => tx.get("authEmailChallenges/" + body.challengeId));
  expect(challenge?.code).toBeUndefined();
  expect(challenge?.codeHash).toMatch(/^[a-f0-9]{64}$/);
  await verify("someone-else", body.challengeId, mail[0].code).expect(400);
  await send().expect(429);
  advance(31_000);
  const next = await send().expect(200);
  await verify("alice", body.challengeId, mail[0].code).expect(410);
  await verify("alice", next.body.challengeId, mail[1].code).expect(200, { verified: true });
  await send().expect(200, { verified: true });
  expect(mail).toHaveLength(2);
});

it("only issues a single-use reset token after the correct email OTP", async () => {
  const { app, mail, updatedPasswords } = otpFixture();
  const send = (email: string) => request(app).post("/auth/password-reset/email-otp").send({ email });
  const known = await send("alice@example.com").expect(200);
  const unknown = await send("unknown@example.com").expect(200);
  expect(Object.keys(known.body)).toEqual(Object.keys(unknown.body));
  expect(mail).toHaveLength(1);
  expect(mail[0].purpose).toBe("PASSWORD_RESET");
  const verify = (challengeId: string, code: string) => request(app)
    .post("/auth/password-reset/email-otp/verify").send({ challengeId, code });
  await verify(unknown.body.challengeId, mail[0].code).expect(400);
  const wrong = mail[0].code === "000000" ? "111111" : "000000";
  await verify(known.body.challengeId, wrong).expect(400);
  const { body } = await verify(known.body.challengeId, mail[0].code).expect(200);
  await verify(known.body.challengeId, mail[0].code).expect(400);
  const confirm = () => request(app).post("/auth/password-reset/email-otp/confirm")
    .send({ resetToken: body.resetToken, password: "NewPassword123" });
  await confirm().expect(200, { updated: true });
  await confirm().expect(410);
  expect(updatedPasswords).toEqual(["NewPassword123"]);
});

it("locks repeated guesses and expires unused OTP codes", async () => {
  const { app, mail, advance } = otpFixture();
  const send = () => request(app).post("/auth/password-reset/email-otp").send({ email: "alice@example.com" });
  const verify = (challengeId: string, code: string) => request(app)
    .post("/auth/password-reset/email-otp/verify").send({ challengeId, code });
  const first = await send().expect(200);
  const wrong = mail[0].code === "000000" ? "111111" : "000000";
  for (let attempt = 0; attempt < 4; attempt++) await verify(first.body.challengeId, wrong).expect(400);
  await verify(first.body.challengeId, wrong).expect(429);
  await verify(first.body.challengeId, mail[0].code).expect(429);
  advance(31_000);
  const next = await send().expect(200);
  advance(300_000);
  await verify(next.body.challengeId, mail[1].code).expect(410);
});

it("renders distinct Vietnamese email subjects and shows the OTP in both formats", () => {
  const register = authEmailContent("REGISTRATION", "042817");
  const reset = authEmailContent("PASSWORD_RESET", "042817");
  expect(register.subject).not.toBe(reset.subject);
  for (const email of [register, reset]) {
    expect(email.html).toContain("Nimbus Bank");
    expect(email.html).toContain("042817");
    expect(email.text).toContain("042817");
  }
});
