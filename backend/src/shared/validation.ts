import { z } from "zod";
export const id = z.string().regex(/^[A-Za-z0-9_-]{1,100}$/);
export const amount = z.number().int().positive().max(Number.MAX_SAFE_INTEGER);
export const currency = z.string().regex(/^[A-Z]{3}$/);
export const paymentRequest = z.union([
  z
    .object({ billOrOrderRef: id, requestId: id, sourceAccountId: id.optional(), paymentMethod: z.enum(["STRIPE_CARD", "NIMBUS_ACCOUNT"]).optional() })
    .strict()
    .transform((v) => ({ billId: v.billOrOrderRef, requestId: v.requestId, sourceAccountId: v.sourceAccountId, paymentMethod: v.paymentMethod ?? "STRIPE_CARD" })),
  z.object({ billId: id, requestId: id, sourceAccountId: id.optional(), paymentMethod: z.enum(["STRIPE_CARD", "NIMBUS_ACCOUNT"]).optional() }).strict().transform((v) => ({ ...v, paymentMethod: v.paymentMethod ?? "STRIPE_CARD" })),
]);
