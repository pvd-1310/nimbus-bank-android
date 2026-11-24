import { z } from "zod";
import type { AppDependencies, Route } from "../../core/http.js";
import { digest } from "../../shared/crypto.js";
import { check } from "../../shared/errors.js";
import { amount, currency, id } from "../../shared/validation.js";
import { rate } from "../security/intent.js";
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
    return d.bank.store.run(async (t) => {
      check(a.role === "CUSTOMER", "FORBIDDEN", 403);
      await d.bank.authorize(t, a);
      const source = await d.bank.owned(t, a, input.sourceAccountId);
      check(source.type === "CHECKING" && source.currency === "VND" && source.status === "ACTIVE", "INVALID_ACCOUNT", 409);
      await rate(t, "transfer-recipient", a.uid, d.bank.now(), 15, 60_000);
      const localPhone = input.destinationPhone.startsWith("+84") ? "0" + input.destinationPhone.slice(3) : input.destinationPhone;
      const [canonical, legacy] = await Promise.all([
        t.list("users", { contactPhone: "+84" + localPhone.slice(1) }, { limit: 2 }),
        t.list("users", { contactPhone: localPhone }, { limit: 2 }),
      ]);
      const users = [...new Map([...canonical, ...legacy].map((user) => [user.id, user])).values()];
      check(users.length === 1, "INVALID_DESTINATION", 404);
      const recipient = users[0];
      check(recipient.role === "CUSTOMER" &&
        (recipient.status === "ACTIVE" ? recipient.active !== false : recipient.status === undefined && recipient.active === true),
        "INVALID_DESTINATION", 404);
      const accounts = await t.list("accounts", { ownerUid: recipient.id, type: "CHECKING", status: "ACTIVE" }, { limit: 2 });
      check(accounts.length === 1 && accounts[0].id !== source.id && accounts[0].currency === "VND", "INVALID_DESTINATION", 404);
      return { accountId: accounts[0].id, fullName: recipient.fullName ?? "" };
    });
  });
  route("post", "/transfers/intents", (a, r) => {
    const input = transferRequest.parse(r.body);
    check(input.currency === "VND", "CURRENCY_NOT_SUPPORTED", 409);
    return d.bank.transfers.intent(a, input);
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
    if (intent.provider === "STRIPE_TREASURY") {
      check(d.stripeSandboxMovements, "SETUP_REQUIRED", 503);
      return d.stripeSandboxMovements.commit(a, id, "TRANSFER");
    }
    return d.bank.transfers.commit(a, id);
  });
}
