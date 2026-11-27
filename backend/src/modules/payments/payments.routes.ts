import { z } from "zod";
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
    d.bank.read(async (t) => {
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
  route("post", "/topups/intents", (a, r) => {
    const x = z.object({ accountId: id, amountMinor: z.number().int().positive(), currency: z.literal("VND"), requestId: id }).strict().parse(r.body);
    return d.payments.prepareTopup(a, x.accountId, x.amountMinor, x.currency, x.requestId);
  });
  route("get", "/topup-intents/by-request/:id", (a, r) =>
    d.bank.security.describe(a, digest([a.uid, "TOP_UP", id.parse(r.params.id)])),
  );
  route("post", "/topups", (a, r) => {
    const x = z.object({ accountId: id, requestId: id }).strict().parse(r.body);
    return d.payments.createTopup(a, x.accountId, x.requestId);
  });
  route("get", "/topups/:id", (a, r) => d.payments.getTopup(a, String(r.params.id)));
  route("post", "/payments/intents", async (a, r) => {
    const x = paymentRequest.parse(r.body);
    return d.payments.prepare(a, x.billId, x.requestId, x.paymentMethod, x.sourceAccountId);
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
    return d.payments.create(a, x.billId, x.requestId, x.paymentMethod, x.sourceAccountId);
  });
  route("get", "/payments/:id", (a, r) =>
    d.bank.scoped(a, "payments", String(r.params.id)),
  );
  route("get", "/stripe/cards", (a) => d.payments.listStripeCards(a));
  route("post", "/stripe/cards/setup-intent", (a, r) => {
    const x = z.object({ requestId: id }).strict().parse(r.body);
    return d.payments.prepareStripeCardSetup(a, x.requestId);
  });
  route("delete", "/stripe/cards/:id", (a, r) =>
    d.payments.removeStripeCard(a, id.parse(r.params.id)),
  );
}
