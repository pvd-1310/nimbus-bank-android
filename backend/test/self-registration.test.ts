import request from "supertest";
import { expect, it } from "vitest";
import { createApp } from "../src/app.js";
import { Banking } from "../src/core/banking.js";
import { Evidence, type EvidenceProvider } from "../src/modules/ekyc/ekyc.service.js";
import { Payments } from "../src/modules/payments/payment.service.js";
import type { FaceService } from "../src/modules/security/face.js";
import { MemoryStore } from "./support/memory-store.js";

function registrationFixture() {
  const s = new MemoryStore();
  const authUsers = new Map<string, { uid: string; email: string; password: string; disabled: boolean }>();

  const provider: EvidenceProvider = {
    upload: async (image) => ({ url: `https://s3.local/${image.key}`, fields: {} }),
    verify: async () => "v1",
    download: async (image) => `https://s3.local/${image.key}?v=${image.versionId}`,
    read: async () => Buffer.from([0xff, 0xd8, 0xff, 0xd9]),
  };

  const face = {
    enroll: async (uid: string, requestId: string) => ({
      uid,
      requestId,
      modelVersion: "v1-test",
      encryptedEmbedding: "enc",
      createdAt: 1,
    }),
  } as unknown as FaceService;

  const bank = new Banking(s, { otpSecret: "register-secret", face });
  const app = createApp({
    bank,
    evidence: new Evidence(bank, provider, undefined, "DEMO_SELFIE_ONLY"),
    payments: new Payments(bank, undefined),
    authenticate: async (token) => {
      const uid = token.replace("token-", "");
      return { uid, role: "CUSTOMER", permissions: [] };
    },
    lookupUserByEmail: async (email) => {
      const found = authUsers.get(email.toLowerCase());
      return found ? { uid: found.uid, disabled: found.disabled } : null;
    },
    createCustomerIdentity: async (uid, email, password) => {
      authUsers.set(email.toLowerCase(), { uid, email: email.toLowerCase(), password, disabled: false });
    },
  });

  return { app, s, authUsers };
}

it("completes self-registration with d-OTP and auto-activates customer on eKYC submit", async () => {
  const { app, s, authUsers } = registrationFixture();

  const reqRes = await request(app)
    .post("/auth/register/request")
    .send({
      email: "NewCustomer@Example.com",
      password: "Password123!",
      fullName: "Nguyen Van A",
      phoneNumber: "0901234567",
      nationalIdNumber: "079200012345",
    })
    .expect(200);

  expect(reqRes.body.method).toBe("DEMO_DISPLAY_CODE");
  expect(reqRes.body.code).toMatch(/^\d{6}$/);
  expect(reqRes.body.phoneMasked).toBe("••••4567");
  const { challengeId, code } = reqRes.body;

  await request(app)
    .post("/auth/register/confirm")
    .send({ challengeId, code: "000000", password: "Password123!" })
    .expect(400)
    .expect(({ body }) => expect(body.error).toBe("INVALID_OTP"));

  const confirmRes = await request(app)
    .post("/auth/register/confirm")
    .send({ challengeId, code, password: "Password123!" })
    .expect(200);

  const { uid, accountId, status } = confirmRes.body;
  expect(status).toBe("ONBOARDING");
  expect(authUsers.has("newcustomer@example.com")).toBe(true);

  const authHeader = `Bearer token-${uid}`;
  const draftRes = await request(app)
    .post("/ekyc")
    .set("Authorization", authHeader)
    .send({ consented: true })
    .expect(200);

  const ticketRes = await request(app)
    .post(`/ekyc/${draftRes.body.requestId}/uploads`)
    .set("Authorization", authHeader)
    .send({ mime: "image/jpeg", bytes: 4, index: 2 })
    .expect(200);

  await request(app)
    .post(`/uploads/${ticketRes.body.id}/confirm`)
    .set("Authorization", authHeader)
    .send({})
    .expect(200);

  const submitRes = await request(app)
    .post(`/ekyc/${draftRes.body.requestId}/submit`)
    .set("Authorization", authHeader)
    .send({
      identity: {
        fullName: "Nguyen Van A",
        nationalIdNumber: "079200012345",
        dateOfBirth: "2000-01-01",
        address: "Ho Chi Minh City",
      },
    })
    .expect(200);

  expect(submitRes.body.reviewStatus).toBe("VERIFIED");

  await s.run(async (t) => {
    const user = await t.get(`users/${uid}`);
    const customer = await t.get(`customers/${uid}`);
    const account = await t.get(`accounts/${accountId}`);
    expect(user?.status).toBe("ACTIVE");
    expect(user?.active).toBe(true);
    expect(customer?.kycStatus).toBe("VERIFIED");
    expect(account?.status).toBe("ACTIVE");
  });
});

it("rejects duplicate email, phone, and nationalIdNumber during registration request", async () => {
  const { app } = registrationFixture();

  const first = await request(app)
    .post("/auth/register/request")
    .send({
      email: "dup@example.com",
      password: "Password123!",
      fullName: "Tran Thi B",
      phoneNumber: "0912345678",
      nationalIdNumber: "079200054321",
    })
    .expect(200);

  await request(app)
    .post("/auth/register/confirm")
    .send({ challengeId: first.body.challengeId, code: first.body.code, password: "Password123!" })
    .expect(200);

  await request(app)
    .post("/auth/register/request")
    .send({
      email: "dup@example.com",
      password: "Password123!",
      fullName: "Other",
      phoneNumber: "0988888888",
      nationalIdNumber: "079200099999",
    })
    .expect(409)
    .expect(({ body }) => expect(body.error).toBe("EMAIL_ALREADY_EXISTS"));

  await request(app)
    .post("/auth/register/request")
    .send({
      email: "other@example.com",
      password: "Password123!",
      fullName: "Other",
      phoneNumber: "0912345678",
      nationalIdNumber: "079200099999",
    })
    .expect(409)
    .expect(({ body }) => expect(body.error).toBe("PHONE_ALREADY_EXISTS"));

  await request(app)
    .post("/auth/register/request")
    .send({
      email: "other2@example.com",
      password: "Password123!",
      fullName: "Other",
      phoneNumber: "0977777777",
      nationalIdNumber: "079200054321",
    })
    .expect(409)
    .expect(({ body }) => expect(body.error).toBe("NATIONAL_ID_EXISTS"));
});
