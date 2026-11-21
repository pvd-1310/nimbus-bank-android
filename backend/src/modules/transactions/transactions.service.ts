import type { Banking } from "../../core/banking.js";
import type { Doc } from "../../core/store.js";
import { queryOptions } from "../../shared/pagination.js";
import type { Actor } from "../auth/auth.types.js";
export class TransactionService {
  constructor(private bank: Banking) {}
  async list(a: Actor, filters: Doc) {
    return this.bank.store.run(async (t) => {
      await this.bank.authorize(t, a);
      const where: Doc = { ownerUid: a.uid };
      if (filters.accountId) where.sourceAccountId = filters.accountId;
      if (filters.type) where.type = filters.type;
      if (filters.status) where.status = filters.status;
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
          type: x.type,
          amountMinor: x.amountMinor,
          currency: x.currency,
          status: x.status,
          ...(x.failureCode ? { failureCode: x.failureCode } : {}),
          ...(Number.isSafeInteger(x.balanceAfterMinor) ? { balanceAfterMinor: x.balanceAfterMinor } : {}),
          committedAt: x.committedAt,
        }));
    });
  }
}
