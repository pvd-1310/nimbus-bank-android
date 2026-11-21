import type { Banking } from "../../core/banking.js";
import type { Doc } from "../../core/store.js";
import { check, key } from "../../shared/errors.js";
import type { Actor } from "../auth/auth.types.js";
export class RateService {
  constructor(private bank: Banking) {}
  async create(a: Actor, input: Doc) {
    key(input.productId);
    check(
      Number.isInteger(input.annualRateBasisPoints) &&
        input.annualRateBasisPoints >= 0 &&
        input.annualRateBasisPoints <= 10000 &&
        input.effectiveFrom > this.bank.now(),
      "INVALID_TERMS",
    );
    return this.bank.store.run(async (t) => {
      await this.bank.authorize(t, a, "RATE_UPDATE");
      const head = await t.get("policyHeads/" + input.productId);
      const version = (head?.version ?? 0) + 1;
      const id = input.productId + "_" + version;
      t.set("ratePolicies/" + id, { ...input, id, version, updatedBy: a.uid });
      t.set("policyHeads/" + input.productId, { version });
      this.bank.audit(t, a, "RATE_UPDATE", id, "", {
        before: { version: head?.version ?? null },
        after: {
          version,
          accountProduct: input.productId,
          annualRateBasisPoints: input.annualRateBasisPoints,
          effectiveFrom: input.effectiveFrom,
        },
      });
      return { id, policyId: id, accountProduct: input.productId, version };
    });
  }
}
