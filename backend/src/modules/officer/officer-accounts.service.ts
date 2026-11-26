import type { Banking } from "../../core/banking.js";
import type { Doc } from "../../core/store.js";
import { normalizeSearchText, phoneSearchDigits } from "../../shared/search.js";
import { check, key } from "../../shared/errors.js";
import { page, queryOptions } from "../../shared/pagination.js";
import type { Actor } from "../auth/auth.types.js";

export class OfficerAccountsService {
  constructor(private readonly bank: Banking) {}

  async listCheckingAccounts(actor: Actor, query: Doc) {
    const options = queryOptions(query);
    const rawQuery = typeof query.q === "string" ? query.q.trim() : "";
    const q = rawQuery ? normalizeSearchText(rawQuery) : "";
    return this.bank.store.run(async (t) => {
      await this.bank.authorize(t, actor, "CUSTOMER_ADMIN");
      let accounts: Doc[];
      const exactAccount = q && /^[a-zA-Z0-9_-]{1,128}$/.test(rawQuery)
        ? await t.get("accounts/" + rawQuery)
        : undefined;
      if (exactAccount?.type === "CHECKING") accounts = [exactAccount];
      else if (!q) accounts = await t.list("accounts", { type: "CHECKING" }, options);
      else {
        const ownerUids = new Set<string>();

        // A customer ID in this screen is the Firebase UID exposed as customerId.
        const user = await t.get("users/" + rawQuery);
        if (user?.role === "CUSTOMER") ownerUids.add(rawQuery);
        const customer = await t.get("customers/" + rawQuery);
        const customerUid = String(customer?.uidRef ?? customer?.uid ?? rawQuery);
        if (customer && (user?.role === "CUSTOMER" || customer.uidRef || customer.uid))
          ownerUids.add(customerUid);
        for (const match of await t.list("customers", { customerNo: rawQuery }, { limit: 20 })) {
          const uid = String(match.uidRef ?? match.uid ?? match.id ?? "");
          if (uid) ownerUids.add(uid);
        }

        const phoneDigits = phoneSearchDigits(rawQuery);
        if (phoneDigits) {
          const matches = await t.list("users", {}, {
            limit: Math.max(options.limit ?? 25, 100),
            arrayContains: { field: "contactPhoneSearchTokens", value: phoneDigits },
          });
          for (const match of matches) {
            if (match.role !== "CUSTOMER") continue;
            if (typeof match.uid === "string") ownerUids.add(match.uid);
            else if (typeof match.id === "string") ownerUids.add(match.id);
          }
        }

        if (ownerUids.size) {
          accounts = [];
          for (const ownerUid of ownerUids) {
            accounts.push(...await t.list("accounts", { type: "CHECKING", ownerUid }, { limit: options.limit }));
          }
          accounts = accounts.slice(0, options.limit);
        } else if (isPhoneLike(rawQuery) || exactAccount) {
          // An unmatched phone or account ID is a normal empty result, not a name-prefix query.
          accounts = [];
        } else {
          accounts = await t.list("accounts", { type: "CHECKING" }, {
            limit: options.limit,
            after: options.after,
            prefix: { field: "ownerNameSearch", value: q },
          });
        }
      }
      const items = [];
      for (const account of accounts) {
        const ownerUid = String(account.ownerUid ?? "");
        const owner = ownerUid ? await t.get("users/" + ownerUid) : undefined;
        if (!owner || owner.role !== "CUSTOMER") continue;
        items.push({
          accountId: String(account.accountId ?? account.id ?? ""),
          customerId: ownerUid,
          ownerName: String(owner.fullName ?? ""),
          contactPhone: String(owner.contactPhone ?? owner.phoneMasked ?? ""),
          status: String(account.status ?? "UNKNOWN"),
          _cursor: account._cursor,
        });
      }
      return page(items, query);
    });
  }

  async getCustomer(actor: Actor, uid: string) {
    key(uid);
    return this.bank.store.run(async (t) => {
      await this.bank.authorize(t, actor, "CUSTOMER_ADMIN");
      const user = await t.get("users/" + uid);
      check(user?.role === "CUSTOMER", "NOT_FOUND", 404);
      const customer = await this.bank.linkedProfile(t, "customers", uid);
      check(customer, "CUSTOMER_PROFILE_NOT_FOUND", 404);
      return {
        fullName: String(user.fullName ?? ""),
        contactEmail: String(user.contactEmail ?? ""),
        contactPhone: String(user.contactPhone ?? user.phoneMasked ?? ""),
        address: String(customer.address ?? ""),
      };
    });
  }
}

function isPhoneLike(value: string) {
  return /^[+()\d\s-]+$/.test(value) && value.replace(/\D/g, "").length >= 4;
}
