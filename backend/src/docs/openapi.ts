// Public contract for the production-shaped banking API.
import { templateFields } from "../database/template-schema.js";
const text = { type: "string" },
  integer = { type: "integer" },
  id = { type: "string", pattern: "^[A-Za-z0-9_-]{1,100}$" };
const object = (
  properties: Record<string, unknown>,
  required = Object.keys(properties),
) => ({ type: "object", properties, required, additionalProperties: false });
const legacyTransfer = object({
  requestId: id,
  sourceAccountId: id,
  destinationAccountId: id,
  amountMinor: {
    type: "integer",
    minimum: 1,
    maximum: Number.MAX_SAFE_INTEGER,
  },
  currency: { type: "string", pattern: "^[A-Z]{3}$" },
});
const transfer = {
  oneOf: [
    object({
      requestId: id,
      sourceId: id,
      target: object({ kind: { const: "ACCOUNT" }, id }),
      amountMinor: {
        type: "integer",
        minimum: 1,
        maximum: Number.MAX_SAFE_INTEGER,
      },
      currency: { type: "string", pattern: "^[A-Z]{3}$" },
    }),
    { ...legacyTransfer, deprecated: true },
  ],
};
const payment = {
  oneOf: [
    object({ billOrOrderRef: id, requestId: id }),
    { ...object({ billId: id, requestId: id }), deprecated: true },
  ],
};
const endpoints: [string, string, string, unknown?][] = [
  ["get", "/me", "Current safe profile"],
  [
    "patch",
    "/me",
    "Update permitted contact/preferences",
    object(
      {
        contactEmail: { type: "string", format: "email" },
        contactPhone: text,
        preferences: object(
          {
            language: { enum: ["vi", "en"] },
            notificationsEnabled: { type: "boolean" },
          },
          [],
        ),
      },
      [],
    ),
  ],
  ["get", "/capabilities", "Configured and deferred capabilities"],
  ["get", "/accounts", "Owned accounts"],
  ["get", "/accounts/{id}", "Owned account"],
  ["get", "/accounts/{id}/details", "Account subtype details"],
  [
    "get",
    "/accounts/{id}/savings-estimate",
    "ACT/365 savings estimate; days query",
  ],
  ["get", "/accounts/{id}/mortgage-schedule", "Projected amortization"],
  ["get", "/transactions", "History; accountId,type,status,from,to filters"],
  [
    "post",
    "/transfers/intents",
    "Validate and freeze internal transfer",
    transfer,
  ],
  ["get", "/intents/{id}", "Intent status"],
  ["post", "/intents/{id}/otp", "Deliver local demo OTP"],
  [
    "post",
    "/intents/{id}/verify-otp",
    "Verify transaction-bound OTP",
    object({ code: text }),
  ],
  ["post", "/transfers/{id}/commit", "Atomic balanced internal transfer"],
  [
    "post",
    "/money-requests",
    "Request only; no fund mutation",
    object({
      requestId: id,
      accountId: id,
      amountMinor: { type: "integer", minimum: 1 },
      currency: text,
      type: { enum: ["DEPOSIT", "WITHDRAWAL"] },
    }),
  ],
  ["get", "/money-requests", "Owned money requests"],
  ["post", "/external-transfers", "Unavailable until provider configured"],
  ["get", "/notifications", "Owned notifications"],
  ["post", "/notifications/{id}/read", "Acknowledge notification"],
  [
    "post",
    "/ekyc",
    "Create consented draft",
    object({ consented: { type: "boolean", const: true } }),
  ],
  ["get", "/ekyc", "Owned eKYC requests"],
  [
    "post",
    "/ekyc/{id}/uploads",
    "Issue private S3 upload",
    object({
      mime: { enum: ["image/jpeg", "image/png"] },
      bytes: { type: "integer", minimum: 1, maximum: 5000000 },
    }),
  ],
  [
    "post",
    "/uploads/{id}/confirm",
    "Validate immutable uploaded object version",
  ],
  ["post", "/ekyc/{id}/submit", "Submit two or more validated images"],
  ["get", "/bills", "Owned invoices"],
  [
    "post",
    "/payments/intents",
    "Freeze server-priced invoice for OTP",
    payment,
  ],
  ["post", "/payments", "Create/retry test PaymentIntent after OTP", payment],
  ["get", "/payments/{id}", "Owned payment status"],
  ["get", "/catalog", "Explicitly simulated service catalog"],
  [
    "post",
    "/orders",
    "Create priced demo order/invoice",
    object({
      requestId: id,
      productId: id,
      quantity: { type: "integer", minimum: 1, maximum: 10 },
      customerDetails: text,
    }),
  ],
  ["get", "/orders", "Owned orders"],
  ["get", "/branches", "Active branch directory"],
  ["post", "/branches/routes", "Unavailable until Routes configured"],
  ["get", "/officer/customers", "Permission CUSTOMER_ADMIN required"],
  [
    "post",
    "/officer/customers",
    "Provision onboarding customer",
    object({
      requestId: id,
      email: { type: "string", format: "email" },
      fullName: text,
    }),
  ],
  [
    "patch",
    "/officer/customers/{id}",
    "Controlled name change with reason",
    object({ fullName: text, reason: text }),
  ],
  ["get", "/officer/ekyc", "Permission EKYC_REVIEW required"],
  ["get", "/officer/audit", "Permission AUDIT_READ required"],
  [
    "post",
    "/officer/rate-policies",
    "Create future-effective policy version",
    object({
      productId: id,
      annualRateBasisPoints: { type: "integer", minimum: 0, maximum: 10000 },
      effectiveFrom: integer,
    }),
  ],
  [
    "post",
    "/officer/ekyc/{id}/review",
    "Audited manual review",
    object({
      status: { enum: ["APPROVED", "REJECTED", "RESUBMIT_REQUIRED"] },
      reason: text,
    }),
  ],
  [
    "get",
    "/officer/ekyc/{id}/evidence/{index}",
    "Temporary version-specific officer download",
  ],
];
const paths: Record<string, any> = {};
for (const [method, path, summary, body] of endpoints) {
  const params = [...path.matchAll(/\{([^}]+)\}/g)].map((m) => ({
    name: m[1],
    in: "path",
    required: true,
    schema: m[1] === "index" ? { type: "integer", minimum: 0 } : id,
  }));
  if (
    method === "get" &&
    !path.includes("{") &&
    !["/me", "/capabilities"].includes(path)
  )
    params.push(
      ...(["limit", "cursor"].map((name) => ({
        name,
        in: "query",
        required: false,
        schema:
          name === "limit" ? { type: "integer", minimum: 1, maximum: 100 } : id,
      })) as any[]),
    );
  paths[path] ??= {};
  paths[path][method] = {
    summary,
    parameters: params,
    ...(body
      ? {
          requestBody: {
            required: true,
            content: { "application/json": { schema: body } },
          },
        }
      : {}),
    responses: {
      "200": {
        description:
          "Operation result or {items,nextCursor}; see README for workflow",
      },
      "400": { description: "Validation/business error" },
      "401": { description: "Invalid session" },
      "403": { description: "Role/ownership denied" },
      "409": { description: "Conflict" },
      "503": { description: "Provider requires setup" },
    },
  };
}
paths["/webhooks/stripe"] = {
  post: {
    summary: "Signed Stripe test events; raw body required",
    security: [],
    parameters: [
      { name: "Stripe-Signature", in: "header", required: true, schema: text },
    ],
    requestBody: {
      required: true,
      content: { "application/json": { schema: { type: "object" } } },
    },
    responses: {
      "200": { description: "Event processed/deduplicated" },
      "400": { description: "Invalid signature or mismatched payment" },
      "503": { description: "Stripe not configured" },
    },
  },
};
export const openapi = {
  openapi: "3.1.0",
  info: {
    title: "Nimbus Bank API",
    version: "0.1.0",
    description:
      "Authenticated banking API. Provider capabilities are explicit; chatbot and unavailable external providers remain deferred.",
  },
  servers: [{ url: process.env.PUBLIC_API_URL ?? "https://api.example.com/v1" }],
  security: [{ firebaseToken: [] }],
  components: {
    securitySchemes: {
      firebaseToken: {
        type: "http",
        scheme: "bearer",
        bearerFormat: "Firebase ID token",
      },
    },
  },
  paths,
  "x-firestore-template-fields": templateFields,
};
