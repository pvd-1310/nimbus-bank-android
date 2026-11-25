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
  route("get", "/accounts/:id/limits", (a, r) =>
    d.bank.accounts.limits(a, String(r.params.id)),
  );
  route("patch", "/accounts/:id/card-controls", (a, r) => {
    const patch = z
      .object({
        nfcEnabled: z.boolean().optional(),
        cardFrozen: z.boolean().optional(),
      })
      .strict()
      .parse(r.body);
    return d.bank.accounts.updateCardControls(a, String(r.params.id), patch, r.header("x-request-id") ?? "");
  });
  route("get", "/accounts/:id/stripe-sandbox", async (a, r) => {
    const id = String(r.params.id);
    await d.bank.accounts.get(a, id);
    return { status: "disabled", balanceSource: "FIREBASE_VND_LEDGER" };
  });
  route("get", "/accounts/:id/savings-estimate", (a, r) =>
    d.bank.accounts.savings(
      a,
      String(r.params.id),
      r.query.month == null
        ? undefined
        : z.string().regex(/^\d{4}-(0[1-9]|1[0-2])$/).parse(r.query.month),
    ),
  );
  route("get", "/accounts/:id/mortgage-schedule", (a, r) =>
    d.bank.accounts.mortgage(
      a,
      String(r.params.id),
      r.query.frequency == null
        ? undefined
        : z.enum(["MONTHLY", "BIWEEKLY"]).parse(r.query.frequency),
    ),
  );
}
