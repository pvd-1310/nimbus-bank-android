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
        public final String ownerUid;
        public final Long availableMinor;
        public final boolean demo;

        public AccountSummary(@NonNull String id, String type, String currency, long balanceMinor, String status) {
            this(id, type, currency, balanceMinor, status, "");
        }

        public AccountSummary(@NonNull String id, String type, String currency, long balanceMinor,
                              String status, String ownerUid) {
            this(id, type, currency, balanceMinor, status, ownerUid, null);
        }

        public AccountSummary(@NonNull String id, String type, String currency, long balanceMinor,
                              String status, String ownerUid, Long availableMinor) {
            this(id, type, currency, balanceMinor, status, ownerUid, availableMinor, false);
        }

        public AccountSummary(@NonNull String id, String type, String currency, long balanceMinor,
                              String status, String ownerUid, Long availableMinor, boolean demo) {
            this.id = id;
            this.type = type == null ? "UNKNOWN" : type;
            this.currency = currency == null ? "" : currency;
            this.balanceMinor = balanceMinor;
            this.status = status == null ? "UNKNOWN" : status;
            this.ownerUid = ownerUid == null ? "" : ownerUid;
            this.availableMinor = availableMinor;
            this.demo = demo;
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
        public final String failureCode;
        public final Long balanceAfterMinor;

        public TransactionSummary(@NonNull String id, String accountId, String type, String currency,
                                   long amountMinor, String status, Timestamp createdAt) {
            this(id, accountId, type, currency, amountMinor, status, createdAt, null, null);
        }

        public TransactionSummary(@NonNull String id, String accountId, String type, String currency,
                                   long amountMinor, String status, Timestamp createdAt,
                                   String failureCode, Long balanceAfterMinor) {
            this.id = id;
            this.accountId = accountId == null ? "" : accountId;
            this.type = type == null ? "TRANSACTION" : type;
            this.currency = currency == null ? "" : currency;
            this.amountMinor = amountMinor;
            this.status = status == null ? "UNKNOWN" : status;
            this.createdAt = createdAt;
            this.failureCode = failureCode;
            this.balanceAfterMinor = balanceAfterMinor;
        }

        public boolean isBillPayment() { return "BILL_PAYMENT".equals(type); }
        public boolean isStripeSandbox() { return "STRIPE_CREDIT".equals(type) || "STRIPE_DEBIT".equals(type); }
        public boolean isOutgoing() {
            return isBillPayment() || "INTERNAL_TRANSFER".equals(type) || "DEBIT".equals(type)
                    || "DEMO_EXTERNAL_TRANSFER".equals(type) || "DEMO_WITHDRAWAL".equals(type)
                    || "UTILITY_ORDER".equals(type)
                    || "STRIPE_DEBIT".equals(type);
        }
        public boolean isIncoming() {
            return "TRANSFER_RECEIVED".equals(type) || "CREDIT".equals(type) || "DEMO_DEPOSIT".equals(type)
                    || "TOP_UP".equals(type)
                    || "STRIPE_CREDIT".equals(type);
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
