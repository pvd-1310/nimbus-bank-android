import { randomUUID } from "node:crypto";
import { Banking } from "../../core/banking.js";
import type { Doc, Tx } from "../../core/store.js";
import { digest } from "../../shared/crypto.js";
import { check, key, minor } from "../../shared/errors.js";
import { type Actor } from "../auth/auth.types.js";
import type { StripeGateway } from "./stripe.provider.js";
import { intentDigest } from "../security/intent.js";
import { drainOutbox } from "../orders/fulfillment.service.js";
const compatiblePayment = (d: Doc | undefined): Doc | undefined =>
  d
    ? {
        ...d,
        ...((d.billOrOrderRef ?? d.billId) !== undefined
          ? { billId: d.billOrOrderRef ?? d.billId }
          : {}),
        ...((d.providerTxnRef ?? d.providerId)
          ? { providerId: d.providerTxnRef ?? d.providerId }
          : {}),
      }
    : undefined;

async function recordBillPayment(t: Tx, id: string, payment: Doc, intent: Doc | undefined, committedAt: number) {
  const path = "transactions/stripe_" + id;
  if (await t.get(path)) return;
  t.set(path, {
    ownerUid: payment.ownerUid,
    actorUid: payment.ownerUid,
    requestId: intent?.requestId ?? id,
    type: "BILL_PAYMENT",
    paymentId: id,
    billId: payment.billId,
    ...(payment.sourceAccountId ? { sourceAccountId: payment.sourceAccountId } : {}),
    amountMinor: payment.amountMinor,
    currency: payment.currency,
    status: "SETTLED",
    paymentMethod: payment.paymentMethod ?? "STRIPE_CARD",
    committedAt,
  });
}

export class Payments {
  constructor(
    private bank: Banking,
    readonly provider: StripeGateway | undefined,
  ) {}
  async prepare(a: Actor, billId: string, requestId: string, paymentMethod = "STRIPE_CARD", sourceAccountId?: string) {
    check(paymentMethod === "STRIPE_CARD" || paymentMethod === "NIMBUS_ACCOUNT", "INVALID_PAYMENT_METHOD");
    if (paymentMethod === "STRIPE_CARD") check(this.provider, "SETUP_REQUIRED", 503);
    if (paymentMethod === "NIMBUS_ACCOUNT") key(sourceAccountId);
    key(billId);
    key(requestId);
    return this.bank.store.run(async (t) => {
      await this.bank.authorize(t, a);
      const bill = await t.get("bills/" + billId);
      check(bill?.ownerUid === a.uid, "FORBIDDEN", 403);
      minor(bill.amountMinor);
      const lock = await t.get("billPaymentLocks/" + billId);
      if (lock && lock.released !== true && bill.status === "UNPAID") {
        key(lock.paymentId);
        const payment = compatiblePayment(await t.get("payments/" + lock.paymentId));
        const intent = await t.get("transactionIntents/" + lock.paymentId);
        check(
          payment?.ownerUid === a.uid && payment.billId === billId && payment.gateway !== "SIMULATOR" &&
          ["CREATED", "PROCESSING"].includes(payment.status) &&
          intent?.actorUid === a.uid && intent.kind === "PAYMENT" &&
          (intent.billId ?? intent.target?.id) === billId &&
          payment.amountMinor === intent.amountMinor && payment.currency === intent.currency &&
          intent.status === "COMMIT_PENDING" &&
          typeof intent.requestId === "string" &&
          digest([a.uid, "PAYMENT", intent.requestId]) === lock.paymentId &&
          payment.authorizationDigest === intent.digest &&
          intent.digest === intentDigest(intent),
          "PAYMENT_RECONCILIATION_REQUIRED",
          409,
        );
        check((intent.paymentMethod ?? "STRIPE_CARD") === paymentMethod &&
          (intent.sourceAccountId ?? undefined) === (paymentMethod === "NIMBUS_ACCOUNT" ? sourceAccountId : undefined), "IDEMPOTENCY_CONFLICT", 409);
        if (payment.amountMinor === 0 && payment.status === "CREATED" && !payment.providerId) {
          // Stripe rejected the zero amount. The corrected amount needs its own OTP.
          t.set("payments/" + lock.paymentId, { ...payment, status: "CANCELLED" });
          t.set("transactionIntents/" + lock.paymentId, {
            ...intent, status: "FAILED", failureCode: "INVALID_AMOUNT",
          });
          t.set("billPaymentLocks/" + billId, { ...lock, released: true });
          this.bank.audit(t, a, "PAYMENT_INVALID_AMOUNT", lock.paymentId);
        } else {
          return {
            id: lock.paymentId,
            intentId: lock.paymentId,
            requestId: intent.requestId,
            status: "COMMIT_PENDING",
          };
        }
      }
      const originalId = digest([a.uid, "PAYMENT", requestId]);
      const original = await t.get("transactionIntents/" + originalId);
      const replacesInvalidAmount =
        original?.actorUid === a.uid && original.kind === "PAYMENT" &&
        (original.billId ?? original.target?.id) === billId &&
        original.status === "FAILED" && original.failureCode === "INVALID_AMOUNT";
      const paymentRequestId = replacesInvalidAmount
        ? digest(["PAYMENT_INVALID_AMOUNT", originalId])
        : requestId;
      const id = replacesInvalidAmount ? digest([a.uid, "PAYMENT", paymentRequestId]) : originalId;
      if (paymentMethod === "NIMBUS_ACCOUNT") {
        const account = await this.bank.owned(t, a, sourceAccountId!);
        check(account.type === "CHECKING" && account.status === "ACTIVE" && account.currency === "VND" && bill.currency === "VND", "INVALID_ACCOUNT", 409);
      }
      const hash = paymentMethod === "STRIPE_CARD"
        ? digest([billId, bill.amountMinor, bill.currency])
        : digest([billId, bill.amountMinor, bill.currency, paymentMethod, sourceAccountId]);
      const old = replacesInvalidAmount ? await t.get("transactionIntents/" + id) : original;
      if (old) {
        check((old.payloadDigest ?? old.digest) === hash, "IDEMPOTENCY_CONFLICT", 409);
        return { id, intentId: id, requestId: paymentRequestId, status: old.status };
      }
      check(bill.status === "UNPAID", "INVALID_STATE");
      this.checkQuote(bill);
      const intent = {
        id,
        sourceId: paymentMethod === "NIMBUS_ACCOUNT" ? sourceAccountId : "STRIPE",
        ...(paymentMethod === "NIMBUS_ACCOUNT" ? { sourceAccountId } : {}),
        paymentMethod,
        feeMinor: 0,
        securityVersion: 1,
        faceRequired: this.bank.security.requiresFace(bill.currency, bill.amountMinor),
        actorUid: a.uid,
        requestId: paymentRequestId,
        kind: "PAYMENT",
        billId,
        amountMinor: bill.amountMinor,
        currency: bill.currency,
        payloadDigest: hash,
        expiresAt: Math.min(this.bank.now() + 300000, bill.quoteExpiresAt ?? Infinity),
        status: "CREATED",
      };
      t.set("transactionIntents/" + id, { ...intent, digest: intentDigest(intent) });
      return { id, intentId: id, requestId: paymentRequestId, status: intent.status, expiresAt: intent.expiresAt, faceRequired: intent.faceRequired };
    });
  }

  async prepareTopup(a: Actor, accountId: string, amountMinor: number, currency: string, requestId: string) {
    check(this.provider, "SETUP_REQUIRED", 503);
    check(a.role === "CUSTOMER", "FORBIDDEN", 403);
    key(accountId);
    key(requestId);
    minor(amountMinor);
    check(amountMinor > 0 && currency === "VND", "INVALID_AMOUNT");
    const id = digest([a.uid, "TOP_UP", requestId]);
    const payloadDigest = digest([accountId, amountMinor, currency]);
    return this.bank.store.run(async (t) => {
      await this.bank.authorize(t, a);
      const old = await t.get("transactionIntents/" + id);
      if (old) {
        check(old.payloadDigest === payloadDigest, "IDEMPOTENCY_CONFLICT", 409);
        return { id, intentId: id, status: old.status, expiresAt: old.expiresAt, faceRequired: old.faceRequired ?? false };
      }
      const account = await this.bank.owned(t, a, accountId);
      check(account.type === "CHECKING" && account.status === "ACTIVE" && account.currency === "VND", "INVALID_ACCOUNT", 409);
      check(Number.isSafeInteger(account.availableMinor + amountMinor) && Number.isSafeInteger(account.bookedMinor + amountMinor), "AMOUNT_OVERFLOW", 409);
      const now = this.bank.now();
      const intent = {
        id, kind: "TOP_UP", actorUid: a.uid, requestId, sourceId: accountId,
        sourceAccountId: accountId, destinationAccountId: accountId,
        amountMinor, feeMinor: 0, currency, payloadDigest,
        faceRequired: this.bank.security.requiresFace(currency, amountMinor),
        securityVersion: 1, status: "CREATED", createdAt: now, expiresAt: now + 300_000,
      };
      t.set("transactionIntents/" + id, { ...intent, digest: intentDigest(intent) });
      this.bank.audit(t, a, "TOP_UP_INTENT", id, requestId);
      return { id, intentId: id, status: intent.status, expiresAt: intent.expiresAt, faceRequired: intent.faceRequired };
    });
  }

  async createTopup(a: Actor, accountId: string, requestId: string) {
    check(this.provider, "SETUP_REQUIRED", 503);
    key(accountId);
    key(requestId);
    const id = digest([a.uid, "TOP_UP", requestId]);
    const payment = await this.bank.store.run<Doc>(async (t) => {
      await this.bank.authorize(t, a);
      const existing = await t.get("payments/" + id);
      if (existing) {
        check(existing.ownerUid === a.uid && existing.purpose === "TOP_UP" && existing.accountId === accountId, "IDEMPOTENCY_CONFLICT", 409);
        return existing;
      }
      const intent = await this.bank.transfers.getIntent(t, a, id);
      check(intent.kind === "TOP_UP" && intent.sourceAccountId === accountId && intent.status === "CREATED", "INVALID_INTENT", 409);
      const account = await this.bank.owned(t, a, accountId);
      check(account.type === "CHECKING" && account.status === "ACTIVE" && account.currency === "VND", "INVALID_ACCOUNT", 409);
      await this.bank.security.consume(t, intent);
      const record = {
        id, ownerUid: a.uid, purpose: "TOP_UP", accountId,
        amountMinor: intent.amountMinor, currency: intent.currency,
        status: "CREATED", authorizationDigest: intent.digest, createdAt: this.bank.now(),
      };
      t.set("payments/" + id, record);
      t.set("transactionIntents/" + id, { ...intent, status: "COMMIT_PENDING" });
      this.bank.audit(t, a, "TOP_UP_PAYMENT_CREATE", id, requestId);
      return record;
    });
    if (payment.status === "SUCCEEDED") return { id, paymentId: id, gateway: "STRIPE", status: "SUCCEEDED" };
    if (payment.status === "CANCELLED") return { id, paymentId: id, gateway: "STRIPE", status: "FAILED" };
    check(payment.authorizationDigest, "REAUTHORIZE_REQUIRED", 409);
    check(payment.providerId || this.bank.now() - payment.createdAt < 23 * 3_600_000, "PAYMENT_RECONCILIATION_REQUIRED", 409);
    const customerId = await this.getOrCreateStripeCustomer(a.uid, this.provider);
    const stripeIntent = payment.providerId
      ? await this.provider.retrieve(payment.providerId)
      : await this.provider.create(id, payment.amountMinor, payment.currency, customerId, "TOP_UP");
    check(stripeIntent.metadata.paymentId === id && stripeIntent.metadata.purpose === "TOP_UP", "PROVIDER_MISMATCH", 409);
    const stripeCustomer = typeof stripeIntent.customer === "string" ? stripeIntent.customer : stripeIntent.customer?.id;
    check(stripeCustomer === customerId, "PROVIDER_MISMATCH", 409);
    await this.bank.store.run(async (t) => {
      const latest = await t.get("payments/" + id);
      check(latest?.ownerUid === a.uid && latest.purpose === "TOP_UP", "NOT_FOUND", 404);
      check(!latest.providerId || latest.providerId === stripeIntent.id, "PROVIDER_MISMATCH", 409);
      t.set("payments/" + id, { ...latest, providerId: stripeIntent.id, status: latest.status === "CREATED" ? "PROCESSING" : latest.status });
    });
    return {
      id, paymentId: id, gateway: "STRIPE", status: "COMMIT_PENDING", clientSecret: stripeIntent.client_secret,
      customerId,
      customerSessionClientSecret: await this.provider.createCustomerSession(customerId),
    };
  }

  async getTopup(a: Actor, id: string) {
    return this.bank.store.run(async (t) => {
      await this.bank.authorize(t, a);
      const payment = await t.get("payments/" + id);
      check(payment?.ownerUid === a.uid && payment.purpose === "TOP_UP", "NOT_FOUND", 404);
      return { id, status: payment.status, amountMinor: payment.amountMinor, currency: payment.currency, accountId: payment.accountId };
    });
  }

  private checkQuote(bill: Doc) {
    check(bill.quoteExpiresAt == null || this.bank.now() < bill.quoteExpiresAt, "QUOTE_EXPIRED", 410);
  }

  private async getOrCreateStripeCustomer(uid: string, provider: StripeGateway) {
    const path = "stripeCustomers/" + digest([uid]);
    const requestId = randomUUID();
    const customer = await this.bank.store.run<Doc>(async (t) => {
      const existing = await t.get(path);
      if (existing) return existing;
      t.set(path, { requestId });
      return { requestId };
    });
    if (typeof customer.customerId === "string") return customer.customerId;

    // Concurrent checkouts and retries share the same Stripe idempotency key.
    const customerId = await provider.createCustomer(customer.requestId);
    return this.bank.store.run(async (t) => {
      const existing = await t.get(path);
      if (typeof existing?.customerId === "string") return existing.customerId;
      t.set(path, { customerId });
      return customerId;
    });
  }

  async create(a: Actor, billId: string, requestId: string, paymentMethod = "STRIPE_CARD", sourceAccountId?: string) {
    if (paymentMethod === "STRIPE_CARD") check(this.provider, "SETUP_REQUIRED", 503);
    key(billId);
    key(requestId);
    const id = digest([a.uid, "PAYMENT", requestId]);
    const record = await this.bank.store.run<Doc>(async (t) => {
      await this.bank.authorize(t, a);
      const existing = compatiblePayment(await t.get("payments/" + id));
      if (existing) {
        check(existing.ownerUid === a.uid, "FORBIDDEN", 403);
        check(existing.gateway !== "SIMULATOR", "INVALID_STATE", 409);
        check(existing.billId === billId, "IDEMPOTENCY_CONFLICT", 409);
        check((existing.paymentMethod ?? "STRIPE_CARD") === paymentMethod && (existing.sourceAccountId ?? undefined) === (paymentMethod === "NIMBUS_ACCOUNT" ? sourceAccountId : undefined), "IDEMPOTENCY_CONFLICT", 409);
        return existing;
      }
      const i = await this.bank.transfers.getIntent(t, a, id),
        bill = await t.get("bills/" + billId);
      check(i.kind === "PAYMENT" && i.sourceId !== "SIMULATOR" && i.billId === billId, "INVALID_INTENT");
      check((i.paymentMethod ?? "STRIPE_CARD") === paymentMethod && (i.sourceAccountId ?? undefined) === (paymentMethod === "NIMBUS_ACCOUNT" ? sourceAccountId : undefined), "IDEMPOTENCY_CONFLICT", 409);
      minor(i.amountMinor);
      await this.bank.security.consume(t, i);
      check(
        bill?.ownerUid === a.uid && bill.status === "UNPAID",
        "INVALID_STATE",
      );
      this.checkQuote(bill);
      check(
        bill.amountMinor === i.amountMinor && bill.currency === i.currency,
        "REQUOTE_REQUIRED",
        409,
      );
      const lock = await t.get("billPaymentLocks/" + billId);
      check(
        !lock || lock.released === true || lock.paymentId === id,
        "PAYMENT_IN_PROGRESS",
        409,
      );
      if (paymentMethod === "NIMBUS_ACCOUNT") {
        const account = await this.bank.owned(t, a, sourceAccountId!);
        check(account.type === "CHECKING" && account.status === "ACTIVE" && account.currency === "VND" && bill.currency === "VND", "INVALID_ACCOUNT", 409);
        check(Number.isSafeInteger(account.availableMinor) && account.availableMinor >= i.amountMinor, "INSUFFICIENT_FUNDS", 409);
        const settledAt = this.bank.now();
        const dayUtc = new Date(settledAt).toISOString().slice(0, 10);
        const usageId = digest([sourceAccountId!, dayUtc]);
        const [policy, usage, notifEnabled] = await Promise.all([
          t.get("checkingDetails/" + sourceAccountId!),
          t.get("dailyUsage/" + usageId),
          this.bank.notifications.isTransactionEnabled(t, a.uid),
        ]);
        const dailyAmount = (usage?.amountMinor ?? 0) + i.amountMinor;
        check(Number.isSafeInteger(dailyAmount), "AMOUNT_OVERFLOW");
        if (policy?.dailyLimitMinor !== undefined) {
          check(dailyAmount <= policy.dailyLimitMinor, "DAILY_LIMIT_EXCEEDED", 409);
        }
        const availableMinor = account.availableMinor - i.amountMinor;
        const bookedMinor = account.bookedMinor - i.amountMinor;
        check(bookedMinor >= 0 && Number.isSafeInteger(bookedMinor), "INSUFFICIENT_FUNDS", 409);
        const p = { id, ownerUid: a.uid, purpose: "BILL_PAYMENT", billId, amountMinor: i.amountMinor, currency: i.currency,
          paymentMethod, sourceAccountId, status: "SUCCEEDED", gateway: "NIMBUS", authorizationDigest: i.digest, createdAt: settledAt, updatedAt: settledAt };
        t.set("dailyUsage/" + usageId, { accountId: sourceAccountId, amountMinor: dailyAmount, dayUtc });
        t.set("accounts/" + sourceAccountId, { ...account, availableMinor, bookedMinor, balanceMinor: bookedMinor, version: (account.version ?? 0) + 1 });
        t.set("payments/" + id, p);
        t.set("transactions/stripe_" + id, { ownerUid: a.uid, actorUid: a.uid, requestId, type: "BILL_PAYMENT", paymentId: id, billId, sourceAccountId, paymentMethod, amountMinor: i.amountMinor, currency: i.currency, status: "SETTLED", balanceAfterMinor: bookedMinor, committedAt: settledAt });
        t.set("ledgerEntries/" + id + "_debit", { transactionId: id, accountId: sourceAccountId, signedMinor: -i.amountMinor, amountMinor: i.amountMinor, direction: "DEBIT", currency: i.currency, postedAt: settledAt });
        t.set("ledgerEntries/" + id + "_clearing", { transactionId: id, accountId: "NIMBUS_BILLING_CLEARING", signedMinor: i.amountMinor, amountMinor: i.amountMinor, direction: "CREDIT", currency: i.currency, postedAt: settledAt });
        t.set("transactionIntents/" + id, { ...i, status: "COMMITTED", committedAt: settledAt });
        t.set("bills/" + billId, { ...bill, status: "PAID", paymentId: id });
        t.set("billPaymentLocks/" + billId, { paymentId: id });
        t.set("outbox/" + id, { paymentId: id, billId, ownerUid: a.uid, status: "PENDING", attempts: 0, createdAt: settledAt });
        this.bank.audit(t, a, "NIMBUS_PAYMENT_SETTLED", id, requestId);
        this.bank.notifications.emit(t, {
          id: `pay_${id}`,
          ownerUid: a.uid,
          type: "TRANSACTION",
          title: "Thanh toán hóa đơn thành công",
          body: `-${i.amountMinor.toLocaleString("vi-VN")} ₫ cho hóa đơn ${bill?.provider ?? billId}. Số dư mới: ${bookedMinor.toLocaleString("vi-VN")} ₫`,
          refId: id,
        }, notifEnabled);
        return { ...p, _nimbusSettled: true };
      }
      const p = {
        id,
        ownerUid: a.uid,
        purpose: "BILL_PAYMENT",
        billId,
        amountMinor: i.amountMinor,
        currency: i.currency,
        status: "CREATED",
        authorizationDigest: i.digest,
        paymentMethod: "STRIPE_CARD",
        savedCardsEnabled: true,
        createdAt: this.bank.now(),
      };
      t.set("payments/" + id, p);
      t.set("billPaymentLocks/" + billId, { paymentId: id });
      t.set("transactionIntents/" + id, { ...i, status: "COMMIT_PENDING" });
      this.bank.audit(t, a, "PAYMENT_CREATE", id, requestId);
      return p;
    });
    if (record._nimbusSettled === true || (record.paymentMethod === "NIMBUS_ACCOUNT" && record.status === "SUCCEEDED")) {
      await drainOutbox(this.bank.store);
      const { _nimbusSettled, ...result } = record;
      return { ...result, id, paymentId: id, gateway: "NIMBUS", status: "SUCCEEDED" };
    }
    if (record.status === "SUCCEEDED" || record.status === "CANCELLED")
      return { id, paymentId: id, gateway: "STRIPE", status: record.status };
    minor(record.amountMinor);
    const provider = this.provider;
    check(provider, "SETUP_REQUIRED", 503);
    check(record.authorizationDigest, "REAUTHORIZE_REQUIRED", 409);
    check(record.providerId || this.bank.now() - record.createdAt < 23 * 3_600_000,
      "PAYMENT_RECONCILIATION_REQUIRED", 409);
    // Older pending payments must keep the Stripe parameters they were created with.
    const customerId = record.savedCardsEnabled === true
      ? await this.getOrCreateStripeCustomer(a.uid, provider)
      : undefined;
    const operation = record.providerId ? "retrieve" : "create";
    const result = await (record.providerId
      ? provider.retrieve(record.providerId)
      : provider.create(id, record.amountMinor, record.currency, customerId)
    ).catch((error: unknown) => {
      const code = typeof error === "object" && error !== null && "code" in error &&
        typeof error.code === "string" ? error.code : undefined;
      console.error("Stripe payment request failed", { paymentId: id, operation, code });
      throw error;
    });
    if (customerId) {
      const intentCustomer = typeof result.customer === "string" ? result.customer : result.customer?.id;
      check(intentCustomer === customerId, "PROVIDER_MISMATCH");
    }
    await this.bank.store.run(async (t) => {
      const p = compatiblePayment(await t.get("payments/" + id));
      check(p, "NOT_FOUND", 404);
      check(!p.providerId || p.providerId === result.id, "PROVIDER_MISMATCH");
      t.set("payments/" + id, {
        ...p,
        providerId: result.id,
        status: p.status === "CREATED" ? "PROCESSING" : p.status,
      });
    });
    const customerSession = customerId && result.client_secret
      ? { customerId, customerSessionClientSecret: await provider.createCustomerSession(customerId) }
      : {};
    return {
      id,
      paymentId: id,
      gateway: "STRIPE",
      status: "COMMIT_PENDING",
      clientSecret: result.client_secret,
      ...customerSession,
    };
  }
  // Only call after the HTTP boundary verifies Stripe's signature.
  async event(event: Doc) {
    key(event.id);
    if (
      ![
        "payment_intent.succeeded",
        "payment_intent.payment_failed",
        "payment_intent.canceled",
      ].includes(event.type)
    )
      return { ignored: true };
    const o = event.data.object;
    check(o.livemode === false, "LIVE_MODE_FORBIDDEN");
    key(o.metadata?.paymentId);
    const success = event.type === "payment_intent.succeeded";
    const outcome = await this.bank.store.run(async (t) => {
      const id = o.metadata.paymentId,
        previousEvent = await t.get("webhookEvents/" + event.id),
        p = compatiblePayment(await t.get("payments/" + id));
      if (previousEvent && (!success || previousEvent.paymentId !== id ||
          previousEvent.type !== event.type || p?.status !== "SUCCEEDED"))
        return { duplicate: true };
      check(p && p.gateway !== "SIMULATOR", "UNKNOWN_PAYMENT");
      check(!p.providerId || p.providerId === o.id, "PROVIDER_MISMATCH");
      check(
        p.amountMinor === o.amount && p.currency.toLowerCase() === o.currency,
        "PAYMENT_MISMATCH",
      );
      const topup = p.purpose === "TOP_UP";
      check(o.metadata?.purpose === (topup ? "TOP_UP" : "BILL_PAYMENT") || (!topup && !o.metadata?.purpose), "PAYMENT_PURPOSE_MISMATCH", 409);
      const bill = topup ? undefined : await t.get("bills/" + p.billId);
      if (topup) {
        const account = await t.get("accounts/" + p.accountId);
        check(account && account.ownerUid === p.ownerUid && account.type === "CHECKING" && account.currency === "VND" &&
          p.currency === "VND", "TOP_UP_ACCOUNT_MISMATCH", 409);
      } else {
        check(bill && bill.ownerUid === p.ownerUid && bill.amountMinor === p.amountMinor && bill.currency === p.currency,
          "INVOICE_MISMATCH");
      }
      const intent = await t.get("transactionIntents/" + id);
      if (p.authorizationDigest)
        check(intent && intent.digest === p.authorizationDigest && intent.digest === intentDigest(intent), "INVALID_INTENT");
      if (success && topup && p.status === "SUCCEEDED" && await t.get("transactions/" + id)) return { duplicate: true };
      if (success && !topup && p.status === "SUCCEEDED" && bill?.status === "PAID" &&
          bill.paymentId === id && p.providerId === o.id) {
          if (!topup) await recordBillPayment(
            t, id, p, intent, previousEvent?.receivedAt ?? p.updatedAt ?? this.bank.now(),
          );
      }
      if (previousEvent) return { duplicate: true };
      const lock = topup ? undefined : await t.get("billPaymentLocks/" + p.billId);
      if (p.status !== "SUCCEEDED" && p.status !== "CANCELLED") {
        const settledAt = this.bank.now();
        const status = success
          ? "SUCCEEDED"
        : event.type === "payment_intent.canceled"
            ? "CANCELLED"
            : "PROCESSING";
        t.set("payments/" + id, {
          ...p,
          providerId: o.id,
          status,
          updatedAt: settledAt,
        });
        if (intent && (success || status === "CANCELLED"))
          t.set("transactionIntents/" + id, { ...intent, status: success ? "COMMITTED" : "FAILED" });
        if (status === "CANCELLED" && lock?.paymentId === id)
          t.set("billPaymentLocks/" + p.billId, { ...lock, released: true });
        if (success && topup) {
          const notifEnabled = await this.bank.notifications.isTransactionEnabled(t, p.ownerUid);
          const account = await t.get("accounts/" + p.accountId);
          check(account && account.ownerUid === p.ownerUid && account.type === "CHECKING" && account.currency === "VND", "TOP_UP_ACCOUNT_MISMATCH", 409);
          const availableMinor = account.availableMinor + p.amountMinor;
          const bookedMinor = account.bookedMinor + p.amountMinor;
          check(Number.isSafeInteger(availableMinor) && Number.isSafeInteger(bookedMinor), "AMOUNT_OVERFLOW");
          t.set("accounts/" + p.accountId, { ...account, availableMinor, bookedMinor, balanceMinor: bookedMinor, version: (account.version ?? 0) + 1 });
          t.set("ledgerEntries/" + id + "_credit", { transactionId: id, accountId: p.accountId, signedMinor: p.amountMinor, amountMinor: p.amountMinor, direction: "CREDIT", currency: "VND", postedAt: settledAt });
          t.set("ledgerEntries/" + id + "_clearing", { transactionId: id, accountId: "STRIPE_CLEARING", signedMinor: -p.amountMinor, amountMinor: p.amountMinor, direction: "DEBIT", currency: "VND", postedAt: settledAt });
          t.set("transactions/" + id, { ownerUid: p.ownerUid, actorUid: p.ownerUid, requestId: intent?.requestId ?? id, type: "TOP_UP", accountId: p.accountId, sourceAccountId: p.accountId, amountMinor: p.amountMinor, currency: "VND", status: "SETTLED", balanceAfterMinor: bookedMinor, paymentId: id, committedAt: settledAt });
          this.bank.audit(t, { uid: p.ownerUid, role: "CUSTOMER", permissions: [] }, "TOP_UP_SETTLED", id, intent?.requestId);
          this.bank.notifications.emit(t, {
            id: `pay_${id}`,
            ownerUid: p.ownerUid,
            type: "TRANSACTION",
            title: "Nạp tiền thành công",
            body: `+${p.amountMinor.toLocaleString("vi-VN")} ₫ đã được cộng vào tài khoản. Số dư mới: ${bookedMinor.toLocaleString("vi-VN")} ₫`,
            refId: id,
          }, notifEnabled);
        } else if (success) {
          const notifEnabled = await this.bank.notifications.isTransactionEnabled(t, p.ownerUid);
          await recordBillPayment(t, id, p, intent, settledAt);
          t.set("bills/" + p.billId, {
            ...bill,
            status: "PAID",
            paymentId: id,
          });
          t.set("outbox/" + id, {
            paymentId: id,
            billId: p.billId,
            ownerUid: p.ownerUid,
            status: "PENDING",
            attempts: 0,
            createdAt: this.bank.now(),
          });
          this.bank.audit(
            t,
            { uid: p.ownerUid, role: "CUSTOMER", permissions: [] },
            "STRIPE_SETTLED",
            id,
          );
          this.bank.notifications.emit(t, {
            id: `pay_${id}`,
            ownerUid: p.ownerUid,
            type: "TRANSACTION",
            title: "Thanh toán hóa đơn thành công",
            body: `-${p.amountMinor.toLocaleString("vi-VN")} ₫ cho hóa đơn ${bill?.provider ?? p.billId}.`,
            refId: id,
          }, notifEnabled);
        }
      }
      t.set("webhookEvents/" + event.id, {
        paymentId: id,
        type: event.type,
        receivedAt: this.bank.now(),
      });
      return { received: true };
    });
    if (success) await drainOutbox(this.bank.store);
    return outcome;
  }
}
