import { expect, it } from "vitest";
import { Banking } from "../src/core/banking.js";
import { Payments } from "../src/modules/payments/payment.service.js";
import { drainOutbox } from "../src/modules/orders/fulfillment.service.js";
import { MemoryStore } from "./support/memory-store.js";

it("settles only owned demo invoices with OTP and fulfills a demo order once", async () => {
  const a = { uid: "alice", role: "CUSTOMER", permissions: [] } as const;
  const actor = { ...a, permissions: [] };
  const store = new MemoryStore();
  const bank = new Banking(store, { otpSecret: "test fixture" });
  const payments = new Payments(bank, undefined);
  await store.run(async (t) => {
    t.set("users/alice", { active: true, role: "CUSTOMER" });
    t.set("bills/demo", { ownerUid: a.uid, demo: true, orderId: "demo", amountMinor: 50000, currency: "VND", status: "UNPAID" });
    t.set("orders/demo", { ownerUid: a.uid, demo: true, fulfillmentStatus: "AWAITING_PAYMENT" });
    t.set("bills/other", { ownerUid: a.uid, amountMinor: 50000, currency: "VND", status: "UNPAID" });
  });
  await expect(payments.prepareDemo(actor, "other", "order")).rejects.toMatchObject({ code: "DEMO_BILL_REQUIRED" });
  const intent = await payments.prepareDemo(actor, "demo", "order");
  await expect(payments.settleDemo(actor, "demo", "order")).rejects.toMatchObject({ code: "OTP_REQUIRED" });
  const otp = await bank.security.challenge(actor, intent.id);
  await bank.security.verify(actor, intent.id, otp.challengeId, otp.code);
  const result = await payments.settleDemo(actor, "demo", "order");
  expect(result).toMatchObject({ gateway: "SIMULATOR", status: "SUCCEEDED" });
  expect(await payments.settleDemo(actor, "demo", "order")).toEqual(result);
  expect((await store.run((t) => t.get("orders/demo")))?.fulfillmentStatus).toBe("AWAITING_PAYMENT");
  await drainOutbox(store);
  expect((await store.run((t) => t.get("orders/demo")))?.fulfillmentStatus).toBe("DEMO_FULFILLED");
});

it("blocks demo authorization and settlement while Stripe holds the invoice lock", async () => {
  const actor = { uid: "alice", role: "CUSTOMER" as const, permissions: [] };
  const store = new MemoryStore();
  const bank = new Banking(store, { otpSecret: "test fixture" });
  const payments = new Payments(bank, undefined);
  await store.run(async (t) => {
    t.set("users/alice", { active: true, role: "CUSTOMER" });
    t.set("bills/demo", { ownerUid: actor.uid, demo: true, amountMinor: 50000, currency: "VND", status: "UNPAID" });
  });
  const intent = await payments.prepareDemo(actor, "demo", "existing");
  const otp = await bank.security.challenge(actor, intent.id);
  await bank.security.verify(actor, intent.id, otp.challengeId, otp.code);
  await store.run(async (t) => {
    t.set("billPaymentLocks/demo", { paymentId: "stripe" });
  });

  await expect(payments.prepareDemo(actor, "demo", "new")).rejects.toMatchObject({ code: "PAYMENT_IN_PROGRESS" });
  await expect(payments.prepareDemo(actor, "demo", "existing")).rejects.toMatchObject({ code: "PAYMENT_IN_PROGRESS" });
  await expect(payments.settleDemo(actor, "demo", "existing")).rejects.toMatchObject({ code: "PAYMENT_IN_PROGRESS" });

  await store.run(async (t) => {
    t.set("billPaymentLocks/demo", { paymentId: "stripe", released: true });
  });
  expect(await payments.settleDemo(actor, "demo", "existing")).toMatchObject({ status: "SUCCEEDED" });
  expect(await payments.prepareDemo(actor, "demo", "existing")).toMatchObject({ id: intent.id, status: "COMMITTED" });
});
