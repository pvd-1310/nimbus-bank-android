package com.example.final_term.core;

/** Immutable application-level configuration kept separate from Android UI code. */
public final class AppConfig {
    public static final String APPLICATION_ID = "com.example.final_term";
    public static final String CUSTOMER_ROLE = "CUSTOMER";
    public static final String OFFICER_ROLE = "OFFICER";

    private AppConfig() {
        // No instances.
    }
}
