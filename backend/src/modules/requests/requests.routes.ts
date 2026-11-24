import { z } from "zod";
import type { AppDependencies, Route } from "../../core/http.js";
import { digest } from "../../shared/crypto.js";
import { check } from "../../shared/errors.js";
import { page } from "../../shared/pagination.js";
import { amount, currency, id } from "../../shared/validation.js";
const demo = z.object({
  requestId: id, accountId: id, amountMinor: amount, currency,
});
export function registerRequestsRoutes(route: Route, d: AppDependencies) {
  const cash = demo.extend({ type: z.enum(["DEPOSIT", "WITHDRAWAL"]) }).strict();
  const external = demo.extend({ bankCode: z.literal("DEMO_BANK"), beneficiaryAccount: z.string().regex(/^0{9}[12]$/) }).strict();
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
  route("post", "/money-requests/intents", (a, r) => {
    const input = cash.parse(r.body);
    if (input.type === "DEPOSIT") check(false, "TOP_UP_REQUIRES_CARD", 409);
    check(input.currency === "VND", "CURRENCY_NOT_SUPPORTED", 409);
    return d.bank.requests.demoIntent(a, input);
  });
  route("post", "/external-transfers", (a, r) => {
    const input = { ...external.parse(r.body), type: "EXTERNAL_TRANSFER" };
    check(input.currency === "VND", "CURRENCY_NOT_SUPPORTED", 409);
    return d.bank.requests.demoIntent(a, input);
  });
  route("get", "/demo-movements/by-request/:id", (a, r) =>
    d.bank.requests.getDemo(a, String(r.params.id)),
  );
  route("post", "/demo-movements/:id/commit", async (a, r) => {
    const id = String(r.params.id);
    if ((await d.bank.security.describe(a, id)).provider === "STRIPE_TREASURY") {
      check(d.stripeSandboxMovements, "SETUP_REQUIRED", 503);
      return d.stripeSandboxMovements.commit(a, id, "DEMO_MOVEMENT");
    }
    return d.bank.requests.commitDemo(a, id);
  });
  route("post", "/demo-movements/:id/reconcile", async (a, r) => {
    const id = String(r.params.id);
    if ((await d.bank.security.describe(a, id)).provider === "STRIPE_TREASURY") {
      check(d.stripeSandboxMovements, "SETUP_REQUIRED", 503);
      return d.stripeSandboxMovements.commit(a, id, "DEMO_MOVEMENT");
    }
    return d.bank.requests.reconcileDemo(a, id);
  });
  route("get", "/money-requests/by-request/:id", (a, r) =>
    d.bank.scoped(a, "moneyRequests", digest([a.uid, "MONEY_REQUEST", id.parse(r.params.id)])),
  );
  route("get", "/money-requests", async (a, r) =>
    page(await d.bank.listOwned(a, "moneyRequests", r.query), r.query),
  );
}
