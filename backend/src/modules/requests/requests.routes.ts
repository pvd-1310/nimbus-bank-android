import { z } from "zod";
import type { AppDependencies, Route } from "../../core/http.js";
import { digest } from "../../shared/crypto.js";
import { page } from "../../shared/pagination.js";
import { amount, currency, id } from "../../shared/validation.js";
const demo = z.object({
  requestId: id, accountId: id, amountMinor: amount, currency,
});
export function registerRequestsRoutes(route: Route, d: AppDependencies) {
  route("post", "/money-requests", (a, r) =>
    d.bank.requests.create(
      a,
      z
        .object({
          requestId: id,
          accountId: id,
          amountMinor: amount,
          currency,
          type: z.enum(["DEPOSIT", "WITHDRAWAL"]),
        })
        .strict()
        .parse(r.body),
    ),
  );
  route("post", "/money-requests/intents", (a, r) =>
    d.bank.requests.demoIntent(a, demo.extend({ type: z.enum(["DEPOSIT", "WITHDRAWAL"]) }).strict().parse(r.body)),
  );
  route("post", "/external-transfers", (a, r) =>
    d.bank.requests.demoIntent(a, {
      ...demo.extend({ bankCode: z.literal("DEMO_BANK"), beneficiaryAccount: z.string().regex(/^0{9}[12]$/) })
        .strict().parse(r.body),
      type: "EXTERNAL_TRANSFER",
    }),
  );
  route("get", "/demo-movements/by-request/:id", (a, r) =>
    d.bank.requests.getDemo(a, String(r.params.id)),
  );
  route("post", "/demo-movements/:id/commit", (a, r) =>
    d.bank.requests.commitDemo(a, String(r.params.id)),
  );
  route("post", "/demo-movements/:id/reconcile", (a, r) =>
    d.bank.requests.reconcileDemo(a, String(r.params.id)),
  );
  route("get", "/money-requests/by-request/:id", (a, r) =>
    d.bank.scoped(a, "moneyRequests", digest([a.uid, "MONEY_REQUEST", id.parse(r.params.id)])),
  );
  route("get", "/money-requests", async (a, r) =>
    page(await d.bank.listOwned(a, "moneyRequests", r.query), r.query),
  );
}
