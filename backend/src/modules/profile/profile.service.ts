import type { Banking } from "../../core/banking.js";
import type { Doc } from "../../core/store.js";
import { check } from "../../shared/errors.js";
import { releaseContactPhone, reserveContactPhone } from "./contact-phone-reservations.js";
import type { Actor } from "../auth/auth.types.js";
export class ProfileService {
  constructor(private bank: Banking) {}
  async get(a: Actor) {
    return this.bank.store.run(async (t) => {
      const u = await this.bank.authorize(t, a, undefined, true);
      return {
        uid: a.uid,
        fullName: u.fullName ?? "",
        emailMasked: u.emailMasked ?? "",
        phoneMasked: u.phoneMasked ?? "",
        contactEmail: u.contactEmail ?? "",
        contactPhone: u.contactPhone ?? "",
        preferences: u.preferences ?? {},
        status: u.status ?? (u.active ? "ACTIVE" : "INACTIVE"),
        createdAt: u.createdAt ?? null,
      };
    });
  }
  async update(a: Actor, patch: Doc, requestId = "") {
    return this.bank.store.run(async (t) => {
      const u = await this.bank.authorize(t, a, undefined, true);
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
      const before: Doc = {}, after: Doc = {};
      const nextPatch = { ...patch };
      if (patch.preferences) nextPatch.preferences = { ...(u.preferences ?? {}), ...patch.preferences };
      if (typeof patch.contactPhone === "string" && patch.contactPhone !== u.contactPhone) {
        await reserveContactPhone(t, a.uid, patch.contactPhone, this.bank.now());
        await releaseContactPhone(t, a.uid, typeof u.contactPhone === "string" ? u.contactPhone : undefined, this.bank.now());
        nextPatch.phoneMasked = "••••" + patch.contactPhone.slice(-4);
      }
      for (const field of Object.keys(patch)) {
        before[field] = field === "contactEmail" ? this.maskEmail(u[field])
          : field === "contactPhone" ? this.maskPhone(u[field]) : u[field] ?? null;
        const value = nextPatch[field];
        after[field] = field === "contactEmail" ? this.maskEmail(value)
          : field === "contactPhone" ? this.maskPhone(value) : value;
      }
      t.set("users/" + a.uid, { ...u, ...nextPatch });
      this.bank.audit(t, a, "PROFILE_UPDATE", a.uid, requestId, {
        changedFields: Object.keys(patch),
        before,
        after,
      });
      return { updated: true };
    });
  }

  private maskEmail(value: unknown) {
    if (typeof value !== "string") return null;
    const at = value.indexOf("@");
    return at > 0 ? value[0] + "***" + value.slice(at) : "***";
  }

  private maskPhone(value: unknown) {
    if (typeof value !== "string") return null;
    return "••••" + value.slice(-4);
  }
}
