import type { Banking } from "../../core/banking.js";
import type { Doc } from "../../core/store.js";
import { digest } from "../../shared/crypto.js";
import { check, key, minor } from "../../shared/errors.js";
import type { Actor } from "../auth/auth.types.js";
export class RequestService {
  constructor(private bank: Banking) {}
  async create(a: Actor, input: Doc) {
    key(input.requestId);
    key(input.accountId);
    minor(input.amountMinor);
    check(["DEPOSIT", "WITHDRAWAL"].includes(input.type), "INVALID_TYPE");
    const id = digest([a.uid, "MONEY_REQUEST", input.requestId]),
      hash = digest(input);
    return this.bank.store.run(async (t) => {
      await this.bank.authorize(t, a);
      const acc = await this.bank.owned(t, a, input.accountId);
      check(
        acc.currency === input.currency && acc.status === "ACTIVE",
        "INVALID_ACCOUNT",
      );
      const old = await t.get("moneyRequests/" + id);
      if (old) {
        check(old.digest === hash, "IDEMPOTENCY_CONFLICT", 409);
        return old;
      }
      const d = {
        ...input,
        id,
        ownerUid: a.uid,
        digest: hash,
        status: "REQUESTED",
        createdAt: this.bank.now(),
      };
      t.set("moneyRequests/" + id, d);
      this.bank.audit(t, a, "MONEY_REQUEST", id, input.requestId);
      return d;
    });
  }
}
