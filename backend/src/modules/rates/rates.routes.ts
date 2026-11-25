import { z } from "zod";
import type { AppDependencies, Route } from "../../core/http.js";
const common = {
  annualRateBasisPoints: z.number().int().min(0).max(10000),
  effectiveFrom: z.number().int(),
};
const rateRequest = z.object({
  ...common,
  rateType: z.enum(["SAVINGS", "LOAN"]),
  reason: z.string().trim().min(1).max(240),
}).strict();
export function registerRatesRoutes(route: Route, d: AppDependencies) {
  route("get", "/officer/rate-types", (a) => d.bank.rates.catalog(a));
  // Keep the old path as a compatibility alias for already installed clients.
  route("get", "/officer/rate-products", (a) => d.bank.rates.catalog(a));
  route("get", "/officer/rate-policies", (a, r) =>
    d.bank.rates.list(a, pageLimit(queryString(r.query.limit)), queryString(r.query.cursor)),
  );
  route("post", "/officer/rate-policies", (a, r) =>
    d.bank.rates.create(a, rateRequest.parse(r.body)),
  );
}
function pageLimit(raw?: string) { return raw === undefined ? 25 : Math.min(100, Math.max(1, Number(raw) || 25)); }
function queryString(value: unknown): string | undefined { return typeof value === "string" ? value : undefined; }
