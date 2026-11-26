import type { Doc } from "../core/store.js";
import { check } from "../shared/errors.js";

// Optional identity fields are populated only after the customer confirms eKYC.
export const optionalCustomerIdentityFields = ["dateOfBirth", "gender", "nationality", "identityDocumentExpiry"] as const;

export const templateFields: Record<string, readonly string[]> = {
  users: [
    "uid",
    "role",
    "fullName",
    "emailMasked",
    "phoneMasked",
    "contactPhoneSearchTokens",
    "status",
    "createdAt",
  ],
  customers: [
    "customerId",
    "uidRef",
    "customerNo",
    "nationalIdNumber",
    "kycStatus",
    "address",
    "createdBy",
  ],
  officers: [
    "officerId",
    "uidRef",
    "staffCode",
    "permissions",
    "active",
    "branchId",
  ],
  accounts: [
    "accountId",
    "ownerUid",
    "ownerNameSearch",
    "type",
    "currency",
    "status",
    "availableMinor",
    "bookedMinor",
    "version",
  ],
  checkingDetails: [
    "accountId",
    "overdraftPolicy",
    "dailyLimitMinor",
    "cardAlias",
  ],
  savingsDetails: [
    "accountId",
    "principalMinor",
    "productId",
    "ratePolicyVersion",
    "openedAt",
    "maturityAt",
  ],
  mortgageDetails: [
    "accountId",
    "loanPrincipalMinor",
    "outstandingMinor",
    "annualRate",
    "paymentFrequency",
    "termMonths",
  ],
  transactions: [
    "transactionId",
    "requestId",
    "actorUid",
    "sourceAccountId",
    "destinationAccountId",
    "type",
    "amountMinor",
    "status",
    "committedAt",
  ],
  transactionIntents: [
    "intentId",
    "actorUid",
    "sourceId",
    "target",
    "amountMinor",
    "digest",
    "expiresAt",
    "status",
  ],
  ledgerEntries: [
    "entryId",
    "transactionId",
    "accountId",
    "direction",
    "amountMinor",
    "currency",
    "postedAt",
  ],
  otpChallenges: [
    "challengeId",
    "intentId",
    "actorUid",
    "otpHashOrProviderRef",
    "expiry",
    "attempts",
    "usedAt",
  ],
  ekycRequests: [
    "requestId",
    "customerUid",
    "consentAt",
    "storageRefs",
    "reviewStatus",
    "reviewedBy",
    "reviewedAt",
  ],
  bills: [
    "billId",
    "ownerUid",
    "provider",
    "invoiceRef",
    "amountMinor",
    "dueAt",
    "status",
  ],
  payments: [
    "paymentId",
    "billOrOrderRef",
    "gateway",
    "merchantRef",
    "amountMinor",
    "status",
    "providerTxnRef",
  ],
  orders: [
    "orderId",
    "ownerUid",
    "serviceType",
    "quoteRef",
    "amountMinor",
    "fulfillmentStatus",
    "paymentId",
  ],
  ratePolicies: [
    "policyId",
    "accountProduct",
    "annualRateBasisPoints",
    "effectiveFrom",
    "version",
    "updatedBy",
  ],
  bankBranches: [
    "branchId",
    "name",
    "address",
    "latitude",
    "longitude",
    "active",
    "openingHours",
  ],
  notifications: [
    "notificationId",
    "ownerUid",
    "category",
    "title",
    "message",
    "readAt",
    "createdAt",
  ],
  chatHistory: [
    "messageId",
    "ownerUid",
    "role",
    "redactedText",
    "actionId",
    "createdAt",
  ],
  auditLogs: [
    "eventId",
    "actorUid",
    "action",
    "resourceId",
    "requestId",
    "timestamp",
    "beforeAfterSummary",
  ],
};
export const templateCollections = Object.keys(templateFields);
const identity: Record<string, string> = {
  users: "uid",
  customers: "customerId",
  officers: "officerId",
  accounts: "accountId",
  checkingDetails: "accountId",
  savingsDetails: "accountId",
  mortgageDetails: "accountId",
  transactions: "transactionId",
  transactionIntents: "intentId",
  ledgerEntries: "entryId",
  otpChallenges: "challengeId",
  ekycRequests: "requestId",
  bills: "billId",
  payments: "paymentId",
  orders: "orderId",
  ratePolicies: "policyId",
  bankBranches: "branchId",
  notifications: "notificationId",
  chatHistory: "messageId",
  auditLogs: "eventId",
};

// Add canonical fields at the persistence boundary. Legacy aliases are retained
// during migration so existing clients and already-issued authorizations work.
export function normalizeDocument(path: string, input: Doc): Doc {
  const [collection, id] = path.split("/");
  if (!identity[collection]) return { ...input };
  const out = { ...input };
  const assign = (field: string, value: unknown) => {
    if (value === undefined) return;
    if (out[field] !== undefined && out[field] !== null)
      check(
        JSON.stringify(out[field]) === JSON.stringify(value),
        "SCHEMA_CONFLICT:" + field,
        409,
      );
    else out[field] = value;
  };
  assign(identity[collection], id);
  switch (collection) {
    case "users":
      if (out.status === undefined && typeof out.active === "boolean")
        out.status = out.active ? "ACTIVE" : "INACTIVE";
      break;
    case "customers":
    case "officers":
      assign("uidRef", out.uid);
      break;
    case "transactionIntents":
      assign(
        "sourceId",
        out.sourceAccountId ?? (out.kind === "PAYMENT" ? out.sourceId ?? "STRIPE" : undefined),
      );
      if (out.destinationAccountId)
        assign("target", { kind: "ACCOUNT", id: out.destinationAccountId });
      else if (out.billId) assign("target", { kind: "BILL", id: out.billId });
      break;
    case "otpChallenges":
      assign("intentId", id);
      assign("otpHashOrProviderRef", out.hash);
      assign("expiry", out.expiresAt);
      if (out.usedAt === undefined) out.usedAt = null;
      break;
    case "payments":
      assign("billOrOrderRef", out.billId);
      assign("gateway", out.demo === true && out.gateway === "SIMULATOR" ? "SIMULATOR" : "STRIPE");
      assign("merchantRef", id);
      assign("providerTxnRef", out.providerId);
      if (out.providerTxnRef === undefined) out.providerTxnRef = null;
      break;
    case "ratePolicies":
      assign("accountProduct", out.productId);
      break;
    case "mortgageDetails":
      if (out.annualRateBasisPoints !== undefined)
        assign("annualRate", out.annualRateBasisPoints / 10000);
      break;
    case "notifications":
      assign("message", out.body);
      if (out.category === undefined) out.category = "GENERAL";
      if (out.readAt === undefined && out.read !== true) out.readAt = null;
      break;
    case "ekycRequests":
      if (out.reviewedBy === undefined) out.reviewedBy = null;
      if (out.reviewedAt === undefined) out.reviewedAt = null;
      break;
  }
  return out;
}
export function missingTemplateFields(path: string, data: Doc) {
  return (templateFields[path.split("/")[0]] ?? []).filter(
    (k) => data[k] === undefined,
  );
}
export function migrationPatch(path: string, data: Doc): Doc {
  // Historical audit events are immutable. New events are written with summaries.
  if (path.startsWith("auditLogs/")) return {};
  const next = normalizeDocument(path, data),
    patch: Doc = {};
  for (const [k, v] of Object.entries(next))
    if (data[k] === undefined) patch[k] = v;
  return patch;
}
