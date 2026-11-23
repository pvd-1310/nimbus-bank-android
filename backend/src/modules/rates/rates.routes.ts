import { z } from "zod";
import type { AppDependencies, Route } from "../../core/http.js";
import { page, queryOptions } from "../../shared/pagination.js";
import { id } from "../../shared/validation.js";
const common = {
  annualRateBasisPoints: z.number().int().min(0).max(10000),
  effectiveFrom: z.number().int(),
};
const rateRequest = z.union([
  z
    .object({ ...common, accountProduct: id })
    .strict()
    .transform((x) => ({
      productId: x.accountProduct,
      annualRateBasisPoints: x.annualRateBasisPoints,
      effectiveFrom: x.effectiveFrom,
    })),
  z.object({ ...common, productId: id }).strict(),
]);
export function registerRatesRoutes(route: Route, d: AppDependencies) {
  route("get", "/officer/rate-policies", (a, r) =>
    d.bank.store.run(async (t) => {
      await d.bank.authorize(t, a, "RATE_UPDATE");
      return page(await t.list("ratePolicies", {}, queryOptions(r.query)), r.query);
    }),
  );
  route("post", "/officer/rate-policies", (a, r) =>
    d.bank.rates.create(a, rateRequest.parse(r.body)),
  );
}
