import { z } from "zod";
import type { AppDependencies, Route } from "../../core/http.js";
import { Fault } from "../../shared/errors.js";
import { amount, currency, id } from "../../shared/validation.js";
const common = { requestId: id, amountMinor: amount, currency };
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
    .object({ ...common, sourceAccountId: id, destinationPhone: z.string().regex(/^(?:0[0-9]{9}|\+84[0-9]{9})$/) })
    .strict()
    .transform((x) => ({ ...x, destinationAccountId: x.destinationPhone }))
    .or(z.object({ ...common, sourceAccountId: id, destinationAccountId: id }).strict())
]);
export function registerTransfersRoutes(route: Route, d: AppDependencies) {
  route("post", "/transfers/intents", (a, r) =>
    d.bank.transfers.intent(a, transferRequest.parse(r.body)),
  );
  route("get", "/intents/:id", (a, r) => d.bank.security.describe(a, String(r.params.id)));
  route("post", "/intents/:id/cancel", (a, r) => d.bank.security.cancel(a, String(r.params.id)));
  route("post", "/intents/:id/otp", (a, r) => d.bank.security.challenge(a, String(r.params.id)));
  route("post", "/intents/:id/verify-otp", (a, r) => {
    const body = z.object({ challengeId: z.uuid(), code: z.string().regex(/^\d{6}$/) }).strict().parse(r.body);
    return d.bank.security.verify(a, String(r.params.id), body.challengeId, body.code);
  });
  route("post", "/transfers/:id/commit", (a, r) =>
    d.bank.transfers.commit(a, String(r.params.id)),
  );
  route("post", "/external-transfers", async () => {
    throw new Fault("SETUP_REQUIRED", 503);
  });
}
