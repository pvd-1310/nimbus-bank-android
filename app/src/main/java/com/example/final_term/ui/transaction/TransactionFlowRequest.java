package com.example.final_term.ui.transaction;

import android.os.Build;
import android.os.Bundle;
import android.os.Parcel;
import android.os.Parcelable;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

public final class TransactionFlowRequest implements Parcelable {
    public static final String EXTRA_REQUEST = "transaction_flow_request";

    public enum Kind {
        INTERNAL_TRANSFER,
        EXTERNAL_TRANSFER,
        DEPOSIT,
        WITHDRAWAL,
        BILL_PAYMENT,
        ORDER_PAYMENT
    }

    @NonNull
    public final Kind kind;
    public final long amountMinor;
    @NonNull
    public final String currency;
    @Nullable
    public final String sourceAccountId;
    @Nullable
    public final String sourceLabel;
    @Nullable
    public final String recipientName;
    public final boolean recipientVerified;
    @Nullable
    public final String bankName;
    @Nullable
    public final String bankSubtitle;
    @Nullable
    public final String bankCode;
    @Nullable
    public final String targetNumber;
    @Nullable
    public final String methodLabel;
    @Nullable
    public final String note;
    @Nullable
    public final String recipientAccountId;
    @Nullable
    public final String billId;
    @Nullable
    public final String orderCategory;
    @Nullable
    public final String productId;
    @Nullable
    public final String orderCustomerDetails;
    @Nullable
    public final String serviceName;
    @Nullable
    public final String providerName;
    public final int quantity;

    private TransactionFlowRequest(
            @NonNull Kind kind,
            long amountMinor,
            @Nullable String currency,
            @Nullable String sourceAccountId,
            @Nullable String sourceLabel,
            @Nullable String recipientName,
            boolean recipientVerified,
            @Nullable String bankName,
            @Nullable String bankSubtitle,
            @Nullable String bankCode,
            @Nullable String targetNumber,
            @Nullable String methodLabel,
            @Nullable String note,
            @Nullable String recipientAccountId,
            @Nullable String billId,
            @Nullable String orderCategory,
            @Nullable String productId,
            @Nullable String orderCustomerDetails,
            @Nullable String serviceName,
            @Nullable String providerName) {
        this(kind, amountMinor, currency, sourceAccountId, sourceLabel, recipientName,
                recipientVerified, bankName, bankSubtitle, bankCode, targetNumber, methodLabel,
                note, recipientAccountId, billId, orderCategory, productId, orderCustomerDetails,
                serviceName, providerName, 1);
    }

    private TransactionFlowRequest(
            @NonNull Kind kind,
            long amountMinor,
            @Nullable String currency,
            @Nullable String sourceAccountId,
            @Nullable String sourceLabel,
            @Nullable String recipientName,
            boolean recipientVerified,
            @Nullable String bankName,
            @Nullable String bankSubtitle,
            @Nullable String bankCode,
            @Nullable String targetNumber,
            @Nullable String methodLabel,
            @Nullable String note,
            @Nullable String recipientAccountId,
            @Nullable String billId,
            @Nullable String orderCategory,
            @Nullable String productId,
            @Nullable String orderCustomerDetails,
            @Nullable String serviceName,
            @Nullable String providerName,
            int quantity) {
        this.kind = kind;
        this.amountMinor = amountMinor;
        this.currency = isBlank(currency) ? "VND" : currency.trim();
        this.sourceAccountId = normalize(sourceAccountId);
        this.sourceLabel = normalize(sourceLabel);
        this.recipientName = normalize(recipientName);
        this.recipientVerified = recipientVerified;
        this.bankName = normalize(bankName);
        this.bankSubtitle = normalize(bankSubtitle);
        this.bankCode = normalize(bankCode);
        this.targetNumber = normalize(targetNumber);
        this.methodLabel = normalize(methodLabel);
        this.note = normalize(note);
        this.recipientAccountId = normalize(recipientAccountId);
        this.billId = normalize(billId);
        this.orderCategory = normalize(orderCategory);
        this.productId = normalize(productId);
        this.orderCustomerDetails = normalize(orderCustomerDetails);
        this.serviceName = normalize(serviceName);
        this.providerName = normalize(providerName);
        this.quantity = Math.max(1, quantity);
    }

    public static TransactionFlowRequest internalTransfer(
            @Nullable String sourceAccountId,
            long amountMinor,
            @Nullable String currency,
            @Nullable String recipientAccountId,
            @Nullable String recipientName,
            @Nullable String targetNumber,
            @Nullable String note) {
        boolean verified = !isBlank(recipientName);
        return new TransactionFlowRequest(
                Kind.INTERNAL_TRANSFER,
                amountMinor,
                currency,
                sourceAccountId,
                null,
                recipientName,
                verified,
                "Nimbus Bank",
                null,
                null,
                targetNumber,
                "Chuyển nhanh nội bộ 24/7",
                note,
                recipientAccountId,
                null,
                null,
                null,
                null,
                null,
                null
        );
    }

    public static TransactionFlowRequest externalTransfer(
            @Nullable String sourceAccountId,
            long amountMinor,
            @Nullable String currency,
            @Nullable String bankCode,
            @Nullable String bankName,
            @Nullable String targetNumber,
            @Nullable String note) {
        return externalTransfer(sourceAccountId, amountMinor, currency, bankCode, bankName, null, targetNumber, note);
    }

    public static TransactionFlowRequest externalTransfer(
            @Nullable String sourceAccountId,
            long amountMinor,
            @Nullable String currency,
            @Nullable String bankCode,
            @Nullable String bankName,
            @Nullable String bankSubtitle,
            @Nullable String targetNumber,
            @Nullable String note) {
        return new TransactionFlowRequest(
                Kind.EXTERNAL_TRANSFER,
                amountMinor,
                currency,
                sourceAccountId,
                null,
                null,
                false,
                bankName,
                bankSubtitle,
                bankCode,
                targetNumber,
                "Chuyển nhanh Napas 24/7",
                note,
                null,
                null,
                null,
                null,
                null,
                null,
                null
        );
    }

    public static TransactionFlowRequest deposit(
            @Nullable String sourceAccountId,
            long amountMinor,
            @Nullable String currency) {
        return new TransactionFlowRequest(
                Kind.DEPOSIT,
                amountMinor,
                currency,
                sourceAccountId,
                null,
                null,
                false,
                null,
                null,
                null,
                null,
                "Thanh toán thẻ Stripe",
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null
        );
    }

    public static TransactionFlowRequest withdrawal(
            @Nullable String sourceAccountId,
            long amountMinor,
            @Nullable String currency) {
        return new TransactionFlowRequest(
                Kind.WITHDRAWAL,
                amountMinor,
                currency,
                sourceAccountId,
                null,
                null,
                false,
                null,
                null,
                null,
                null,
                "Rút tiền tài khoản",
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null
        );
    }

    public static TransactionFlowRequest billPayment(
            @Nullable String billId,
            @Nullable String serviceName,
            @Nullable String providerName,
            @Nullable String targetNumber,
            @Nullable String note,
            long amountMinor,
            @Nullable String currency,
            @Nullable String sourceAccountId) {
        String method = isBlank(sourceAccountId) ? "Thanh toán qua Stripe" : "Tài khoản thanh toán";
        return new TransactionFlowRequest(
                Kind.BILL_PAYMENT,
                amountMinor,
                currency,
                sourceAccountId,
                null,
                null,
                false,
                null,
                null,
                null,
                targetNumber,
                method,
                note,
                null,
                billId,
                null,
                null,
                null,
                serviceName,
                providerName
        );
    }

    public static TransactionFlowRequest orderPayment(
            @Nullable String orderCategory,
            @Nullable String productId,
            @Nullable String providerName,
            @Nullable String serviceName,
            @Nullable String targetNumber,
            long amountMinor,
            @Nullable String currency,
            @Nullable String sourceAccountId) {
        return orderPayment(orderCategory, productId, providerName, serviceName, targetNumber,
                null, null, null, amountMinor, currency, sourceAccountId);
    }

    public static TransactionFlowRequest orderPayment(
            @Nullable String orderCategory,
            @Nullable String productId,
            @Nullable String providerName,
            @Nullable String serviceName,
            @Nullable String targetNumber,
            @Nullable String note,
            @Nullable String billId,
            @Nullable String orderCustomerDetails,
            long amountMinor,
            @Nullable String currency,
            @Nullable String sourceAccountId) {
        return orderPayment(
                orderCategory,
                productId,
                providerName,
                serviceName,
                targetNumber,
                note,
                billId,
                orderCustomerDetails,
                amountMinor,
                1,
                currency,
                sourceAccountId);
    }

    public static TransactionFlowRequest orderPayment(
            @Nullable String orderCategory,
            @Nullable String productId,
            @Nullable String providerName,
            @Nullable String serviceName,
            @Nullable String targetNumber,
            @Nullable String note,
            @Nullable String billId,
            @Nullable String orderCustomerDetails,
            long amountMinor,
            int quantity,
            @Nullable String currency,
            @Nullable String sourceAccountId) {
        String method = isBlank(sourceAccountId) ? "Thanh toán qua Stripe" : "Tài khoản thanh toán";
        return new TransactionFlowRequest(
                Kind.ORDER_PAYMENT,
                amountMinor,
                currency,
                sourceAccountId,
                null,
                null,
                false,
                null,
                null,
                null,
                targetNumber,
                method,
                note,
                null,
                billId,
                orderCategory,
                productId,
                orderCustomerDetails,
                serviceName,
                providerName,
                quantity
        );
    }

    @NonNull
    public Bundle toBundle() {
        Bundle bundle = new Bundle();
        bundle.putParcelable(EXTRA_REQUEST, this);
        return bundle;
    }

    @Nullable
    @SuppressWarnings("deprecation")
    public static TransactionFlowRequest fromBundle(@Nullable Bundle bundle) {
        if (bundle == null) return null;
        bundle.setClassLoader(TransactionFlowRequest.class.getClassLoader());
        if (Build.VERSION.SDK_INT >= 33) {
            return bundle.getParcelable(EXTRA_REQUEST, TransactionFlowRequest.class);
        }
        return bundle.getParcelable(EXTRA_REQUEST);
    }

    private TransactionFlowRequest(Parcel in) {
        Kind parsedKind;
        try {
            parsedKind = Kind.valueOf(in.readString());
        } catch (Exception e) {
            parsedKind = Kind.INTERNAL_TRANSFER;
        }
        this.kind = parsedKind;
        this.amountMinor = in.readLong();
        String cur = in.readString();
        this.currency = isBlank(cur) ? "VND" : cur;
        this.sourceAccountId = in.readString();
        this.sourceLabel = in.readString();
        this.recipientName = in.readString();
        this.recipientVerified = in.readInt() != 0;
        this.bankName = in.readString();
        this.bankSubtitle = in.readString();
        this.bankCode = in.readString();
        this.targetNumber = in.readString();
        this.methodLabel = in.readString();
        this.note = in.readString();
        this.recipientAccountId = in.readString();
        this.billId = in.readString();
        this.orderCategory = in.readString();
        this.productId = in.readString();
        this.orderCustomerDetails = in.readString();
        this.serviceName = in.readString();
        this.providerName = in.readString();
        this.quantity = Math.max(1, in.readInt());
    }

    @Override
    public void writeToParcel(@NonNull Parcel dest, int flags) {
        dest.writeString(kind.name());
        dest.writeLong(amountMinor);
        dest.writeString(currency);
        dest.writeString(sourceAccountId);
        dest.writeString(sourceLabel);
        dest.writeString(recipientName);
        dest.writeInt(recipientVerified ? 1 : 0);
        dest.writeString(bankName);
        dest.writeString(bankSubtitle);
        dest.writeString(bankCode);
        dest.writeString(targetNumber);
        dest.writeString(methodLabel);
        dest.writeString(note);
        dest.writeString(recipientAccountId);
        dest.writeString(billId);
        dest.writeString(orderCategory);
        dest.writeString(productId);
        dest.writeString(orderCustomerDetails);
        dest.writeString(serviceName);
        dest.writeString(providerName);
        dest.writeInt(quantity);
    }

    @Override
    public int describeContents() {
        return 0;
    }

    public static final Creator<TransactionFlowRequest> CREATOR = new Creator<TransactionFlowRequest>() {
        @Override
        public TransactionFlowRequest createFromParcel(Parcel in) {
            return new TransactionFlowRequest(in);
        }

        @Override
        public TransactionFlowRequest[] newArray(int size) {
            return new TransactionFlowRequest[size];
        }
    };

    private static boolean isBlank(@Nullable String value) {
        return value == null || value.trim().isEmpty();
    }

    @Nullable
    private static String normalize(@Nullable String value) {
        if (value == null) return null;
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
