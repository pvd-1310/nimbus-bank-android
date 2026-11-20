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
}
