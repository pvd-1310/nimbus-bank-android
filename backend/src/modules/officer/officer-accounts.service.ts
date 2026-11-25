import type { Banking } from "../../core/banking.js";
import type { Doc } from "../../core/store.js";
import { normalizeSearchText } from "../../shared/search.js";
import { check, key } from "../../shared/errors.js";
import { page, queryOptions } from "../../shared/pagination.js";
import type { Actor } from "../auth/auth.types.js";

export class OfficerAccountsService {
  constructor(private readonly bank: Banking) {}

  async listCheckingAccounts(actor: Actor, query: Doc) {
    const options = queryOptions(query);
    const q = typeof query.q === "string" ? normalizeSearchText(query.q) : "";
    return this.bank.store.run(async (t) => {
      await this.bank.authorize(t, actor, "CUSTOMER_ADMIN");
      let accounts: Doc[];
      const exact = q && /^[a-zA-Z0-9_-]{1,128}$/.test(query.q)
        ? await t.get("accounts/" + query.q)
        : undefined;
      if (exact?.type === "CHECKING") {
        accounts = [exact];
      } else {
        accounts = await t.list("accounts", { type: "CHECKING" }, {
          limit: options.limit,
          after: options.after,
          ...(q ? { prefix: { field: "ownerNameSearch", value: q } } : {}),
        });
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
