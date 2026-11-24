import type Stripe from "stripe";
import type { Banking } from "../../core/banking.js";
import type { Doc } from "../../core/store.js";
import { check } from "../../shared/errors.js";
import { digest } from "../../shared/crypto.js";
import type { Actor } from "../auth/auth.types.js";

function customerName(user: Doc) {
  return typeof user.fullName === "string" ? user.fullName.trim() : "";
}

export class StripeSandboxAccounts {
  private readonly provisioning = new Map<string, Promise<boolean>>();
  private readonly scheduled = new Set<string>();

  constructor(private bank: Banking, private stripe: Stripe, private namespace = "local") {}

  provision(accountId: string) {
    const existing = this.provisioning.get(accountId);
    if (existing) return existing;
    const work = this.provisionCustomerAccount(accountId).finally(() => this.provisioning.delete(accountId));
    this.provisioning.set(accountId, work);
    return work;
  }

  scheduleProvision(accountId: string) {
    if (this.scheduled.has(accountId)) return;
    this.scheduled.add(accountId);
    const attempt = async (retry: number) => {
      try {
        const pending = await this.provision(accountId);
        if (pending && retry < 12) {
          setTimeout(() => void attempt(retry + 1), Math.min(30_000, 5_000 * (retry + 1))).unref();
          return;
        }
      } catch {
        console.warn("Stripe sandbox account provisioning is pending");
      }
      this.scheduled.delete(accountId);
    };
    void attempt(0);
  }

  async resumePendingProvisioning() {
    let after: string | undefined;
    do {
      const links = await this.bank.store.run(t =>
        t.list("stripeSandboxAccountLinks", { status: "pending" }, { limit: 100, after }));
      for (const link of links) this.scheduleProvision(link.id);
      after = links.length === 100 ? links[99].id : undefined;
    } while (after);
  }

  private async provisionCustomerAccount(accountId: string) {
    const path = "stripeSandboxAccountLinks/" + accountId;
    const resources = await this.bank.store.run(async (t) => {
      const accountPath = "accounts/" + accountId;
      const account = await t.get(accountPath);
      if (account?.type !== "CHECKING" || typeof account.ownerUid !== "string") return;
      const user = await t.get("users/" + account.ownerUid);
      if (user?.role !== "CUSTOMER") return;
      const existing = await t.get(path);
      if (existing) {
        check(existing.ownerUid === account.ownerUid, "STRIPE_SANDBOX_RESOURCE_MISMATCH", 409);
        if (account.stripeSandboxLinkPath !== path) t.set(accountPath, { ...account, stripeSandboxLinkPath: path });
        return { link: existing, name: customerName(user) };
      }
      const pending = { ownerUid: account.ownerUid, status: "pending", createdAt: this.bank.now() };
      t.set(path, pending);
      t.set(accountPath, { ...account, stripeSandboxLinkPath: path });
      return { link: pending as Doc, name: customerName(user) };
    });
    if (!resources) return false;
    const { link, name } = resources;
    if (link.cardId) {
      if (name && link.displayName !== name) await this.syncName(accountId, link, name);
      return false;
    }

    const reference = digest([this.namespace, accountId]).slice(0, 32);
    const key = (step: string) => "nimbus-stripe-sandbox-" + reference + "-" + step;
    const update = (field: string, value: string) => this.bank.store.run(async (t) => {
      const current = await t.get(path);
      check(current && current.ownerUid === link.ownerUid && (!current[field] || current[field] === value),
        "STRIPE_SANDBOX_RESOURCE_MISMATCH", 409);
      t.set(path, { ...current, [field]: value });
    });

    // Provision only synthetic test identities. The runtime accepts test API keys.
    const email = "nimbus-" + reference + "@example.com";
    const address = { line1: "address_full_match", city: "San Francisco", state: "CA", postal_code: "94103", country: "US" };
    const dob = { day: 1, month: 1, year: 1901 };
    const terms = { date: Math.floor(link.createdAt / 1000), ip: "127.0.0.1" };
    let connectedAccount = link.connectedAccountId
      ? await this.stripe.accounts.retrieve(link.connectedAccountId) : undefined;
    if (!connectedAccount) {
      connectedAccount = await this.stripe.accounts.create({
        type: "custom", country: "US", business_type: "individual", email,
        capabilities: { treasury: { requested: true }, card_issuing: { requested: true }, transfers: { requested: true } },
        business_profile: {
          mcc: "5734", product_description: "Synthetic sandbox account for testing USD business expenses.",
          annual_revenue: { amount: 1000000, currency: "usd", fiscal_year_end: "2025-12-31" }, estimated_worker_count: 1,
        },
        external_account: "btok_us_verified",
        individual: { address, dob, email, first_name: "Nimbus", last_name: "Sandbox", phone: "0000000000", id_number: "000000000" },
        tos_acceptance: terms,
        settings: { treasury: { tos_acceptance: terms }, card_issuing: { tos_acceptance: terms } },
        metadata: { nimbusSandboxAccount: reference },
      }, { idempotencyKey: key("connect") });
      await update("connectedAccountId", connectedAccount.id);
    }
    if (connectedAccount.capabilities?.treasury !== "active" || connectedAccount.capabilities?.card_issuing !== "active") return true;

    const scope = { stripeAccount: connectedAccount.id };
    let financial = link.financialAccountId
      ? await this.stripe.treasury.financialAccounts.retrieve(link.financialAccountId, scope) : undefined;
    if (!financial) {
      financial = await this.stripe.treasury.financialAccounts.create({
        supported_currencies: ["usd"],
        features: { card_issuing: { requested: true }, intra_stripe_flows: { requested: true },
          financial_addresses: { aba: { requested: true } } },
        metadata: { nimbusSandboxAccount: reference },
      }, { ...scope, idempotencyKey: key("financial") });
      check(financial.livemode === false, "LIVE_MODE_FORBIDDEN");
      await update("financialAccountId", financial.id);
    }
    if (name && link.displayName !== name) {
      await this.syncName(accountId, { ...link, connectedAccountId: connectedAccount.id, financialAccountId: financial.id }, name);
    }
    if (financial.status !== "open" || !financial.active_features?.includes("card_issuing")) return true;

    let holder = link.cardholderId ? await this.stripe.issuing.cardholders.retrieve(link.cardholderId, scope) : undefined;
    if (!holder) {
      holder = await this.stripe.issuing.cardholders.create({
        name: "Nimbus Sandbox", email, phone_number: "+14155550100", type: "individual", status: "active",
        billing: { address: { ...address, line1: "123 Test Street" } },
        individual: { first_name: "Nimbus", last_name: "Sandbox", dob, card_issuing: { user_terms_acceptance: terms } },
      }, { ...scope, idempotencyKey: key("cardholder") });
      await update("cardholderId", holder.id);
    }
    if (holder.requirements?.past_due?.length || holder.requirements?.disabled_reason) return true;
    const card = await this.stripe.issuing.cards.create({
      cardholder: holder.id, financial_account: financial.id, currency: "usd", type: "virtual", status: "active",
    }, { ...scope, idempotencyKey: key("card") });
    check(card.livemode === false && card.financial_account === financial.id, "STRIPE_SANDBOX_RESOURCE_MISMATCH", 409);
    await this.bank.store.run(async (t) => {
      const current = await t.get(path);
      check(current && current.ownerUid === link.ownerUid && current.financialAccountId === financial.id,
        "STRIPE_SANDBOX_RESOURCE_MISMATCH", 409);
      t.set(path, { ...current, cardId: card.id, status: "ready" });
    });
    return false;
  }

  private async syncName(accountId: string, link: Doc, name: string) {
    const scope = { stripeAccount: link.connectedAccountId };
    const stripeAccount = await this.stripe.treasury.financialAccounts.retrieve(link.financialAccountId, scope);
    check(stripeAccount.livemode === false && stripeAccount.id === link.financialAccountId,
      "STRIPE_SANDBOX_RESOURCE_MISMATCH", 409);
    if (stripeAccount.nickname !== name) {
      const updated = await this.stripe.treasury.financialAccounts.update(link.financialAccountId, { nickname: name }, scope);
      check(updated.nickname === name, "STRIPE_SANDBOX_RESOURCE_MISMATCH", 409);
    }
    await this.bank.store.run(async (t) => {
      const path = "stripeSandboxAccountLinks/" + accountId;
      const current = await t.get(path);
      const user = await t.get("users/" + link.ownerUid);
      check(current && current.ownerUid === link.ownerUid && current.financialAccountId === link.financialAccountId &&
        current.connectedAccountId === link.connectedAccountId && user?.role === "CUSTOMER",
        "STRIPE_SANDBOX_RESOURCE_MISMATCH", 409);
      if (customerName(user) === name) t.set(path, { ...current, displayName: name });
    });
  }

  async get(actor: Actor, accountId: string) {
    const link = await this.bank.store.run(async (t) => {
      const user = await this.bank.authorize(t, actor);
      const account = await this.bank.owned(t, actor, accountId);
      if (account.type !== "CHECKING" || actor.role !== "CUSTOMER") return undefined;
      const mapping = await t.get("stripeSandboxAccountLinks/" + accountId);
      if (mapping) check(mapping.ownerUid === actor.uid, "FORBIDDEN", 403);
      const name = customerName(user);
      return mapping ? { mapping, needsName: !!name && name !== mapping.displayName } : null;
    });
    if (link === undefined) return { status: "unlinked" as const };
    if (link === null) {
      this.scheduleProvision(accountId);
      return { status: "pending" as const };
    }
    const { mapping, needsName } = link;
    if (needsName) this.scheduleProvision(accountId);
    if (!mapping.cardId && mapping.status === "pending") {
      this.scheduleProvision(accountId);
      return { status: "pending" as const };
    }

    const { connectedAccountId, financialAccountId, cardId } = mapping;
    check(typeof connectedAccountId === "string" && /^acct_[a-zA-Z0-9_]+$/.test(connectedAccountId) &&
      typeof financialAccountId === "string" && /^fa_[a-zA-Z0-9_]+$/.test(financialAccountId) &&
      (!cardId || (typeof cardId === "string" && /^ic_[a-zA-Z0-9_]+$/.test(cardId))),
      "STRIPE_SANDBOX_RESOURCE_MISMATCH", 409);
    const scope = { stripeAccount: connectedAccountId };
    const [account, activity, card] = await Promise.all([
      this.stripe.treasury.financialAccounts.retrieve(financialAccountId, scope),
      this.stripe.treasury.transactions.list({ financial_account: financialAccountId, limit: 100 }, scope),
      cardId ? this.stripe.issuing.cards.retrieve(cardId, scope) : undefined,
    ]);
    const available = account.balance.cash.usd;
    check(account.livemode === false && account.id === financialAccountId &&
      account.supported_currencies.includes("usd") && Number.isSafeInteger(available),
      "STRIPE_SANDBOX_RESOURCE_MISMATCH", 409);
    if (card) {
      check(card.livemode === false && card.financial_account === financialAccountId && card.currency === "usd",
        "STRIPE_SANDBOX_RESOURCE_MISMATCH", 409);
    }
    const recent = activity.data.slice(0, 10);
    let page = activity;
    while (true) {
      const rows = page.data.map((transaction) => {
        check(transaction.livemode === false && transaction.financial_account === financialAccountId &&
          transaction.currency === "usd" && Number.isSafeInteger(transaction.amount),
          "STRIPE_SANDBOX_RESOURCE_MISMATCH", 409);
        return {
          id: "stripe_treasury_" + digest([accountId, transaction.id]),
          providerTransactionId: transaction.id,
          amountMinor: Math.abs(transaction.amount),
          type: transaction.amount < 0 ? "STRIPE_DEBIT" : "STRIPE_CREDIT",
          currency: "USD",
          status: transaction.status === "posted" ? "SETTLED" : transaction.status === "void" ? "FAILED" : "PENDING",
          committedAt: transaction.created * 1000,
          description: transaction.description ?? "",
        };
      });
      if (rows.length) await this.bank.store.run(async (t) => {
        await this.bank.authorize(t, actor);
        await this.bank.owned(t, actor, accountId);
        const currentLink = await t.get("stripeSandboxAccountLinks/" + accountId);
        check(currentLink?.ownerUid === actor.uid && currentLink.financialAccountId === financialAccountId &&
          currentLink.connectedAccountId === connectedAccountId, "STRIPE_SANDBOX_RESOURCE_MISMATCH", 409);
        const existing = await Promise.all(rows.map((row) => t.get("transactions/" + row.id)));
        rows.forEach((row, index) => {
          const old = existing[index];
          if (old && (old.status === row.status || old.status !== "PENDING")) return;
          t.set("transactions/" + row.id, {
            ...row, ownerUid: actor.uid, actorUid: actor.uid, sourceAccountId: accountId,
            provider: "STRIPE_TREASURY",
          });
        });
      });
      const last = page.data.at(-1);
      if (!page.has_more || !last) break;
      page = await this.stripe.treasury.transactions.list({
        financial_account: financialAccountId, limit: 100, starting_after: last.id,
      }, scope);
    }
    return {
      status: account.status,
      provisioning: mapping.status === "pending",
      currency: "USD",
      availableMinor: available,
      inboundPendingMinor: account.balance.inbound_pending.usd ?? 0,
      outboundPendingMinor: account.balance.outbound_pending.usd ?? 0,
      card: card ? { brand: card.brand, last4: card.last4, status: card.status } : null,
      transactions: recent.map((transaction) => {
        return {
          amountMinor: transaction.amount, currency: transaction.currency.toUpperCase(),
          status: transaction.status, description: transaction.description, createdAt: transaction.created * 1000,
        };
      }),
    };
  }
}
