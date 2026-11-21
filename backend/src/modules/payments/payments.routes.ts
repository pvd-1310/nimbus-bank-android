import type { AppDependencies, Route } from "../../core/http.js";
import { page } from "../../shared/pagination.js";
import { paymentRequest } from "../../shared/validation.js";
export function registerPaymentsRoutes(route: Route, d: AppDependencies) {
  route("get", "/bills", async (a, r) =>
    page(await d.bank.listOwned(a, "bills", r.query), r.query),
  );
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
