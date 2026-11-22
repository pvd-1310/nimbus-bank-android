import { beforeEach, it, expect } from "vitest";
import request from "supertest";
import { createApp } from "../src/app.js";
import { Banking } from "../src/core/banking.js";
import { Evidence } from "../src/modules/ekyc/ekyc.service.js";
import { Payments } from "../src/modules/payments/payment.service.js";
import { MemoryStore } from "./support/memory-store.js";
let store: MemoryStore, app: ReturnType<typeof createApp>;
beforeEach(async () => {
  store = new MemoryStore();
  await store.run(async (t) => {
    t.set("users/alice", { role: "CUSTOMER", active: true, fullName: "Alice" });
    for (const [id, type] of [
      ["checking", "CHECKING"],
      ["savings", "SAVINGS"],
      ["mortgage", "MORTGAGE"],
    ])
      t.set("accounts/" + id, {
        ownerUid: "alice",
        type,
        currency: "VND",
        status: "ACTIVE",
        availableMinor: 1000,
        bookedMinor: 1000,
        version: 0,
      });
    t.set("accounts/foreign", {
      ownerUid: "bob",
      type: "SAVINGS",
      status: "ACTIVE",
    });
    t.set("checkingDetails/checking", {
      dailyLimitMinor: 500,
      overdraftPolicy: "NONE",
    });
    t.set("savingsDetails/savings", {
      principalMinor: 365000,
      ratePolicyVersion: "rate",
    });
    t.set("ratePolicies/rate", {
      productId: "savings",
      annualRateBasisPoints: 500,
    });
    t.set("mortgageDetails/mortgage", {
      loanPrincipalMinor: 1000,
      annualRateBasisPoints: 0,
      termMonths: 12,
      paymentFrequency: "MONTHLY",
    });
    t.set("notifications/n", {
      ownerUid: "alice",
      title: "Hi",
      body: "Welcome",
      read: false,
    });
    t.set("catalog/movie", {
      active: true,
      demo: true,
      serviceType: "MOVIE",
      amountMinor: 20,
      currency: "VND",
    });
    t.set("bankBranches/branch", { active: true, name: "Demo branch" });
  });
  const bank = new Banking(store, {
    otpSecret: "test",
    deliver: async () => {},
  });
  app = createApp({
    bank,
    evidence: new Evidence(bank, undefined),
    payments: new Payments(bank, undefined),
    authenticate: async () => ({
      uid: "alice",
      role: "CUSTOMER",
      permissions: [],
    }),
  });
});
const auth = "Bearer test";
it("keeps scoped checking details and missing-account behavior", async () => {
  expect(
    (
      await request(app)
        .get("/accounts/checking/details")
        .set("Authorization", auth)
    ).body.details.dailyLimitMinor,
  ).toBe(500);
  expect(
    (
      await request(app)
        .get("/accounts/foreign/details")
        .set("Authorization", auth)
    ).status,
  ).toBe(403);
  expect(
    (await request(app).get("/accounts/missing").set("Authorization", auth))
      .status,
  ).toBe(404);
});
it("preserves savings and mortgage calculation responses", async () => {
  const savings = await request(app)
    .get("/accounts/savings/savings-estimate?days=365")
    .set("Authorization", auth);
  expect(savings.status).toBe(200);
  expect(savings.body.profitMinor).toBe(18250);
  const mortgage = await request(app)
    .get("/accounts/mortgage/mortgage-schedule")
    .set("Authorization", auth);
  expect(mortgage.status).toBe(200);
  expect(mortgage.body.items).toHaveLength(12);
  expect(
    mortgage.body.items.reduce(
      (n: number, x: { principalMinor: number }) => n + x.principalMinor,
      0,
    ),
  ).toBe(1000);
});
it("keeps profile updates, notification acknowledgement and request-only deposits", async () => {
  expect(
    (
      await request(app)
        .patch("/me")
        .set("Authorization", auth)
        .send({ preferences: { language: "vi" } })
    ).status,
  ).toBe(200);
  expect(
    (await request(app).get("/me").set("Authorization", auth)).body
      .preferences.language,
  ).toBe("vi");
  expect(
    (
      await request(app)
        .post("/notifications/n/read")
        .set("Authorization", auth)
    ).status,
  ).toBe(200);
  expect((await store.run((t) => t.get("notifications/n")))!.read).toBe(true);
  const response = await request(app)
    .post("/money-requests")
    .set("Authorization", auth)
    .send({
      requestId: "deposit",
      accountId: "checking",
      amountMinor: 20,
      currency: "VND",
      type: "DEPOSIT",
    });
  expect(response.body.status).toBe("REQUESTED");
  expect(
    (await store.run((t) => t.get("accounts/checking")))!.bookedMinor,
  ).toBe(1000);
});
it("keeps order invoice, idempotency and branch routes mounted", async () => {
  const body = {
    requestId: "order",
    productId: "movie",
    quantity: 2,
    customerDetails: "Demo",
  };
  const first = await request(app)
    .post("/orders")
    .set("Authorization", auth)
    .send(body);
  expect(first.status).toBe(200);
  expect(first.body.amountMinor).toBe(40);
  const retry = await request(app)
    .post("/orders")
    .set("Authorization", auth)
    .send(body);
  expect(retry.body.orderId).toBe(first.body.orderId);
  expect(await store.run((t) => t.list("bills"))).toHaveLength(1);
  expect(
    (await request(app).get("/branches").set("Authorization", auth)).body
      .items,
  ).toHaveLength(1);
  expect(
    (await request(app).get("/officer/audit").set("Authorization", auth))
      .status,
  ).toBe(403);
});
