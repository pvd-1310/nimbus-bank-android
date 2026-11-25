import { beforeEach, describe, expect, it } from "vitest";
import { Banking } from "../src/core/banking.js";
import {
  mortgageSchedule,
  savingsMonthlyEstimate,
  savingsEstimate,
} from "../src/modules/accounts/calculations.js";
import { type Actor } from "../src/modules/auth/auth.types.js";
import { MemoryStore } from "./support/memory-store.js";
const customer: Actor = { uid: "alice", role: "CUSTOMER", permissions: [] };
const rateOfficer: Actor = { uid: "rate-officer", role: "OFFICER", permissions: ["RATE_UPDATE"] };
let s: MemoryStore, b: Banking, now: number;
beforeEach(async () => {
  s = new MemoryStore();
  now = 1700000000000;
  b = new Banking(s, {
    otpSecret: "test-secret-with-at-least-32-characters",
    now: () => now,
  });
  await s.run(async (t) => {
    t.set("users/alice", { active: true, role: "CUSTOMER" });
    t.set("users/bob", { active: true, role: "CUSTOMER" });
    t.set("users/rate-officer", { active: true, role: "OFFICER" });
    t.set("officers/rate-officer", { active: true, permissions: ["RATE_UPDATE"] });
    t.set("accounts/a", {
      ownerUid: "alice",
      type: "CHECKING",
      currency: "VND",
      availableMinor: 1000,
      bookedMinor: 1000,
      status: "ACTIVE",
      version: 0,
    });
    t.set("accounts/b", {
      ownerUid: "bob",
      type: "CHECKING",
      currency: "VND",
      availableMinor: 100,
      bookedMinor: 100,
      status: "ACTIVE",
      version: 0,
    });
  });
});
async function authorized(key = "request-0001", amount = 300) {
  const i = await b.transfers.intent(customer, {
    requestId: key,
    sourceAccountId: "a",
    destinationAccountId: "b",
    amountMinor: amount,
    currency: "VND",
  });
  const { challengeId, code } = await b.security.challenge(customer, i.id);
  await b.security.verify(customer, i.id, challengeId, code);
  return i;
}
describe("banking invariants", () => {
  it("enforces daily limit atomically across concurrent intents", async () => {
    await s.run(async (t) =>
      t.set("checkingDetails/a", { dailyLimitMinor: 500, version: 1 }),
    );
    const x = await authorized("limit-one", 300),
      y = await authorized("limit-two", 300);
    const result = await Promise.allSettled([
      b.transfers.commit(customer, x.id),
      b.transfers.commit(customer, y.id),
    ]);
    expect(result.filter((x) => x.status === "fulfilled")).toHaveLength(1);
    expect((await b.accounts.get(customer, "a")).availableMinor).toBe(700);
  });
  it("refuses another customer account", async () => {
    await expect(b.accounts.get(customer, "b")).rejects.toMatchObject({
      code: "FORBIDDEN",
    });
  });
  it("requires server profile to agree with claims", async () => {
    await expect(
      b.accounts.list({ ...customer, role: "OFFICER" }),
    ).rejects.toMatchObject({ code: "FORBIDDEN" });
  });
  it.each([0, -1, 1.5, Number.MAX_SAFE_INTEGER + 1])(
    "rejects invalid minor amount %s",
    async (amount) => {
      await expect(
        b.transfers.intent(customer, {
          requestId: "invalid-001",
          sourceAccountId: "a",
          destinationAccountId: "b",
          amountMinor: amount,
          currency: "VND",
        }),
      ).rejects.toThrow();
    },
  );
  it("commits once with balanced entries on retry", async () => {
    const i = await authorized();
    const r = await b.transfers.commit(customer, i.id);
    expect(await b.transfers.commit(customer, i.id)).toEqual(r);
    expect((await b.accounts.get(customer, "a")).availableMinor).toBe(700);
    const rows = await s.run((t) => t.list("ledgerEntries"));
    expect(rows).toHaveLength(2);
    expect(rows.reduce((n, x) => n + x.signedMinor, 0)).toBe(0);
    expect((await s.run((t) => t.get("accounts/b")))!.availableMinor).toBe(400);
  });
  it("rejects changed payload for same request id", async () => {
    await authorized();
    await expect(
      b.transfers.intent(customer, {
        requestId: "request-0001",
        sourceAccountId: "a",
        destinationAccountId: "b",
        amountMinor: 400,
        currency: "VND",
      }),
    ).rejects.toMatchObject({ code: "IDEMPOTENCY_CONFLICT" });
  });
  it("concurrent commits cannot overspend", async () => {
    const x = await authorized("request-0001", 700),
      y = await authorized("request-0002", 700);
    const r = await Promise.allSettled([
      b.transfers.commit(customer, x.id),
      b.transfers.commit(customer, y.id),
    ]);
    expect(r.filter((x) => x.status === "fulfilled")).toHaveLength(1);
    expect((await b.accounts.get(customer, "a")).availableMinor).toBe(300);
  });
  it("expired authorization cannot debit", async () => {
    const i = await authorized();
    now += 301000;
    await expect(b.transfers.commit(customer, i.id)).rejects.toMatchObject({
      code: "EXPIRED",
    });
    expect((await b.accounts.get(customer, "a")).availableMinor).toBe(1000);
  });
  it("persists wrong OTP attempts and blocks brute force", async () => {
    const i = await b.transfers.intent(customer, {
      requestId: "request-0001",
      sourceAccountId: "a",
      destinationAccountId: "b",
      amountMinor: 300,
      currency: "VND",
    });
    const { challengeId, code } = await b.security.challenge(customer, i.id);
    for (let n = 0; n < 5; n++)
      await expect(
        b.security.verify(customer, i.id, challengeId, code === "000000" ? "000001" : "000000"),
      ).rejects.toThrow();
    await expect(
      b.security.verify(customer, i.id, challengeId, code),
    ).rejects.toMatchObject({
      code: "OTP_LOCKED",
    });
  });
  it("denies commit without OTP", async () => {
    const i = await b.transfers.intent(customer, {
      requestId: "request-0001",
      sourceAccountId: "a",
      destinationAccountId: "b",
      amountMinor: 300,
      currency: "VND",
    });
    await expect(b.transfers.commit(customer, i.id)).rejects.toMatchObject({
      code: "OTP_REQUIRED",
    });
  });
  it("blocks high value without configured verifier", async () => {
    b.options.faceThresholds = { VND: 500 };
    const intent = await authorized("high-value", 700);
    expect(intent.faceRequired).toBe(true);
    await expect(b.transfers.commit(customer, intent.id)).rejects.toMatchObject({ code: "STEP_UP_UNAVAILABLE" });
    expect((await b.accounts.get(customer, "a")).bookedMinor).toBe(1000);
  });
  it("request submission never credits money and survives retry", async () => {
    const x = {
      requestId: "deposit-001",
      accountId: "a",
      amountMinor: 100,
      currency: "VND",
      type: "DEPOSIT",
    };
    const r = await b.requests.create(customer, x);
    expect(await b.requests.create(customer, x)).toEqual(r);
    expect((await b.accounts.get(customer, "a")).availableMinor).toBe(1000);
  });
  it("filters history without exposing other participant data", async () => {
    await b.transfers.commit(customer, (await authorized()).id);
    expect(
      await b.transactions.list(customer, { accountId: "b" }),
    ).toHaveLength(0);
    expect(await b.transactions.list(customer, {})).toHaveLength(1);
  });
  it("requires an officer permission for policy changes", async () => {
    await expect(
      b.rates.create(customer, {
        rateType: "SAVINGS",
        annualRateBasisPoints: 500,
        effectiveFrom: now + 10000,
        reason: "Policy update",
      }),
    ).rejects.toMatchObject({ code: "FORBIDDEN" });
  });
  it("creates immutable bank-wide rate versions and audits the selected rate type", async () => {
    const input = { rateType: "SAVINGS", annualRateBasisPoints: 650, effectiveFrom: now + 10000, reason: "Điều chỉnh theo chính sách kỳ hạn" };
    const first = await b.rates.create(rateOfficer, input);
    const second = await b.rates.create(rateOfficer, { ...input, annualRateBasisPoints: 700 });
    expect(first.version).toBe(1);
    expect(second.version).toBe(2);
    expect(await s.run((t) => t.get(`ratePolicies/${first.id}`))).toMatchObject({ ...input, version: 1, createdBy: "rate-officer" });
    expect(await s.run((t) => t.get(`ratePolicies/${second.id}`))).toMatchObject({ version: 2, reason: input.reason });
    const audit = await s.run((t) => t.list("auditLogs"));
    expect(audit.some((row) => row.action === "RATE_UPDATE" && row.beforeAfterSummary?.after?.annualRateBasisPoints === 650 && row.beforeAfterSummary?.after?.reason === input.reason)).toBe(true);
  });
  it("applies savings and loan rate policies immediately when their effective date is today", async () => {
    await s.run(async (t) => {
      t.set("accounts/savings-today", { ownerUid: "alice", type: "SAVINGS", currency: "VND", status: "ACTIVE", availableMinor: 10000000, balanceMinor: 10000000 });
      t.set("savingsDetails/savings-today", { accountId: "savings-today", principalMinor: 10000000, productId: "sav-standard", ratePolicyVersion: "sav-base" });
      t.set("ratePolicies/sav-base", { productId: "sav-standard", annualRateBasisPoints: 500, effectiveFrom: now - 40 * 86400000, version: 1 });
      t.set("accounts/loan-today", { ownerUid: "alice", type: "MORTGAGE", currency: "VND", status: "ACTIVE", balanceMinor: 100000000 });
      t.set("mortgageDetails/loan-today", { accountId: "loan-today", loanPrincipalMinor: 100000000, outstandingMinor: 100000000, annualRateBasisPoints: 850, annualRate: 0.085, paymentFrequency: "MONTHLY", termMonths: 120, remainingTermMonths: 120 });
    });
    const savings = await b.rates.create(rateOfficer, { rateType: "SAVINGS", annualRateBasisPoints: 600, effectiveFrom: now, reason: "Áp dụng lãi tiết kiệm hôm nay" });
    const result = await b.rates.create(rateOfficer, { rateType: "LOAN", annualRateBasisPoints: 900, effectiveFrom: now, reason: "Áp dụng lãi vay hôm nay" });
    expect((await s.run((t) => t.get(`ratePolicies/${result.id}`))).effectiveFrom).toBe(now);
    expect((await s.run((t) => t.get(`ratePolicies/${savings.id}`))).effectiveFrom).toBe(now);
    expect((await b.accounts.savings(customer, "savings-today")).annualRateBasisPoints).toBe(600);
    expect((await b.accounts.mortgage(customer, "loan-today")).annualRateBasisPoints).toBe(900);
  });
  it("creates bank-wide savings and loan rate versions and applies them to every matching account after effectiveness", async () => {
    await s.run(async (t) => {
      for (const [id, ownerUid] of [["sav-a", "alice"], ["sav-b", "bob"]]) {
        t.set(`accounts/${id}`, { ownerUid, type: "SAVINGS", currency: "VND", status: "ACTIVE", availableMinor: 10000000, balanceMinor: 10000000 });
        t.set(`savingsDetails/${id}`, { accountId: id, principalMinor: 10000000, productId: "sav-standard", ratePolicyVersion: "sav-base" });
      }
      t.set("ratePolicies/sav-base", { productId: "sav-standard", annualRateBasisPoints: 500, effectiveFrom: now - 40 * 86400000, version: 1 });
      for (const [id, ownerUid] of [["loan-a", "alice"], ["loan-b", "bob"]]) {
        t.set(`accounts/${id}`, { ownerUid, type: "MORTGAGE", currency: "VND", status: "ACTIVE", balanceMinor: 100000000 });
        t.set(`mortgageDetails/${id}`, { accountId: id, loanPrincipalMinor: 100000000, outstandingMinor: 100000000, annualRateBasisPoints: 850, annualRate: 0.085, paymentFrequency: "MONTHLY", termMonths: 120, remainingTermMonths: 120 });
      }
    });
    const savingsEffective = now + 10000;
    const loanEffective = now + 20000;
    await b.rates.create(rateOfficer, { rateType: "SAVINGS", annualRateBasisPoints: 700, effectiveFrom: savingsEffective, reason: "Điều chỉnh lãi tiết kiệm" });
    await b.rates.create(rateOfficer, { rateType: "LOAN", annualRateBasisPoints: 900, effectiveFrom: loanEffective, reason: "Điều chỉnh lãi vay" });

    expect((await b.accounts.savings(customer, "sav-a")).annualRateBasisPoints).toBe(500);
    expect((await b.accounts.mortgage(customer, "loan-a")).annualRateBasisPoints).toBe(850);

    now = loanEffective + 1;
    for (const accountId of ["sav-a", "sav-b"])
      expect((await b.accounts.savings(accountId === "sav-a" ? customer : { uid: "bob", role: "CUSTOMER", permissions: [] }, accountId)).annualRateBasisPoints).toBe(700);
    for (const accountId of ["loan-a", "loan-b"])
      expect((await b.accounts.mortgage(accountId === "loan-a" ? customer : { uid: "bob", role: "CUSTOMER", permissions: [] }, accountId)).annualRateBasisPoints).toBe(900);
  });
  it("rejects unknown rate types, empty reasons, and rates outside configured limits", async () => {
    await expect(b.rates.create(rateOfficer, { rateType: "UNKNOWN", annualRateBasisPoints: 500, effectiveFrom: now + 10000, reason: "Policy" })).rejects.toMatchObject({ code: "INVALID_RATE_TYPE" });
    await expect(b.rates.create(rateOfficer, { rateType: "SAVINGS", annualRateBasisPoints: 500, effectiveFrom: now + 10000, reason: " " })).rejects.toMatchObject({ code: "INVALID_TERMS" });
    await expect(b.rates.create(rateOfficer, { rateType: "SAVINGS", annualRateBasisPoints: 10001, effectiveFrom: now + 10000, reason: "Policy" })).rejects.toMatchObject({ code: "INVALID_TERMS" });
  });
});
describe("calculations", () => {
  it("uses VND minor units directly for ACT/365 estimate", () =>
    expect(savingsEstimate(365000, 500, 365)).toBe(18250));
  it.each([
    ["February 2023", Date.UTC(2023, 1, 1), Date.UTC(2023, 2, 1), 28, 1400],
    ["February 2024", Date.UTC(2024, 1, 1), Date.UTC(2024, 2, 1), 29, 1450],
    ["April 2024", Date.UTC(2024, 3, 1), Date.UTC(2024, 4, 1), 30, 1500],
    ["January 2024", Date.UTC(2024, 0, 1), Date.UTC(2024, 1, 1), 31, 1550],
  ])("estimates savings interest for %s using its calendar days", (_label, start, end, days, profit) => {
    expect(savingsMonthlyEstimate(365000, start, end, [
      { id: "policy-1", effectiveFrom: start, annualRateBasisPoints: 500, version: 1 },
    ])).toMatchObject({ days, profitMinor: profit });
  });
  it("rounds a half minor unit half up and returns zero for zero principal", () => {
    const start = Date.UTC(2024, 0, 1);
    const end = Date.UTC(2025, 0, 1);
    const policy = [{ id: "policy-1", effectiveFrom: start, annualRateBasisPoints: 5000, version: 1 }];
    expect(savingsMonthlyEstimate(1, start, end, policy).profitMinor).toBe(1);
    expect(savingsMonthlyEstimate(0, start, end, policy).profitMinor).toBe(0);
  });
  it("splits a monthly estimate at the exact effective time of a new policy", () => {
    const start = Date.UTC(2024, 0, 1);
    const change = Date.UTC(2024, 0, 16);
    const end = Date.UTC(2024, 1, 1);
    const result = savingsMonthlyEstimate(365000, start, end, [
      { id: "policy-1", effectiveFrom: start, annualRateBasisPoints: 500, version: 1 },
      { id: "policy-2", effectiveFrom: change, annualRateBasisPoints: 1000, version: 2 },
    ]);
    expect(result).toMatchObject({ days: 31, profitMinor: 2350, policyIds: ["policy-1", "policy-2"] });
  });
  it("does not apply a future policy before its effective time", () => {
    const start = Date.UTC(2024, 0, 1);
    const effective = Date.UTC(2024, 1, 1);
    const result = savingsMonthlyEstimate(365000, start, effective, [
      { id: "policy-1", effectiveFrom: start, annualRateBasisPoints: 500, version: 1 },
      { id: "policy-2", effectiveFrom: effective, annualRateBasisPoints: 1000, version: 2 },
    ]);
    expect(result.policyIds).toEqual(["policy-1"]);
    expect(result.profitMinor).toBe(1550);
  });
  it("requires a policy in effect at the beginning of the accrual period", () => {
    const start = Date.UTC(2024, 0, 1);
    try {
      savingsMonthlyEstimate(1000, start, Date.UTC(2024, 1, 1), [
        { id: "future", effectiveFrom: Date.UTC(2024, 0, 2), annualRateBasisPoints: 500, version: 1 },
      ]);
      throw new Error("expected POLICY_UNAVAILABLE");
    } catch (error) {
      expect(error).toMatchObject({ code: "POLICY_UNAVAILABLE" });
    }
  });
  it("applies product-matched savings policies across the selected month", async () => {
    now = Date.UTC(2024, 0, 15);
    await s.run(async (t) => {
      t.set("accounts/savings", {
        ownerUid: "alice", type: "SAVINGS", currency: "VND", status: "ACTIVE", balanceMinor: 365000,
      });
      t.set("savingsDetails/savings", {
        principalMinor: 365000, productId: "savings_standard", ratePolicyVersion: "savings-v1",
      });
      t.set("ratePolicies/savings-v1", {
        id: "savings-v1", productId: "savings_standard", annualRateBasisPoints: 500,
        effectiveFrom: Date.UTC(2024, 0, 1), version: 1,
      });
      t.set("ratePolicies/savings-v2", {
        id: "savings-v2", productId: "savings_standard", annualRateBasisPoints: 1000,
        effectiveFrom: Date.UTC(2024, 0, 16), version: 2,
      });
      t.set("ratePolicies/other-product", {
        id: "other-product", productId: "premium_savings", annualRateBasisPoints: 9000,
        effectiveFrom: Date.UTC(2024, 0, 1), version: 1,
      });
    });
    const result = await b.accounts.savings(customer, "savings", "2024-01");
    expect(result).toMatchObject({
      annualRateBasisPoints: 500,
      profitMinor: 2350,
      periodStart: Date.UTC(2024, 0, 1),
      periodEnd: Date.UTC(2024, 1, 1),
      days: 31,
      estimate: true,
    });
    expect(result.ratePolicyVersion).toBe("savings-v1");
    await expect(b.accounts.savings({ ...customer, uid: "bob" }, "savings", "2024-01"))
      .rejects.toMatchObject({ code: "FORBIDDEN" });
  });
  it("fails savings estimates when no policy is effective in the period", async () => {
    now = Date.UTC(2024, 0, 15);
    await s.run(async (t) => {
      t.set("accounts/savings", { ownerUid: "alice", type: "SAVINGS", currency: "VND", status: "ACTIVE" });
      t.set("savingsDetails/savings", { principalMinor: 1000, productId: "savings_standard", ratePolicyVersion: "future" });
      t.set("ratePolicies/future", {
        id: "future", productId: "savings_standard", annualRateBasisPoints: 500,
        effectiveFrom: Date.UTC(2024, 1, 1), version: 1,
      });
    });
    await expect(b.accounts.savings(customer, "savings", "2024-01"))
      .rejects.toMatchObject({ code: "POLICY_UNAVAILABLE" });
  });
  it("builds mortgage payment schedules from outstanding balance", async () => {
    await s.run(async (t) => {
      t.set("accounts/mortgage", { ownerUid: "alice", type: "MORTGAGE", currency: "VND", status: "ACTIVE" });
      t.set("mortgageDetails/mortgage", {
        loanPrincipalMinor: 1000, outstandingMinor: 800, annualRateBasisPoints: 0,
        termMonths: 12, paymentFrequency: "MONTHLY",
      });
    });
    const monthly = await b.accounts.mortgage(customer, "mortgage", "MONTHLY");
    const biweekly = await b.accounts.mortgage(customer, "mortgage", "BIWEEKLY");
    expect(monthly.principalMinor).toBe(1000);
    expect(monthly.outstandingMinor).toBe(800);
    expect(monthly.items.reduce((sum, item) => sum + item.principalMinor, 0)).toBe(800);
    expect(biweekly.items.reduce((sum, item) => sum + item.principalMinor, 0)).toBe(800);
    expect(monthly.monthlyFirstPaymentMinor).toBeGreaterThan(0);
    expect(monthly.biweeklyFirstPaymentMinor).toBeGreaterThan(0);
  });
  it("zero interest installments preserve principal", () => {
    const r = mortgageSchedule(1000, 0, 12, "MONTHLY");
    expect(r.reduce((n, x) => n + x.principalMinor, 0)).toBe(1000);
    expect(r.at(-1)!.remainingMinor).toBe(0);
  });
  it("biweekly schedule uses 26 periods per year", () =>
    expect(mortgageSchedule(1000000, 500, 12, "BIWEEKLY")).toHaveLength(26));
});
