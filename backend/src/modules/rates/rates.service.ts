import type { Banking } from "../../core/banking.js";
import type { Doc } from "../../core/store.js";
import { check } from "../../shared/errors.js";
import type { Actor } from "../auth/auth.types.js";
export class RateService {
  constructor(private bank: Banking) {}
  async catalog(a: Actor) {
    return this.bank.store.run(async (t) => {
      await this.bank.authorize(t, a, "RATE_UPDATE");
      return [
        { rateType: "SAVINGS", name: "Lãi suất tiết kiệm" },
        { rateType: "LOAN", name: "Lãi vay" },
      ];
    });
  }
  async list(a: Actor, limit: number, after?: string) {
    return this.bank.store.run(async (t) => {
      await this.bank.authorize(t, a, "RATE_UPDATE");
      const policies = await t.list("ratePolicies", {}, { limit: limit + 1, ...(after ? { after } : {}) });
      const now = this.bank.now();
      const all = await t.list("ratePolicies", {}, { limit: 1000 });
      const currentByPolicyGroup = new Map<string, { effectiveFrom: number; version: number }>();
      for (const policy of all) if (policy.effectiveFrom <= now) {
        const rateType = policy.rateType ?? (policy.productId ? "SAVINGS" : "UNKNOWN");
        const group = policy.rateType ? `type:${rateType}` : `product:${policy.productId ?? "unknown"}`;
        const old = currentByPolicyGroup.get(group);
        if (!old || policy.effectiveFrom > old.effectiveFrom || (policy.effectiveFrom === old.effectiveFrom && policy.version > old.version))
          currentByPolicyGroup.set(group, { effectiveFrom: policy.effectiveFrom, version: policy.version });
      }
      const rows = policies.slice(0, limit).map(({ _cursor, ...policy }) => {
        const rateType = policy.rateType ?? (policy.productId ? "SAVINGS" : "UNKNOWN");
        const group = policy.rateType ? `type:${rateType}` : `product:${policy.productId ?? "unknown"}`;
        return {
          ...policy,
          rateType,
          createdBy: policy.createdBy ?? policy.updatedBy,
          status: policy.effectiveFrom > now ? "SCHEDULED" : currentByPolicyGroup.get(group)?.effectiveFrom === policy.effectiveFrom && currentByPolicyGroup.get(group)?.version === policy.version ? "CURRENT" : "ENDED",
        };
      });
      return { items: rows, nextCursor: policies.length > limit ? policies[limit - 1]._cursor ?? policies[limit - 1].id : null };
    });
  }
  async create(a: Actor, input: Doc) {
    check(input.rateType === "SAVINGS" || input.rateType === "LOAN", "INVALID_RATE_TYPE");
    check(typeof input.reason === "string" && input.reason.trim().length > 0 && input.reason.trim().length <= 240, "INVALID_TERMS");
    const limits = this.bank.options.rateLimits ?? { minBasisPoints: 0, maxBasisPoints: 10000 };
    const now = this.bank.now();
    const utcStartOfToday = new Date(now);
    utcStartOfToday.setUTCHours(0, 0, 0, 0);
    check(
      Number.isInteger(input.annualRateBasisPoints) &&
        input.annualRateBasisPoints >= limits.minBasisPoints &&
        input.annualRateBasisPoints <= limits.maxBasisPoints &&
        (input.effectiveFrom > now || input.effectiveFrom >= utcStartOfToday.getTime()),
      "INVALID_TERMS",
    );
    return this.bank.store.run(async (t) => {
      await this.bank.authorize(t, a, "RATE_UPDATE");
      const policyKey = String(input.rateType).toLowerCase();
      const headPath = "policyHeads/bank_" + policyKey;
      const head = await t.get(headPath);
      const version = (head?.version ?? 0) + 1;
      const id = "bank_" + policyKey + "_" + version;
      t.set("ratePolicies/" + id, { ...input, reason: input.reason.trim(), id, version, createdBy: a.uid });
      t.set(headPath, { version });
      this.bank.audit(t, a, "RATE_UPDATE", id, "", {
        before: { version: head?.version ?? null },
        after: {
          version,
          rateType: input.rateType,
          annualRateBasisPoints: input.annualRateBasisPoints,
          effectiveFrom: input.effectiveFrom,
          reason: input.reason.trim(),
        },
      });
      return { id, policyId: id, rateType: input.rateType, version };
    });
  }
}
