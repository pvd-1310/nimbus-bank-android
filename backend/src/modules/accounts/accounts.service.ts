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
    const acc = await this.get(a, id);
    check(acc.type === "SAVINGS", "INVALID_TYPE");
    return this.bank.store.run(async (t) => {
      const detail = await t.get("savingsDetails/" + acc.id);
      check(detail, "DETAILS_UNAVAILABLE", 404);
      const rate = await t.get("ratePolicies/" + detail.ratePolicyVersion);
      check(rate, "POLICY_UNAVAILABLE");
      return {
        profitMinor: savingsEstimate(
          detail.principalMinor,
          rate.annualRateBasisPoints,
          days,
        ),
        currency: acc.currency,
        convention: "ACT/365 SIMPLE HALF_UP",
        ratePolicyVersion: detail.ratePolicyVersion,
        estimate: true,
      };
    });
  }
  async mortgage(a: Actor, id: string) {
    const acc = await this.get(a, id);
    check(acc.type === "MORTGAGE", "INVALID_TYPE");
    return this.bank.store.run(async (t) => {
      const terms = await t.get("mortgageDetails/" + acc.id);
      check(terms, "DETAILS_UNAVAILABLE");
      return {
        currency: acc.currency,
        projected: true,
        items: mortgageSchedule(
          terms.loanPrincipalMinor,
          terms.annualRateBasisPoints ?? Math.round(terms.annualRate * 10000),
          terms.termMonths,
          terms.paymentFrequency,
        ),
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
