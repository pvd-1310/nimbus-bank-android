import request from "supertest";
import { expect, it } from "vitest";
import { createApp } from "../src/app.js";
import { Banking } from "../src/core/banking.js";
import { Evidence, type EvidenceProvider } from "../src/modules/ekyc/ekyc.service.js";
import { Payments } from "../src/modules/payments/payment.service.js";
import type { FaceService } from "../src/modules/security/face.js";
import { MemoryStore } from "./support/memory-store.js";

function registrationFixture({ failGrantOnce = false } = {}) {
  const s = new MemoryStore();
  const grants = new Set<string>();

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
    verifyRegistrationIdentity: async (token) => {
      if (token === "invalid") throw new Error("invalid Firebase token");
      return {
        uid: token,
        email: token + "@example.com",
        emailVerified: token !== "unverified",
        role: token === "officer" ? "OFFICER" : grants.has(token) ? "CUSTOMER" : undefined,
      };
    },
    grantCustomerRole: async (uid) => {
      if (failGrantOnce) {
        failGrantOnce = false;
        throw new Error("Firebase unavailable");
      }
      grants.add(uid);
    },
  });

  return { app, s, grants };
}

const profile = {
  fullName: "Nguyen Van A",
  phoneNumber: "0901234567",
  nationalIdNumber: "079200012345",
};

it("completes verified self-registration and activates the customer after eKYC", async () => {
  const { app, s, grants } = registrationFixture();
  const confirmRes = await request(app)
    .post("/auth/register/complete")
    .set("Authorization", "Bearer newcustomer")
    .send(profile)
    .expect(200);

  const { uid, accountId, status } = confirmRes.body;
  expect(uid).toBe("newcustomer");
  expect(status).toBe("ONBOARDING");
  expect(grants.has(uid)).toBe(true);
  expect(await s.run((t) => t.get("users/" + uid))).toMatchObject({ contactEmail: "newcustomer@example.com" });

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

it("rejects unverified identities, invalid tokens and officers", async () => {
  const { app, s, grants } = registrationFixture();
  for (const [token, status] of [["unverified", 403], ["invalid", 401], ["officer", 403]] as const) {
    await request(app).post("/auth/register/complete").set("Authorization", "Bearer " + token)
      .send(profile).expect(status);
  }
  expect(grants.size).toBe(0);
  expect(await s.run((t) => t.list("users"))).toHaveLength(0);
});

it("rejects duplicate phone numbers and national IDs", async () => {
  const { app } = registrationFixture();
  const complete = (uid: string, info: typeof profile) => request(app)
    .post("/auth/register/complete").set("Authorization", "Bearer " + uid).send(info);
  await complete("first", profile).expect(200);
  await complete("other", { ...profile, nationalIdNumber: "079200099999" }).expect(409)
    .expect(({ body }) => expect(body.error).toBe("CONTACT_PHONE_EXISTS"));
  await complete("other", { ...profile, phoneNumber: "0912345678" }).expect(409)
    .expect(({ body }) => expect(body.error).toBe("NATIONAL_ID_EXISTS"));
});

it("can retry a grant failure without duplicating the profile, account or audit", async () => {
  const { app, s, grants } = registrationFixture({ failGrantOnce: true });
  const complete = () => request(app).post("/auth/register/complete")
    .set("Authorization", "Bearer newcustomer").send(profile);
  await complete().expect(500);
  const { body } = await complete().expect(200);
  await complete().expect(200).expect(body);
  expect(grants.has(body.uid)).toBe(true);
  expect(await s.run((t) => t.list("accounts"))).toHaveLength(1);
  expect(await s.run((t) => t.list("auditLogs"))).toHaveLength(1);
});
