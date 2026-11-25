package com.example.final_term.ui.customer;

public final class Phase2Presentation {
    private Phase2Presentation() { }

    public static String accountTitle(String type) {
        if ("CHECKING".equalsIgnoreCase(type)) return "Tài khoản thanh toán";
        if ("SAVINGS".equalsIgnoreCase(type)) return "Tài khoản tiết kiệm";
        if ("MORTGAGE".equalsIgnoreCase(type)) return "Khoản vay thế chấp";
        return "Tài khoản";
    }

    public static String accountSubtitle(String type) {
        if ("CHECKING".equalsIgnoreCase(type)) return "Số dư khả dụng";
        if ("SAVINGS".equalsIgnoreCase(type)) return "Lãi suất và lợi nhuận dự kiến";
        if ("MORTGAGE".equalsIgnoreCase(type)) return "Lịch trả góp dự kiến";
        return "Thông tin tài khoản";
    }

    public static String requestStatusMessage(String operation) {
        return "Yêu cầu chưa được gửi; số dư chưa thay đổi.";
    }

    public static boolean usesRequestForm(String operation) {
        return "WITHDRAWAL".equalsIgnoreCase(operation);
    }

    public static boolean requiresRecipientLookup(String cashOperation, boolean external) {
        return !external && cashOperation == null;
    }

    public static boolean validRequestAmount(String value) {
        if (value == null || !value.matches("[0-9]{1,18}")) return false;
        try {
            long amount = Long.parseLong(value);
            return amount > 0 && amount <= 9_007_199_254_740_991L;
        }
        catch (NumberFormatException ignored) { return false; }
    }
}
