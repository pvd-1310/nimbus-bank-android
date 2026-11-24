import { randomUUID } from "node:crypto";
import { Banking } from "../../core/banking.js";
import type { Doc, Tx } from "../../core/store.js";
import { digest } from "../../shared/crypto.js";
import { check, key, minor } from "../../shared/errors.js";
import { type Actor } from "../auth/auth.types.js";
import type { StripeGateway } from "./stripe.provider.js";
import { intentDigest } from "../security/intent.js";
const compatiblePayment = (d: Doc | undefined): Doc | undefined =>
  d
    ? {
        ...d,
        billId: d.billOrOrderRef ?? d.billId,
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
    amountMinor: payment.amountMinor,
    currency: payment.currency,
    status: "SETTLED",
    committedAt,
  });
}

export class Payments {
  constructor(
    private bank: Banking,
    readonly provider: StripeGateway | undefined,
  ) {}
  async prepare(a: Actor, billId: string, requestId: string) {
    check(this.provider, "SETUP_REQUIRED", 503);
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
      const hash = digest([billId, bill.amountMinor, bill.currency]);
      const old = replacesInvalidAmount ? await t.get("transactionIntents/" + id) : original;
      if (old) {
        check((old.payloadDigest ?? old.digest) === hash, "IDEMPOTENCY_CONFLICT", 409);
        return { id, intentId: id, requestId: paymentRequestId, status: old.status };
      }
      check(bill.status === "UNPAID", "INVALID_STATE");
      this.checkQuote(bill);
      const intent = {
        id,
        sourceId: "STRIPE",
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

  async create(a: Actor, billId: string, requestId: string) {
    check(this.provider, "SETUP_REQUIRED", 503);
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
        return existing;
      }
      const i = await this.bank.transfers.getIntent(t, a, id),
        bill = await t.get("bills/" + billId);
      check(i.kind === "PAYMENT" && i.sourceId !== "SIMULATOR" && i.billId === billId, "INVALID_INTENT");
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
      const p = {
        id,
        ownerUid: a.uid,
        billId,
        amountMinor: i.amountMinor,
        currency: i.currency,
        status: "CREATED",
        authorizationDigest: i.digest,
        savedCardsEnabled: true,
        createdAt: this.bank.now(),
      };
      t.set("payments/" + id, p);
      t.set("billPaymentLocks/" + billId, { paymentId: id });
      t.set("transactionIntents/" + id, { ...i, status: "COMMIT_PENDING" });
      this.bank.audit(t, a, "PAYMENT_CREATE", id, requestId);
      return p;
    });
    if (record.status === "SUCCEEDED" || record.status === "CANCELLED")
      return { id, paymentId: id, gateway: "STRIPE", status: record.status };
    minor(record.amountMinor);
    check(record.authorizationDigest, "REAUTHORIZE_REQUIRED", 409);
    check(record.providerId || this.bank.now() - record.createdAt < 23 * 3_600_000,
      "PAYMENT_RECONCILIATION_REQUIRED", 409);
    // Older pending payments must keep the Stripe parameters they were created with.
    const customerId = record.savedCardsEnabled === true
      ? await this.getOrCreateStripeCustomer(a.uid, this.provider)
      : undefined;
    const operation = record.providerId ? "retrieve" : "create";
    const result = await (record.providerId
      ? this.provider.retrieve(record.providerId)
      : this.provider.create(id, record.amountMinor, record.currency, customerId)
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
      ? { customerId, customerSessionClientSecret: await this.provider.createCustomerSession(customerId) }
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
    return this.bank.store.run(async (t) => {
      const id = o.metadata.paymentId,
        previousEvent = await t.get("webhookEvents/" + event.id),
        p = compatiblePayment(await t.get("payments/" + id));
      const success = event.type === "payment_intent.succeeded";
      if (previousEvent && (!success || previousEvent.paymentId !== id ||
          previousEvent.type !== event.type || p?.status !== "SUCCEEDED"))
        return { duplicate: true };
      check(p && p.gateway !== "SIMULATOR", "UNKNOWN_PAYMENT");
      check(!p.providerId || p.providerId === o.id, "PROVIDER_MISMATCH");
      check(
        p.amountMinor === o.amount && p.currency.toLowerCase() === o.currency,
        "PAYMENT_MISMATCH",
      );
      const bill = await t.get("bills/" + p.billId);
      check(
        bill &&
          bill.ownerUid === p.ownerUid &&
          bill.amountMinor === p.amountMinor &&
          bill.currency === p.currency,
        "INVOICE_MISMATCH",
      );
      const intent = await t.get("transactionIntents/" + id);
      if (p.authorizationDigest)
        check(intent && intent.digest === p.authorizationDigest && intent.digest === intentDigest(intent), "INVALID_INTENT");
      if (success && p.status === "SUCCEEDED" && bill.status === "PAID" &&
          bill.paymentId === id && p.providerId === o.id) {
        await recordBillPayment(
          t, id, p, intent, previousEvent?.receivedAt ?? p.updatedAt ?? this.bank.now(),
        );
      }
      if (previousEvent) return { duplicate: true };
      const lock = await t.get("billPaymentLocks/" + p.billId);
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
        if (success) {
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
        }
      }
      t.set("webhookEvents/" + event.id, {
        paymentId: id,
        type: event.type,
        receivedAt: this.bank.now(),
      });
      return { received: true };
    });
  }
}
