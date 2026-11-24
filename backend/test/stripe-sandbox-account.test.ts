import Stripe from "stripe";
import { expect, it, vi } from "vitest";
import { Banking } from "../src/core/banking.js";
import { StripeSandboxAccounts } from "../src/modules/accounts/stripe-sandbox-accounts.service.js";
import { MemoryStore } from "./support/memory-store.js";

it("reads the linked USD balance, card and activity from Stripe for the account owner", async () => {
  const store = new MemoryStore();
  const bank = new Banking(store, { otpSecret: "test" });
  const stripe = new Stripe("sk_test_fixture");
  const accounts = new StripeSandboxAccounts(bank, stripe);
  const owner = { uid: "alice", role: "CUSTOMER" as const, permissions: [] };
  await store.run(async (t) => {
    t.set("users/alice", { role: "CUSTOMER", active: true });
    t.set("users/bob", { role: "CUSTOMER", active: true });
    t.set("accounts/checking", { type: "CHECKING", ownerUid: "alice", currency: "VND", balanceMinor: 500000 });
    t.set("stripeSandboxAccountLinks/checking", {
      ownerUid: "alice", connectedAccountId: "acct_test", financialAccountId: "fa_test", cardId: "ic_test",
    });
  });
  const balance = vi.spyOn(stripe.treasury.financialAccounts, "retrieve").mockResolvedValue({
    id: "fa_test", livemode: false, status: "open", supported_currencies: ["usd"], balance: {
      cash: { usd: 9250 }, inbound_pending: {}, outbound_pending: { usd: 250 },
    },
  } as Stripe.Treasury.FinancialAccount);
  vi.spyOn(stripe.treasury.transactions, "list").mockResolvedValue({
    data: [{ id: "trxn_test", financial_account: "fa_test", livemode: false, currency: "usd",
      amount: -750, created: 1700000000, description: "Test card payment",
      flow_type: "issuing_authorization", status: "posted" }],
  } as Stripe.ApiList<Stripe.Treasury.Transaction>);
  const card = vi.spyOn(stripe.issuing.cards, "retrieve").mockResolvedValue({
    livemode: false, financial_account: "fa_test", currency: "usd", last4: "1234", status: "active", brand: "Visa",
  } as Stripe.Issuing.Card);
  await expect(accounts.get({ ...owner, uid: "bob" }, "checking")).rejects.toMatchObject({ code: "FORBIDDEN" });
  expect(balance).not.toHaveBeenCalled();

  const result = await accounts.get(owner, "checking");
  expect(result).toMatchObject({
    status: "open", currency: "USD", availableMinor: 9250, outboundPendingMinor: 250,
    card: { last4: "1234", status: "active", brand: "Visa" },
    transactions: [{ amountMinor: -750, description: "Test card payment", status: "posted" }],
  });
  expect(balance).toHaveBeenCalledWith("fa_test", { stripeAccount: "acct_test" });
  expect(card).toHaveBeenCalledWith("ic_test", { stripeAccount: "acct_test" });
  expect(JSON.stringify(result)).not.toMatch(/acct_test|fa_test|ic_test/);
  await accounts.get(owner, "checking");
  const history = await bank.transactions.list(owner, { provider: "STRIPE_TREASURY" });
  expect(history).toMatchObject([
    { amountMinor: 750, type: "STRIPE_DEBIT", currency: "USD", accountId: "checking", status: "SETTLED" },
  ]);
  card.mockResolvedValueOnce({ financial_account: "fa_wrong", livemode: false, currency: "usd" } as Stripe.Issuing.Card);
  await expect(accounts.get(owner, "checking")).rejects.toMatchObject({ code: "STRIPE_SANDBOX_RESOURCE_MISMATCH" });
});

it("links only customer checking accounts and resumes pending Stripe onboarding", async () => {
  const store = new MemoryStore();
  const bank = new Banking(store, { otpSecret: "test" });
  const stripe = new Stripe("sk_test_fixture");
  const service = new StripeSandboxAccounts(bank, stripe, "demo-test");
  const create = vi.spyOn(stripe.accounts, "create").mockResolvedValue({
    id: "acct_sandbox", capabilities: { treasury: "pending" },
  } as Stripe.Account);
  const retrieve = vi.spyOn(stripe.accounts, "retrieve").mockResolvedValue({
    id: "acct_sandbox", capabilities: { treasury: "pending" },
  } as Stripe.Account);
  await store.run(async (t) => {
    t.set("users/alice", { role: "CUSTOMER", email: "alice@private.example" });
    t.set("users/staff", { role: "OFFICER" });
    t.set("accounts/customer", { type: "CHECKING", ownerUid: "alice" });
    t.set("accounts/staff", { type: "CHECKING", ownerUid: "staff" });
    t.set("accounts/savings", { type: "SAVINGS", ownerUid: "alice" });
  });
  await service.provision("staff");
  await service.provision("savings");
  expect(create).not.toHaveBeenCalled();
  await Promise.all([service.provision("customer"), service.provision("customer")]);
  await service.provision("customer");
  expect(create).toHaveBeenCalledTimes(1);
  expect(retrieve).toHaveBeenCalledWith("acct_sandbox");
  expect(create.mock.calls[0]?.[0]).toMatchObject({ email: expect.stringMatching(/^nimbus-.*@example\.com$/) });
  await store.run(async (t) => {
    expect(await t.get("accounts/customer")).toMatchObject({ stripeSandboxLinkPath: "stripeSandboxAccountLinks/customer" });
    expect(await t.get("stripeSandboxAccountLinks/customer")).toMatchObject({
      ownerUid: "alice", status: "pending", connectedAccountId: "acct_sandbox",
    });
    expect(await t.get("stripeSandboxAccountLinks/staff")).toBeUndefined();
    expect(await t.get("stripeSandboxAccountLinks/savings")).toBeUndefined();
  });
});

it("updates the financial account nickname from the customer's full name without replacing the card", async () => {
  const store = new MemoryStore();
  const stripe = new Stripe("sk_test_fixture");
  const service = new StripeSandboxAccounts(new Banking(store, { otpSecret: "test" }), stripe);
  const fullName = "Nguyễn Minh Anh";
  await store.run(async (t) => {
    t.set("users/customer", { role: "CUSTOMER", fullName });
    t.set("accounts/checking", { type: "CHECKING", ownerUid: "customer" });
    t.set("stripeSandboxAccountLinks/checking", {
      ownerUid: "customer", connectedAccountId: "acct_test", financialAccountId: "fa_test",
      cardId: "ic_test", status: "ready",
    });
  });
  vi.spyOn(stripe.treasury.financialAccounts, "retrieve").mockResolvedValue({
    id: "fa_test", livemode: false, nickname: null,
  } as Stripe.Treasury.FinancialAccount);
  const update = vi.spyOn(stripe.treasury.financialAccounts, "update").mockResolvedValue({
    nickname: fullName,
  } as Stripe.Treasury.FinancialAccount);
  await service.provision("checking");
  await service.provision("checking");
  expect(update).toHaveBeenCalledTimes(1);
  expect(update).toHaveBeenCalledWith("fa_test", { nickname: fullName }, { stripeAccount: "acct_test" });
  await store.run(async (t) => expect(await t.get("stripeSandboxAccountLinks/checking")).toMatchObject({
    cardId: "ic_test", displayName: fullName,
  }));
});
