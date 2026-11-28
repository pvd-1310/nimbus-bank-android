import Stripe from "stripe";
import request from "supertest";
import { expect, it, vi } from "vitest";
import { createApp } from "../src/app.js";
import { Banking } from "../src/core/banking.js";
import { Evidence } from "../src/modules/ekyc/ekyc.service.js";
import { Payments } from "../src/modules/payments/payment.service.js";
import { StripeGateway } from "../src/modules/payments/stripe.provider.js";
import { MemoryStore } from "./support/memory-store.js";

async function fixture(provider?: StripeGateway) {
  const actor = { uid: "alice", role: "CUSTOMER" as const, permissions: [] };
  const otherActor = { ...actor, uid: "bob" };
  const store = new MemoryStore();
  const bank = new Banking(store, { otpSecret: "test fixture" });
  await store.run(async (t) => {
    t.set("users/alice", { active: true, role: "CUSTOMER" });
    t.set("users/bob", { active: true, role: "CUSTOMER" });
    t.set("bills/demo", { ownerUid: actor.uid, demo: true, orderId: "demo", amountMinor: 50000, currency: "VND", status: "UNPAID" });
    t.set("bills/next", { ownerUid: actor.uid, amountMinor: 20000, currency: "VND", status: "UNPAID" });
    t.set("bills/bob", { ownerUid: otherActor.uid, amountMinor: 20000, currency: "VND", status: "UNPAID" });
    t.set("orders/demo", { ownerUid: actor.uid, demo: true, fulfillmentStatus: "AWAITING_PAYMENT" });
  });
  const app = createApp({
    bank,
    payments: new Payments(bank, provider),
    evidence: new Evidence(bank, undefined),
    authenticate: async (token) => token === "bob" ? otherActor : actor,
  });
  return { api: request(app), bank, store, actor, otherActor };
}

function mockStripeCustomers(stripe: Stripe) {
  let count = 0;
  const customers = vi.spyOn(stripe.customers, "create").mockImplementation(async () =>
    ({ id: "cus_demo" + ++count, livemode: false }) as Stripe.Customer);
  let sessionsCreated = 0;
  const sessions = vi.spyOn(stripe.customerSessions, "create").mockImplementation(async (params) =>
    ({ customer: params.customer, client_secret: "cuss_demo_" + ++sessionsCreated, livemode: false }) as Stripe.CustomerSession);
  return { customers, sessions };
}

it("requires Stripe configuration and has no simulator checkout endpoints", async () => {
  const { api, store } = await fixture();
  const auth = { Authorization: "Bearer valid" };
  const body = { billId: "demo", requestId: "order" };
  const response = await api.post("/payments/intents").set(auth).send(body).expect(503);
  expect(response.body.error).toBe("SETUP_REQUIRED");
  await api.post("/payments").set(auth).send(body).expect(503);
  await api.post("/demo-payments/intents").set(auth).send(body).expect(404);
  await api.post("/demo-payments").set(auth).send(body).expect(404);
  await api.get("/demo-payment-intents/by-request/order").set(auth).expect(404);
  expect(await store.run((t) => t.list("transactionIntents"))).toHaveLength(0);
  expect((await store.run((t) => t.get("bills/demo")))?.status).toBe("UNPAID");
});

it("settles a demo order once after OTP and a signed sandbox webhook", async () => {
  const stripe = new Stripe("sk_test_dummy");
  const signingSecret = "whsec_dummy";
  const { customers, sessions } = mockStripeCustomers(stripe);
  const intent = { id: "pi_demo", client_secret: "pi_demo_secret_dummy", customer: "cus_demo1", livemode: false } as Stripe.PaymentIntent;
  const create = vi.spyOn(stripe.paymentIntents, "create").mockResolvedValue(intent);
  const { api, bank, store, actor } = await fixture(new StripeGateway(stripe, signingSecret));
  const auth = { Authorization: "Bearer valid" };
  const body = { billId: "demo", requestId: "order" };
  const prepared = await api.post("/payments/intents").set(auth).send(body).expect(200);
  const id = prepared.body.intentId;
  const otherBill = { billId: "next", requestId: "next" };
  const otherIntent = await api.post("/payments/intents").set(auth).send(otherBill).expect(200);
  const beforeOtp = await api.post("/payments").set(auth).send(body);
  expect(beforeOtp.body.error).toBe("OTP_REQUIRED");
  expect(create).not.toHaveBeenCalled();
  expect(customers).not.toHaveBeenCalled();
  expect(sessions).not.toHaveBeenCalled();
  const otp = await bank.security.challenge(actor, id);
  await bank.security.challenge(actor, otherIntent.body.intentId);
  await expect(bank.security.verify(actor, otherIntent.body.intentId, otp.challengeId, otp.code))
    .rejects.toMatchObject({ code: "OTP_REQUIRED" });
  await bank.security.verify(actor, id, otp.challengeId, otp.code);
  const otherPayment = await api.post("/payments").set(auth).send(otherBill);
  expect(otherPayment.body.error).toBe("OTP_REQUIRED");
  const committed = await api.post("/payments").set(auth).send(body).expect(200);
  expect((await store.run((t) => t.get("otpChallenges/" + id)))?.status).toBe("USED");
  expect(committed.body).toMatchObject({
    paymentId: id, gateway: "STRIPE", status: "COMMIT_PENDING", clientSecret: intent.client_secret,
    customerId: "cus_demo1", customerSessionClientSecret: "cuss_demo_1",
  });
  expect(create).toHaveBeenCalledWith(expect.objectContaining({
    amount: 50000,
    currency: "vnd",
    customer: "cus_demo1",
    metadata: expect.objectContaining({ paymentId: id, purpose: "BILL_PAYMENT" }),
    automatic_payment_methods: { enabled: true },
  }), { idempotencyKey: id });
  expect(create.mock.calls[0][0]).not.toHaveProperty("setup_future_usage");
  expect(sessions).toHaveBeenCalledWith({
    customer: "cus_demo1",
    components: { mobile_payment_element: { enabled: true, features: {
      payment_method_save: "enabled", payment_method_redisplay: "enabled", payment_method_remove: "enabled",
    } } },
  });
  expect((await store.run((t) => t.get("bills/demo")))?.status).toBe("UNPAID");
  expect(await store.run((t) => t.list("outbox"))).toHaveLength(0);

  const webhook = (livemode: boolean) => {
    const payload = JSON.stringify({
      id: "evt_demo", type: "payment_intent.succeeded", livemode,
      data: { object: { id: intent.id, amount: 50000, currency: "vnd", livemode, metadata: { paymentId: id } } },
    });
    const signature = stripe.webhooks.generateTestHeaderString({ payload, secret: signingSecret });
    return api.post("/webhooks/stripe").set("Stripe-Signature", signature).type("json").send(payload);
  };
  expect((await webhook(true).expect(400)).body.error).toBe("LIVE_MODE_FORBIDDEN");
  expect((await store.run((t) => t.get("bills/demo")))?.status).toBe("UNPAID");
  await webhook(false).expect(200);
  expect((await webhook(false).expect(200)).body.duplicate).toBe(true);
  expect((await store.run((t) => t.get("bills/demo")))?.status).toBe("PAID");
  expect(await store.run((t) => t.list("transactions"))).toEqual([
    expect.objectContaining({ paymentId: id, status: "SETTLED" }),
  ]);
  expect((await store.run((t) => t.get("orders/demo")))?.fulfillmentStatus).toBe("DEMO_FULFILLED");
});

it("reuses the owner's Stripe customer, refreshes sessions on retry and isolates different users", async () => {
  const stripe = new Stripe("sk_test_dummy");
  const { customers, sessions } = mockStripeCustomers(stripe);
  const intents = new Map<string, Stripe.PaymentIntent>();
  const create = vi.spyOn(stripe.paymentIntents, "create").mockImplementation(async (params) => {
    const id = "pi_demo" + intents.size;
    const payment = {
      id, client_secret: id + "_secret", livemode: false, customer: params?.customer ?? null,
    } as Stripe.PaymentIntent;
    intents.set(id, payment);
    return payment;
  });
  vi.spyOn(stripe.paymentIntents, "retrieve").mockImplementation(async (id) => intents.get(id)!);
  const { api, bank, actor, otherActor, store } = await fixture(new StripeGateway(stripe, "whsec_dummy"));
  async function authorize(billId: string, owner = actor) {
    const auth = { Authorization: "Bearer " + owner.uid };
    const body = { billId, requestId: billId };
    const prepared = await api.post("/payments/intents").set(auth).send(body).expect(200);
    const otp = await bank.security.challenge(owner, prepared.body.intentId);
    await bank.security.verify(owner, prepared.body.intentId, otp.challengeId, otp.code);
    return { auth, body, id: prepared.body.intentId };
  }
  const first = await authorize("demo");
  const second = await authorize("next");
  const other = await authorize("bob", otherActor);
  const initial = await api.post("/payments").set(first.auth).send(first.body).expect(200);
  const retry = await api.post("/payments").set(first.auth).send(first.body).expect(200);
  const following = await api.post("/payments").set(second.auth).send(second.body).expect(200);
  const bob = await api.post("/payments").set(other.auth).send(other.body).expect(200);
  expect(retry.body.customerId).toBe(initial.body.customerId);
  expect(retry.body.clientSecret).toBe(initial.body.clientSecret);
  expect(retry.body.customerSessionClientSecret).not.toBe(initial.body.customerSessionClientSecret);
  expect(following.body.customerId).toBe(initial.body.customerId);
  expect(bob.body.customerId).not.toBe(initial.body.customerId);
  expect(customers).toHaveBeenCalledTimes(2);
  expect(create).toHaveBeenCalledTimes(3);
  expect(sessions).toHaveBeenCalledTimes(4);
  const payment = await api.get("/payments/" + first.id).set(first.auth).expect(200);
  expect(payment.body).not.toHaveProperty("customerSessionClientSecret");
  const savedCustomers = await store.run((t) => t.list("stripeCustomers"));
  expect(JSON.stringify(savedCustomers)).not.toContain("cuss_demo_");
});

it("retries an older pending Stripe payment with its original parameters", async () => {
  const stripe = new Stripe("sk_test_dummy");
  const { customers, sessions } = mockStripeCustomers(stripe);
  const create = vi.spyOn(stripe.paymentIntents, "create").mockResolvedValue({
    id: "pi_legacy", client_secret: "pi_legacy_secret_dummy", livemode: false, customer: null,
  } as Stripe.PaymentIntent);
  const { api, bank, actor, store } = await fixture(new StripeGateway(stripe, "whsec_dummy"));
  const auth = { Authorization: "Bearer valid" };
  const body = { billId: "demo", requestId: "legacy" };
  const prepared = await api.post("/payments/intents").set(auth).send(body).expect(200);
  const id = prepared.body.intentId;
  await store.run(async (t) => {
    const intent = (await t.get("transactionIntents/" + id))!;
    t.set("payments/" + id, {
      id, ownerUid: actor.uid, billId: body.billId, amountMinor: intent.amountMinor,
      currency: intent.currency, authorizationDigest: intent.digest, status: "CREATED", createdAt: bank.now(),
    });
    t.set("transactionIntents/" + id, { ...intent, status: "COMMIT_PENDING" });
  });
  const response = await api.post("/payments").set(auth).send(body).expect(200);
  expect(response.body.clientSecret).toBe("pi_legacy_secret_dummy");
  expect(response.body).not.toHaveProperty("customerSessionClientSecret");
  expect(create.mock.calls[0][0]).not.toHaveProperty("customer");
  expect(customers).not.toHaveBeenCalled();
  expect(sessions).not.toHaveBeenCalled();
});

it("settles a VND card top-up from a signed webhook without an undefined bill field", async () => {
  const stripe = new Stripe("sk_test_dummy");
  const signingSecret = "whsec_topup_test";
  mockStripeCustomers(stripe);
  vi.spyOn(stripe.paymentIntents, "create").mockImplementation(async (params) => ({
    id: "pi_topup",
    client_secret: "pi_topup_secret_dummy",
    customer: params?.customer ?? null,
    metadata: params?.metadata,
    livemode: false,
  } as Stripe.PaymentIntent));
  const { api, bank, store, actor } = await fixture(new StripeGateway(stripe, signingSecret));
  const auth = { Authorization: "Bearer valid" };
  await store.run(async (t) => t.set("accounts/checking", {
    ownerUid: actor.uid, type: "CHECKING", currency: "VND", status: "ACTIVE",
    availableMinor: 100000, bookedMinor: 100000,
  }));

  const body = { accountId: "checking", amountMinor: 20000, currency: "VND", requestId: "topup-1" };
  const prepared = await api.post("/topups/intents").set(auth).send(body).expect(200);
  const otp = await bank.security.challenge(actor, prepared.body.id);
  await bank.security.verify(actor, prepared.body.id, otp.challengeId, otp.code);
  const payment = await api.post("/topups")
    .set(auth)
    .send({ accountId: body.accountId, requestId: body.requestId })
    .expect(200);

  const payload = JSON.stringify({
    id: "evt_topup",
    type: "payment_intent.succeeded",
    livemode: false,
    data: { object: {
      id: "pi_topup", amount: body.amountMinor, currency: "vnd", livemode: false,
      metadata: { paymentId: payment.body.id, purpose: "TOP_UP" },
    } },
  });
  const signature = stripe.webhooks.generateTestHeaderString({ payload, secret: signingSecret });
  await api.post("/webhooks/stripe")
    .set("Stripe-Signature", signature)
    .type("json")
    .send(payload)
    .expect(200);

  expect(await store.run((t) => t.get("payments/" + payment.body.id))).toMatchObject({
    purpose: "TOP_UP", status: "SUCCEEDED", providerId: "pi_topup",
  });
  expect(await store.run((t) => t.get("payments/" + payment.body.id))).not.toHaveProperty("billId");
  expect(await store.run((t) => t.get("accounts/checking"))).toMatchObject({
    availableMinor: 120000, bookedMinor: 120000,
  });
  expect(await store.run((t) => t.get("transactions/" + payment.body.id))).toMatchObject({
    type: "TOP_UP", amountMinor: 20000, status: "SETTLED",
  });
});

it("settles a bill or utility order via NIMBUS_ACCOUNT without Stripe, enforces daily limit, emits notification, and counts in summary", async () => {
  const { api, bank, store, actor } = await fixture(undefined);
  const auth = { Authorization: "Bearer valid" };
  await store.run(async (t) => {
    t.set("accounts/checking", {
      ownerUid: actor.uid, type: "CHECKING", currency: "VND", status: "ACTIVE",
      availableMinor: 120000, bookedMinor: 120000, version: 1,
    });
    t.set("checkingDetails/checking", { accountId: "checking", dailyLimitMinor: 60000 });
  });

  const body = { billId: "demo", requestId: "nimbus-pay-1", paymentMethod: "NIMBUS_ACCOUNT", sourceAccountId: "checking" };
  const prepared = await api.post("/payments/intents").set(auth).send(body).expect(200);
  const otp = await bank.security.challenge(actor, prepared.body.intentId);
  await bank.security.verify(actor, prepared.body.intentId, otp.challengeId, otp.code);

  const committed = await api.post("/payments").set(auth).send(body).expect(200);
  expect(committed.body).toMatchObject({
    paymentId: prepared.body.intentId,
    gateway: "NIMBUS",
    paymentMethod: "NIMBUS_ACCOUNT",
    sourceAccountId: "checking",
    status: "SUCCEEDED",
  });
  const retry = await api.post("/payments").set(auth).send(body).expect(200);
  expect(retry.body).toMatchObject({ paymentId: prepared.body.intentId, gateway: "NIMBUS", status: "SUCCEEDED" });

  expect(await store.run((t) => t.get("accounts/checking"))).toMatchObject({
    availableMinor: 70000, bookedMinor: 70000, balanceMinor: 70000, version: 2,
  });
  expect(await store.run((t) => t.get("bills/demo"))).toMatchObject({ status: "PAID", paymentId: prepared.body.intentId });
  expect(await store.run((t) => t.get("orders/demo"))).toMatchObject({ fulfillmentStatus: "DEMO_FULFILLED" });
  expect(await store.run((t) => t.get("notifications/pay_" + prepared.body.intentId))).toMatchObject({
    ownerUid: actor.uid, type: "TRANSACTION", title: "Thanh toán hóa đơn thành công",
  });

  const summary = await api.get("/transactions/summary").set(auth).expect(200);
  expect(summary.body.totalOutMinor).toBe(50000);

  // Second bill (20000) pushes daily usage to 70000 > dailyLimitMinor 60000
  const nextBody = { billId: "next", requestId: "nimbus-pay-2", paymentMethod: "NIMBUS_ACCOUNT", sourceAccountId: "checking" };
  const nextPrep = await api.post("/payments/intents").set(auth).send(nextBody).expect(200);
  const nextOtp = await bank.security.challenge(actor, nextPrep.body.intentId);
  await bank.security.verify(actor, nextPrep.body.intentId, nextOtp.challengeId, nextOtp.code);
  const overLimit = await api.post("/payments").set(auth).send(nextBody).expect(409);
  expect(overLimit.body.error).toBe("DAILY_LIMIT_EXCEEDED");
  expect(await store.run((t) => t.get("accounts/checking"))).toMatchObject({ availableMinor: 70000 });
  expect(await store.run((t) => t.get("bills/next"))).toMatchObject({ status: "UNPAID" });
});

it("excludes external STRIPE_CARD bill payments from checking account summary totalOutMinor", async () => {
  const stripe = new Stripe("sk_test_dummy");
  const signingSecret = "whsec_summary_test";
  mockStripeCustomers(stripe);
  const intent = { id: "pi_card_only", client_secret: "pi_card_only_secret", customer: "cus_demo1", livemode: false } as Stripe.PaymentIntent;
  vi.spyOn(stripe.paymentIntents, "create").mockResolvedValue(intent);
  const { api, bank, actor } = await fixture(new StripeGateway(stripe, signingSecret));
  const auth = { Authorization: "Bearer valid" };
  const body = { billId: "demo", requestId: "card-summary-1", paymentMethod: "STRIPE_CARD" };
  const prepared = await api.post("/payments/intents").set(auth).send(body).expect(200);
  const otp = await bank.security.challenge(actor, prepared.body.intentId);
  await bank.security.verify(actor, prepared.body.intentId, otp.challengeId, otp.code);
  await api.post("/payments").set(auth).send(body).expect(200);

  const payload = JSON.stringify({
    id: "evt_card_summary", type: "payment_intent.succeeded", livemode: false,
    data: { object: { id: intent.id, amount: 50000, currency: "vnd", livemode: false, metadata: { paymentId: prepared.body.intentId, purpose: "BILL_PAYMENT" } } },
  });
  const signature = stripe.webhooks.generateTestHeaderString({ payload, secret: signingSecret });
  await api.post("/webhooks/stripe").set("Stripe-Signature", signature).type("json").send(payload).expect(200);

  const summary = await api.get("/transactions/summary").set(auth).expect(200);
  expect(summary.body.totalOutMinor).toBe(0);
});
