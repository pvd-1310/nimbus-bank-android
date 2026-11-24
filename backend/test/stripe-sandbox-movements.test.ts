import Stripe from "stripe";
import { expect, it, vi } from "vitest";
import { Banking } from "../src/core/banking.js";
import { StripeSandboxMovements } from "../src/modules/accounts/stripe-sandbox-movements.service.js";
import { MemoryStore } from "./support/memory-store.js";

it("requires OTP, transfers once through Stripe and leaves the old Firestore balances untouched", async () => {
  const store = new MemoryStore();
  const bank = new Banking(store, { otpSecret: "test" });
  const stripe = new Stripe("sk_test_fixture");
  const service = new StripeSandboxMovements(bank, stripe);
  const actor = { uid: "alice", role: "CUSTOMER" as const, permissions: [] };
  await store.run(async (t) => {
    for (const uid of ["alice", "bob"]) {
      t.set("users/" + uid, { role: "CUSTOMER", active: true });
      t.set("accounts/" + uid, { type: "CHECKING", ownerUid: uid, status: "ACTIVE", availableMinor: 900000 });
      t.set("stripeSandboxAccountLinks/" + uid, { ownerUid: uid, status: "ready", connectedAccountId: "acct_" + uid,
        financialAccountId: "fa_" + uid, cardId: "ic_" + uid });
    }
  });
  vi.spyOn(stripe.treasury.financialAccounts, "retrieve").mockResolvedValue({
    id: "fa_alice", livemode: false, status: "open", balance: { cash: { usd: 2500 } },
  } as Stripe.Treasury.FinancialAccount);
  const send = vi.spyOn(stripe.treasury.outboundPayments, "create").mockResolvedValue({
    livemode: false, id: "obp_test", status: "posted",
  } as Stripe.Treasury.OutboundPayment);
  const input = { requestId: "unique-request", sourceAccountId: "alice", destinationAccountId: "bob",
    amountMinor: 500, currency: "USD" };
  await store.run(async (t) => {
    t.set("users/bob", { role: "CUSTOMER", active: true, fullName: "Người nhận", contactPhone: "+84901234567",
      primaryCheckingAccountId: "bob" });
    t.set("accounts/secondary", { ownerUid: "bob", type: "CHECKING", status: "ACTIVE" });
  });
  await expect(service.lookupRecipient(actor, "alice", "0901234567")).resolves.toEqual({
    accountId: "bob", fullName: "Người nhận",
  });
  await expect(service.prepare(actor, "TRANSFER", { ...input, destinationAccountId: "+84901234567", recipientAccountId: "secondary" }))
    .rejects.toMatchObject({ code: "RECIPIENT_CHANGED" });
  input.destinationAccountId = "+84901234567";
  const intent = await service.prepare(actor, "TRANSFER", input);
  await expect(service.commit(actor, intent.id, "TRANSFER")).rejects.toMatchObject({ code: "OTP_REQUIRED" });
  expect(send).not.toHaveBeenCalled();
  const c = await bank.security.challenge(actor, intent.id);
  await bank.security.verify(actor, intent.id, c.challengeId, c.code);
  await expect(service.commit(actor, intent.id, "TRANSFER")).resolves.toMatchObject({ status: "SETTLED" });
  await expect(service.commit(actor, intent.id, "TRANSFER")).resolves.toMatchObject({ status: "SETTLED" });
  expect(send).toHaveBeenCalledTimes(1);
  expect(send).toHaveBeenCalledWith(expect.objectContaining({ amount: 500, financial_account: "fa_alice",
    destination_payment_method_data: { type: "financial_account", financial_account: "fa_bob" } }),
    expect.objectContaining({ stripeAccount: "acct_alice", idempotencyKey: expect.any(String) }));
  await store.run(async (t) => expect((await t.get("accounts/alice"))?.availableMinor).toBe(900000));
});
