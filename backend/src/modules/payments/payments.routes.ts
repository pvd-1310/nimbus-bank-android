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
  route("get", "/demo-payment-intents/by-request/:id", (a, r) =>
    d.bank.security.describe(a, digest([a.uid, "SIMULATOR_PAYMENT", id.parse(r.params.id)])),
  );
  route("post", "/demo-payments/intents", (a, r) => {
    const x = paymentRequest.parse(r.body);
    return d.payments.prepareDemo(a, x.billId, x.requestId);
  });
  route("post", "/demo-payments", (a, r) => {
    const x = paymentRequest.parse(r.body);
    return d.payments.settleDemo(a, x.billId, x.requestId);
  });
  route("post", "/payments/intents", (a, r) => {
    const x = paymentRequest.parse(r.body);
    return d.payments.prepare(a, x.billId, x.requestId);
  });
  route("post", "/payments", (a, r) => {
    const x = paymentRequest.parse(r.body);
    return d.payments.create(a, x.billId, x.requestId);
  });
  route("get", "/payments/:id", (a, r) =>
    d.bank.scoped(a, "payments", String(r.params.id)),
  );
}
