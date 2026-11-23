import { beforeEach, describe, expect, it } from "vitest";
import { Banking } from "../src/core/banking.js";
import {
  mortgageSchedule,
  savingsEstimate,
} from "../src/modules/accounts/calculations.js";
import { type Actor } from "../src/modules/auth/auth.types.js";
import { MemoryStore } from "./support/memory-store.js";
const customer: Actor = { uid: "alice", role: "CUSTOMER", permissions: [] };
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
        productId: "sav",
        annualRateBasisPoints: 500,
        effectiveFrom: now + 10000,
      }),
    ).rejects.toMatchObject({ code: "FORBIDDEN" });
  });
});
describe("calculations", () => {
  it("uses VND minor units directly for ACT/365 estimate", () =>
    expect(savingsEstimate(365000, 500, 365)).toBe(18250));
  it("zero interest installments preserve principal", () => {
    const r = mortgageSchedule(1000, 0, 12, "MONTHLY");
    expect(r.reduce((n, x) => n + x.principalMinor, 0)).toBe(1000);
    expect(r.at(-1)!.remainingMinor).toBe(0);
  });
  it("biweekly schedule uses 26 periods per year", () =>
    expect(mortgageSchedule(1000000, 500, 12, "BIWEEKLY")).toHaveLength(26));
});
