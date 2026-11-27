import type { Banking } from "../../core/banking.js";
import type { Doc } from "../../core/store.js";
import { queryOptions } from "../../shared/pagination.js";
import type { Actor } from "../auth/auth.types.js";
export class TransactionService {
  constructor(private bank: Banking) {}
  async list(a: Actor, filters: Doc) {
    return this.bank.read(async (t) => {
      await this.bank.authorize(t, a);
      const where: Doc = { ownerUid: a.uid };
      if (filters.accountId) where.sourceAccountId = filters.accountId;
      if (filters.type) where.type = filters.type;
      if (filters.status) where.status = filters.status;
      if (filters.provider) where.provider = filters.provider;
      const rows = await t.list("transactions", where, queryOptions(filters));
      return rows
        .filter(
          (x) =>
            (!filters.accountId || x.sourceAccountId === filters.accountId) &&
            (!filters.type || x.type === filters.type) &&
            (!filters.status || x.status === filters.status) &&
            (!filters.from || x.committedAt >= filters.from) &&
            (!filters.to || x.committedAt <= filters.to),
        )
        .map((x) => ({
          id: x.id,
          transactionId: x.transactionId ?? x.id,
          _cursor: x._cursor,
          requestId: x.requestId,
          accountId: x.sourceAccountId,
          sourceAccountId: x.sourceAccountId,
          paymentMethod: x.paymentMethod,
          type: x.type,
          amountMinor: x.amountMinor,
          currency: x.currency,
          status: x.status,
          ...(typeof x.destinationBankCode === "string" ? { destinationBankCode: x.destinationBankCode } : {}),
          ...(typeof x.counterpartyName === "string" ? { counterpartyName: x.counterpartyName } : {}),
          ...(x.failureCode ? { failureCode: x.failureCode } : {}),
          ...(Number.isSafeInteger(x.balanceAfterMinor) ? { balanceAfterMinor: x.balanceAfterMinor } : {}),
          committedAt: x.committedAt,
        }));
    });
  }
  async summary(a: Actor, month?: string) {
    return this.bank.read(async (t) => {
      await this.bank.authorize(t, a);
      const nowMs = this.bank.now();
      const targetMonth = month ?? new Date(nowMs).toISOString().slice(0, 7);
      const match = /^(\d{4})-(0[1-9]|1[0-2])$/.exec(targetMonth);
      const year = match ? Number(match[1]) : new Date(nowMs).getUTCFullYear();
      const monthIdx = match ? Number(match[2]) - 1 : new Date(nowMs).getUTCMonth();
      const monthStart = Date.UTC(year, monthIdx, 1);
      const monthEnd = Date.UTC(year, monthIdx + 1, 1);
      const rows = await t.list("transactions", { ownerUid: a.uid }, { from: monthStart, to: monthEnd - 1, limit: 500 });
      let totalInMinor = 0;
      let totalOutMinor = 0;
      for (const tx of rows) {
        if (tx.status !== "SETTLED" && tx.status !== "DEMO_SETTLED" && tx.status !== "SUCCEEDED") continue;
        if (typeof tx.committedAt !== "number" || typeof tx.amountMinor !== "number") continue;
        if (tx.committedAt < monthStart || tx.committedAt >= monthEnd) continue;
        // Card payments settled externally via Stripe do not debit a Nimbus checking balance.
        if (tx.type === "BILL_PAYMENT" && (tx.paymentMethod === "STRIPE_CARD" || !tx.sourceAccountId)) continue;
        const isIn = tx.type === "TRANSFER_RECEIVED" || tx.type === "TOP_UP" || tx.type === "STRIPE_CREDIT" || tx.type === "DEMO_DEPOSIT";
        if (isIn) totalInMinor += tx.amountMinor;
        else totalOutMinor += tx.amountMinor;
      }
      return { month: targetMonth, totalInMinor, totalOutMinor };
    });
  }
}
