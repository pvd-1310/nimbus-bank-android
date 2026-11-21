import {
  createHmac,
  randomInt,
  randomUUID,
  timingSafeEqual,
} from "node:crypto";
import type { Banking } from "../../core/banking.js";
import type { Doc, Tx } from "../../core/store.js";
import { digest } from "../../shared/crypto.js";
import { check, key, minor } from "../../shared/errors.js";
import type { Actor } from "../auth/auth.types.js";
export class TransferService {
  constructor(private bank: Banking) {}
  async intent(a: Actor, input: Doc) {
    key(input.requestId);
    key(input.sourceAccountId);
    key(input.destinationAccountId);
    minor(input.amountMinor);
    check(/^[A-Z]{3}$/.test(input.currency), "INVALID_CURRENCY");
    const payload = {
      sourceAccountId: input.sourceAccountId,
      destinationAccountId: input.destinationAccountId,
      amountMinor: input.amountMinor,
      currency: input.currency,
    };
    const hash = digest(payload),
      id = digest([a.uid, "TRANSFER", input.requestId]);
    return this.bank.store.run(async (t) => {
      await this.bank.authorize(t, a);
      const old = await t.get("transactionIntents/" + id);
      if (old) {
        check(old.payloadDigest === hash, "IDEMPOTENCY_CONFLICT", 409);
        return {
          id,
          intentId: id,
          status: old.status,
          expiresAt: old.expiresAt,
        };
      }
      check(input.amountMinor < 100000000, "STEP_UP_UNAVAILABLE", 503);
      const src = await this.bank.owned(t, a, input.sourceAccountId),
        dst = await t.get("accounts/" + input.destinationAccountId);
      check(
        dst && src.id !== input.destinationAccountId,
        "INVALID_DESTINATION",
      );
      check(
        src.status === "ACTIVE" && dst.status === "ACTIVE",
        "ACCOUNT_INACTIVE",
      );
      check(
        src.currency === input.currency && dst.currency === input.currency,
        "CURRENCY_MISMATCH",
      );
      check(src.availableMinor >= input.amountMinor, "INSUFFICIENT_FUNDS", 409);
      const policy = await t.get("checkingDetails/" + input.sourceAccountId);
      const policyDigest = digest(policy ?? {});
      const d = {
        ...payload,
        id,
        actorUid: a.uid,
        requestId: input.requestId,
        payloadDigest: hash,
        policyDigest,
        digest: digest([hash, policyDigest]),
        status: "CREATED",
        createdAt: this.bank.now(),
        expiresAt: this.bank.now() + 300000,
      };
      t.set("transactionIntents/" + id, d);
      return { id, intentId: id, status: d.status, expiresAt: d.expiresAt };
    });
  }
  async getIntent(t: Tx, a: Actor, id: string): Promise<Doc> {
    key(id);
    const i = await t.get("transactionIntents/" + id);
    check(i, "NOT_FOUND", 404);
    check(i.actorUid === a.uid, "FORBIDDEN", 403);
    return {
      ...i,
      ...(i.sourceId && i.sourceId !== "STRIPE"
        ? { sourceAccountId: i.sourceId }
        : {}),
      ...(i.target?.kind === "ACCOUNT"
        ? { destinationAccountId: i.target.id }
        : {}),
      ...(i.target?.kind === "BILL" ? { billId: i.target.id } : {}),
    };
  }
  hashOtp(id: string, nonce: string, code: string) {
    return createHmac("sha256", this.bank.options.otpSecret)
      .update(id + ":" + nonce + ":" + code)
      .digest("hex");
  }
  async challenge(a: Actor, id: string) {
    const code = String(randomInt(100000, 1000000)),
      nonce = randomUUID();
    await this.bank.store.run(async (t) => {
      await this.bank.authorize(t, a);
      const i = await this.getIntent(t, a, id),
        old = await t.get("otpChallenges/" + id);
      check(i.status !== "COMMITTED", "ALREADY_COMMITTED", 409);
      check(this.bank.now() < i.expiresAt, "EXPIRED");
      check(!old || this.bank.now() - old.sentAt >= 30000, "RATE_LIMITED", 429);
      check(!old || old.attempts < 5, "OTP_LOCKED", 429);
      t.set("otpChallenges/" + id, {
        actorUid: a.uid,
        intentDigest: i.digest,
        nonce,
        hash: this.hashOtp(id, nonce, code),
        expiresAt: i.expiresAt,
        sentAt: this.bank.now(),
        attempts: old?.attempts ?? 0,
        verified: false,
        used: false,
      });
    });
    await this.bank.options.deliver(a.uid, code, id);
    return { sent: true };
  }
  async verify(a: Actor, id: string, code: string) {
    const outcome = await this.bank.store.run(async (t) => {
      await this.bank.authorize(t, a);
      const i = await this.getIntent(t, a, id),
        c = await t.get("otpChallenges/" + id);
      check(c, "OTP_REQUIRED");
      check(!c.used, "OTP_USED");
      check(c.attempts < 5, "OTP_LOCKED", 429);
      check(this.bank.now() < (c.expiry ?? c.expiresAt), "EXPIRED");
      check(c.intentDigest === i.digest, "INTENT_CHANGED");
      const match = timingSafeEqual(
        Buffer.from(this.hashOtp(id, c.nonce, code)),
        Buffer.from(c.otpHashOrProviderRef ?? c.hash),
      );
      t.set("otpChallenges/" + id, {
        ...c,
        attempts: c.attempts + 1,
        verified: match,
      });
      return match;
    });
    check(outcome, "INVALID_OTP");
    return { authorized: true };
  }
  async commit(a: Actor, id: string) {
    return this.bank.store.run(async (t) => {
      await this.bank.authorize(t, a);
      const i = await this.getIntent(t, a, id);
      const existing = await t.get("transactions/" + id);
      if (existing) return { transactionId: id, status: existing.status };
      check(this.bank.now() < i.expiresAt, "EXPIRED");
      check(i.kind !== "PAYMENT", "INVALID_INTENT");
      const c = await t.get("otpChallenges/" + id);
      check(
        c?.verified && !c.used && c.intentDigest === i.digest,
        "OTP_REQUIRED",
      );
      check(this.bank.now() < (c.expiry ?? c.expiresAt), "EXPIRED");
      const src = await this.bank.owned(t, a, i.sourceAccountId),
        dst = await t.get("accounts/" + i.destinationAccountId);
      check(
        dst && src.status === "ACTIVE" && dst.status === "ACTIVE",
        "ACCOUNT_INACTIVE",
      );
      check(
        src.currency === i.currency && dst.currency === i.currency,
        "CURRENCY_MISMATCH",
      );
      check(src.availableMinor >= i.amountMinor, "INSUFFICIENT_FUNDS", 409);
      const policy = await t.get("checkingDetails/" + i.sourceAccountId);
      check(digest(policy ?? {}) === i.policyDigest, "POLICY_CHANGED", 409);
      const usageId = digest([
        i.sourceAccountId,
        new Date(this.bank.now()).toISOString().slice(0, 10),
      ]);
      const usage = await t.get("dailyUsage/" + usageId);
      const dailyAmount = (usage?.amountMinor ?? 0) + i.amountMinor;
      check(Number.isSafeInteger(dailyAmount), "AMOUNT_OVERFLOW");
      if (policy?.dailyLimitMinor !== undefined)
        check(
          dailyAmount <= policy.dailyLimitMinor,
          "DAILY_LIMIT_EXCEEDED",
          409,
        );
      t.set("dailyUsage/" + usageId, {
        accountId: i.sourceAccountId,
        amountMinor: dailyAmount,
        dayUtc: new Date(this.bank.now()).toISOString().slice(0, 10),
      });
      check(
        Number.isSafeInteger(dst.bookedMinor + i.amountMinor) &&
          Number.isSafeInteger(dst.availableMinor + i.amountMinor),
        "AMOUNT_OVERFLOW",
      );
      for (const [account, accountId, sign] of [
        [src, i.sourceAccountId, -1],
        [dst, i.destinationAccountId, 1],
      ] as const) {
        const amount = sign * i.amountMinor;
        t.set("accounts/" + accountId, {
          ...account,
          availableMinor: account.availableMinor + amount,
          bookedMinor: account.bookedMinor + amount,
          balanceMinor: account.bookedMinor + amount,
          version: account.version + 1,
        });
        t.set("ledgerEntries/" + id + "_" + (sign < 0 ? "debit" : "credit"), {
          transactionId: id,
          accountId,
          signedMinor: amount,
          amountMinor: i.amountMinor,
          direction: sign < 0 ? "DEBIT" : "CREDIT",
          currency: i.currency,
          postedAt: this.bank.now(),
        });
      }
      t.set("otpChallenges/" + id, {
        ...c,
        used: true,
        usedAt: this.bank.now(),
      });
      t.set("transactionIntents/" + id, { ...i, status: "COMMITTED" });
      const tx = {
        ownerUid: a.uid,
        actorUid: a.uid,
        requestId: i.requestId,
        sourceAccountId: i.sourceAccountId,
        destinationAccountId: i.destinationAccountId,
        type: "INTERNAL_TRANSFER",
        amountMinor: i.amountMinor,
        currency: i.currency,
        status: "SETTLED",
        committedAt: this.bank.now(),
      };
      t.set("transactions/" + id, tx);
      // Recipient gets a private projection; raw transfer documents are API-only.
      t.set("transactions/" + id + "_credit", {
        ownerUid: dst.ownerUid,
        requestId: i.requestId,
        sourceAccountId: i.destinationAccountId,
        type: "TRANSFER_RECEIVED",
        amountMinor: i.amountMinor,
        currency: i.currency,
        status: "SETTLED",
        committedAt: this.bank.now(),
      });
      for (const uid of new Set([a.uid, dst.ownerUid]))
        t.set("notifications/" + digest([id, uid]), {
          ownerUid: uid,
          title: "Giao dịch đã hoàn tất",
          message: "Đã ghi nhận giao dịch nội bộ.",
          body: "Đã ghi nhận giao dịch nội bộ.",
          read: false,
          createdAt: this.bank.now(),
        });
      this.bank.audit(t, a, "TRANSFER_COMMIT", id, i.requestId, {
        before: {
          sourceBookedMinor: src.bookedMinor,
          destinationBookedMinor: dst.bookedMinor,
        },
        after: {
          sourceBookedMinor: src.bookedMinor - i.amountMinor,
          destinationBookedMinor: dst.bookedMinor + i.amountMinor,
        },
        currency: i.currency,
      });
      return { transactionId: id, status: "SETTLED" };
    });
  }
}
