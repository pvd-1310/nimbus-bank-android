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
    t.set("transactions/checking-recent", { ownerUid: "alice", accountId: "checking", sourceAccountId: "checking", type: "DEMO_WITHDRAWAL", amountMinor: 25, currency: "VND", status: "DEMO_SETTLED", committedAt: Date.UTC(2024, 0, 10) });
    t.set("transactions/foreign-row", { ownerUid: "bob", accountId: "foreign", type: "TOP_UP", amountMinor: 1000, currency: "VND", status: "SETTLED", committedAt: Date.UTC(2024, 0, 10) });
    t.set("checkingDetails/checking", {
      dailyLimitMinor: 500,
      overdraftPolicy: "NONE",
    });
    t.set("savingsDetails/savings", {
      principalMinor: 365000,
      ratePolicyVersion: "rate",
      productId: "savings",
    });
    t.set("ratePolicies/rate", {
      productId: "savings",
      annualRateBasisPoints: 500,
      effectiveFrom: Date.UTC(2024, 0, 1),
      version: 1,
    });
    t.set("mortgageDetails/mortgage", {
      loanPrincipalMinor: 1000,
      outstandingMinor: 800,
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
it("returns a calendar-month savings estimate and mortgage schedules from the outstanding balance", async () => {
  const savings = await request(app)
    .get("/accounts/savings/savings-estimate?month=2024-01")
    .set("Authorization", auth);
  expect(savings.status).toBe(200);
  expect(savings.body).toMatchObject({
    profitMinor: 1521,
    days: 31,
    periodStart: Date.UTC(2024, 0, 1),
    periodEnd: Date.UTC(2024, 1, 1),
    estimate: true,
    ratePolicyVersion: "rate",
  });
  const mortgage = await request(app)
    .get("/accounts/mortgage/mortgage-schedule")
    .set("Authorization", auth);
  expect(mortgage.status).toBe(200);
  expect(mortgage.body).toMatchObject({ principalMinor: 1000, outstandingMinor: 800, projected: true });
  expect(mortgage.body.items).toHaveLength(12);
  expect(
    mortgage.body.items.reduce(
      (n: number, x: { principalMinor: number }) => n + x.principalMinor,
      0,
    ),
  ).toBe(800);
  await request(app)
    .get("/accounts/savings/savings-estimate?month=2024-13")
    .set("Authorization", auth)
    .expect(400);
});
it("lets customers review their checking, savings and mortgage details and own transaction history", async () => {
  for (const [accountId, path] of [["checking", "details"], ["savings", "savings-estimate?month=2024-01"], ["mortgage", "mortgage-schedule"]]) {
    const response = await request(app).get(`/accounts/${accountId}/${path}`).set("Authorization", auth);
    expect(response.status).toBe(200);
  }
  const history = await request(app).get("/transactions?limit=25").set("Authorization", auth).expect(200);
  expect(history.body.items).toHaveLength(1);
  expect(history.body.items[0]).toMatchObject({ accountId: "checking", type: "DEMO_WITHDRAWAL", status: "DEMO_SETTLED" });
  await request(app).get("/accounts/foreign/details").set("Authorization", auth).expect(403);
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

it("preserves the requested Vietnamese phone number and rejects invalid top-up destinations", async () => {
  await store.run(async (t) => {
    t.set("catalog/topup", {
      active: true, demo: true, serviceType: "TOPUP", amountMinor: 20000, currency: "VND",
    });
  });
  const createOrder = (requestId: string, phone: string) => request(app)
    .post("/orders")
    .set("Authorization", auth)
    .send({ requestId, productId: "topup", quantity: 1, customerDetails: phone });

  const phone = "0312345678";
  const first = await createOrder("first", phone).expect(200);
  expect(first.body).toMatchObject({ customerDetails: phone, serviceType: "TOPUP" });
  const retry = await createOrder("first", phone).expect(200);
  expect(retry.body.orderId).toBe(first.body.orderId);
  await createOrder("first", "0700000012").expect(409);
  const second = await createOrder("second", "0700000012").expect(200);
  expect(second.body.customerDetails).toBe("0700000012");

  for (const badPhone of ["031234567", "03123456789", "+84312345678", "1312345678", "031234567a"]) {
    const result = await createOrder("invalid", badPhone).expect(400);
    expect(result.body.error).toBe("INVALID_TOPUP_PHONE");
  }
  expect(await store.run((t) => t.list("bills"))).toHaveLength(2);
});
