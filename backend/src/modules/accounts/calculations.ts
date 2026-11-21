import { check, minor } from "../../shared/errors.js";
export function savingsEstimate(
  principal: number,
  basisPoints: number,
  days: number,
) {
  minor(principal);
  check(
    Number.isInteger(basisPoints) &&
      basisPoints >= 0 &&
      basisPoints <= 10000 &&
      Number.isInteger(days) &&
      days >= 0 &&
      days <= 36500,
    "INVALID_TERMS",
  );
  return Number(
    (BigInt(principal) * BigInt(basisPoints) * BigInt(days) + 1825000n) /
      3650000n,
  );
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
