import type Stripe from "stripe";
import type { Banking } from "../../core/banking.js";
import type { Doc, Tx } from "../../core/store.js";
import { digest } from "../../shared/crypto.js";
import { check, Fault } from "../../shared/errors.js";
import type { Actor } from "../auth/auth.types.js";
import { intentDigest, rate } from "../security/intent.js";
import type { StripeSandboxAccounts } from "./stripe-sandbox-accounts.service.js";

type Kind = "TRANSFER" | "DEMO_MOVEMENT" | "PAYMENT";
type Link = { connectedAccountId: string; financialAccountId: string; cardId: string };
const vndPerCent = 250;
const linkIds = (link: Link | null) => link
  ? [link.connectedAccountId, link.financialAccountId, link.cardId] : null;
const movementPolicy = (source: Link, destination: Link | null, movement: string, beneficiary?: string) =>
  digest([linkIds(source), linkIds(destination), movement, beneficiary ?? null]);

export class StripeSandboxMovements {
  constructor(private bank: Banking, private stripe: Stripe, private demoRecipient = "demo_recipient_checking",
    private accounts?: StripeSandboxAccounts) {}

  private async linked(t: Tx, account: Doc): Promise<Link> {
    check(account.type === "CHECKING" && account.status === "ACTIVE", "INVALID_ACCOUNT", 409);
    const link = await t.get("stripeSandboxAccountLinks/" + account.id);
    check(link && (link.status === "ready" || link.status === undefined) && link.ownerUid === account.ownerUid &&
      typeof link.connectedAccountId === "string" && typeof link.financialAccountId === "string" &&
      typeof link.cardId === "string", "STRIPE_SANDBOX_ACCOUNT_REQUIRED", 409);
    return { connectedAccountId: link.connectedAccountId, financialAccountId: link.financialAccountId, cardId: link.cardId };
  }

  private async scope(link: Link) {
    const scope = { stripeAccount: link.connectedAccountId };
    const account = await this.stripe.treasury.financialAccounts.retrieve(link.financialAccountId, scope);
    check(account.livemode === false && account.id === link.financialAccountId && account.status === "open",
      "STRIPE_SANDBOX_RESOURCE_MISMATCH", 409);
    return { scope, balance: account.balance.cash.usd ?? 0 };
  }

  private async resolvePhone(t: Tx, phone: string) {
    const local = phone.startsWith("+84") ? "0" + phone.slice(3) : phone;
    const [canonical, legacy] = await Promise.all([
      t.list("users", { contactPhone: "+84" + local.slice(1) }, { limit: 2 }),
      t.list("users", { contactPhone: local }, { limit: 2 }),
    ]);
    const users = [...new Map([...canonical, ...legacy].map(user => [user.id, user])).values()];
    check(users.length === 1, "INVALID_DESTINATION", 404);
    const user = users[0];
    check(user.role === "CUSTOMER" && (user.status === "ACTIVE" ? user.active !== false
      : user.status === undefined && user.active === true), "INVALID_DESTINATION", 404);

    if (typeof user.primaryCheckingAccountId === "string") {
      const account = await t.get("accounts/" + user.primaryCheckingAccountId);
      check(account?.ownerUid === user.id, "INVALID_DESTINATION", 404);
      return { account: { ...account, id: user.primaryCheckingAccountId }, fullName: user.fullName ?? "" };
    }
    const accounts = await t.list("accounts", { ownerUid: user.id, type: "CHECKING", status: "ACTIVE" }, { limit: 2 });
    check(accounts.length === 1, "INVALID_DESTINATION", 404);
    return { account: accounts[0], fullName: user.fullName ?? "" };
  }

  async lookupRecipient(actor: Actor, sourceAccountId: string, phone: string) {
    check(actor.role === "CUSTOMER", "FORBIDDEN", 403);
    await this.bank.store.run(async (t) => {
      await this.bank.authorize(t, actor);
      await this.linked(t, await this.bank.owned(t, actor, sourceAccountId));
      await rate(t, "transfer-recipient", actor.uid, this.bank.now(), 15, 60_000);
    });
    return this.bank.store.run(async (t) => {
      const { account, fullName } = await this.resolvePhone(t, phone);
      const source = await this.linked(t, await this.bank.owned(t, actor, sourceAccountId));
      const destination = await this.linked(t, account);
      check(source.connectedAccountId !== destination.connectedAccountId, "INVALID_DESTINATION", 400);
      return { accountId: account.id, fullName };
    });
  }

  async prepare(actor: Actor, kind: Kind, input: Doc) {
    check(actor.role === "CUSTOMER", "FORBIDDEN", 403);
    const id = digest([actor.uid, kind, input.requestId]);
    const hash = digest(input);
    const read = await this.bank.store.run(async (t) => {
      await this.bank.authorize(t, actor);
      const old = await t.get("transactionIntents/" + id);
      if (old) {
        check(old.payloadDigest === hash && old.provider === "STRIPE_TREASURY", "IDEMPOTENCY_CONFLICT", 409);
        return { old };
      }
      let bill;
      let amountMinor = input.amountMinor;
      let sourceAccountId = input.sourceAccountId ?? input.accountId;
      if (kind === "PAYMENT") {
        bill = await t.get("bills/" + input.billId);
        check(bill?.ownerUid === actor.uid, "FORBIDDEN", 403);
        const lock = await t.get("billPaymentLocks/" + input.billId);
        if (lock && lock.released !== true && bill.status === "UNPAID") {
          const pending = await t.get("transactionIntents/" + lock.paymentId);
          check(pending?.provider === "STRIPE_TREASURY" && pending.status === "COMMIT_PENDING" &&
            pending.actorUid === actor.uid && pending.billId === input.billId, "PAYMENT_IN_PROGRESS", 409);
          return { old: pending };
        }
        check(bill.status === "UNPAID", "INVALID_STATE", 409);
        check(bill.quoteExpiresAt == null || this.bank.now() < bill.quoteExpiresAt, "QUOTE_EXPIRED", 410);
        check(Number.isSafeInteger(bill.amountMinor) && bill.amountMinor > 0, "INVALID_AMOUNT");
        check(["VND", "USD"].includes(bill.currency), "INVALID_CURRENCY");
        amountMinor = bill.currency === "VND" ? Math.round(bill.amountMinor / vndPerCent) : bill.amountMinor;
        check(amountMinor > 0, "INVALID_AMOUNT");
        if (!sourceAccountId) {
          const owned = await t.list("accounts", { ownerUid: actor.uid, type: "CHECKING", status: "ACTIVE" });
          sourceAccountId = owned.find(account => account.stripeSandboxLinkPath)?.id;
          check(sourceAccountId, "STRIPE_SANDBOX_ACCOUNT_REQUIRED", 409);
        }
      }
      const source = await this.linked(t, await this.bank.owned(t, actor, sourceAccountId));
      const movement = kind === "DEMO_MOVEMENT" ? input.type : kind;
      let destinationAccountId = movement === "EXTERNAL_TRANSFER" ? this.demoRecipient
        : movement === "TRANSFER" ? input.destinationAccountId : "DEMO_CASH";
      let destination: Link | null = null;
      if (movement === "TRANSFER" || movement === "EXTERNAL_TRANSFER") {
        let account: Doc | undefined;
        if (movement === "TRANSFER" && /^(?:0[0-9]{9}|\+84[0-9]{9})$/.test(destinationAccountId)) {
          account = (await this.resolvePhone(t, destinationAccountId)).account;
          destinationAccountId = account.id;
          check(!input.recipientAccountId || input.recipientAccountId === destinationAccountId, "RECIPIENT_CHANGED", 409);
        }
        account ??= await t.get("accounts/" + destinationAccountId);
        check(account && destinationAccountId !== sourceAccountId, "INVALID_DESTINATION", 400);
        destination = await this.linked(t, { ...account, id: destinationAccountId });
        check(source.connectedAccountId !== destination.connectedAccountId, "INVALID_DESTINATION", 409);
      }
      const now = this.bank.now();
      return { source, destination, intent: {
        id, kind, movement, actorUid: actor.uid, sourceAccountId,
        ...(bill ? { billId: input.billId, billedMinor: bill.amountMinor, billedCurrency: bill.currency } : { destinationAccountId }),
        provider: "STRIPE_TREASURY", requestId: input.requestId, payloadDigest: hash,
        ...(movement === "EXTERNAL_TRANSFER" ? { beneficiaryAccount: input.beneficiaryAccount } : {}),
        policyDigest: movementPolicy(source, destination, movement, input.beneficiaryAccount),
        amountMinor, feeMinor: 0, currency: "USD",
        faceRequired: this.bank.security.requiresFace("VND", amountMinor * vndPerCent) ||
          this.bank.security.requiresFace("USD", amountMinor),
        status: "CREATED", securityVersion: 1, createdAt: now, expiresAt: Math.min(now + 300000, bill?.quoteExpiresAt ?? Infinity),
      } };
    });
    if (read.old) return this.result(read.old);
    const { intent, source, destination } = read;
    const { balance } = await this.scope(source);
    check(intent.movement === "DEPOSIT" || balance >= intent.amountMinor, "INSUFFICIENT_FUNDS", 409);
    return this.bank.store.run(async (t) => {
      await this.bank.authorize(t, actor);
      const old = await t.get("transactionIntents/" + id);
      if (old) {
        check(old.payloadDigest === hash && old.provider === intent.provider, "IDEMPOTENCY_CONFLICT", 409);
        return this.result(old);
      }
      t.set("transactionIntents/" + id, { ...intent, source, destination, digest: intentDigest(intent) });
      return this.result(intent);
    });
  }

  async commit(actor: Actor, id: string, kind: Kind) {
    const intent: Doc = await this.bank.store.run(async (t) => {
      await this.bank.authorize(t, actor);
      const i = await this.bank.transfers.getIntent(t, actor, id);
      check(i.kind === kind && i.provider === "STRIPE_TREASURY", "INVALID_INTENT", 409);
      if (["FAILED", "COMMITTED"].includes(i.status)) return i;
      const source = await this.linked(t, await this.bank.owned(t, actor, i.sourceAccountId));
      let destination = null;
      if (i.destination) {
        const account = await t.get("accounts/" + i.destinationAccountId);
        check(account, "INVALID_DESTINATION", 409);
        destination = await this.linked(t, { ...account, id: i.destinationAccountId });
      }
      check(i.policyDigest === movementPolicy(source, destination, i.movement, i.beneficiaryAccount) &&
        i.policyDigest === movementPolicy(i.source, i.destination, i.movement, i.beneficiaryAccount) &&
        i.digest === intentDigest(i), "INTENT_CHANGED", 409);
      if (i.status === "COMMIT_PENDING") return i;
      if (kind === "PAYMENT") {
        const bill = await t.get("bills/" + i.billId);
        check(bill?.ownerUid === actor.uid && bill.status === "UNPAID" &&
          bill.amountMinor === i.billedMinor && bill.currency === i.billedCurrency, "REQUOTE_REQUIRED", 409);
        const lock = await t.get("billPaymentLocks/" + i.billId);
        check(!lock || lock.released === true || lock.paymentId === id, "PAYMENT_IN_PROGRESS", 409);
        t.set("billPaymentLocks/" + i.billId, { paymentId: id });
      }
      await this.bank.security.consume(t, i);
      const submittedAt = this.bank.now();
      const usageId = i.movement === "DEPOSIT" ? null
        : digest([i.sourceAccountId, new Date(submittedAt).toISOString().slice(0, 10)]);
      if (usageId) {
        const path = "stripeSandboxDailyUsage/" + usageId;
        const usage = await t.get(path);
        const policy = await t.get("checkingDetails/" + i.sourceAccountId);
        const amountVnd = (usage?.amountVnd ?? 0) + i.amountMinor * vndPerCent;
        check(Number.isSafeInteger(amountVnd), "AMOUNT_OVERFLOW");
        check(policy?.dailyLimitMinor == null || amountVnd <= policy.dailyLimitMinor, "DAILY_LIMIT_EXCEEDED", 409);
        t.set(path, { accountId: i.sourceAccountId, amountVnd });
      }
      const pending = { ...i, status: "COMMIT_PENDING", submittedAt, usageId };
      t.set("transactionIntents/" + id, pending);
      return pending;
    });
    if (intent.status !== "COMMIT_PENDING") return this.result(intent, true);
    // Stripe may expire an idempotency key after 24 hours. An ambiguous old request requires reconciliation.
    check(intent.providerReference || this.bank.now() - intent.submittedAt < 23 * 3600_000,
      "STRIPE_RECONCILIATION_REQUIRED", 409);
    let settlement;
    try {
      settlement = await this.execute(intent);
    } catch (error) {
      if (!(error instanceof Fault) || !["INSUFFICIENT_FUNDS", "STRIPE_CARD_DECLINED", "DEMO_PROVIDER_REJECTED"].includes(error.code))
        throw error;
      settlement = { status: "FAILED", failureCode: error.code };
    }
    const result = await this.bank.store.run(async (t) => {
      const current = await this.bank.transfers.getIntent(t, actor, id);
      if (current.status !== "COMMIT_PENDING") return this.result(current, true);
      const finished = { ...current, ...settlement };
      if (settlement.status === "COMMIT_PENDING") {
        t.set("transactionIntents/" + id, finished);
        return this.result(finished, true);
      }
      if (settlement.status === "FAILED" && current.usageId) {
        const path = "stripeSandboxDailyUsage/" + current.usageId;
        const usage = await t.get(path);
        if (usage) t.set(path, { ...usage, amountVnd: Math.max(0, usage.amountVnd - current.amountMinor * vndPerCent) });
      }
      if (kind === "PAYMENT") await this.finishBill(t, actor, finished);
      t.set("transactionIntents/" + id, finished);
      this.bank.audit(t, actor, "STRIPE_MOVEMENT_" + settlement.status, id, current.requestId);
      return this.result(finished, true);
    });
    if (settlement.status === "COMMITTED" && this.accounts) {
      const sender = this.accounts.get(actor, intent.sourceAccountId);
      let receiver;
      if (intent.destination) {
        const d = await this.bank.store.run(t => t.get("accounts/" + intent.destinationAccountId));
        if (d) receiver = this.accounts.get({ uid: d.ownerUid, role: "CUSTOMER", permissions: [] }, intent.destinationAccountId);
      }
      await Promise.allSettled([sender, receiver]);
    }
    return result;
  }

  private async finishBill(t: Tx, actor: Actor, i: Doc) {
    const bill = await t.get("bills/" + i.billId);
    const lock = await t.get("billPaymentLocks/" + i.billId);
    check(bill?.ownerUid === actor.uid && bill.status === "UNPAID" &&
      lock?.paymentId === i.id, "PAYMENT_RECONCILIATION_REQUIRED", 409);
    const success = i.status === "COMMITTED";
    const now = this.bank.now();
    t.set("payments/" + i.id, {
      ownerUid: actor.uid, billId: i.billId, status: success ? "SUCCEEDED" : "CANCELLED",
      amountMinor: i.billedMinor, currency: i.billedCurrency, chargedAmountMinor: i.amountMinor,
      chargedCurrency: "USD", sourceAccountId: i.sourceAccountId, authorizationDigest: i.digest, updatedAt: now,
      ...(i.providerReference && { providerId: i.providerReference }),
    });
    if (!success) {
      t.set("billPaymentLocks/" + i.billId, { ...lock, released: true });
      return;
    }
    t.set("bills/" + i.billId, { ...bill, status: "PAID", paymentId: i.id });
    t.set("outbox/" + i.id, { paymentId: i.id, billId: i.billId, ownerUid: actor.uid, status: "PENDING", attempts: 0, createdAt: now });
  }

  private async execute(i: Doc) {
    const { scope } = await this.scope(i.source as Link);
    const options = { ...scope, idempotencyKey: "nimbus-movement-" + i.id };
    if (i.movement === "DEPOSIT") {
      const credit = i.providerReference
        ? await this.stripe.treasury.receivedCredits.retrieve(i.providerReference, scope)
        : await this.stripe.testHelpers.treasury.receivedCredits.create({
        financial_account: i.source.financialAccountId, amount: i.amountMinor, currency: "usd", network: "ach",
      }, options);
      check(credit.livemode === false, "LIVE_MODE_FORBIDDEN");
      return this.providerState(credit.id, credit.status, "succeeded");
    }
    if (i.movement === "WITHDRAWAL" || i.movement === "PAYMENT") {
      const card = await this.stripe.issuing.cards.retrieve(i.source.cardId, scope);
      check(card.livemode === false && card.status === "active" && card.financial_account === i.source.financialAccountId,
        "STRIPE_SANDBOX_RESOURCE_MISMATCH", 409);
      const authorization = i.providerReference
        ? await this.stripe.issuing.authorizations.retrieve(i.providerReference, scope)
        : await this.stripe.testHelpers.issuing.authorizations.create({
        card: i.source.cardId, amount: i.amountMinor, currency: "usd",
        merchant_data: i.movement === "WITHDRAWAL"
          ? { name: "Nimbus Sandbox ATM" }
          : { name: "Nimbus Sandbox Utilities", category: "utilities" },
      }, options);
      check(authorization.livemode === false, "LIVE_MODE_FORBIDDEN");
      check(authorization.approved, "STRIPE_CARD_DECLINED", 409);
      if (authorization.status !== "pending")
        return this.providerState(authorization.id, authorization.status, "closed");
      const captured = await this.stripe.testHelpers.issuing.authorizations.capture(authorization.id, {},
        { ...scope, idempotencyKey: options.idempotencyKey + "-capture" });
      check(captured.livemode === false, "LIVE_MODE_FORBIDDEN");
      return this.providerState(captured.id, captured.status, "closed");
    }
    check(i.beneficiaryAccount !== "0000000002", "DEMO_PROVIDER_REJECTED", 409);
    let payment;
    try {
      payment = i.providerReference ? await this.stripe.treasury.outboundPayments.retrieve(i.providerReference, scope)
        : await this.stripe.treasury.outboundPayments.create({
        financial_account: i.source.financialAccountId,
        destination_payment_method_data: { type: "financial_account", financial_account: i.destination.financialAccountId },
        amount: i.amountMinor, currency: "usd", description: "Nimbus Sandbox Transfer",
      }, options);
    } catch (error) {
      if ((error as { code?: string }).code === "balance_insufficient") throw new Fault("INSUFFICIENT_FUNDS", 409);
      throw error;
    }
    check(payment.livemode === false, "LIVE_MODE_FORBIDDEN");
    if (payment.status === "processing")
      payment = await this.stripe.treasury.outboundPayments.retrieve(payment.id, scope);
    return this.providerState(payment.id, payment.status, "posted");
  }

  private providerState(providerReference: string, state: string, success: string) {
    const failed = ["failed", "returned", "canceled", "cancelled", "reversed", "expired"].includes(state);
    return {
      providerReference,
      status: state === success ? "COMMITTED" : failed ? "FAILED" : "COMMIT_PENDING",
      ...(failed && { failureCode: "STRIPE_MOVEMENT_FAILED" }),
    };
  }

  private result(i: Doc, committed = false) {
    const status = committed && i.kind === "TRANSFER" && i.status === "COMMITTED" ? "SETTLED"
      : committed && i.kind === "PAYMENT" && i.status === "COMMITTED" ? "SUCCEEDED"
      : i.status;
    return {
      id: i.id, intentId: i.id,
      status, requestId: i.requestId,
      expiresAt: i.expiresAt, faceRequired: i.faceRequired, currency: i.currency, amountMinor: i.amountMinor,
      providerReference: i.providerReference ?? null, failureCode: i.failureCode ?? null,
    };
  }
}
