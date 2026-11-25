import type { Banking } from "../../core/banking.js";
import type { Doc } from "../../core/store.js";
import { check } from "../../shared/errors.js";
import { queryOptions } from "../../shared/pagination.js";
import type { Actor } from "../auth/auth.types.js";
import { mortgageSchedule, savingsMonthlyEstimate } from "./calculations.js";
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
  async savings(a: Actor, id: string, month?: string) {
    return this.bank.store.run(async (t) => {
      await this.bank.authorize(t, a);
      const acc = await this.bank.owned(t, a, id);
      check(acc.type === "SAVINGS", "INVALID_TYPE");
      const detail = await t.get("savingsDetails/" + acc.id);
      check(detail, "DETAILS_UNAVAILABLE", 404);
      const pinned = await t.get("ratePolicies/" + detail.ratePolicyVersion);
      check(pinned, "POLICY_UNAVAILABLE");
      const selectedMonth = month ?? new Date(this.bank.now()).toISOString().slice(0, 7);
      const monthMatch = /^(\d{4})-(0[1-9]|1[0-2])$/.exec(selectedMonth);
      check(monthMatch, "INVALID_TERMS");
      const year = Number(monthMatch[1]);
      const monthIndex = Number(monthMatch[2]) - 1;
      const startDate = new Date(0);
      startDate.setUTCFullYear(year, monthIndex, 1);
      startDate.setUTCHours(0, 0, 0, 0);
      const endDate = new Date(0);
      endDate.setUTCFullYear(year, monthIndex + 1, 1);
      endDate.setUTCHours(0, 0, 0, 0);
      const periodStart = startDate.getTime();
      const periodEnd = endDate.getTime();

      const productId = detail.productId ?? pinned.productId;
      const productPolicies: Doc[] = [];
      if (typeof productId === "string" && productId.length > 0) {
        let after: string | undefined;
        do {
          const policies = await t.list("ratePolicies", { productId }, { after, limit: 100 });
          productPolicies.push(...policies);
          after = policies.length === 100 ? policies.at(-1)?.id : undefined;
        } while (after);
      }
      const bankPolicies = await t.list("ratePolicies", { rateType: "SAVINGS" }, { limit: 1000 });
      const firstBankEffectiveFrom = bankPolicies.reduce<number | null>((first, policy) =>
        first == null || policy.effectiveFrom < first ? policy.effectiveFrom : first, null);
      const ratePolicies = [
        ...productPolicies.filter((policy) => firstBankEffectiveFrom == null || policy.effectiveFrom < firstBankEffectiveFrom),
        ...bankPolicies,
      ];
      if (ratePolicies.length === 0) {
        check(pinned.productId == null || pinned.productId === productId, "POLICY_UNAVAILABLE");
        ratePolicies.push({ ...pinned, id: detail.ratePolicyVersion });
      }
      const calculationPolicies = ratePolicies.map((policy) => ({
        id: String(policy.id),
        effectiveFrom: Number.isSafeInteger(policy.effectiveFrom)
          ? policy.effectiveFrom : periodStart,
        annualRateBasisPoints: policy.annualRateBasisPoints,
        version: policy.version,
      }));
      const now = this.bank.now();
      const currentPolicy = calculationPolicies
        .filter((policy) => policy.effectiveFrom <= now)
        .sort((x, y) => x.effectiveFrom - y.effectiveFrom || (x.version ?? 0) - (y.version ?? 0))
        .at(-1);
      check(currentPolicy, "POLICY_UNAVAILABLE");
      const estimatePolicies = selectedMonth === new Date(now).toISOString().slice(0, 7)
        ? [{ ...currentPolicy, effectiveFrom: periodStart }]
        : calculationPolicies;
      const estimate = savingsMonthlyEstimate(
        detail.principalMinor,
        periodStart,
        periodEnd,
        estimatePolicies,
      );
      return {
        demo: acc.demo === true,
        principalMinor: detail.principalMinor,
        balanceMinor: acc.availableMinor ?? acc.balanceMinor ?? detail.principalMinor,
        annualRateBasisPoints: currentPolicy.annualRateBasisPoints,
        effectiveFrom: currentPolicy.effectiveFrom,
        openedAt: detail.openedAt ?? null,
        maturityAt: detail.maturityAt ?? null,
        month: selectedMonth,
        periodStart,
        periodEnd,
        days: estimate.days,
        profitMinor: estimate.profitMinor,
        monthlyProfitMinor: estimate.profitMinor,
        currency: acc.currency,
        convention: "ANNUAL_RATE_DIVIDED_BY_12 SIMPLE HALF_UP",
        ratePolicyVersion: currentPolicy.id,
        ratePolicyVersions: estimate.policyIds,
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
      const bankPolicies = await t.list("ratePolicies", { rateType: "LOAN" }, { limit: 1000 });
      const currentBankPolicy = bankPolicies
        .filter((policy) => policy.effectiveFrom <= this.bank.now())
        .sort((a, b) => a.effectiveFrom - b.effectiveFrom || (a.version ?? 0) - (b.version ?? 0))
        .at(-1);
      const annualRateBasisPoints = currentBankPolicy?.annualRateBasisPoints
        ?? terms.annualRateBasisPoints
        ?? Math.round(terms.annualRate * 10000);
      const originalPrincipal = terms.loanPrincipalMinor;
      const outstanding = terms.outstandingMinor ?? originalPrincipal;
      const remainingTermMonths = terms.remainingTermMonths ?? terms.termMonths;
      check(["MONTHLY", "BIWEEKLY"].includes(terms.paymentFrequency), "INVALID_TERMS");
      const monthly = mortgageSchedule(outstanding, annualRateBasisPoints, remainingTermMonths, "MONTHLY");
      const biweekly = mortgageSchedule(outstanding, annualRateBasisPoints, remainingTermMonths, "BIWEEKLY");
      const paymentFrequency = frequency ?? terms.paymentFrequency;
      const items = paymentFrequency === "BIWEEKLY" ? biweekly : monthly;
      const totalMinor = items.reduce((total, item) => total + item.totalMinor, 0);
      check(Number.isSafeInteger(totalMinor), "AMOUNT_OVERFLOW");
      return {
        currency: acc.currency,
        demo: acc.demo === true,
        projected: true,
        principalMinor: originalPrincipal,
        startingBalanceMinor: outstanding,
        outstandingMinor: outstanding,
        annualRateBasisPoints,
        effectiveFrom: currentBankPolicy?.effectiveFrom ?? null,
        termMonths: remainingTermMonths,
        remainingTermMonths,
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
