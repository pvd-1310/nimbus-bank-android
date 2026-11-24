import { z } from "zod";
import type { AppDependencies, Route } from "../../core/http.js";
import { digest } from "../../shared/crypto.js";
import { check } from "../../shared/errors.js";
import { amount, currency, id } from "../../shared/validation.js";
const common = { requestId: id, amountMinor: amount, currency };
const phone = z.string().trim().regex(/^(?:0[0-9]{9}|\+84[0-9]{9})$/);
const transferRequest = z.union([
  z
    .object({
      ...common,
      sourceId: id,
      target: z.object({ kind: z.literal("ACCOUNT"), id }).strict(),
    })
    .strict()
    .transform((x) => ({
      requestId: x.requestId,
      sourceAccountId: x.sourceId,
      destinationAccountId: x.target.id,
      amountMinor: x.amountMinor,
      currency: x.currency,
    })),
  z
    .object({ ...common, sourceAccountId: id, destinationPhone: phone, recipientAccountId: id.optional() })
    .strict()
    .transform((x) => ({ ...x, destinationAccountId: x.destinationPhone }))
    .or(z.object({ ...common, sourceAccountId: id, destinationAccountId: id }).strict())
]);
export function registerTransfersRoutes(route: Route, d: AppDependencies) {
  route("post", "/transfers/recipient", (a, r) => {
    const input = z.object({ sourceAccountId: id, destinationPhone: phone }).strict().parse(r.body);
    check(d.stripeSandboxMovements, "SETUP_REQUIRED", 503);
    return d.stripeSandboxMovements.lookupRecipient(a, input.sourceAccountId, input.destinationPhone);
  });
  route("post", "/transfers/intents", (a, r) => {
    const input = transferRequest.parse(r.body);
    if (input.currency !== "USD") return d.bank.transfers.intent(a, input);
    check(d.stripeSandboxMovements, "SETUP_REQUIRED", 503);
    return d.stripeSandboxMovements.prepare(a, "TRANSFER", input);
  });
  route("get", "/transfers/by-request/:id", (a, r) =>
    d.bank.security.describe(a, digest([a.uid, "TRANSFER", id.parse(r.params.id)])),
  );
  route("get", "/intents/:id", (a, r) => d.bank.security.describe(a, String(r.params.id)));
  route("post", "/intents/:id/cancel", (a, r) => d.bank.security.cancel(a, String(r.params.id)));
  route("post", "/intents/:id/otp", (a, r) => d.bank.security.challenge(a, String(r.params.id)));
  route("post", "/intents/:id/verify-otp", (a, r) => {
    const body = z.object({ challengeId: z.uuid(), code: z.string().regex(/^\d{6}$/) }).strict().parse(r.body);
    return d.bank.security.verify(a, String(r.params.id), body.challengeId, body.code);
  });
  route("post", "/transfers/:id/commit", async (a, r) => {
    const id = String(r.params.id);
    const intent = await d.bank.security.describe(a, id);
    if (intent.provider !== "STRIPE_TREASURY") return d.bank.transfers.commit(a, id);
    check(d.stripeSandboxMovements, "SETUP_REQUIRED", 503);
    return d.stripeSandboxMovements.commit(a, id, "TRANSFER");
  });
}
