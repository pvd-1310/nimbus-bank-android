package com.example.final_term.data;

import androidx.annotation.NonNull;

import com.google.firebase.Timestamp;

public final class ReadModels {
    private ReadModels() { }

    public static final class AccountSummary {
        public final String id;
        public final String type;
        public final String currency;
        public final long balanceMinor;
        public final String status;

        public AccountSummary(@NonNull String id, String type, String currency, long balanceMinor, String status) {
            this.id = id;
            this.type = type == null ? "UNKNOWN" : type;
            this.currency = currency == null ? "" : currency;
            this.balanceMinor = balanceMinor;
            this.status = status == null ? "UNKNOWN" : status;
        }
    }

    public static final class TransactionSummary {
        public final String id;
        public final String accountId;
        public final String type;
        public final String currency;
        public final long amountMinor;
        public final String status;
        public final Timestamp createdAt;

        public TransactionSummary(@NonNull String id, String accountId, String type, String currency,
                                   long amountMinor, String status, Timestamp createdAt) {
            this.id = id;
            this.accountId = accountId == null ? "" : accountId;
            this.type = type == null ? "TRANSACTION" : type;
            this.currency = currency == null ? "" : currency;
            this.amountMinor = amountMinor;
            this.status = status == null ? "UNKNOWN" : status;
            this.createdAt = createdAt;
        }
    }

    public static final class NotificationSummary {
        public final String id;
        public final String title;
        public final String body;
        public final boolean read;
        public final Timestamp createdAt;

        public NotificationSummary(@NonNull String id, String title, String body, boolean read, Timestamp createdAt) {
            this.id = id;
            this.title = title == null ? "Thông báo" : title;
            this.body = body == null ? "" : body;
            this.read = read;
            this.createdAt = createdAt;
        }
    }
}
