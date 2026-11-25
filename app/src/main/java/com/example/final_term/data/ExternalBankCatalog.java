package com.example.final_term.data;

public final class ExternalBankCatalog {
    private static final String[] CODES = {"", "VCB", "BIDV", "ICB", "TCB", "MB"};
    private static final String[] NAMES = {"Chọn ngân hàng nhận", "Vietcombank", "BIDV", "VietinBank", "Techcombank", "MB"};

    private ExternalBankCatalog() { }

    public static String[] codes() { return CODES.clone(); }
    public static String[] names() { return NAMES.clone(); }

    public static String nameForCode(String code) {
        if (code == null || code.isEmpty()) return NAMES[0];
        for (int i = 0; i < CODES.length; i++) if (CODES[i].equals(code)) return NAMES[i];
        return "Ngân hàng khác";
    }

    public static int positionForCode(String code) {
        for (int i = 0; i < CODES.length; i++) if (CODES[i].equals(code)) return i;
        return 0;
    }
}
