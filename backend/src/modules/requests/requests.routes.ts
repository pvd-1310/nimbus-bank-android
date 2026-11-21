import { z } from "zod";
import type { AppDependencies, Route } from "../../core/http.js";
import { page } from "../../shared/pagination.js";
import { amount, currency, id } from "../../shared/validation.js";
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
  route("get", "/money-requests", async (a, r) =>
    page(await d.bank.listOwned(a, "moneyRequests", r.query), r.query),
  );
}
