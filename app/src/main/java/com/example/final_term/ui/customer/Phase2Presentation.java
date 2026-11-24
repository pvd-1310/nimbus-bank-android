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
        return "Chưa thể gửi yêu cầu lúc này. Số dư chưa thay đổi; vui lòng thử lại.";
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
