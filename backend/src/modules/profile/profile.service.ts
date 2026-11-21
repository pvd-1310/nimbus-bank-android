import type { Banking } from "../../core/banking.js";
import type { Doc } from "../../core/store.js";
import { check } from "../../shared/errors.js";
import type { Actor } from "../auth/auth.types.js";
export class ProfileService {
  constructor(private bank: Banking) {}
  async get(a: Actor) {
    return this.bank.store.run(async (t) => {
      const u = await this.bank.authorize(t, a);
      return {
        uid: a.uid,
        fullName: u.fullName ?? "",
        emailMasked: u.emailMasked ?? "",
        phoneMasked: u.phoneMasked ?? "",
        preferences: u.preferences ?? {},
        status: u.status ?? (u.active ? "ACTIVE" : "INACTIVE"),
        createdAt: u.createdAt ?? null,
      };
    });
  }
  async update(a: Actor, patch: Doc) {
    return this.bank.store.run(async (t) => {
      const u = await this.bank.authorize(t, a);
      check(
        Object.keys(patch).every((k) =>
          ["contactEmail", "contactPhone", "preferences"].includes(k),
        ),
        "PROTECTED_FIELD",
      );
      if (patch.preferences)
        check(
          Object.keys(patch.preferences).every((k) =>
            ["language", "notificationsEnabled"].includes(k),
          ),
          "INVALID_PREFERENCE",
        );
      t.set("users/" + a.uid, { ...u, ...patch });
      this.bank.audit(t, a, "PROFILE_UPDATE", a.uid, "", {
        changedFields: Object.keys(patch),
        before: { preferences: u.preferences ?? {} },
        after: { preferences: patch.preferences ?? u.preferences ?? {} },
      });
      return { updated: true };
    });
  }
}
