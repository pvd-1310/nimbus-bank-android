import type { Banking } from "../../core/banking.js";
import type { Doc, Tx } from "../../core/store.js";
import { digest } from "../../shared/crypto.js";
import { check, key, minor } from "../../shared/errors.js";
import { intentDigest } from "../security/intent.js";
import type { Actor } from "../auth/auth.types.js";
import { externalBankName, externalBanks } from "./external-banks.js";
export class RequestService {
  constructor(private bank: Banking) {}
  async create(a: Actor, input: Doc) {
    key(input.requestId);
    key(input.accountId);
    minor(input.amountMinor);
    check(["DEPOSIT", "WITHDRAWAL"].includes(input.type), "INVALID_TYPE");
    const id = digest([a.uid, "MONEY_REQUEST", input.requestId]),
      hash = digest(input);
    return this.bank.store.run(async (t) => {
      await this.bank.authorize(t, a);
      const acc = await this.bank.owned(t, a, input.accountId);
      check(
        acc.currency === input.currency && acc.status === "ACTIVE",
        "INVALID_ACCOUNT",
      );
      const old = await t.get("moneyRequests/" + id);
      if (old) {
        check(old.digest === hash, "IDEMPOTENCY_CONFLICT", 409);
        return old;
      }
      const d = {
        ...input,
        id,
        ownerUid: a.uid,
        digest: hash,
        status: "REQUESTED",
        createdAt: this.bank.now(),
      };
      t.set("moneyRequests/" + id, d);
      this.bank.audit(t, a, "MONEY_REQUEST", id, input.requestId);
      return d;
    });
  }
  private async demoAccount(t: Tx, a: Actor, id: string, currency: string) {
    check(a.role === "CUSTOMER", "FORBIDDEN", 403);
    const account = await this.bank.owned(t, a, id);
    check(account.type === "CHECKING" &&
      account.status === "ACTIVE" && account.currency === currency, "DEMO_ACCOUNT_REQUIRED", 409);
    return account;
  }

  async demoIntent(a: Actor, input: Doc) {
    key(input.requestId);
    minor(input.amountMinor);
    check(["DEPOSIT", "WITHDRAWAL", "EXTERNAL_TRANSFER"].includes(input.type), "INVALID_TYPE");
    check(input.type !== "DEPOSIT", "TOP_UP_REQUIRES_CARD", 409);
    const external = input.type === "EXTERNAL_TRANSFER";
    if (external) {
      check(externalBanks.some((bank) => bank.code === input.bankCode), "INVALID_BANK");
      check(/^0{9}[12]$/.test(input.beneficiaryAccount), "DEMO_BENEFICIARY_REQUIRED");
    }
    const id = digest([a.uid, "DEMO_MOVEMENT", input.requestId]);
    const hash = digest(input);
    return this.bank.store.run(async (t) => {
      await this.bank.authorize(t, a);
      const old = await t.get("transactionIntents/" + id);
      if (old) {
        check(old.payloadDigest === hash, "IDEMPOTENCY_CONFLICT", 409);
        return this.demoResult(old);
      }
      const account = await this.demoAccount(t, a, input.accountId, input.currency);
      check(input.type === "DEPOSIT" || account.availableMinor >= input.amountMinor, "INSUFFICIENT_FUNDS", 409);
      const now = this.bank.now();
      const destinationBankName = external ? externalBankName(input.bankCode) : undefined;
      const intent = {
        id, actorUid: a.uid, kind: "DEMO_MOVEMENT", movement: input.type,
        requestId: input.requestId, sourceAccountId: input.accountId,
        destinationAccountId: external ? input.bankCode + "_" + input.beneficiaryAccount : "DEMO_CASH",
        ...(external ? { bankCode: input.bankCode, destinationBankName, beneficiaryAccount: input.beneficiaryAccount } : {}),
        amountMinor: input.amountMinor, feeMinor: 0, currency: input.currency,
        policyDigest: digest([input.type, hash]),
        payloadDigest: hash, demo: true, status: "CREATED", securityVersion: 1,
        faceRequired: this.bank.security.requiresFace(input.currency, input.amountMinor),
        createdAt: now, expiresAt: now + 300000,
      };
      t.set("transactionIntents/" + id, { ...intent, digest: intentDigest(intent) });
      if (!external) t.set("moneyRequests/" + id, {
        ...input, id, ownerUid: a.uid, demo: true, createdAt: now, status: "REQUESTED",
      });
      this.bank.audit(t, a, "DEMO_MOVEMENT_INTENT", id, input.requestId);
      return this.demoResult(intent);
    });
  }

  private demoResult(intent: Doc) {
    return {
      id: intent.id, intentId: intent.id, demo: true, type: intent.movement,
      status: intent.status, expiresAt: intent.expiresAt, faceRequired: intent.faceRequired,
      providerReference: intent.providerReference ?? null, failureCode: intent.failureCode ?? null,
      feeMinor: intent.feeMinor, amountMinor: intent.amountMinor, currency: intent.currency,
      ...(intent.bankCode ? { bankCode: intent.bankCode, beneficiaryAccount: intent.beneficiaryAccount } : {}),
      balanceAfterMinor: intent.balanceAfterMinor ?? null,
      transactionId: intent.status === "COMMITTED" ? intent.id : null,
    };
  }

  async getDemo(a: Actor, requestId: string) {
    key(requestId);
    const id = digest([a.uid, "DEMO_MOVEMENT", requestId]);
    const intent = await this.bank.security.describe(a, id);
    return { ...intent, demo: true };
  }

  async commitDemo(a: Actor, id: string) {
    return this.bank.store.run(async (t) => {
      await this.bank.authorize(t, a);
      const intent = await this.bank.transfers.getIntent(t, a, id);
      check(intent.kind === "DEMO_MOVEMENT" && a.role === "CUSTOMER", "INVALID_INTENT");
      if (["COMMIT_PENDING", "COMMITTED", "FAILED"].includes(intent.status)) return this.demoResult(intent);
      await this.demoAccount(t, a, intent.sourceAccountId, intent.currency);
      await this.bank.security.consume(t, intent);
      const pending = {
        ...intent, status: "COMMIT_PENDING",
        providerReference: "DEMO-" + id,
        reconcileAfter: this.bank.now() + 1200,
      };
      t.set("transactionIntents/" + id, pending);
      await this.updateRequest(t, pending, "PENDING");
      this.bank.audit(t, a, "DEMO_MOVEMENT_PENDING", id, intent.requestId);
      return this.demoResult(pending);
    });
  }

  private async updateRequest(t: Tx, intent: Doc, status: string) {
    if (intent.movement === "EXTERNAL_TRANSFER") return;
    const request = await t.get("moneyRequests/" + intent.id);
    check(request?.demo === true && request.ownerUid === intent.actorUid, "INVALID_REQUEST", 409);
    t.set("moneyRequests/" + intent.id, { ...request, status, providerReference: intent.providerReference });
  }

  async reconcileDemo(a: Actor, id: string) {
    return this.bank.store.run(async (t) => {
      await this.bank.authorize(t, a);
      const intent = await this.bank.transfers.getIntent(t, a, id);
      check(intent.kind === "DEMO_MOVEMENT" && a.role === "CUSTOMER", "INVALID_INTENT");
      const now = this.bank.now();
      if (intent.status !== "COMMIT_PENDING" || now < intent.reconcileAfter)
        return this.demoResult(intent);

      const account = await this.bank.owned(t, a, intent.sourceAccountId);
      const delta = intent.movement === "DEPOSIT" ? intent.amountMinor : -intent.amountMinor;
      const bookedMinor = account.bookedMinor + delta;
      const availableMinor = account.availableMinor + delta;
      const dayUtc = new Date(now).toISOString().slice(0, 10);
      const usagePath = "dailyUsage/" + digest([account.id, dayUtc]);
      const usage = delta < 0 ? await t.get(usagePath) : undefined;
      const policy = delta < 0 ? await t.get("checkingDetails/" + account.id) : undefined;
      const notifEnabled = await this.bank.notifications.isTransactionEnabled(t, a.uid);
      const amountMinor = (usage?.amountMinor ?? 0) + Math.max(0, -delta);
      const failureCode =
        account.type !== "CHECKING" || account.status !== "ACTIVE" ||
          account.currency !== intent.currency ? "DEMO_ACCOUNT_REQUIRED"
        : intent.movement === "EXTERNAL_TRANSFER" && intent.beneficiaryAccount === "0000000002"
          ? "DEMO_PROVIDER_REJECTED"
        : availableMinor < 0 || bookedMinor < 0 ? "INSUFFICIENT_FUNDS"
        : ![bookedMinor, availableMinor, amountMinor].every(Number.isSafeInteger) ? "AMOUNT_OVERFLOW"
        : delta < 0 && amountMinor > (policy?.dailyLimitMinor ?? 50_000_000) ? "DAILY_LIMIT_EXCEEDED"
        : null;
      const result = { ...intent, status: failureCode ? "FAILED" : "COMMITTED", failureCode,
        ...(failureCode ? {} : { balanceAfterMinor: bookedMinor }) };
      await this.updateRequest(t, result, failureCode ? "FAILED" : "DEMO_SETTLED");
      t.set("transactionIntents/" + id, result);
      this.bank.audit(t, a, failureCode ? "DEMO_MOVEMENT_REJECTED" : "DEMO_MOVEMENT_SETTLED", id, intent.requestId);
      if (failureCode) {
        this.bank.notifications.emit(t, {
          id: digest(["demo_req", id, "failed"]),
          ownerUid: a.uid,
          type: "TRANSACTION",
          title: intent.movement === "EXTERNAL_TRANSFER" ? "Chuyển khoản ngoài thất bại" : "Yêu cầu rút tiền thất bại",
          body: `Giao dịch không thành công (${failureCode}).`,
          refId: id,
        }, notifEnabled);
        return this.demoResult(result);
      }

      if (delta < 0) t.set(usagePath, { accountId: account.id, amountMinor, dayUtc });
      t.set("accounts/" + account.id, {
        ...account, availableMinor, bookedMinor, balanceMinor: bookedMinor, version: (account.version ?? 0) + 1,
      });
      for (const [accountId, signedMinor] of [[account.id, delta], ["DEMO_CLEARING", -delta]] as const)
        t.set("ledgerEntries/" + id + (signedMinor < 0 ? "_debit" : "_credit"), {
          transactionId: id, accountId, signedMinor, amountMinor: intent.amountMinor,
          direction: signedMinor < 0 ? "DEBIT" : "CREDIT", currency: intent.currency, postedAt: now, demo: true,
        });
      t.set("transactions/" + id, {
        ownerUid: a.uid, actorUid: a.uid, requestId: intent.requestId, sourceAccountId: account.id,
        destinationAccountId: intent.destinationAccountId, type: "DEMO_" + intent.movement,
        ...(intent.movement === "EXTERNAL_TRANSFER" ? {
          destinationBankCode: intent.bankCode,
          destinationBankName: intent.destinationBankName,
          beneficiaryAccount: intent.beneficiaryAccount,
        } : {}),
        amountMinor: intent.amountMinor, currency: intent.currency, status: "DEMO_SETTLED",
        providerReference: intent.providerReference, balanceAfterMinor: bookedMinor, committedAt: now, demo: true,
      });
      const isExternal = intent.movement === "EXTERNAL_TRANSFER";
      const isDeposit = intent.movement === "DEPOSIT";
      this.bank.notifications.emit(t, {
        id: digest(["demo_req", id, "settled"]),
        ownerUid: a.uid,
        type: "TRANSACTION",
        title: isExternal
          ? "Chuyển khoản ngoài thành công"
          : isDeposit
            ? "Nạp tiền thành công"
            : "Rút tiền thành công",
        body: isExternal
          ? `-${intent.amountMinor.toLocaleString("vi-VN")} ₫ tới ${intent.destinationBankName ?? intent.bankCode ?? "ngân hàng ngoài"}. Số dư mới: ${bookedMinor.toLocaleString("vi-VN")} ₫`
          : `${isDeposit ? "+" : "-"}${intent.amountMinor.toLocaleString("vi-VN")} ₫. Số dư mới: ${bookedMinor.toLocaleString("vi-VN")} ₫`,
        refId: id,
      }, notifEnabled);
      return this.demoResult(result);
    });
  }

}
