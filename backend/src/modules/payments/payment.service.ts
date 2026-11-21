import { Banking } from "../../core/banking.js";
import type { Doc } from "../../core/store.js";
import { digest } from "../../shared/crypto.js";
import { check, key } from "../../shared/errors.js";
import { type Actor } from "../auth/auth.types.js";
import type { StripeGateway } from "./stripe.provider.js";
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
export class Payments {
  constructor(
    private bank: Banking,
    readonly provider: StripeGateway | undefined,
  ) {}
  async prepare(a: Actor, billId: string, requestId: string) {
    key(billId);
    key(requestId);
    return this.bank.store.run(async (t) => {
      await this.bank.authorize(t, a);
      const bill = await t.get("bills/" + billId);
      check(bill?.ownerUid === a.uid, "FORBIDDEN", 403);
      check(bill.status === "UNPAID", "INVALID_STATE");
      check(bill.amountMinor < 100000000, "STEP_UP_UNAVAILABLE", 503);
      const id = digest([a.uid, "PAYMENT", requestId]);
      const hash = digest([billId, bill.amountMinor, bill.currency]);
      const old = await t.get("transactionIntents/" + id);
      if (old) {
        check(old.digest === hash, "IDEMPOTENCY_CONFLICT", 409);
        return { id, intentId: id, status: old.status };
      }
      t.set("transactionIntents/" + id, {
        actorUid: a.uid,
        requestId,
        kind: "PAYMENT",
        billId,
        amountMinor: bill.amountMinor,
        currency: bill.currency,
        digest: hash,
        expiresAt: this.bank.now() + 300000,
        status: "CREATED",
      });
      return { id, intentId: id, status: "CREATED" };
    });
  }
  async create(a: Actor, billId: string, requestId: string) {
    check(this.provider, "SETUP_REQUIRED", 503);
    key(billId);
    key(requestId);
    const id = digest([a.uid, "PAYMENT", requestId]);
    const record = await this.bank.store.run(async (t) => {
      await this.bank.authorize(t, a);
      const existing = compatiblePayment(await t.get("payments/" + id));
      if (existing) {
        check(existing.billId === billId, "IDEMPOTENCY_CONFLICT", 409);
        return existing;
      }
      const i = await this.bank.transfers.getIntent(t, a, id),
        c = await t.get("otpChallenges/" + id),
        bill = await t.get("bills/" + billId);
      check(i.kind === "PAYMENT" && i.billId === billId, "INVALID_INTENT");
      check(this.bank.now() < i.expiresAt, "EXPIRED");
      check(
        c?.verified && !c.used && c.intentDigest === i.digest,
        "OTP_REQUIRED",
      );
      check(
        bill?.ownerUid === a.uid && bill.status === "UNPAID",
        "INVALID_STATE",
      );
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
        createdAt: this.bank.now(),
      };
      t.set("payments/" + id, p);
      t.set("billPaymentLocks/" + billId, { paymentId: id });
      t.set("otpChallenges/" + id, {
        ...c,
        used: true,
        usedAt: this.bank.now(),
      });
      t.set("transactionIntents/" + id, { ...i, status: "COMMITTED" });
      this.bank.audit(t, a, "PAYMENT_CREATE", id, requestId);
      return p;
    });
    const result = await this.provider.create(
      id,
      record.amountMinor,
      record.currency,
    );
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
    return {
      id,
      paymentId: id,
      gateway: "STRIPE",
      clientSecret: result.clientSecret,
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
      if (await t.get("webhookEvents/" + event.id)) return { duplicate: true };
      const id = o.metadata.paymentId,
        p = compatiblePayment(await t.get("payments/" + id));
      check(p, "UNKNOWN_PAYMENT");
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
      const success = event.type === "payment_intent.succeeded";
      const lock = await t.get("billPaymentLocks/" + p.billId);
      if (p.status !== "SUCCEEDED" && p.status !== "CANCELLED") {
        const status = success
          ? "SUCCEEDED"
          : event.type === "payment_intent.canceled"
            ? "CANCELLED"
            : "PROCESSING";
        t.set("payments/" + id, {
          ...p,
          providerId: o.id,
          status,
          updatedAt: this.bank.now(),
        });
        if (status === "CANCELLED" && lock?.paymentId === id)
          t.set("billPaymentLocks/" + p.billId, { ...lock, released: true });
        if (success) {
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
