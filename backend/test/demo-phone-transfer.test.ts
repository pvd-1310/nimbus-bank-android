import { expect, it } from "vitest";
import { Banking } from "../src/core/banking.js";
import { MemoryStore } from "./support/memory-store.js";

it("commits the allowed demo transfer once and rejects reused or out-of-scope proof", async () => {
  const store = new MemoryStore();
  const now = 1_800_000_000_000;
  const alice = { uid: "alice", role: "CUSTOMER" as const, permissions: [] };
  await store.run(async (tx) => {
    tx.set("users/alice", { role: "CUSTOMER", active: true });
    for (const id of ["source", "recipient", "other"]) {
      tx.set("accounts/" + id, {
        ownerUid: "alice", demo: true, type: "CHECKING", status: "ACTIVE", currency: "VND",
        availableMinor: id === "source" ? 20_000 : 0,
        bookedMinor: id === "source" ? 20_000 : 0, version: 0,
      });
    }
  });
  const bank = new Banking(store, {
    otpSecret: "test",
    now: () => now,
    deliver: async () => { throw Error("Local OTP must not be used"); },
    demoPhone: {
      customerUid: "alice", sourceId: "source", recipientId: "recipient",
      phoneNumber: "+16505550100",
      verify: async (token) => ({ uid: token === "second-proof" ? "phone-user-2" : "phone-user", authTime: now / 1000 }),
    },
  });
  const create = (requestId: string, destinationAccountId = "recipient") =>
    bank.transfers.intent(alice, {
      requestId, sourceAccountId: "source", destinationAccountId,
      amountMinor: 10_000, currency: "VND",
    });
  const first = await create("first");
  const second = await create("second");
  const other = await create("other", "other");
  expect(await bank.transfers.challenge(alice, first.id)).toEqual({
    method: "FIREBASE_PHONE_DEMO", phoneNumber: "+16505550100",
  });
  await bank.transfers.challenge(alice, second.id);
  await expect(bank.transfers.challenge(alice, other.id)).rejects.toMatchObject({ code: "OTP_PROVIDER_REQUIRED" });
  await expect(bank.transfers.commit(alice, first.id)).rejects.toMatchObject({ code: "OTP_REQUIRED" });
  await expect(bank.transfers.verify(alice, first.id, "123456")).rejects.toMatchObject({ code: "OTP_METHOD_MISMATCH" });
  await bank.transfers.verifyDemoPhone(alice, first.id, "proof");
  await expect(bank.transfers.verifyDemoPhone(alice, second.id, "proof")).rejects.toMatchObject({ code: "OTP_PROOF_REUSED" });
  const receipt = await bank.transfers.commit(alice, first.id);
  expect(await bank.transfers.commit(alice, first.id)).toEqual(receipt);
  await bank.transfers.verifyDemoPhone(alice, second.id, "second-proof");
  await store.run(async (tx) => tx.set("checkingDetails/source", { dailyLimitMinor: 10 }));
  await expect(bank.transfers.commit(alice, second.id)).rejects.toMatchObject({ code: "POLICY_CHANGED" });
  await expect(bank.transfers.commit(alice, second.id)).rejects.toMatchObject({ code: "POLICY_CHANGED" });
  await expect(bank.transfers.intent(alice, {
    requestId: "too_large", sourceAccountId: "source", destinationAccountId: "recipient",
    amountMinor: 11_000, currency: "VND",
  })).rejects.toMatchObject({ code: "INSUFFICIENT_FUNDS" });
  await expect(bank.transfers.intent(alice, {
    requestId: "too_large", sourceAccountId: "source", destinationAccountId: "recipient",
    amountMinor: 11_000, currency: "VND",
  })).rejects.toMatchObject({ code: "INSUFFICIENT_FUNDS" });
  const history = await bank.transactions.list(alice, {});
  expect(history).toEqual(expect.arrayContaining([
    expect.objectContaining({type: "INTERNAL_TRANSFER", status: "SETTLED",
      amountMinor: 10_000, accountId: "source", balanceAfterMinor: 10_000}),
    expect.objectContaining({type: "TRANSFER_RECEIVED", status: "SETTLED",
      amountMinor: 10_000, accountId: "recipient", balanceAfterMinor: 10_000}),
    expect.objectContaining({type: "INTERNAL_TRANSFER", status: "FAILED",
      amountMinor: 11_000, accountId: "source", failureCode: "INSUFFICIENT_FUNDS"}),
  ]));
  expect(history.filter(row => row.failureCode === "INSUFFICIENT_FUNDS")).toHaveLength(1);
  expect(history.filter(row => row.failureCode === "POLICY_CHANGED")).toHaveLength(1);
  expect((await store.run(tx => tx.list("ledgerEntries")))).toHaveLength(2);
  expect(await store.run(async (tx) => [
    (await tx.get("accounts/source"))?.bookedMinor,
    (await tx.get("accounts/recipient"))?.bookedMinor,
  ])).toEqual([10_000, 10_000]);
});
