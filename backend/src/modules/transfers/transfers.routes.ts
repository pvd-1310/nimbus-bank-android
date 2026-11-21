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
    .object({ ...common, sourceAccountId: id, destinationAccountId: id })
    .strict(),
]);
export function registerTransfersRoutes(route: Route, d: AppDependencies) {
  route("post", "/transfers/intents", (a, r) =>
    d.bank.transfers.intent(a, transferRequest.parse(r.body)),
  );
  route("get", "/intents/:id", async (a, r) =>
    d.bank.store.run(async (t) => {
      const i = await d.bank.transfers.getIntent(t, a, String(r.params.id));
      return {
        id: r.params.id,
        intentId: i.intentId ?? r.params.id,
        sourceId: i.sourceId,
        target: i.target,
        status: i.status,
        expiresAt: i.expiresAt,
      };
    }),
  );
  route("post", "/intents/:id/otp", (a, r) =>
    d.bank.transfers.challenge(a, String(r.params.id)),
  );
  route("post", "/intents/:id/verify-otp", (a, r) =>
    d.bank.transfers.verify(
      a,
      String(r.params.id),
      z
        .object({ code: z.string().min(1).max(20) })
        .strict()
        .parse(r.body).code,
    ),
  );
  route("post", "/transfers/:id/commit", (a, r) =>
    d.bank.transfers.commit(a, String(r.params.id)),
  );
  route("post", "/external-transfers", async () => {
    throw new Fault("SETUP_REQUIRED", 503);
  });
}
