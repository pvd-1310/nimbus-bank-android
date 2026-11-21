import { expect, it } from "vitest";
import { drainOutbox } from "../src/modules/orders/fulfillment.service.js";
import { MemoryStore } from "./support/memory-store.js";
it("demo fulfillment is idempotent and never claims real fulfillment", async () => {
  const s = new MemoryStore();
  await s.run(async (t) => {
    t.set("payments/p", { status: "SUCCEEDED" });
    t.set("bills/b", { orderId: "o", demo: true, status: "PAID" });
    t.set("orders/o", { demo: true, fulfillmentStatus: "AWAITING_PAYMENT" });
    t.set("outbox/p", {
      paymentId: "p",
      billId: "b",
      status: "PENDING",
      attempts: 0,
    });
  });
  await drainOutbox(s);
  await drainOutbox(s);
  expect((await s.run((t) => t.get("orders/o")))!.fulfillmentStatus).toBe(
    "DEMO_FULFILLED",
  );
  expect((await s.run((t) => t.get("outbox/p")))!.attempts).toBe(1);
});
it("real fulfillment remains reconciliation required without a provider", async () => {
  const s = new MemoryStore();
  await s.run(async (t) => {
    t.set("payments/p", { status: "SUCCEEDED" });
    t.set("bills/b", { demo: false, status: "PAID" });
    t.set("outbox/p", {
      paymentId: "p",
      billId: "b",
      status: "PENDING",
      attempts: 0,
    });
  });
  await drainOutbox(s);
  expect((await s.run((t) => t.get("outbox/p")))!.status).toBe(
    "RECONCILIATION_REQUIRED",
  );
});
