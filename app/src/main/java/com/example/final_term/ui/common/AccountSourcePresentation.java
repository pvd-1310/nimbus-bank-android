package com.example.final_term.ui.common;

import android.content.Context;

import androidx.annotation.Nullable;

import com.example.final_term.R;

public final class AccountSourcePresentation {
    private AccountSourcePresentation() { }

    public static String number(Context context, String accountId, @Nullable String accountNumber) {
        if (accountNumber != null && !accountNumber.trim().isEmpty()) return accountNumber.trim();
        String last4 = accountId.substring(Math.max(0, accountId.length() - 4));
        return context.getString(R.string.transaction_account_masked_number, last4);
    }

    public static String identity(Context context, String number, @Nullable String ownerName) {
        String name = ownerName == null ? "" : ownerName.trim();
        return name.isEmpty() ? number : context.getString(R.string.transaction_account_name, number, name);
    }
}
