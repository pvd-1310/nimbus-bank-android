import type { Banking } from "../../core/banking.js";
import type { Doc, Tx } from "../../core/store.js";
import { digest } from "../../shared/crypto.js";
import { check, Fault, key, minor } from "../../shared/errors.js";
import type { Actor } from "../auth/auth.types.js";
import { intentDigest } from "../security/intent.js";

const transferRejections = new Set([
  "INVALID_DESTINATION", "ACCOUNT_INACTIVE", "CURRENCY_MISMATCH",
  "INSUFFICIENT_FUNDS", "POLICY_CHANGED", "DAILY_LIMIT_EXCEEDED",
  "AMOUNT_OVERFLOW", "EXPIRED", "INVALID_ACCOUNT",
]);

export class TransferService {
  constructor(private bank: Banking) {}
  async intent(a: Actor, input: Doc) {
    check(a.role === "CUSTOMER", "FORBIDDEN", 403);
    key(input.requestId);
    key(input.sourceAccountId);
    const destinationInput = String(input.destinationAccountId);
    minor(input.amountMinor);
    check(/^[A-Z]{3}$/.test(input.currency), "INVALID_CURRENCY");
    const payload = {
      sourceAccountId: input.sourceAccountId,
      destinationAccountId: destinationInput,
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
          faceRequired: old.faceRequired ?? false,
        };
      }
      const src = await this.bank.owned(t, a, input.sourceAccountId);
      let destinationAccountId = destinationInput;
      if (/^(?:0[0-9]{9}|\+84[0-9]{9})$/.test(destinationInput)) {
        const localPhone = destinationInput.startsWith("+84") ? "0" + destinationInput.slice(3) : destinationInput;
        const [canonical, legacy] = await Promise.all([
          t.list("users", { contactPhone: "+84" + localPhone.slice(1) }, { limit: 2 }),
          t.list("users", { contactPhone: localPhone }, { limit: 2 }),
        ]);
        const users = [...new Map([...canonical, ...legacy].map((user) => [user.id, user])).values()];
        check(users.length === 1, "INVALID_DESTINATION", 404);
        const recipient = users[0];
        check(recipient.role === "CUSTOMER" &&
          (recipient.status === "ACTIVE" ? recipient.active !== false : recipient.status === undefined && recipient.active === true),
          "INVALID_DESTINATION", 404);
        const accounts = await t.list("accounts", { ownerUid: recipient.id, type: "CHECKING", status: "ACTIVE" }, { limit: 2 });
        check(accounts.length === 1 && accounts[0].currency === "VND", "INVALID_DESTINATION", 404);
        destinationAccountId = accounts[0].id;
      } else key(destinationAccountId);
      const dst = await t.get("accounts/" + destinationAccountId);
      check(
        dst && src.id !== destinationAccountId,
        "INVALID_DESTINATION",
      );
      check(
        src.status === "ACTIVE" && dst.status === "ACTIVE",
        "ACCOUNT_INACTIVE",
      );
      check(src.type !== "MORTGAGE" && dst.type !== "MORTGAGE" &&
        (src.demo === true) === (dst.demo === true), "INVALID_ACCOUNT", 409);
      check(
        src.currency === input.currency && dst.currency === input.currency,
        "CURRENCY_MISMATCH",
      );
      check(src.availableMinor >= input.amountMinor, "INSUFFICIENT_FUNDS", 409);
      const policy = await t.get("checkingDetails/" + input.sourceAccountId);
      const policyDigest = digest(policy ?? {});
      const d = {
        ...payload,
        destinationAccountId,
        id,
        actorUid: a.uid,
        requestId: input.requestId,
        payloadDigest: hash,
        policyDigest,
        kind: "TRANSFER",
        feeMinor: 0,
        securityVersion: 1,
        faceRequired: this.bank.security.requiresFace(payload.currency, payload.amountMinor),
        status: "CREATED",
        createdAt: this.bank.now(),
        expiresAt: this.bank.now() + 300000,
      };
      t.set("transactionIntents/" + id, { ...d, digest: intentDigest(d) });
      return { id, intentId: id, status: d.status, expiresAt: d.expiresAt, faceRequired: d.faceRequired };
    }).catch(async (error: unknown) => {
      await this.recordRejection(a, id, "INTENT", error, { ...payload, requestId: input.requestId });
      throw error;
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
  private async recordRejection(
    actor: Actor, id: string, stage: "INTENT" | "COMMIT", error: unknown, input?: Doc,
  ) {
    if (!(error instanceof Fault) || !transferRejections.has(error.code)) return;
    await this.bank.store.run(async (t) => {
      await this.bank.authorize(t, actor);
      const transfer = input ?? await this.getIntent(t, actor, id);
      const source = await t.get("accounts/" + transfer.sourceAccountId);
      if (source?.ownerUid !== actor.uid) return;
      const recordId = digest([id, stage, "FAILED"]);
      const path = "transactions/" + recordId;
      if (await t.get("transactions/" + id) || await t.get(path)) return;
      t.set(path, {
        ownerUid: actor.uid,
        actorUid: actor.uid,
        requestId: transfer.requestId,
        sourceAccountId: transfer.sourceAccountId,
        type: "INTERNAL_TRANSFER",
        amountMinor: transfer.amountMinor,
        currency: transfer.currency,
        status: "FAILED",
        failureCode: error.code,
        failureStage: stage,
        // The history API uses committedAt as its ordering field for every outcome.
        committedAt: this.bank.now(),
      });
      if (stage === "COMMIT" && transfer.status === "CREATED") {
        t.set("transactionIntents/" + id, { ...transfer, status: "FAILED", failureCode: error.code });
      }
      this.bank.audit(t, actor, "TRANSFER_REJECTED", recordId, transfer.requestId);
    });
  }

  async commit(a: Actor, id: string) {
    check(a.role === "CUSTOMER", "FORBIDDEN", 403);
    return this.bank.store.run(async (t) => {
      await this.bank.authorize(t, a);
      const i = await this.getIntent(t, a, id);
      const existing = await t.get("transactions/" + id);
      if (existing) return { transactionId: id, status: existing.status };
      check(i.kind === "TRANSFER", "INVALID_INTENT");
      await this.bank.security.consume(t, i);
      const src = await this.bank.owned(t, a, i.sourceAccountId),
        dst = await t.get("accounts/" + i.destinationAccountId);
      check(
        dst && src.status === "ACTIVE" && dst.status === "ACTIVE",
        "ACCOUNT_INACTIVE",
      );
      check(src.type !== "MORTGAGE" && dst.type !== "MORTGAGE" &&
        (src.demo === true) === (dst.demo === true), "INVALID_ACCOUNT", 409);
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
      t.set("transactionIntents/" + id, { ...i, status: "COMMITTED" });
      const senderUser = await t.get("users/" + a.uid);
      const recipientUser = await t.get("users/" + dst.ownerUid);
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
        balanceAfterMinor: src.bookedMinor - i.amountMinor,
        ...(typeof recipientUser?.fullName === "string" ? { counterpartyName: recipientUser.fullName } : {}),
        ...(typeof recipientUser?.contactPhone === "string" ? { destinationPhone: recipientUser.contactPhone } : {}),
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
        balanceAfterMinor: dst.bookedMinor + i.amountMinor,
        ...(typeof senderUser?.fullName === "string" ? { counterpartyName: senderUser.fullName } : {}),
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
    }).catch(async (error: unknown) => {
      await this.recordRejection(a, id, "COMMIT", error);
      throw error;
    });
  }
  async recentRecipients(a: Actor) {
    check(a.role === "CUSTOMER", "FORBIDDEN", 403);
    return this.bank.store.run(async (t) => {
      await this.bank.authorize(t, a);
      const txs = await t.list("transactions", { ownerUid: a.uid }, { limit: 100 });
      const seen = new Set<string>();
      const items: Array<{ name: string; target: string; accountId: string }> = [];
      const sorted = [...txs].sort((x, y) => Number(y.committedAt ?? 0) - Number(x.committedAt ?? 0));
      for (const tx of sorted) {
        if (tx.type !== "INTERNAL_TRANSFER" || tx.status !== "SETTLED") continue;
        const target = typeof tx.destinationPhone === "string" && tx.destinationPhone.length > 0
          ? tx.destinationPhone : "";
        const name = typeof tx.counterpartyName === "string" && tx.counterpartyName.length > 0
          ? tx.counterpartyName : "";
        const accountId = typeof tx.destinationAccountId === "string" ? tx.destinationAccountId : "";
        if (!target || !name || seen.has(target)) continue;
        seen.add(target);
        items.push({ name, target, accountId });
        if (items.length >= 5) break;
      }
      return { items };
    });
  }
}
