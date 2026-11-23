import { randomUUID } from "node:crypto";
import { z } from "zod";
import type { AppDependencies, Route } from "../../core/http.js";
import type { Doc } from "../../core/store.js";
import { check, key } from "../../shared/errors.js";
import { page, queryOptions } from "../../shared/pagination.js";
import { id } from "../../shared/validation.js";
import { releaseContactPhone, reserveContactPhone } from "../profile/contact-phone-reservations.js";
export function registerOfficerRoutes(route: Route, d: AppDependencies) {
  for (const [path, collection, permission] of [
    ["customers", "customers", "CUSTOMER_ADMIN"],
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
          phoneNumber: z.string().regex(/^(?:0[0-9]{9}|\+84[0-9]{9})$/)
            .transform((phone) => phone.startsWith("0") ? "+84" + phone.slice(1) : phone),
          customerNo: z.string().trim().min(1).max(60).optional(),
          address: z.string().trim().min(1).max(300).optional(),
        })
        .strict()
        .parse(r.body),
    );
  });
  route("get", "/officer/customers/:id/changes", async (a, r) => {
    const uid = String(r.params.id);
    key(uid);
    return d.bank.store.run(async (t) => {
      await d.bank.authorize(t, a, "CUSTOMER_ADMIN");
      const user = await t.get(`users/${uid}`);
      check(user?.role === "CUSTOMER", "NOT_FOUND", 404);
      return page(
        await t.list("customerChanges", { customerUid: uid }, queryOptions(r.query)),
        r.query,
      );
    });
  });
  route("patch", "/officer/customers/:id", async (a, r) => {
    const x = z
        .object({
          fullName: z.string().trim().min(2).max(120).optional(),
          contactEmail: z.email().max(254).optional(),
          contactPhone: z.string().trim().regex(/^\+[1-9][0-9]{7,14}$/).optional(),
          address: z.string().trim().min(1).max(300).optional(),
          reason: z.string().trim().min(3).max(500),
        })
        .refine((value) => Object.keys(value).some((field) => field !== "reason"), "NO_FIELDS_TO_UPDATE")
        .strict()
        .parse(r.body),
      uid = String(r.params.id);
    key(uid);
    return d.bank.store.run(async (t) => {
      await d.bank.authorize(t, a, "CUSTOMER_ADMIN");
      const u = await t.get("users/" + uid);
      check(u?.role === "CUSTOMER", "NOT_FOUND", 404);
      const customer = await d.bank.linkedProfile(t, "customers", uid);
      check(customer, "CUSTOMER_PROFILE_NOT_FOUND", 404);
      const userPatch: Doc = {}, customerPatch: Doc = {}, before: Doc = {}, after: Doc = {};
      for (const field of ["fullName", "contactEmail", "contactPhone", "address"] as const) {
        const value = x[field];
        if (value === undefined) continue;
        const source = field === "address" ? customer : u;
        before[field] = source[field] ?? null;
        after[field] = value;
        if (field === "address") customerPatch.address = value;
        else userPatch[field] = value;
      }
      if (userPatch.contactPhone) {
        const nextPhone = String(userPatch.contactPhone);
        if (nextPhone !== u.contactPhone) {
          await reserveContactPhone(t, uid, nextPhone, d.bank.now());
          await releaseContactPhone(t, uid, typeof u.contactPhone === "string" ? u.contactPhone : undefined, d.bank.now());
        }
        userPatch.phoneMasked = "••••" + nextPhone.slice(-4);
      }
      t.set("users/" + uid, { ...u, ...userPatch });
      const { id: customerId, _cursor, ...customerData } = customer;
      t.set(`customers/${customerId}`, { ...customerData, ...customerPatch });
      const changeId = randomUUID();
      t.set("customerChanges/" + changeId, {
        id: changeId,
        customerUid: uid,
        before,
        after,
        reason: x.reason,
        actorUid: a.uid,
        createdAt: d.bank.now(),
        requestId: r.res?.locals.requestId ?? "",
      });
      const mask = (field: string, value: unknown) => {
        if (typeof value !== "string") return value;
        if (field === "contactEmail") {
          const at = value.indexOf("@");
          return at > 0 ? value[0] + "***" + value.slice(at) : "***";
        }
        if (field === "contactPhone") return "••••" + value.slice(-4);
        return value;
      };
      const safeBefore: Doc = {}, safeAfter: Doc = {};
      for (const field of Object.keys(before)) {
        safeBefore[field] = mask(field, before[field]);
        safeAfter[field] = mask(field, after[field]);
      }
      d.bank.audit(t, a, "CUSTOMER_UPDATE", uid, r.res?.locals.requestId ?? "", {
        changedFields: Object.keys(after), before: safeBefore, after: safeAfter, reason: x.reason,
      });
      return { updated: true, changeId, changes: { before, after, reason: x.reason } };
    });
  });
}
