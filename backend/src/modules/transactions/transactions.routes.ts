import { z } from "zod";
import type { AppDependencies, Route } from "../../core/http.js";
import { page } from "../../shared/pagination.js";
import { id } from "../../shared/validation.js";
export function registerTransactionsRoutes(route: Route, d: AppDependencies) {
  route("get", "/transactions", async (a, r) => {
    const filters = z
      .object({
        accountId: id.optional(),
        type: z.string().max(40).optional(),
        status: z.string().max(40).optional(),
        from: z.coerce.number().int().optional(),
        to: z.coerce.number().int().optional(),
        cursor: z
          .string()
          .regex(/^[A-Za-z0-9_-]{1,256}$/)
          .optional(),
        limit: z.coerce.number().int().min(1).max(100).optional(),
      })
      .strict()
      .parse(r.query);
    return page(await d.bank.transactions.list(a, filters), filters);
  });
}
