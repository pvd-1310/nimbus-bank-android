import request from "supertest";
import { expect, it } from "vitest";
import { createApp } from "../src/app.js";
import { Banking } from "../src/core/banking.js";
import { Evidence } from "../src/modules/ekyc/ekyc.service.js";
import { Payments } from "../src/modules/payments/payment.service.js";
import type { Actor } from "../src/modules/auth/auth.types.js";
import { MemoryStore } from "./support/memory-store.js";

async function fixture(provision = async () => ({ uid: "new-customer", accountId: "new-checking", status: "ONBOARDING" })) {
  const store = new MemoryStore();
  await store.run(async (t) => {
    t.set("users/officer", { role: "OFFICER", active: true });
    t.set("officers/officer", { active: true, permissions: ["CUSTOMER_ADMIN"] });
    t.set("users/restricted", { role: "OFFICER", active: true });
    t.set("officers/restricted", { active: true, permissions: ["AUDIT_READ"] });
    t.set("users/c1", { role: "CUSTOMER", active: true, fullName: "Nguyễn Minh Anh", contactPhone: "+84901234567", contactEmail: "anh@example.com" });
    t.set("customers/c1", { uidRef: "c1", address: "12 Đường A", nationalIdNumber: "012345678901" });
    t.set("users/c2", { role: "CUSTOMER", active: true, fullName: "Nguyễn Minh An", contactPhone: "+84907654321" });
    t.set("customers/c2", { uidRef: "c2" });
    t.set("accounts/acct-001", { accountId: "acct-001", ownerUid: "c1", type: "CHECKING", ownerNameSearch: "nguyen minh anh", status: "ACTIVE", balanceMinor: 900000, demo: true, demoNamespace: "local_demo" });
    t.set("accounts/acct-002", { accountId: "acct-002", ownerUid: "c2", type: "CHECKING", ownerNameSearch: "nguyen minh an", status: "ACTIVE", balanceMinor: 100000, demo: true, demoNamespace: "local_demo" });
    t.set("accounts/savings-1", { accountId: "savings-1", ownerUid: "c1", type: "SAVINGS", status: "ACTIVE", demo: true, demoNamespace: "local_demo" });
  });
  const bank = new Banking(store, { otpSecret: "test", deliver: async () => {} });
  const actors: Record<string, Actor> = {
    officer: { uid: "officer", role: "OFFICER", permissions: ["CUSTOMER_ADMIN"] },
    restricted: { uid: "restricted", role: "OFFICER", permissions: ["AUDIT_READ"] },
  };
  return createApp({
    bank,
    evidence: new Evidence(bank, undefined),
    payments: new Payments(bank, undefined),
    authenticate: async (token) => {
      const actor = actors[token];
      if (!actor) throw Error("bad token");
      return actor;
    },
    provision: provision as (actor: Actor, body: Record<string, unknown>) => Promise<unknown>,
  });
}

it("lists only checking accounts and finds account number or normalized owner-name prefix", async () => {
  const app = await fixture();
  const exact = await request(app).get("/officer/checking-accounts?q=acct-001")
    .set("Authorization", "Bearer officer").expect(200);
  expect(exact.body.items).toHaveLength(1);
  expect(exact.body.items[0]).toEqual({ accountId: "acct-001", customerId: "c1", ownerName: "Nguyễn Minh Anh", contactPhone: "+84901234567", status: "ACTIVE" });
  expect(JSON.stringify(exact.body)).not.toMatch(/balance|ownerUid|900000/);
  expect(JSON.stringify(exact.body)).not.toMatch(/demo/i);

  const byName = await request(app).get("/officer/checking-accounts?q=NGUYEN%20MINH%20A")
    .set("Authorization", "Bearer officer").expect(200);
  expect(byName.body.items.map((item: { accountId: string }) => item.accountId).sort()).toEqual(["acct-001", "acct-002"]);
  expect(byName.body.items.every((item: { accountId: string }) => item.accountId !== "savings-1")).toBe(true);
});

it("paginates checking accounts and returns an empty page for no match", async () => {
  const app = await fixture();
  const first = await request(app).get("/officer/checking-accounts?limit=1")
    .set("Authorization", "Bearer officer").expect(200);
  expect(first.body.items).toHaveLength(1);
  expect(first.body.nextCursor).toEqual(expect.any(String));
  const next = await request(app).get(`/officer/checking-accounts?limit=1&cursor=${encodeURIComponent(first.body.nextCursor)}`)
    .set("Authorization", "Bearer officer").expect(200);
  expect(next.body.items).toHaveLength(1);
  expect(next.body.items[0].accountId).not.toBe(first.body.items[0].accountId);
  const missing = await request(app).get("/officer/checking-accounts?q=missing")
    .set("Authorization", "Bearer officer").expect(200);
  expect(missing.body).toMatchObject({ items: [], nextCursor: null });
  const namedFirst = await request(app).get("/officer/checking-accounts?q=nguyen&limit=1")
    .set("Authorization", "Bearer officer").expect(200);
  const namedNext = await request(app).get(`/officer/checking-accounts?q=nguyen&limit=1&cursor=${encodeURIComponent(namedFirst.body.nextCursor)}`)
    .set("Authorization", "Bearer officer").expect(200);
  expect(namedFirst.body.items).toHaveLength(1);
  expect(namedNext.body.items).toHaveLength(1);
  expect(namedNext.body.items[0].accountId).not.toBe(namedFirst.body.items[0].accountId);
});

it("requires customer-admin permission for account and customer detail reads", async () => {
  const app = await fixture();
  await request(app).get("/officer/checking-accounts").set("Authorization", "Bearer restricted").expect(403);
  await request(app).get("/officer/customers/c1").set("Authorization", "Bearer restricted").expect(403);
  await request(app).get("/officer/customers/missing").set("Authorization", "Bearer officer").expect(404);
});

it("returns editable contact and identity fields without exposing CCCD", async () => {
  const app = await fixture();
  const response = await request(app).get("/officer/customers/c1")
    .set("Authorization", "Bearer officer").expect(200);
  expect(response.body).toEqual({
    fullName: "Nguyễn Minh Anh",
    customerNo: "",
    dateOfBirth: "",
    gender: "",
    nationality: "",
    identityDocumentExpiry: "",
    address: "12 Đường A",
    contactEmail: "anh@example.com",
    contactPhone: "+84901234567",
    preferences: {},
  });
  expect(JSON.stringify(response.body)).not.toContain("012345678901");
  const directory = await request(app).get("/officer/customers").set("Authorization", "Bearer officer").expect(200);
  expect(JSON.stringify(directory.body)).not.toContain("012345678901");
});

it("updates the normalized checking-account name search index after officer edits", async () => {
  const app = await fixture();
  await request(app).patch("/officer/customers/c1").set("Authorization", "Bearer officer")
    .send({ fullName: "Lê Thanh Bình", reason: "Corrected customer name" }).expect(200);
  const oldName = await request(app).get("/officer/checking-accounts?q=nguyen%20min")
    .set("Authorization", "Bearer officer").expect(200);
  const newName = await request(app).get("/officer/checking-accounts?q=le%20thanh%20binh")
    .set("Authorization", "Bearer officer").expect(200);
  expect(oldName.body.items.map((item: { accountId: string }) => item.accountId)).toEqual(["acct-002"]);
  expect(newName.body.items.map((item: { accountId: string }) => item.accountId)).toEqual(["acct-001"]);
  const stored = await request(app).get("/officer/checking-accounts?q=acct-001")
    .set("Authorization", "Bearer officer").expect(200);
  expect(stored.body.items[0]).not.toHaveProperty("id");
});

it("validates and forwards CCCD and full identity fields on provisioning without returning CCCD", async () => {
  let receivedBody: Record<string, unknown> = {};
  const app = await fixture(async (_actor, body) => {
    receivedBody = body;
    return { uid: "new-customer", accountId: "new-checking", status: "ONBOARDING" };
  });
  const body = {
    requestId: "new-customer",
    email: "new@example.com",
    fullName: "New Customer",
    phoneNumber: "0901234567",
    nationalIdNumber: "012345678901",
    dateOfBirth: "1998-05-12",
    gender: "Nữ",
    nationality: "Việt Nam",
    identityDocumentExpiry: "2032-05-12",
    address: "18 Nguyễn Văn Linh",
    preferences: { notificationsEnabled: true, language: "vi" },
  };
  const created = await request(app).post("/officer/customers").set("Authorization", "Bearer officer").send(body).expect(200);
  expect(created.body).toEqual({ uid: "new-customer", accountId: "new-checking", status: "ONBOARDING" });
  expect(receivedBody.nationalIdNumber).toBe("012345678901");
  expect(receivedBody.dateOfBirth).toBe("1998-05-12");
  expect(receivedBody.gender).toBe("Nữ");
  expect(receivedBody.nationality).toBe("Việt Nam");
  expect(receivedBody.identityDocumentExpiry).toBe("2032-05-12");
  await request(app).post("/officer/customers").set("Authorization", "Bearer officer").send({ ...body, requestId: "invalid-id", nationalIdNumber: "123" }).expect(400);
});

it("updates extended customer identity and preferences fields via PATCH", async () => {
  const app = await fixture();
  await request(app).patch("/officer/customers/c1").set("Authorization", "Bearer officer")
    .send({
      dateOfBirth: "1995-08-20",
      gender: "Nữ",
      nationality: "Việt Nam",
      identityDocumentExpiry: "2035-08-20",
      preferences: { notificationsEnabled: false, language: "en" },
      reason: "Cập nhật thông tin định danh đầy đủ",
    }).expect(200);
  const updated = await request(app).get("/officer/customers/c1")
    .set("Authorization", "Bearer officer").expect(200);
  expect(updated.body).toMatchObject({
    dateOfBirth: "1995-08-20",
    gender: "Nữ",
    nationality: "Việt Nam",
    identityDocumentExpiry: "2035-08-20",
    preferences: { notificationsEnabled: false, language: "en" },
  });
});
