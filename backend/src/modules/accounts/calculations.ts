import { check, minor } from "../../shared/errors.js";
const DAY_MS = 86400000n;

export interface SavingsRatePeriod {
  id: string;
  effectiveFrom?: number;
  annualRateBasisPoints: number;
  version?: number;
}

export function savingsMonthlyEstimate(
  principal: number,
  periodStart: number,
  periodEnd: number,
  inputPolicies: SavingsRatePeriod[],
) {
  check(Number.isSafeInteger(principal) && principal >= 0, "INVALID_TERMS");
  check(Number.isSafeInteger(periodStart) && Number.isSafeInteger(periodEnd) && periodEnd > periodStart,
    "INVALID_TERMS");
  check(Array.isArray(inputPolicies) && inputPolicies.length > 0, "POLICY_UNAVAILABLE");
  const policies = inputPolicies
    .map((policy) => ({
      ...policy,
      effectiveFrom: policy.effectiveFrom ?? periodStart,
    }))
    .filter((policy) => policy.effectiveFrom < periodEnd)
    .sort((a, b) => a.effectiveFrom - b.effectiveFrom || (a.version ?? 0) - (b.version ?? 0));

  for (const policy of policies) {
    check(typeof policy.id === "string" && policy.id.length > 0 &&
      Number.isInteger(policy.annualRateBasisPoints) &&
      policy.annualRateBasisPoints >= 0 && policy.annualRateBasisPoints <= 10000,
    "INVALID_TERMS");
  }

  let activeIndex = -1;
  for (let i = 0; i < policies.length; i++) {
    if (policies[i].effectiveFrom <= periodStart) activeIndex = i;
    else break;
  }
  check(activeIndex >= 0, "POLICY_UNAVAILABLE");

  // For policies sharing an effective instant, the highest version wins.
  const boundaries: SavingsRatePeriod[] = [];
  for (const policy of policies) {
    const previous = boundaries.at(-1);
    if (previous?.effectiveFrom === policy.effectiveFrom) {
      if ((policy.version ?? 0) >= (previous.version ?? 0)) boundaries[boundaries.length - 1] = policy;
    } else {
      boundaries.push(policy);
    }
  }
  activeIndex = -1;
  for (let i = 0; i < boundaries.length; i++) {
    if ((boundaries[i].effectiveFrom ?? periodStart) <= periodStart) activeIndex = i;
  }
  check(activeIndex >= 0, "POLICY_UNAVAILABLE");

  let cursor = BigInt(periodStart);
  const end = BigInt(periodEnd);
  let numerator = 0n;
  const policyIds: string[] = [];
  const accrueUntil = (next: bigint, policy: SavingsRatePeriod) => {
    if (next <= cursor) return;
    const duration = next - cursor;
    numerator += BigInt(principal) * BigInt(policy.annualRateBasisPoints) * duration;
    if (policyIds.at(-1) !== policy.id) policyIds.push(policy.id);
    cursor = next;
  };

  let active = boundaries[activeIndex];
  for (let i = activeIndex + 1; i < boundaries.length; i++) {
    const boundary = BigInt(boundaries[i].effectiveFrom ?? periodStart);
    if (boundary >= end) break;
    accrueUntil(boundary, active);
    active = boundaries[i];
  }
  accrueUntil(end, active);

  const monthlyDenominator = 10000n * 12n * BigInt(periodEnd - periodStart);
  const profitMinor = Number((numerator + monthlyDenominator / 2n) / monthlyDenominator);
  check(Number.isSafeInteger(profitMinor), "AMOUNT_OVERFLOW");
  const durationMs = periodEnd - periodStart;
  check(durationMs % Number(DAY_MS) === 0, "INVALID_TERMS");
  return {
    profitMinor,
    days: durationMs / Number(DAY_MS),
    policyIds,
  };
}

export function savingsEstimate(
  principal: number,
  basisPoints: number,
  months: number,
) {
  minor(principal);
  check(
    Number.isInteger(basisPoints) &&
      basisPoints >= 0 &&
      basisPoints <= 10000 &&
      Number.isInteger(months) &&
      months >= 0 &&
      months <= 1200,
    "INVALID_TERMS",
  );
  const denominator = 10000n * 12n;
  const profitMinor = Number(
    (BigInt(principal) * BigInt(basisPoints) * BigInt(months) + denominator / 2n) /
      denominator,
  );
  check(Number.isSafeInteger(profitMinor), "AMOUNT_OVERFLOW");
  return profitMinor;
}
export function mortgageSchedule(
  principal: number,
  basisPoints: number,
  months: number,
  frequency: string,
) {
  minor(principal);
  check(
    Number.isInteger(months) &&
      months > 0 &&
      months <= 600 &&
      Number.isInteger(basisPoints) &&
      basisPoints >= 0 &&
      basisPoints <= 10000 &&
      ["MONTHLY", "BIWEEKLY"].includes(frequency),
    "INVALID_TERMS",
  );
  const periods = frequency === "BIWEEKLY" ? 26 : 12,
    n = Math.ceil((months * periods) / 12),
    r = basisPoints / 10000 / periods;
  const payment =
    r === 0 ? principal / n : (principal * r) / (1 - Math.pow(1 + r, -n));
  let remaining = principal;
  const result = [];
  for (let i = 1; i <= n; i++) {
    const interest = Math.round(remaining * r);
    const part =
      i === n
        ? remaining
        : Math.min(remaining, Math.max(0, Math.round(payment) - interest));
    remaining -= part;
    result.push({
      installment: i,
      principalMinor: part,
      interestMinor: interest,
      totalMinor: part + interest,
      remainingMinor: remaining,
    });
  }
  return result;
}
