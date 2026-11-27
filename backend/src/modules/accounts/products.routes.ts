import { z } from "zod";
import type { AppDependencies, Route } from "../../core/http.js";
import type { Doc, Tx } from "../../core/store.js";
import { digest, generateAccountNumber } from "../../shared/crypto.js";
import { check } from "../../shared/errors.js";
import { id } from "../../shared/validation.js";
import { mortgageSchedule } from "./calculations.js";

const DEFAULT_SAVINGS_BPS = 480;
const DEFAULT_LOAN_BPS = 850;

async function activeBankRatePolicy(t: Tx, rateType: "SAVINGS" | "LOAN", now: number) {
  const policies = await t.list("ratePolicies", { rateType }, { limit: 1000 });
  const current = policies
    .filter((p) => Number(p.effectiveFrom ?? 0) <= now)
    .sort((a, b) => Number(a.effectiveFrom ?? 0) - Number(b.effectiveFrom ?? 0) || Number(a.version ?? 0) - Number(b.version ?? 0))
    .at(-1);
  if (current) {
    return {
      id: String(current.id),
      annualRateBasisPoints: Number(current.annualRateBasisPoints ?? (rateType === "SAVINGS" ? DEFAULT_SAVINGS_BPS : DEFAULT_LOAN_BPS)),
    };
  }
  const fallbackId = rateType === "SAVINGS" ? "bank_savings_default" : "bank_loan_default";
  const bps = rateType === "SAVINGS" ? DEFAULT_SAVINGS_BPS : DEFAULT_LOAN_BPS;
  return { id: fallbackId, annualRateBasisPoints: bps, isFallback: true };
}

export function registerProductsRoutes(route: Route, d: AppDependencies) {
  route("get", "/products/rates", (a) =>
    d.bank.read(async (t) => {
      await d.bank.authorize(t, a);
      const now = d.bank.now();
      const [savings, loan] = await Promise.all([
        activeBankRatePolicy(t, "SAVINGS", now),
        activeBankRatePolicy(t, "LOAN", now),
      ]);
      return {
        savingsRateBasisPoints: savings.annualRateBasisPoints,
        savingsPolicyId: savings.id,
        loanRateBasisPoints: loan.annualRateBasisPoints,
        loanPolicyId: loan.id,
      };
    }),
  );

  // 1. Open Savings Account (Customer)
  route("post", "/savings", (a, r) => {
    const body = z.object({
      sourceAccountId: id,
      amountMinor: z.number().int().min(1_000_000).max(50_000_000_000),
      termMonths: z.union([z.literal(3), z.literal(6), z.literal(12), z.literal(24)]),
      requestId: id,
    }).strict().parse(r.body);

    return d.bank.store.run(async (t) => {
      await d.bank.authorize(t, a);
      const reqKey = "savingsOpenRequests/" + digest([a.uid, body.requestId]);
      const existing = await t.get(reqKey);
      if (existing) {
        return { accountId: existing.accountId, accountNumber: existing.accountNumber, status: "ACTIVE" };
      }

      const source = await d.bank.owned(t, a, body.sourceAccountId);
      check(source.type === "CHECKING" && source.status === "ACTIVE", "INVALID_ACCOUNT", 409);
      const available = Number(source.availableMinor ?? source.balanceMinor ?? 0);
      check(available >= body.amountMinor, "INSUFFICIENT_FUNDS", 409);

      const now = d.bank.now();
      const policy = await activeBankRatePolicy(t, "SAVINGS", now);
      if ("isFallback" in policy && policy.isFallback) {
        t.set("ratePolicies/" + policy.id, {
          id: policy.id,
          rateType: "SAVINGS",
          annualRateBasisPoints: policy.annualRateBasisPoints,
          effectiveFrom: 0,
          version: 1,
          reason: "Default savings rate",
        });
      }

      const savingsId = "sav_" + digest([a.uid, body.requestId]).slice(0, 20);
      const accountNumber = generateAccountNumber(savingsId);
      const maturityAt = now + body.termMonths * 30 * 86_400_000;

      const { id: _srcId, _cursor: _srcCur, ...srcData } = source;
      void _srcId;
      void _srcCur;
      t.set("accounts/" + body.sourceAccountId, {
        ...srcData,
        availableMinor: available - body.amountMinor,
        bookedMinor: Number(source.bookedMinor ?? available) - body.amountMinor,
        balanceMinor: Number(source.balanceMinor ?? available) - body.amountMinor,
        version: Number(source.version ?? 0) + 1,
      });

      t.set("accounts/" + savingsId, {
        accountId: savingsId,
        accountNumber,
        ownerUid: a.uid,
        type: "SAVINGS",
        currency: "VND",
        status: "ACTIVE",
        availableMinor: body.amountMinor,
        bookedMinor: body.amountMinor,
        balanceMinor: body.amountMinor,
        version: 0,
        createdAt: now,
      });

      t.set("savingsDetails/" + savingsId, {
        accountId: savingsId,
        principalMinor: body.amountMinor,
        productId: "savings_standard_vnd",
        ratePolicyVersion: policy.id,
        termMonths: body.termMonths,
        openedAt: now,
        maturityAt,
      });

      t.set("transactions/sav_open_" + savingsId, {
        ownerUid: a.uid,
        actorUid: a.uid,
        accountId: body.sourceAccountId,
        type: "SAVINGS_OPEN",
        amountMinor: body.amountMinor,
        currency: "VND",
        status: "SETTLED",
        counterpartyName: "Sổ tiết kiệm " + body.termMonths + " tháng",
        committedAt: now,
      });

      t.set(reqKey, { accountId: savingsId, accountNumber, ownerUid: a.uid, createdAt: now });
      d.bank.notifications.emit(t, {
        id: "sav_open_" + savingsId,
        ownerUid: a.uid,
        type: "TRANSACTION",
        title: "Mở sổ tiết kiệm thành công",
        body: `Đã mở sổ tiết kiệm kỳ hạn ${body.termMonths} tháng với số tiền ${body.amountMinor.toLocaleString("vi-VN")} ₫.`,
        refId: savingsId,
      });
      d.bank.audit(t, a, "SAVINGS_OPEN", savingsId, body.requestId);
      return { accountId: savingsId, accountNumber, status: "ACTIVE" };
    });
  });

  // 2. Close / Settle Savings Account (Customer)
  route("post", "/savings/:id/close", (a, r) => {
    const savingsId = id.parse(r.params.id);
    const body = z.object({ destinationAccountId: id, requestId: id }).strict().parse(r.body);

    return d.bank.store.run(async (t) => {
      await d.bank.authorize(t, a);
      const reqKey = "savingsCloseRequests/" + digest([a.uid, savingsId, body.requestId]);
      const existing = await t.get(reqKey);
      if (existing) return existing;

      const sav = await d.bank.owned(t, a, savingsId);
      check(sav.type === "SAVINGS" && sav.status === "ACTIVE", "INVALID_ACCOUNT", 409);
      const dest = await d.bank.owned(t, a, body.destinationAccountId);
      check(dest.type === "CHECKING" && dest.status === "ACTIVE", "INVALID_ACCOUNT", 409);

      const detail = await t.get("savingsDetails/" + savingsId);
      const now = d.bank.now();
      const policy = await activeBankRatePolicy(t, "SAVINGS", now);
      const principal = Number(detail?.principalMinor ?? sav.balanceMinor ?? 0);
      const openedAt = Number(detail?.openedAt ?? now);
      const termMonths = Number(detail?.termMonths ?? 12);
      const elapsedDays = Math.max(0, Math.floor((now - openedAt) / 86_400_000));
      const matured = typeof detail?.maturityAt === "number" && now >= detail.maturityAt;
      const interestMinor = matured
        ? Math.round(principal * (policy.annualRateBasisPoints / 10000) * (termMonths / 12))
        : Math.round(principal * (policy.annualRateBasisPoints / 10000) * (elapsedDays / 365));
      const payoutMinor = principal + interestMinor;

      const { id: _savId, _cursor: _savCur, ...savData } = sav;
      void _savId;
      void _savCur;
      t.set("accounts/" + savingsId, {
        ...savData,
        status: "CLOSED",
        availableMinor: 0,
        bookedMinor: 0,
        balanceMinor: 0,
        version: Number(sav.version ?? 0) + 1,
      });

      const { id: _destId, _cursor: _destCur, ...destData } = dest;
      void _destId;
      void _destCur;
      const destAvail = Number(dest.availableMinor ?? dest.balanceMinor ?? 0);
      t.set("accounts/" + body.destinationAccountId, {
        ...destData,
        availableMinor: destAvail + payoutMinor,
        bookedMinor: Number(dest.bookedMinor ?? destAvail) + payoutMinor,
        balanceMinor: Number(dest.balanceMinor ?? destAvail) + payoutMinor,
        version: Number(dest.version ?? 0) + 1,
      });

      t.set("transactions/sav_close_" + savingsId, {
        ownerUid: a.uid,
        actorUid: a.uid,
        accountId: body.destinationAccountId,
        type: "SAVINGS_CLOSE",
        amountMinor: payoutMinor,
        currency: "VND",
        status: "SETTLED",
        counterpartyName: "Tất toán sổ tiết kiệm",
        committedAt: now,
      });

      const outcome = { closed: true, principalMinor: principal, interestMinor, payoutMinor };
      t.set(reqKey, outcome);
      d.bank.notifications.emit(t, {
        id: "sav_close_" + savingsId,
        ownerUid: a.uid,
        type: "TRANSACTION",
        title: "Tất toán sổ tiết kiệm thành công",
        body: `Đã tất toán ${payoutMinor.toLocaleString("vi-VN")} ₫ (gồm lãi ${interestMinor.toLocaleString("vi-VN")} ₫) về tài khoản thanh toán.`,
        refId: savingsId,
      });
      d.bank.audit(t, a, "SAVINGS_CLOSE", savingsId, body.requestId);
      return outcome;
    });
  });

  // 3. Loan Application (Customer)
  route("post", "/loans/applications", (a, r) => {
    const body = z.object({
      amountMinor: z.number().int().min(10_000_000).max(5_000_000_000),
      termMonths: z.union([z.literal(12), z.literal(24), z.literal(36), z.literal(60)]),
      paymentFrequency: z.enum(["MONTHLY", "BIWEEKLY"]),
      purpose: z.string().trim().min(2).max(200),
      requestId: id,
    }).strict().parse(r.body);

    return d.bank.store.run(async (t) => {
      await d.bank.authorize(t, a);
      const appId = digest([a.uid, "LOAN_APP", body.requestId]).slice(0, 32);
      const path = "loanApplications/" + appId;
      const existing = await t.get(path);
      if (existing) return existing;

      const checkings = await t.list("accounts", { ownerUid: a.uid, type: "CHECKING" }, { limit: 10 });
      const activeChecking = checkings.find((acc) => acc.status === "ACTIVE");
      check(activeChecking, "ACTIVE_CHECKING_REQUIRED", 409);

      const user = await t.get("users/" + a.uid);
      const now = d.bank.now();
      const policy = await activeBankRatePolicy(t, "LOAN", now);
      const doc: Doc = {
        id: appId,
        customerUid: a.uid,
        customerName: String(user?.fullName ?? "Khách hàng"),
        checkingAccountId: String(activeChecking!.id),
        amountMinor: body.amountMinor,
        termMonths: body.termMonths,
        paymentFrequency: body.paymentFrequency,
        purpose: body.purpose,
        annualRateBasisPoints: policy.annualRateBasisPoints,
        status: "PENDING",
        createdAt: now,
      };
      t.set(path, doc);
      d.bank.audit(t, a, "LOAN_APPLICATION_SUBMIT", appId, body.requestId);
      return doc;
    });
  });

  route("get", "/loans/applications", (a) =>
    d.bank.read(async (t) => {
      await d.bank.authorize(t, a);
      const rows = await t.list("loanApplications", { customerUid: a.uid }, { limit: 50 });
      rows.sort((x, y) => Number(y.createdAt ?? 0) - Number(x.createdAt ?? 0));
      return { items: rows };
    }),
  );

  // 4. Officer Loan Review & Disbursement
  route("get", "/officer/loans", (a) =>
    d.bank.read(async (t) => {
      await d.bank.authorize(t, a, "CUSTOMER_ADMIN");
      const rows = await t.list("loanApplications", {}, { limit: 100 });
      rows.sort((x, y) => Number(y.createdAt ?? 0) - Number(x.createdAt ?? 0));
      return { items: rows };
    }),
  );

  route("post", "/officer/loans/:id/review", (a, r) => {
    const appId = id.parse(r.params.id);
    const body = z.object({
      decision: z.enum(["APPROVED", "REJECTED"]),
      reason: z.string().trim().max(300).optional(),
    }).strict().parse(r.body);

    return d.bank.store.run(async (t) => {
      await d.bank.authorize(t, a, "CUSTOMER_ADMIN");
      const appPath = "loanApplications/" + appId;
      const appDoc = await t.get(appPath);
      check(appDoc, "NOT_FOUND", 404);
      if (appDoc.status === body.decision) return appDoc;
      check(appDoc.status === "PENDING", "INVALID_STATE", 409);

      const now = d.bank.now();
      const customerUid = String(appDoc.customerUid);
      const { id: _id, _cursor, ...appData } = appDoc;
      void _id;
      void _cursor;

      if (body.decision === "REJECTED") {
        const cleanReason = body.reason?.trim() || "Hồ sơ vay chưa đáp ứng điều kiện thẩm định.";
        const rejected = { ...appData, id: appId, status: "REJECTED", reason: cleanReason, reviewedBy: a.uid, reviewedAt: now };
        t.set(appPath, rejected);
        d.bank.notifications.emit(t, {
          id: "loan_rej_" + appId,
          ownerUid: customerUid,
          type: "EKYC",
          title: "Hồ sơ vay chưa được duyệt",
          body: cleanReason,
          refId: appId,
        });
        d.bank.audit(t, a, "LOAN_REJECTED", appId, cleanReason);
        return rejected;
      }

      const checkingId = String(appDoc.checkingAccountId);
      const checking = await t.get("accounts/" + checkingId);
      check(checking && checking.ownerUid === customerUid && checking.status === "ACTIVE", "ACTIVE_CHECKING_REQUIRED", 409);

      const amountMinor = Number(appDoc.amountMinor);
      const termMonths = Number(appDoc.termMonths);
      const bps = Number(appDoc.annualRateBasisPoints ?? DEFAULT_LOAN_BPS);
      const frequency = appDoc.paymentFrequency === "BIWEEKLY" ? "BIWEEKLY" : "MONTHLY";
      const mortgageId = "loan_" + appId.slice(0, 20);
      const accountNumber = generateAccountNumber(mortgageId);

      // Credit loan disbursement to checking account
      const { id: _chkId, _cursor: _chkCur, ...chkData } = checking;
      void _chkId;
      void _chkCur;
      const avail = Number(checking.availableMinor ?? checking.balanceMinor ?? 0);
      t.set("accounts/" + checkingId, {
        ...chkData,
        availableMinor: avail + amountMinor,
        bookedMinor: Number(checking.bookedMinor ?? avail) + amountMinor,
        balanceMinor: Number(checking.balanceMinor ?? avail) + amountMinor,
        version: Number(checking.version ?? 0) + 1,
      });

      t.set("accounts/" + mortgageId, {
        accountId: mortgageId,
        accountNumber,
        ownerUid: customerUid,
        type: "MORTGAGE",
        currency: "VND",
        status: "ACTIVE",
        availableMinor: amountMinor,
        bookedMinor: amountMinor,
        balanceMinor: amountMinor,
        version: 0,
        createdAt: now,
      });

      const periodDays = frequency === "BIWEEKLY" ? 14 : 30;
      t.set("mortgageDetails/" + mortgageId, {
        accountId: mortgageId,
        loanPrincipalMinor: amountMinor,
        outstandingMinor: amountMinor,
        annualRateBasisPoints: bps,
        termMonths,
        remainingTermMonths: termMonths,
        paymentFrequency: frequency,
        contractCode: "HDV-" + mortgageId.slice(-6).toUpperCase(),
        paidInstallments: 0,
        nextDueAt: now + periodDays * 86_400_000,
      });

      t.set("transactions/loan_disb_" + mortgageId, {
        ownerUid: customerUid,
        actorUid: a.uid,
        accountId: checkingId,
        type: "LOAN_DISBURSEMENT",
        amountMinor,
        currency: "VND",
        status: "SETTLED",
        counterpartyName: "Giải ngân khoản vay",
        committedAt: now,
      });

      const approved = {
        ...appData,
        id: appId,
        status: "APPROVED",
        mortgageAccountId: mortgageId,
        reviewedBy: a.uid,
        reviewedAt: now,
      };
      t.set(appPath, approved);
      d.bank.notifications.emit(t, {
        id: "loan_app_" + appId,
        ownerUid: customerUid,
        type: "TRANSACTION",
        title: "Khoản vay đã được giải ngân",
        body: `Khoản vay ${amountMinor.toLocaleString("vi-VN")} ₫ đã được duyệt và giải ngân vào tài khoản thanh toán.`,
        refId: mortgageId,
      });
      d.bank.audit(t, a, "LOAN_APPROVED", appId, mortgageId);
      return approved;
    });
  });

  // 5. Repay Mortgage Installment (Customer)
  route("post", "/loans/:id/repay", (a, r) => {
    const mortgageId = id.parse(r.params.id);
    const body = z.object({ sourceAccountId: id, requestId: id }).strict().parse(r.body);

    return d.bank.store.run(async (t) => {
      await d.bank.authorize(t, a);
      const reqKey = "loanRepayments/" + digest([a.uid, mortgageId, body.requestId]);
      const existing = await t.get(reqKey);
      if (existing) return existing;

      const mort = await d.bank.owned(t, a, mortgageId);
      check(mort.type === "MORTGAGE" && mort.status === "ACTIVE", "INVALID_ACCOUNT", 409);
      const source = await d.bank.owned(t, a, body.sourceAccountId);
      check(source.type === "CHECKING" && source.status === "ACTIVE", "INVALID_ACCOUNT", 409);

      const terms = await t.get("mortgageDetails/" + mortgageId);
      check(terms, "DETAILS_UNAVAILABLE", 404);
      const outstanding = Number(terms.outstandingMinor ?? mort.balanceMinor ?? 0);
      check(outstanding > 0, "LOAN_ALREADY_PAID", 409);

      const bps = Number(terms.annualRateBasisPoints ?? DEFAULT_LOAN_BPS);
      const remaining = Math.max(1, Number(terms.remainingTermMonths ?? terms.termMonths ?? 12));
      const freq = terms.paymentFrequency === "BIWEEKLY" ? "BIWEEKLY" : "MONTHLY";
      const nextItem = mortgageSchedule(outstanding, bps, remaining, freq)[0];
      const payTotal = nextItem.totalMinor;
      const principalPaid = Math.min(outstanding, nextItem.principalMinor);

      const avail = Number(source.availableMinor ?? source.balanceMinor ?? 0);
      check(avail >= payTotal, "INSUFFICIENT_FUNDS", 409);

      const now = d.bank.now();
      const { id: _srcId, _cursor: _srcCur, ...srcData } = source;
      void _srcId;
      void _srcCur;
      t.set("accounts/" + body.sourceAccountId, {
        ...srcData,
        availableMinor: avail - payTotal,
        bookedMinor: Number(source.bookedMinor ?? avail) - payTotal,
        balanceMinor: Number(source.balanceMinor ?? avail) - payTotal,
        version: Number(source.version ?? 0) + 1,
      });

      const newOutstanding = Math.max(0, outstanding - principalPaid);
      const { id: _mId, _cursor: _mCur, ...mortData } = mort;
      void _mId;
      void _mCur;
      t.set("accounts/" + mortgageId, {
        ...mortData,
        status: newOutstanding === 0 ? "CLOSED" : "ACTIVE",
        availableMinor: newOutstanding,
        bookedMinor: newOutstanding,
        balanceMinor: newOutstanding,
        version: Number(mort.version ?? 0) + 1,
      });

      const { id: _tId, _cursor: _tCur, ...termsData } = terms;
      void _tId;
      void _tCur;
      const periodDays = freq === "BIWEEKLY" ? 14 : 30;
      t.set("mortgageDetails/" + mortgageId, {
        ...termsData,
        outstandingMinor: newOutstanding,
        remainingTermMonths: Math.max(1, remaining - (freq === "MONTHLY" ? 1 : 0)),
        paidInstallments: Number(terms.paidInstallments ?? 0) + 1,
        nextDueAt: newOutstanding === 0 ? null : now + periodDays * 86_400_000,
      });

      t.set("transactions/loan_repay_" + digest([mortgageId, body.requestId]).slice(0, 16), {
        ownerUid: a.uid,
        actorUid: a.uid,
        accountId: body.sourceAccountId,
        type: "LOAN_REPAYMENT",
        amountMinor: payTotal,
        currency: "VND",
        status: "SETTLED",
        counterpartyName: "Trả góp khoản vay",
        committedAt: now,
      });

      const outcome = { paidMinor: payTotal, principalPaidMinor: principalPaid, outstandingMinor: newOutstanding };
      t.set(reqKey, outcome);
      d.bank.notifications.emit(t, {
        id: "loan_repay_" + digest([mortgageId, body.requestId]).slice(0, 16),
        ownerUid: a.uid,
        type: "TRANSACTION",
        title: "Thanh toán kỳ vay thành công",
        body: `Đã thanh toán ${payTotal.toLocaleString("vi-VN")} ₫. Dư nợ còn lại: ${newOutstanding.toLocaleString("vi-VN")} ₫.`,
        refId: mortgageId,
      });
      d.bank.audit(t, a, "LOAN_REPAYMENT", mortgageId, body.requestId);
      return outcome;
    });
  });
}
