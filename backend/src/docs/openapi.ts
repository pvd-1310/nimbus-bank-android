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
  ["get", "/me/avatar", "Create a short-lived private URL for the current avatar"],
  ["post", "/me/avatar", "Create a private S3 avatar upload ticket"],
  ["post", "/me/avatar/{id}/confirm", "Verify the uploaded avatar object and save it to the current profile"],
  [
    "patch",
    "/me",
    "Update permitted contact/preferences",
    object(
      {
        contactEmail: { type: "string", format: "email" },
        contactPhone: { type: "string", pattern: "^\\+[1-9][0-9]{7,14}$" },
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
    "Monthly savings interest estimate using annual rate divided by 12",
  ],
  ["get", "/accounts/{id}/mortgage-schedule", "Projected amortization; optional frequency=MONTHLY|BIWEEKLY"],
  ["get", "/transactions", "History; external transfers include destinationBankCode; accountId,type,status,from,to filters"],
  [
    "post",
    "/transfers/intents",
    "Validate and freeze internal transfer",
    transfer,
  ],
  ["get", "/transfers/by-request/{id}", "Recover an owned transfer by original request ID"],
  ["get", "/intents/{id}", "Read intent, face, challenge and settlement status without returning the code"],
  ["post", "/intents/{id}/cancel", "Cancel an uncommitted intent and invalidate its challenge"],
  ["post", "/intents/{id}/face", "Verify a fresh camera image against the actor's approved face template"],
  ["post", "/auth/face-login", "Identify an eligible customer and return a Firebase custom token"],
  ["post", "/me/face/revoke", "Revoke the face template, reset the latest eKYC request for resubmission, and queue all owned evidence uploads for purge"],
  ["post", "/intents/{id}/otp", "Create or replace a demo challenge and return the plaintext once"],
  [
    "post",
    "/intents/{id}/verify-otp",
    "Verify transaction-bound OTP",
    object({ challengeId: { type: "string", format: "uuid" }, code: { type: "string", pattern: "^[0-9]{6}$" } }),
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
  ["get", "/money-requests/by-request/{id}", "Recover an owned money request by original request ID"],
  ["post", "/money-requests/intents", "Create a VND demo withdrawal intent; deposits use card top-up routes",
    object({ requestId: id, accountId: id, amountMinor: integer, currency: text,
      type: { const: "WITHDRAWAL" } })],
  ["post", "/external-transfers", "Process a VND transfer using the selected destination bank in the simulated settlement flow; no external bank rail is connected",
    object({ requestId: id, accountId: id, amountMinor: integer, currency: text,
      bankCode: { enum: ["VCB", "BIDV", "ICB", "TCB", "MB"] },
      beneficiaryAccount: { enum: ["0000000001", "0000000002"] } })],
  ["get", "/demo-movements/by-request/{id}", "Recover an owned demo movement by original request ID"],
  ["post", "/demo-movements/{id}/commit", "Consume OTP and required face step-up, start simulated processing"],
  ["post", "/demo-movements/{id}/reconcile", "Resolve simulated processing and post balanced demo ledger if successful"],
  ["post", "/chatbot", "Authenticate customer and reply with optional allowlisted navigation",
    object({ message: { type: "string", minLength: 1, maxLength: 300 } })],
  ["get", "/notifications", "Owned notifications"],
  ["post", "/notifications/{id}/read", "Acknowledge notification"],
  [
    "post",
    "/ekyc",
    "Create or resume a consented draft",
    object({ consented: { type: "boolean", const: true } }),
  ],
  ["get", "/ekyc", "Owned requests with mode; active drafts follow current captureMode, submitted requests retain their mode"],
  [
    "post",
    "/ekyc/{id}/uploads",
    "Issue private S3 upload",
    object(
      {
        mime: { enum: ["image/jpeg", "image/png"] },
        bytes: { type: "integer", minimum: 1, maximum: 5000000 },
        index: { type: "integer", minimum: 0, maximum: 2 },
      },
      ["mime", "bytes"],
    ),
  ],
  [
    "post",
    "/uploads/{id}/confirm",
    "Validate immutable uploaded object version",
  ],
  ["post", "/ekyc/{id}/submit", "FULL_ID screens confirmed CCCD front/back and selfie; DEMO_SELFIE_ONLY screens confirmed selfie only without identity verification. VERIFIED activates customer; REVIEWING can be retried after provider failure"],
  ["get", "/ekyc/{id}/evidence/{index}", "Temporary download of owner evidence"],
  ["get", "/bills", "Owned invoices"],
  ["get", "/bills/lookup/{ref}", "Look up an invoice ID or reference within the caller's invoices"],
  ["get", "/payment-intents/by-request/{id}", "Recover the Stripe checkout intent by request ID"],
  ["post", "/topups/intents", "Create a VND card top-up intent with policy-required OTP and face verification",
    { requestBody: object({ accountId: id, amountMinor: { type: "integer", minimum: 1 }, currency: { const: "VND" }, requestId: id }) }],
  ["get", "/topup-intents/by-request/{id}", "Recover an owned VND top-up intent by request ID"],
  ["post", "/topups", "Create or recover a Stripe test PaymentIntent for an authorized VND top-up",
    { requestBody: object({ accountId: id, requestId: id }) }],
  ["get", "/topups/{id}", "Read authoritative top-up settlement status"],
  [
    "post",
    "/payments/intents",
    "Freeze server-priced invoice for OTP",
    payment,
  ],
  ["post", "/payments", "Create/retry test PaymentIntent after OTP and policy-required face proof", payment],
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
      customerDetails: { ...text, description: "For TOPUP, enter the recipient phone number as 10 digits starting with 0." },
      expectedAmountMinor: { type: "integer", minimum: 1 },
      expectedCurrency: { type: "string", pattern: "^[A-Z]{3}$" },
    }, ["requestId", "productId", "quantity", "customerDetails"]),
  ],
  ["get", "/orders", "Owned orders with payment and fulfillment status"],
  ["get", "/orders/{id}", "Owned order with payment and fulfillment status"],
  ["get", "/orders/by-request/{id}", "Recover an owned order by original request ID"],
  ["get", "/officer/rate-types", "List bank-wide savings and loan rate categories with RATE_UPDATE permission"],
  ["get", "/officer/rate-policies", "List stored bank-wide rate policy versions with RATE_UPDATE permission"],
  ["get", "/branches", "Active branch directory"],
  [
    "post",
    "/branches/routes",
    "Walking route to a selected active branch or the shortest returned walking route among up to 20 active valid branches",
    object(
      {
        latitude: { type: "number", minimum: -90, maximum: 90 },
        longitude: { type: "number", minimum: -180, maximum: 180 },
        branchId: { type: "string", minLength: 1, maxLength: 1500 },
      },
      ["latitude", "longitude"],
    ),
  ],
  ["get", "/officer/customers", "Permission CUSTOMER_ADMIN required"],
  ["get", "/officer/checking-accounts", "Permission CUSTOMER_ADMIN required; paginated checking accounts; q matches exact account ID or normalized owner-name prefix"],
  ["get", "/officer/customers/{id}", "Permission CUSTOMER_ADMIN required; editable contact fields only; excludes CCCD"],
  [
    "post",
    "/officer/customers",
    "Provision onboarding customer",
    object({
      requestId: id,
      email: { type: "string", format: "email" },
      fullName: text,
      phoneNumber: { type: "string", pattern: "^(0[0-9]{9}|\\+84[0-9]{9})$" },
      nationalIdNumber: { type: "string", pattern: "^[0-9]{12}$" },
      customerNo: text,
      address: text,
    }, ["requestId", "email", "fullName", "phoneNumber", "nationalIdNumber"]),
  ],
  ["get", "/officer/customers/{id}/changes", "Permission CUSTOMER_ADMIN required; paginated before/after history"],
  [
    "patch",
    "/officer/customers/{id}",
    "Controlled contact/profile change with before/after and reason",
    object({
      fullName: text,
      contactEmail: { type: "string", format: "email" },
      contactPhone: { type: "string", pattern: "^\\+[1-9][0-9]{7,14}$" },
      address: text,
      reason: text,
    }, ["reason"]),
  ],
  ["get", "/officer/ekyc", "Permission EKYC_REVIEW required"],
  ["get", "/officer/audit", "Permission AUDIT_READ required"],
  [
    "post",
    "/officer/rate-policies",
    "Create future-effective bank-wide savings or loan rate version",
    object({
      rateType: { type: "string", enum: ["SAVINGS", "LOAN"] },
      annualRateBasisPoints: { type: "integer", minimum: 0, maximum: 10000 },
      effectiveFrom: integer,
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
    schema: m[1] === "index" ? { type: "integer", minimum: 0, maximum: 2 } : id,
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
const jsonResult = (schema: unknown, description: string) => ({
  description, content: { "application/json": { schema } },
});
paths["/intents/{id}/otp"].post.responses["200"] = jsonResult(object({
  method: { const: "DEMO_DISPLAY_CODE" }, challengeId: { type: "string", format: "uuid" },
  code: { type: "string", pattern: "^[0-9]{6}$" }, expiresAt: integer,
  status: { const: "AWAITING_CODE" },
}), "Plaintext is returned only when the challenge is created");
paths["/intents/{id}/verify-otp"].post.responses["200"] = jsonResult(object({
  authorized: { const: true }, status: { const: "VERIFIED" },
}), "Authorization is available for a single commit");
const intentStatus = {
  id, intentId: id, kind: { enum: ["TRANSFER", "PAYMENT", "DEMO_MOVEMENT"] }, sourceId: text,
  target: object({ kind: { enum: ["ACCOUNT", "BILL"] }, id }),
  amountMinor: integer, feeMinor: integer, currency: text,
  expiresAt: integer, faceRequired: { type: "boolean" },
  faceStatus: { enum: ["REQUIRED", "VERIFIED"] },
  challengeStatus: { enum: ["REQUIRED", "AWAITING_CODE", "VERIFIED", "EXPIRED", "LOCKED", "USED", "CANCELLED"] },
  status: { enum: ["CREATED", "EXPIRED", "CANCELLED", "COMMIT_PENDING", "COMMITTED", "FAILED"] },
  failureCode: text,
  demo: { type: "boolean" },
  providerReference: { type: ["string", "null"] },
};
paths["/intents/{id}"].get.responses["200"] = jsonResult(
  object(intentStatus, Object.keys(intentStatus).filter((field) => !["failureCode", "demo", "providerReference"].includes(field))),
  "Authoritative status for timeout recovery",
);
for (const path of ["/auth/face-login", "/intents/{id}/face"]) {
  const operation = paths[path].post;
  operation.requestBody = {
    required: true,
    description: "Raw JPEG or PNG bytes, maximum 5,000,000 bytes",
    content: {
      "image/jpeg": { schema: { type: "string", format: "binary" } },
      "image/png": { schema: { type: "string", format: "binary" } },
    },
  };
  operation.responses["413"] = { description: "INVALID_BYTES" };
  operation.responses["415"] = { description: "INVALID_MIME" };
  operation.responses["422"] = { description: "INVALID_IMAGE, FACE_NOT_FOUND, MULTIPLE_FACES, FACE_BLURRY, FACE_EXPOSURE, FACE_TOO_SMALL, FACE_CROPPED or other quality errors" };
  operation.responses["429"] = { description: "RATE_LIMITED or FACE_BUSY" };
}
paths["/auth/face-login"].post.security = [];
paths["/auth/face-login"].post.responses["401"] = { description: "FACE_LOGIN_FAILED" };
paths["/auth/face-login"].post.responses["200"] = jsonResult(object({ customToken: text }), "Pass to Firebase Auth signInWithCustomToken");
paths["/intents/{id}/face"].post.responses["200"] = jsonResult(object({
  status: { const: "VERIFIED" }, expiresAt: integer,
}), "Face verification is held server-side for this intent");
for (const path of ["/intents/{id}/otp", "/intents/{id}/verify-otp", "/transfers/{id}/commit", "/payments"]) {
  paths[path].post.responses["410"] = { description: "EXPIRED or OTP_EXPIRED" };
  paths[path].post.responses["429"] = { description: "RATE_LIMITED or OTP_LOCKED" };
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
  servers: [
    { url: process.env.PUBLIC_API_URL ?? "https://api.example.com" },
  ],
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
