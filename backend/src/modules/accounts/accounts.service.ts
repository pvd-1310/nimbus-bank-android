import type { Banking } from "../../core/banking.js";
import type { Doc } from "../../core/store.js";
import { check } from "../../shared/errors.js";
import { queryOptions } from "../../shared/pagination.js";
import type { Actor } from "../auth/auth.types.js";
import { mortgageSchedule, savingsEstimate } from "./calculations.js";
export class AccountService {
  constructor(private bank: Banking) {}
  async details(a: Actor, id: string) {
    const acc = await this.get(a, id);
    const collection = (
      {
        CHECKING: "checkingDetails",
        SAVINGS: "savingsDetails",
        MORTGAGE: "mortgageDetails",
      } as Doc
    )[acc.type];
    check(collection, "INVALID_TYPE");
    return this.bank.store.run(async (t) => {
      await this.bank.authorize(t, a);
      await this.bank.owned(t, a, acc.id);
      const detail = await t.get(collection + "/" + acc.id);
      check(detail, "DETAILS_UNAVAILABLE", 404);
      return { account: acc, details: detail };
    });
  }
  async savings(a: Actor, id: string, days: number) {
    return this.bank.store.run(async (t) => {
      await this.bank.authorize(t, a);
      const acc = await this.bank.owned(t, a, id);
      check(acc.type === "SAVINGS", "INVALID_TYPE");
      const detail = await t.get("savingsDetails/" + acc.id);
      check(detail, "DETAILS_UNAVAILABLE", 404);
      const pinned = await t.get("ratePolicies/" + detail.ratePolicyVersion);
      check(pinned, "POLICY_UNAVAILABLE");
      let rate: Doc = { ...pinned, id: detail.ratePolicyVersion };
      const productId = detail.productId ?? pinned.productId;
      if (productId) {
        let after: string | undefined;
        const now = this.bank.now();
        do {
          const policies = await t.list("ratePolicies", { productId }, { after, limit: 100 });
          for (const policy of policies) {
            if (policy.effectiveFrom <= now &&
                (rate.effectiveFrom > now || policy.effectiveFrom > (rate.effectiveFrom ?? 0)
                || (policy.effectiveFrom === rate.effectiveFrom && policy.version > rate.version))) {
              rate = policy;
            }
          }
          after = policies.length === 100 ? policies.at(-1)?.id : undefined;
        } while (after);
      }
      check(rate.effectiveFrom == null || rate.effectiveFrom <= this.bank.now(), "POLICY_UNAVAILABLE");
      return {
        demo: acc.demo === true,
        principalMinor: detail.principalMinor,
        annualRateBasisPoints: rate.annualRateBasisPoints,
        effectiveFrom: rate.effectiveFrom ?? null,
        openedAt: detail.openedAt ?? null,
        maturityAt: detail.maturityAt ?? null,
        days,
        profitMinor: savingsEstimate(
          detail.principalMinor,
          rate.annualRateBasisPoints,
          days,
        ),
        currency: acc.currency,
        convention: "ACT/365 SIMPLE HALF_UP",
        ratePolicyVersion: rate.id,
        estimate: true,
      };
    });
  }
  async mortgage(a: Actor, id: string, frequency?: "MONTHLY" | "BIWEEKLY") {
    return this.bank.store.run(async (t) => {
      await this.bank.authorize(t, a);
      const acc = await this.bank.owned(t, a, id);
      check(acc.type === "MORTGAGE", "INVALID_TYPE");
      const terms = await t.get("mortgageDetails/" + acc.id);
      check(terms, "DETAILS_UNAVAILABLE", 404);
      const annualRateBasisPoints = terms.annualRateBasisPoints ?? Math.round(terms.annualRate * 10000);
      const principal = terms.loanPrincipalMinor;
      check(["MONTHLY", "BIWEEKLY"].includes(terms.paymentFrequency), "INVALID_TERMS");
      const monthly = mortgageSchedule(principal, annualRateBasisPoints, terms.termMonths, "MONTHLY");
      const biweekly = mortgageSchedule(principal, annualRateBasisPoints, terms.termMonths, "BIWEEKLY");
      const paymentFrequency = frequency ?? terms.paymentFrequency;
      const items = paymentFrequency === "BIWEEKLY" ? biweekly : monthly;
      const totalMinor = items.reduce((total, item) => total + item.totalMinor, 0);
      check(Number.isSafeInteger(totalMinor), "AMOUNT_OVERFLOW");
      return {
        currency: acc.currency,
        demo: acc.demo === true,
        projected: true,
        principalMinor: principal,
        outstandingMinor: terms.outstandingMinor ?? null,
        annualRateBasisPoints,
        termMonths: terms.termMonths,
        paymentFrequency,
        monthlyFirstPaymentMinor: monthly[0].totalMinor,
        biweeklyFirstPaymentMinor: biweekly[0].totalMinor,
        totalMinor,
        convention: "ANNUITY PERIODIC HALF_UP",
        items,
      };
    });
  }
  async list(a: Actor, q: Doc = {}) {
    return this.bank.store.run(async (t) => {
      await this.bank.authorize(t, a);
      return t.list("accounts", { ownerUid: a.uid }, queryOptions(q));
    });
  }
  async get(a: Actor, id: string) {
    return this.bank.store.run(async (t) => {
      await this.bank.authorize(t, a);
      return this.bank.owned(t, a, id);
    });
  }
}
