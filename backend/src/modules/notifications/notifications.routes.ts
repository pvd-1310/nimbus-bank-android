import type { AppDependencies, Route } from "../../core/http.js";
import { page } from "../../shared/pagination.js";
export function registerNotificationsRoutes(route: Route, d: AppDependencies) {
  route("get", "/notifications", async (a, r) =>
    page(await d.bank.listOwned(a, "notifications", r.query), r.query),
  );
  route("post", "/notifications/:id/read", (a, r) =>
    d.bank.notifications.markRead(a, String(r.params.id)),
  );
}
