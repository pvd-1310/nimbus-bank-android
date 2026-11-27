package com.example.final_term.ui.transaction;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.example.final_term.data.ReadSummaryFormatter;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;

public final class TransactionPresentation {
    private static final Locale VI_VN = new Locale("vi", "VN");
    private static final TimeZone VN_TZ = TimeZone.getTimeZone("Asia/Ho_Chi_Minh");

    private TransactionPresentation() {
    }

    public static final class DetailRow {
        @NonNull
        public final String label;
        @NonNull
        public final String value;
        @Nullable
        public final String subtitle;
        public final boolean verifiedBadge;
        public final boolean monospace;
        public final boolean methodPill;
        @Nullable
        public final String copyValue;
        public final boolean walletIcon;
        public final boolean bankIcon;

        public DetailRow(
                @NonNull String label,
                @NonNull String value,
                @Nullable String subtitle,
                boolean verifiedBadge,
                boolean monospace,
                boolean methodPill,
                @Nullable String copyValue,
                boolean walletIcon,
                boolean bankIcon) {
            this.label = label;
            this.value = value;
            this.subtitle = subtitle;
            this.verifiedBadge = verifiedBadge;
            this.monospace = monospace;
            this.methodPill = methodPill;
            this.copyValue = copyValue;
            this.walletIcon = walletIcon;
            this.bankIcon = bankIcon;
        }

        private static DetailRow plain(@NonNull String label, @NonNull String value) {
            return new DetailRow(label, value, null, false, false, false, null, false, false);
        }

        private static DetailRow wallet(@NonNull String label, @NonNull String value) {
            return new DetailRow(label, value, null, false, false, false, null, true, false);
        }

        private static DetailRow verified(@NonNull String label, @NonNull String value, boolean verifiedBadge) {
            return new DetailRow(label, value, null, verifiedBadge, false, false, null, false, false);
        }

        private static DetailRow bank(@NonNull String label, @NonNull String value, @Nullable String subtitle) {
            return new DetailRow(label, value, subtitle, false, false, false, null, false, true);
        }

        private static DetailRow mono(@NonNull String label, @NonNull String value) {
            return new DetailRow(label, value, null, false, true, false, null, false, false);
        }

        private static DetailRow method(@NonNull String label, @NonNull String value) {
            return new DetailRow(label, value, null, false, false, true, null, false, false);
        }

        private static DetailRow copyable(@NonNull String label, @NonNull String value, @Nullable String copyValue) {
            return new DetailRow(label, value, null, false, true, false, copyValue, false, false);
        }
    }

    public static final class ConfirmationModel {
        @NonNull
        public final String headerTitle;
        @NonNull
        public final String heroLabel;
        @NonNull
        public final String amountFormatted;
        @NonNull
        public final String feeText;
        public final boolean feeFree;
        @NonNull
        public final List<DetailRow> rows;

        public ConfirmationModel(
                @NonNull String headerTitle,
                @NonNull String heroLabel,
                @NonNull String amountFormatted,
                @NonNull String feeText,
                boolean feeFree,
                @NonNull List<DetailRow> rows) {
            this.headerTitle = headerTitle;
            this.heroLabel = heroLabel;
            this.amountFormatted = amountFormatted;
            this.feeText = feeText;
            this.feeFree = feeFree;
            this.rows = Collections.unmodifiableList(new ArrayList<>(rows));
        }
    }

    public static final class ReceiptModel {
        @NonNull
        public final String miniHeaderTitle;
        @NonNull
        public final String headline;
        @NonNull
        public final String amountFormatted;
        @NonNull
        public final String timestampText;
        @NonNull
        public final List<DetailRow> rows;
        @NonNull
        public final String shareText;

        public ReceiptModel(
                @NonNull String miniHeaderTitle,
                @NonNull String headline,
                @NonNull String amountFormatted,
                @NonNull String timestampText,
                @NonNull List<DetailRow> rows,
                @NonNull String shareText) {
            this.miniHeaderTitle = miniHeaderTitle;
            this.headline = headline;
            this.amountFormatted = amountFormatted;
            this.timestampText = timestampText;
            this.rows = Collections.unmodifiableList(new ArrayList<>(rows));
            this.shareText = shareText;
        }
    }

    @NonNull
    public static ConfirmationModel buildConfirmation(@NonNull TransactionFlowRequest req, long feeMinor) {
        return buildConfirmation(req, feeMinor, null, null);
    }

    public static ConfirmationModel buildConfirmation(@NonNull TransactionFlowRequest req, long feeMinor,
                                                       @Nullable String paymentMethod,
                                                       @Nullable String paymentAccountId) {
        String amountFormatted = ReadSummaryFormatter.formatCurrency(req.amountMinor, req.currency);
        boolean feeFree = feeMinor <= 0;
        String feeText = feeFree
                ? "Phí dịch vụ: " + ReadSummaryFormatter.formatCurrency(0L, req.currency) + " (Miễn phí)"
                : "Phí dịch vụ: " + ReadSummaryFormatter.formatCurrency(feeMinor, req.currency);

        List<DetailRow> rows = new ArrayList<>();
        String headerTitle;
        String heroLabel;

        switch (req.kind) {
            case INTERNAL_TRANSFER:
                headerTitle = "Xác nhận chuyển tiền";
                heroLabel = "Tổng số tiền chuyển";
                addEntityRows(rows, req);
                rows.add(DetailRow.method("Phương thức", defaultIfBlank(req.methodLabel, "Chuyển nhanh nội bộ 24/7")));
                break;

            case EXTERNAL_TRANSFER:
                headerTitle = "Xác nhận chuyển tiền";
                heroLabel = "Tổng số tiền chuyển";
                addEntityRows(rows, req);
                rows.add(DetailRow.method("Phương thức", defaultIfBlank(req.methodLabel, "Chuyển nhanh Napas 24/7")));
                break;

            case DEPOSIT:
                headerTitle = "Xác nhận nạp tiền";
                heroLabel = "Tổng tiền nạp";
                addEntityRows(rows, req);
                rows.add(DetailRow.method("Phương thức", defaultIfBlank(req.methodLabel, "Thanh toán thẻ Stripe")));
                break;

            case WITHDRAWAL:
                headerTitle = "Xác nhận rút tiền";
                heroLabel = "Tổng tiền rút";
                addEntityRows(rows, req);
                rows.add(DetailRow.method("Phương thức", defaultIfBlank(req.methodLabel, "Rút tiền tài khoản")));
                break;

            case BILL_PAYMENT:
            case ORDER_PAYMENT:
            default:
                headerTitle = "Xác nhận thanh toán";
                heroLabel = "Tổng tiền thanh toán";
                addEntityRows(rows, req);
                if (!rows.isEmpty() && "Nguồn thanh toán".equals(rows.get(0).label)) rows.remove(0);
                boolean nimbus = "NIMBUS_ACCOUNT".equals(paymentMethod);
                rows.add(0, DetailRow.wallet("Nguồn thanh toán", nimbus
                        ? maskAccountLabel(paymentAccountId, "Tài khoản Nimbus") : "Thẻ (Stripe)"));
                rows.add(DetailRow.method("Phương thức", nimbus ? "Tài khoản Nimbus" : "Thẻ Stripe"));
                break;
        }
        if (!isBlank(req.note)) {
            rows.add(DetailRow.plain("Nội dung", req.note.trim()));
        }

        return new ConfirmationModel(headerTitle, heroLabel, amountFormatted, feeText, feeFree, rows);
    }

    @NonNull
    public static ReceiptModel buildReceipt(
            @NonNull TransactionFlowRequest req,
            @Nullable String fullTxId,
            @Nullable String providerReference,
            long feeMinor,
            long completedAtMillis) {
        return buildReceipt(req, fullTxId, providerReference, feeMinor, completedAtMillis, null, null);
    }

    public static ReceiptModel buildReceipt(
            @NonNull TransactionFlowRequest req,
            @Nullable String fullTxId,
            @Nullable String providerReference,
            long feeMinor,
            long completedAtMillis,
            @Nullable String paymentMethod,
            @Nullable String paymentAccountId) {
        return buildReceipt(req, fullTxId, providerReference, feeMinor, completedAtMillis,
                paymentMethod, paymentAccountId, false);
    }

    public static ReceiptModel buildReceipt(
            @NonNull TransactionFlowRequest req,
            @Nullable String fullTxId,
            @Nullable String providerReference,
            long feeMinor,
            long completedAtMillis,
            @Nullable String paymentMethod,
            @Nullable String paymentAccountId,
            boolean pending) {
        String amountFormatted = ReadSummaryFormatter.formatCurrency(req.amountMinor, req.currency);
        String timestampText = formatTimestamp(completedAtMillis);
        String shortId = shortTransactionId(providerReference, fullTxId);
        String copyId = !isBlank(fullTxId)
                ? fullTxId.trim()
                : (!isBlank(providerReference) ? providerReference.trim() : null);

        String miniHeaderTitle;
        String headline;
        switch (req.kind) {
            case INTERNAL_TRANSFER:
            case EXTERNAL_TRANSFER:
                miniHeaderTitle = "BIÊN LAI CHUYỂN KHOẢN";
                headline = pending ? "Đang xử lý chuyển tiền" : "Chuyển tiền thành công";
                break;
            case DEPOSIT:
                miniHeaderTitle = "BIÊN LAI NẠP TIỀN";
                headline = pending ? "Đang xử lý nạp tiền" : "Nạp tiền thành công";
                break;
            case WITHDRAWAL:
                miniHeaderTitle = "BIÊN LAI RÚT TIỀN";
                headline = pending ? "Đang xử lý rút tiền" : "Rút tiền thành công";
                break;
            case BILL_PAYMENT:
            case ORDER_PAYMENT:
            default:
                miniHeaderTitle = "BIÊN LAI THANH TOÁN";
                headline = pending ? "Đang xử lý thanh toán" : "Thanh toán thành công";
                break;
        }

        List<DetailRow> rows = new ArrayList<>();
        rows.add(DetailRow.copyable("Mã giao dịch", shortId, copyId));
        addEntityRows(rows, req);
        if ((req.kind == TransactionFlowRequest.Kind.BILL_PAYMENT || req.kind == TransactionFlowRequest.Kind.ORDER_PAYMENT)
                && paymentMethod != null) {
            if (rows.size() > 1 && "Nguồn thanh toán".equals(rows.get(1).label)) rows.remove(1);
            boolean nimbus = "NIMBUS_ACCOUNT".equals(paymentMethod);
            rows.add(1, DetailRow.wallet("Nguồn thanh toán", nimbus
                    ? maskAccountLabel(paymentAccountId, "Tài khoản Nimbus") : "Thẻ (Stripe)"));
            rows.add(DetailRow.method("Phương thức", nimbus ? "Tài khoản Nimbus" : "Thẻ Stripe"));
        }

        String feeDisplay = feeMinor <= 0
                ? ReadSummaryFormatter.formatCurrency(0L, req.currency) + " (Miễn phí)"
                : ReadSummaryFormatter.formatCurrency(feeMinor, req.currency);
        rows.add(DetailRow.plain("Phí dịch vụ", feeDisplay));

        if (!isBlank(req.note)) {
            rows.add(DetailRow.plain("Nội dung", req.note.trim()));
        }
        rows.add(pending
                ? DetailRow.plain("Trạng thái", "Đang xử lý")
                : DetailRow.verified("Trạng thái", "Hoàn tất", true));

        String shareId = copyId != null ? copyId : shortId;
        StringBuilder share = new StringBuilder();
        share.append(headline).append('\n');
        share.append("Số tiền: ").append(amountFormatted).append('\n');
        share.append("Thời gian: ").append(timestampText).append('\n');
        share.append("Mã giao dịch: ").append(shareId);
        for (int i = 1; i < rows.size(); i++) {
            DetailRow row = rows.get(i);
            share.append('\n').append(row.label).append(": ").append(row.value);
        }

        return new ReceiptModel(miniHeaderTitle, headline, amountFormatted, timestampText, rows, share.toString());
    }

    private static void addEntityRows(@NonNull List<DetailRow> rows, @NonNull TransactionFlowRequest req) {
        switch (req.kind) {
            case INTERNAL_TRANSFER:
                addSourceRow(rows, "Tài khoản nguồn", req);
                if (!isBlank(req.recipientName)) {
                    rows.add(DetailRow.verified("Người thụ hưởng", req.recipientName.trim(), req.recipientVerified));
                }
                String internalBank = !isBlank(req.bankName) ? req.bankName.trim() : "Nimbus Bank";
                rows.add(DetailRow.bank("Ngân hàng nhận", internalBank, nonBlankOrNull(req.bankSubtitle)));
                String internalTarget = !isBlank(req.targetNumber) ? req.targetNumber.trim() : nonBlankOrNull(req.recipientAccountId);
                if (internalTarget != null) {
                    rows.add(DetailRow.plain("Số điện thoại / TK nhận", internalTarget));
                }
                break;

            case EXTERNAL_TRANSFER:
                addSourceRow(rows, "Tài khoản nguồn", req);
                if (!isBlank(req.recipientName)) {
                    rows.add(DetailRow.verified("Người thụ hưởng", req.recipientName.trim(), req.recipientVerified));
                }
                if (!isBlank(req.bankName)) {
                    rows.add(DetailRow.bank("Ngân hàng nhận", req.bankName.trim(), nonBlankOrNull(req.bankSubtitle)));
                }
                if (!isBlank(req.targetNumber)) {
                    rows.add(DetailRow.mono("Số tài khoản nhận", req.targetNumber.trim()));
                }
                break;

            case DEPOSIT:
                rows.add(DetailRow.plain("Nguồn nạp", "Thẻ quốc tế (Stripe)"));
                addSourceRow(rows, "Tài khoản nhận", req);
                break;

            case WITHDRAWAL:
                addSourceRow(rows, "Tài khoản nguồn", req);
                rows.add(DetailRow.plain("Đích nhận", "Rút tiền mặt · Nimbus Bank"));
                break;

            case BILL_PAYMENT:
            case ORDER_PAYMENT:
            default:
                addPaymentSourceRow(rows, req);
                if (!isBlank(req.serviceName)) {
                    rows.add(DetailRow.plain("Dịch vụ", req.serviceName.trim()));
                }
                if (!isBlank(req.providerName)) {
                    rows.add(DetailRow.plain("Nhà cung cấp", req.providerName.trim()));
                }
                if (!isBlank(req.targetNumber)) {
                    rows.add(DetailRow.mono("Mã hóa đơn / SĐT", req.targetNumber.trim()));
                }
                break;
        }
    }

    @NonNull
    public static String maskAccountLabel(@Nullable String accountId, @Nullable String prefix) {
        String safePrefix = isBlank(prefix) ? "Tài khoản" : prefix.trim();
        if (isBlank(accountId)) {
            return safePrefix;
        }
        String trimmed = accountId.trim();
        String last4 = trimmed.length() > 4 ? trimmed.substring(trimmed.length() - 4) : trimmed;
        return safePrefix + " (\u2022\u2022\u2022 " + last4 + ")";
    }

    @NonNull
    public static String shortTransactionId(@Nullable String providerRef, @Nullable String txId) {
        if (!isBlank(providerRef)) {
            return providerRef.trim();
        }
        if (!isBlank(txId)) {
            String trimmed = txId.trim();
            String head = trimmed.length() > 12 ? trimmed.substring(0, 12) : trimmed;
            return head.toUpperCase(Locale.ROOT);
        }
        return "—";
    }

    @NonNull
    public static String formatTimestamp(long millis) {
        long effectiveMillis = millis > 0 ? millis : System.currentTimeMillis();
        SimpleDateFormat fmt = new SimpleDateFormat("HH:mm \u00B7 dd 'Tháng' MM, yyyy", VI_VN);
        fmt.setTimeZone(VN_TZ);
        return fmt.format(new Date(effectiveMillis));
    }

    private static void addSourceRow(
            @NonNull List<DetailRow> rows,
            @NonNull String label,
            @NonNull TransactionFlowRequest req) {
        String resolved = resolveSourceLabel(req);
        if (resolved != null) {
            rows.add(DetailRow.wallet(label, resolved));
        }
    }

    private static void addPaymentSourceRow(@NonNull List<DetailRow> rows, @NonNull TransactionFlowRequest req) {
        if (!isBlank(req.sourceAccountId)) {
            String resolved = resolveSourceLabel(req);
            if (resolved != null) {
                rows.add(DetailRow.wallet("Nguồn thanh toán", resolved));
            }
        } else if (!isBlank(req.sourceLabel)) {
            rows.add(DetailRow.plain("Nguồn thanh toán", req.sourceLabel.trim()));
        } else {
            rows.add(DetailRow.plain("Nguồn thanh toán", "Thẻ (Stripe)"));
        }
    }

    @Nullable
    private static String resolveSourceLabel(@NonNull TransactionFlowRequest req) {
        if (!isBlank(req.sourceLabel)) {
            return req.sourceLabel.trim();
        }
        if (!isBlank(req.sourceAccountId)) {
            return maskAccountLabel(req.sourceAccountId, "Thanh toán");
        }
        return null;
    }

    private static boolean isBlank(@Nullable String value) {
        return value == null || value.trim().isEmpty();
    }

    @Nullable
    private static String nonBlankOrNull(@Nullable String value) {
        return isBlank(value) ? null : value.trim();
    }

    @NonNull
    private static String defaultIfBlank(@Nullable String value, @NonNull String fallback) {
        return isBlank(value) ? fallback : value.trim();
    }
}
