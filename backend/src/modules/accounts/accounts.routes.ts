import { z } from "zod";
import type { AppDependencies, Route } from "../../core/http.js";
import { page } from "../../shared/pagination.js";
export function registerAccountsRoutes(route: Route, d: AppDependencies) {
  route("get", "/accounts", async (a, r) =>
    page(await d.bank.accounts.list(a, r.query), r.query),
  );
  route("get", "/accounts/:id", (a, r) =>
    d.bank.accounts.get(a, String(r.params.id)),
  );
  route("get", "/accounts/:id/details", (a, r) =>
    d.bank.accounts.details(a, String(r.params.id)),
  );
  route("get", "/accounts/:id/savings-estimate", (a, r) =>
    d.bank.accounts.savings(
      a,
      String(r.params.id),
      z.coerce
        .number()
        .int()
        .min(0)
        .max(36500)
        .parse(r.query.days ?? 30),
    ),
  );
  route("get", "/accounts/:id/mortgage-schedule", (a, r) =>
    d.bank.accounts.mortgage(a, String(r.params.id)),
  );
}
