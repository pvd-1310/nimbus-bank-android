import { randomUUID } from "node:crypto";
import { z } from "zod";
import type { AppDependencies, Route } from "../../core/http.js";
import { check, key } from "../../shared/errors.js";
import { page, queryOptions } from "../../shared/pagination.js";
import { id } from "../../shared/validation.js";
export function registerOfficerRoutes(route: Route, d: AppDependencies) {
  for (const [path, collection, permission] of [
    ["customers", "customers", "CUSTOMER_ADMIN"],
    ["ekyc", "ekycRequests", "EKYC_REVIEW"],
    ["audit", "auditLogs", "AUDIT_READ"],
  ] as const)
    route("get", "/officer/" + path, async (a, r) =>
      d.bank.store.run(async (t) => {
        await d.bank.authorize(t, a, permission);
        return page(
          await t.list(collection, {}, queryOptions(r.query)),
          r.query,
        );
      }),
    );
  route("post", "/officer/customers", async (a, r) => {
    check(d.provision, "SETUP_REQUIRED", 503);
    return d.provision(
      a,
      z
        .object({
          requestId: id,
          email: z.email(),
          fullName: z.string().min(2).max(120),
          customerNo: z.string().min(1).max(60).optional(),
          address: z.string().min(1).max(300).optional(),
        })
        .strict()
        .parse(r.body),
    );
  });
  route("patch", "/officer/customers/:id", async (a, r) => {
    const x = z
        .object({
          fullName: z.string().min(2).max(120),
          reason: z.string().min(3).max(500),
        })
        .strict()
        .parse(r.body),
      uid = String(r.params.id);
    key(uid);
    return d.bank.store.run(async (t) => {
      await d.bank.authorize(t, a, "CUSTOMER_ADMIN");
      const u = await t.get("users/" + uid);
      check(u?.role === "CUSTOMER", "NOT_FOUND", 404);
      t.set("users/" + uid, { ...u, fullName: x.fullName });
      t.set("customerChanges/" + randomUUID(), {
        customerUid: uid,
        before: u.fullName ?? "",
        after: x.fullName,
        reason: x.reason,
        actorUid: a.uid,
        createdAt: d.bank.now(),
      });
      d.bank.audit(t, a, "CUSTOMER_UPDATE", uid);
      return { updated: true };
    });
  });
}
