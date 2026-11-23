package com.example.final_term.domain;

public final class DomainDtos {
    private DomainDtos() { }

    public static final class AccountSummary {
        public final String accountId;
        public final String type;
        public final String currency;
        public final long balanceMinor;
        public AccountSummary(String accountId, String type, String currency, long balanceMinor) {
            this.accountId = accountId; this.type = type; this.currency = currency; this.balanceMinor = balanceMinor;
        }
    }
    public static final class TransferRequest {
        public final String requestId; public final String sourceAccountId; public final String destinationAccountId;
        public final long amountMinor; public final String currency;
        public TransferRequest(String requestId, String sourceAccountId, String destinationAccountId, long amountMinor, String currency) {
            this.requestId = requestId; this.sourceAccountId = sourceAccountId; this.destinationAccountId = destinationAccountId; this.amountMinor = amountMinor; this.currency = currency;
        }
    }
    public static final class TransactionIntent { public final String intentId; public final String requestId; public TransactionIntent(String intentId, String requestId) { this.intentId = intentId; this.requestId = requestId; } }
    public static final class OtpVerification {
        public final String intentId;
        public final String challengeId;
        public final String code;

        public OtpVerification(String intentId, String challengeId, String code) {
            this.intentId = intentId;
            this.challengeId = challengeId;
            this.code = code;
        }
    }
    public static final class AuthorizationResult { public final String intentId; public final boolean authorized; public AuthorizationResult(String intentId, boolean authorized) { this.intentId = intentId; this.authorized = authorized; } }
    public static final class TransactionReceipt { public final String transactionId; public final String status; public TransactionReceipt(String transactionId, String status) { this.transactionId = transactionId; this.status = status; } }
    public static final class EkycSubmission { public final String customerUid; public final boolean consented; public EkycSubmission(String customerUid, boolean consented) { this.customerUid = customerUid; this.consented = consented; } }
    public static final class EkycRequest { public final String requestId; public final String status; public EkycRequest(String requestId, String status) { this.requestId = requestId; this.status = status; } }
    public static final class UploadTicket { public final String objectKey; public final String uploadUrl; public UploadTicket(String objectKey, String uploadUrl) { this.objectKey = objectKey; this.uploadUrl = uploadUrl; } }
    public static final class PaymentRequest {
        public final String requestId;
        public final String billOrOrderRef;

        public PaymentRequest(String requestId, String billOrOrderRef) {
            this.requestId = requestId;
            this.billOrOrderRef = billOrOrderRef;
        }
    }
    public static final class PaymentRecord { public final String paymentId; public final String status; public PaymentRecord(String paymentId, String status) { this.paymentId = paymentId; this.status = status; } }
    public static final class Origin { public final double latitude; public final double longitude; public Origin(double latitude, double longitude) { this.latitude = latitude; this.longitude = longitude; } }
    public static final class BranchRecommendation { public final String branchId; public final String label; public BranchRecommendation(String branchId, String label) { this.branchId = branchId; this.label = label; } }
    public static final class ChatMessage { public final String text; public ChatMessage(String text) { this.text = text; } }
    public static final class ChatReply { public final String reply; public final String actionId; public ChatReply(String reply, String actionId) { this.reply = reply; this.actionId = actionId; } }
}
