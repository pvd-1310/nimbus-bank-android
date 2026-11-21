import type { AppDependencies, Route } from "../../core/http.js";
import { Fault } from "../../shared/errors.js";
import { page, queryOptions } from "../../shared/pagination.js";
export function registerBranchesRoutes(route: Route, d: AppDependencies) {
  route("get", "/branches", async (_a, r) =>
    d.bank.store.run(async (t) =>
      page(
        await t.list("bankBranches", { active: true }, queryOptions(r.query)),
        r.query,
      ),
    ),
  );
  route("post", "/branches/routes", async () => {
    throw new Fault("SETUP_REQUIRED", 503);
  });
}
