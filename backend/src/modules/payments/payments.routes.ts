import type { AppDependencies, Route } from "../../core/http.js";
import type { Doc } from "../../core/store.js";
import { page } from "../../shared/pagination.js";
import { digest } from "../../shared/crypto.js";
import { check } from "../../shared/errors.js";
import { id, paymentRequest } from "../../shared/validation.js";
export function registerPaymentsRoutes(route: Route, d: AppDependencies) {
  route("get", "/bills", async (a, r) =>
    page(await d.bank.listOwned(a, "bills", r.query), r.query),
  );
  route("get", "/bills/lookup/:ref", (a, r) =>
    d.bank.store.run(async (t) => {
      await d.bank.authorize(t, a);
      const ref = id.parse(r.params.ref);
      const direct = await t.get("bills/" + ref);
      const rows: Doc[] = direct?.ownerUid === a.uid ? [{ ...direct, id: ref }]
        : await t.list("bills", { ownerUid: a.uid, invoiceRef: ref }, { limit: 2 });
      check(rows.length === 1, "NOT_FOUND", 404);
      const bill = rows[0];
      const lock = bill.status === "UNPAID" ? await t.get("billPaymentLocks/" + bill.id) : undefined;
      return { ...bill, stripePending: !!lock && lock.released !== true };
    }),
  );
  route("get", "/payment-intents/by-request/:id", (a, r) =>
    d.bank.security.describe(a, digest([a.uid, "PAYMENT", id.parse(r.params.id)])),
  );
  route("post", "/payments/intents", async (a, r) => {
    const x = paymentRequest.parse(r.body);
    if (d.stripeSandboxMovements) {
      const legacy = await d.bank.store.run(async (t) => {
        await d.bank.authorize(t, a);
        const lock = await t.get("billPaymentLocks/" + x.billId);
        if (!lock || lock.released === true) return false;
        const bill = await t.get("bills/" + x.billId);
        if (bill?.ownerUid !== a.uid || bill.status !== "UNPAID") return false;
        const intent = await t.get("transactionIntents/" + lock.paymentId);
        return intent?.actorUid === a.uid && intent.kind === "PAYMENT" && intent.provider !== "STRIPE_TREASURY";
      });
      if (!legacy) return d.stripeSandboxMovements.prepare(a, "PAYMENT", x);
    }
    return d.payments.prepare(a, x.billId, x.requestId);
  });
  route("post", "/payments", async (a, r) => {
    const x = paymentRequest.parse(r.body);
    if (d.stripeSandboxMovements) {
      const intentId = digest([a.uid, "PAYMENT", x.requestId]);
      const intent = await d.bank.security.describe(a, intentId);
      if (intent.provider === "STRIPE_TREASURY") {
        check(intent.target?.kind === "BILL" && intent.target.id === x.billId, "IDEMPOTENCY_CONFLICT", 409);
        return d.stripeSandboxMovements.commit(a, intentId, "PAYMENT");
      }
    }
    return d.payments.create(a, x.billId, x.requestId);
  });
  route("get", "/payments/:id", (a, r) =>
    d.bank.scoped(a, "payments", String(r.params.id)),
  );
}
