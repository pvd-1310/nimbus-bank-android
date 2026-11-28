import Stripe from "stripe";
import { expect, it, vi } from "vitest";
import { Banking } from "../src/core/banking.js";
import { StripeSandboxMovements } from "../src/modules/accounts/stripe-sandbox-movements.service.js";
import { digest } from "../src/shared/crypto.js";
import { intentDigest } from "../src/modules/security/intent.js";
import { MemoryStore } from "./support/memory-store.js";

it("can finish existing Treasury intents once after OTP", async () => {
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
  const id = digest(["alice", "TRANSFER", "legacy-request"]);
  const source = { connectedAccountId: "acct_alice", financialAccountId: "fa_alice", cardId: "ic_alice" };
  const destination = { connectedAccountId: "acct_bob", financialAccountId: "fa_bob", cardId: "ic_bob" };
  const now = bank.now();
  const intent = {
    id, source, destination, actorUid: "alice", sourceAccountId: "alice", destinationAccountId: "bob",
    provider: "STRIPE_TREASURY", requestId: "legacy-request", payloadDigest: "legacy-digest",
    kind: "TRANSFER", movement: "TRANSFER", amountMinor: 500, currency: "USD", feeMinor: 0,
    policyDigest: digest([Object.values(source), Object.values(destination), "TRANSFER", null]),
    status: "CREATED", securityVersion: 1, createdAt: now, expiresAt: now + 300000,
  };
  await store.run(async (t) => t.set("transactionIntents/" + id, { ...intent, digest: intentDigest(intent) }));
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
