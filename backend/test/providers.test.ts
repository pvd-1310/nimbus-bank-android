import { beforeEach, expect, it } from "vitest";
import { Banking } from "../src/core/banking.js";
import { type Actor } from "../src/modules/auth/auth.types.js";
import { Evidence } from "../src/modules/ekyc/ekyc.service.js";
import { Payments } from "../src/modules/payments/payment.service.js";
import { MemoryStore } from "./support/memory-store.js";
const a: Actor = { uid: "alice", role: "CUSTOMER", permissions: [] };
let s: MemoryStore, b: Banking;
beforeEach(async () => {
  s = new MemoryStore();
  b = new Banking(s, { otpSecret: "test", deliver: async () => {} });
  await s.run(async (t) => {
    t.set("users/alice", { active: true, role: "CUSTOMER" });
    t.set("bills/bill", {
      ownerUid: "alice",
      amountMinor: 20000,
      currency: "VND",
      status: "UNPAID",
    });
  });
});
it("cancellation stays terminal and releases only its own invoice lock", async () => {
  const p = new Payments(b, undefined);
  await s.run(async (t) => {
    t.set("payments/p", {
      ownerUid: "alice",
      billId: "bill",
      amountMinor: 20000,
      currency: "VND",
      providerId: "pi_1",
      status: "PROCESSING",
    });
    t.set("billPaymentLocks/bill", { paymentId: "p" });
  });
  const event = {
    id: "evt_cancel",
    type: "payment_intent.canceled",
    data: {
      object: {
        id: "pi_1",
        amount: 20000,
        currency: "vnd",
        metadata: { paymentId: "p" },
        livemode: false,
      },
    },
  };
  await p.event(event);
  await p.event({
    ...event,
    id: "evt_old",
    type: "payment_intent.payment_failed",
  });
  expect((await s.run((t) => t.get("payments/p")))!.status).toBe("CANCELLED");
  expect((await s.run((t) => t.get("billPaymentLocks/bill")))!.released).toBe(
    true,
  );
});
it("evidence requires consent and refuses another owner", async () => {
  const e = new Evidence(b, undefined);
  await expect(e.create(a, false)).rejects.toThrow();
  const r = await e.create(a, true);
  await expect(
    e.ticket({ ...a, uid: "bob" }, r.id, "image/jpeg", 1000),
  ).rejects.toThrow();
  await expect(e.ticket(a, r.id, "image/jpeg", 1000)).rejects.toMatchObject({
    code: "SETUP_REQUIRED",
  });
});
it("cannot submit an eKYC request before validated evidence", async () => {
  const e = new Evidence(b, undefined),
    r = await e.create(a, true);
  await expect(e.submit(a, r.id)).rejects.toMatchObject({
    code: "EVIDENCE_REQUIRED",
  });
});
it("missing Stripe config is explicit and does not mark invoice paid", async () => {
  const p = new Payments(b, undefined);
  await expect(p.create(a, "bill", "request-1")).rejects.toMatchObject({
    code: "SETUP_REQUIRED",
  });
  expect((await s.run((t) => t.get("bills/bill")))!.status).toBe("UNPAID");
});
it("webhooks deduplicate and cannot regress successful payment", async () => {
  const p = new Payments(b, undefined);
  await s.run(async (t) => {
    t.set("payments/p", {
      ownerUid: "alice",
      billId: "bill",
      amountMinor: 20000,
      currency: "VND",
      providerId: "pi_1",
      status: "PROCESSING",
    });
  });
  const event = {
    id: "evt_1",
    type: "payment_intent.succeeded",
    data: {
      object: {
        id: "pi_1",
        amount: 20000,
        currency: "vnd",
        metadata: { paymentId: "p" },
        livemode: false,
      },
    },
  };
  await p.event(event);
  await p.event(event);
  await p.event({
    ...event,
    id: "evt_2",
    type: "payment_intent.payment_failed",
  });
  expect((await s.run((t) => t.get("payments/p")))!.status).toBe("SUCCEEDED");
  expect(await s.run((t) => t.list("outbox"))).toHaveLength(1);
});
it("webhook amount mismatch never settles bill", async () => {
  const p = new Payments(b, undefined);
  await s.run(async (t) =>
    t.set("payments/p", {
      ownerUid: "alice",
      billId: "bill",
      amountMinor: 20000,
      currency: "VND",
      providerId: "pi_1",
      status: "PROCESSING",
    }),
  );
  await expect(
    p.event({
      id: "evt_bad",
      type: "payment_intent.succeeded",
      data: {
        object: {
          id: "pi_1",
          amount: 1,
          currency: "vnd",
          metadata: { paymentId: "p" },
          livemode: false,
        },
      },
    }),
  ).rejects.toThrow();
  expect((await s.run((t) => t.get("bills/bill")))!.status).toBe("UNPAID");
});
