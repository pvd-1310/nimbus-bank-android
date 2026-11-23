import { z } from "zod";
import type { AppDependencies, Route } from "../../core/http.js";
import type { Tx, Doc } from "../../core/store.js";
import { digest } from "../../shared/crypto.js";
import { check } from "../../shared/errors.js";
import { page, queryOptions } from "../../shared/pagination.js";
import { id } from "../../shared/validation.js";
async function orderStatus(tx: Tx, order: Doc, now: number) {
  const bill = await tx.get("bills/" + order.id);
  const lock = bill?.status === "UNPAID" ? await tx.get("billPaymentLocks/" + order.id) : undefined;
  const paymentStatus = bill?.status === "PAID" ? "PAID"
    : lock && lock.released !== true ? "PENDING"
    : order.quoteExpiresAt <= now ? "QUOTE_EXPIRED" : "UNPAID";
  return { ...order, paymentStatus };
}

export function registerOrdersRoutes(route: Route, d: AppDependencies) {
  route("get", "/catalog", async (_a, r) =>
    d.bank.store.run(async (t) =>
      page(
        await t.list("catalog", {
          active: true,
          ...(r.query.serviceType ? { serviceType: z.enum([
            "ELECTRICITY", "WATER", "TOPUP", "FLIGHT", "MOVIE", "HOTEL", "ECOMMERCE",
          ]).parse(r.query.serviceType) } : {}),
        }, queryOptions(r.query)),
        r.query,
      ),
    ),
  );
  route("post", "/orders", async (a, r) => {
    const x = z
      .object({
        requestId: id,
        productId: id,
        quantity: z.number().int().min(1).max(10),
        customerDetails: z.string().min(1).max(500),
        expectedAmountMinor: z.number().int().positive().optional(),
        expectedCurrency: z.string().regex(/^[A-Z]{3}$/).optional(),
      })
      .strict()
      .parse(r.body);
    return d.bank.store.run(async (t) => {
      await d.bank.authorize(t, a);
      const oid = digest([a.uid, "ORDER", x.requestId]),
        old = await t.get("orders/" + oid),
        hash = digest(x);
      if (old) {
        check(old.digest === hash, "IDEMPOTENCY_CONFLICT", 409);
        return old;
      }
      const product = await t.get("catalog/" + x.productId);
      check(product?.active && product.demo === true, "PRODUCT_UNAVAILABLE");
      const amountMinor = product.amountMinor * x.quantity;
      if (x.expectedAmountMinor !== undefined || x.expectedCurrency !== undefined)
        check(amountMinor === x.expectedAmountMinor && product.currency === x.expectedCurrency, "REQUOTE_REQUIRED", 409);
      const quoteExpiresAt = d.bank.now() + 15 * 60_000;
      check(Number.isSafeInteger(amountMinor) && amountMinor > 0, "INVALID_AMOUNT");
      const order = {
        id: oid,
        orderId: oid,
        ownerUid: a.uid,
        ...x,
        digest: hash,
        serviceType: product.serviceType,
        amountMinor,
        currency: product.currency,
        fulfillmentStatus: "AWAITING_PAYMENT",
        demo: true,
        createdAt: d.bank.now(),
        quoteRef: digest([
          x.productId,
          x.quantity,
          amountMinor,
          product.currency,
        ]),
        paymentId: null,
        quoteExpiresAt,
      };
      t.set("orders/" + oid, order);
      t.set("bills/" + oid, {
        ownerUid: a.uid,
        orderId: oid,
        amountMinor,
        currency: product.currency,
        status: "UNPAID",
        demo: true,
        invoiceRef: oid,
        provider: "DEMO",
        dueAt: null,
        quoteExpiresAt,
        serviceType: product.serviceType,
      });
      return order;
    });
  });
  route("get", "/orders/by-request/:id", (a, r) =>
    d.bank.scoped(a, "orders", digest([a.uid, "ORDER", id.parse(r.params.id)])),
  );
  route("get", "/orders/:id", (a, r) =>
    d.bank.store.run(async (t) => {
      await d.bank.authorize(t, a);
      const oid = id.parse(r.params.id);
      const order = await t.get("orders/" + oid);
      check(order, "NOT_FOUND", 404);
      check(order.ownerUid === a.uid, "FORBIDDEN", 403);
      return orderStatus(t, { ...order, id: oid }, d.bank.now());
    }),
  );
  route("get", "/orders", async (a, r) =>
    d.bank.store.run(async (t) => {
      await d.bank.authorize(t, a);
      const orders = page(await t.list("orders", { ownerUid: a.uid }, queryOptions(r.query)), r.query);
      return {
        ...orders,
        items: await Promise.all(orders.items.map((order) => orderStatus(t, order, d.bank.now()))),
      };
    }),
  );
}
