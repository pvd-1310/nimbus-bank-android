package com.example.final_term.data;

import java.text.NumberFormat;
import java.util.Locale;

public final class ReadSummaryFormatter {
    private ReadSummaryFormatter() { }

    public static String money(long amountMinor) {
        return NumberFormat.getIntegerInstance(new Locale("vi", "VN")).format(amountMinor) + " ₫";
    }

    public static String signedMoney(long amountMinor) {
        return amountMinor >= 0 ? "+" + money(amountMinor) : "−" + money(Math.abs(amountMinor));
    }

    public static String transactionTitle(ReadModels.TransactionSummary transaction) {
        String type = transaction.isBillPayment() ? "Thanh toán hóa đơn" :
                transaction.isOutgoing() ? "Chuyển đi" :
                transaction.isIncoming() ? "Nhận tiền" : transaction.type;
        String status = "SETTLED".equals(transaction.status) ? "Thành công" :
                "FAILED".equals(transaction.status) ? "Thất bại" : transaction.status;
        return type + " · " + status;
    }

    public static String transactionDetail(ReadModels.TransactionSummary transaction) {
        String amount = formatCurrency(transaction.amountMinor, transaction.currency);
        StringBuilder detail = new StringBuilder();
        if ("FAILED".equals(transaction.status)) {
            detail.append("Số tiền yêu cầu ").append(amount).append(" · Số dư không thay đổi");
            if (transaction.failureCode != null) {
                detail.append("\n").append(transferFailureReason(transaction.failureCode));
            }
        } else {
            if ("SETTLED".equals(transaction.status) && transaction.amountMinor >= 0) {
                if (transaction.isOutgoing()) detail.append("−");
                if (transaction.isIncoming()) detail.append("+");
            }
            detail.append(amount);
        }
        if (!transaction.accountId.isEmpty()) detail.append("\n").append(transaction.accountId);
        if ("SETTLED".equals(transaction.status) && transaction.balanceAfterMinor != null) {
            detail.append("\nSố dư sau giao dịch ")
                    .append(formatCurrency(transaction.balanceAfterMinor, transaction.currency));
        }
        return detail.toString();
    }

    private static String transferFailureReason(String code) {
        switch (code) {
            case "INSUFFICIENT_FUNDS": return "Số dư khả dụng không đủ.";
            case "INVALID_DESTINATION": return "Tài khoản nhận không hợp lệ.";
            case "ACCOUNT_INACTIVE": return "Tài khoản chưa hoạt động.";
            case "CURRENCY_MISMATCH": return "Hai tài khoản khác loại tiền.";
            case "DAILY_LIMIT_EXCEEDED": return "Đã vượt hạn mức chuyển tiền trong ngày.";
            case "POLICY_CHANGED": return "Hạn mức hoặc chính sách tài khoản đã thay đổi.";
            case "EXPIRED": return "Giao dịch đã hết hạn.";
            default: return "Backend từ chối yêu cầu (" + code + ").";
        }
    }

    public static String formatCurrency(long amountMinor, String currency) {
        return "VND".equals(currency) ? money(amountMinor) :
                (NumberFormat.getIntegerInstance(new Locale("vi", "VN")).format(amountMinor) + " " + currency).trim();
    }
}
