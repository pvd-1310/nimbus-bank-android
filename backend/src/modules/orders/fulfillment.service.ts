import type { Doc, Store, Tx } from "../../core/store.js";
export async function fulfillDemoOrder(t: Tx, bill: Doc, paymentId: string) {
  if (bill.demo !== true || !bill.orderId) return;
  const order = await t.get("orders/" + bill.orderId);
  if (order?.demo !== true || order.ownerUid !== bill.ownerUid ||
    order.fulfillmentStatus === "DEMO_FULFILLED") return;
  t.set("orders/" + bill.orderId, {
    ...order,
    fulfillmentStatus: "DEMO_FULFILLED",
    paymentId,
    providerReference: "DEMO-FULFILLMENT-" + paymentId,
  });
}

// One-off processing for outbox entries created before fulfillment moved into settlement.
export async function drainOutbox(store: Store) {
  return store.run(async (t) => {
    const jobs = await t.list("outbox", { status: "PENDING" }, { limit: 25 });
    for (const j of jobs) {
      const p = await t.get("payments/" + j.paymentId),
        bill = await t.get("bills/" + j.billId);
      if (p?.status !== "SUCCEEDED" || bill?.status !== "PAID") continue;
      const order = bill.orderId
        ? await t.get("orders/" + bill.orderId)
        : undefined;
      const demo =
        bill.demo === true && (!bill.orderId || order?.demo === true);
      if (order && demo)
        t.set("orders/" + bill.orderId, {
          ...order,
          fulfillmentStatus: "DEMO_FULFILLED",
          paymentId: j.paymentId,
          providerReference: "DEMO-FULFILLMENT-" + j.id,
          demo: true,
        });
      t.set("outbox/" + j.id, {
        ...j,
        status: demo ? "DEMO_FULFILLED" : "RECONCILIATION_REQUIRED",
        attempts: j.attempts + 1,
        updatedAt: Date.now(),
      });
    }
    return { processed: jobs.length };
  });
}
