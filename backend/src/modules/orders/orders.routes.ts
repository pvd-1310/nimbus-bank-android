import { z } from "zod";
import type { AppDependencies, Route } from "../../core/http.js";
import { digest } from "../../shared/crypto.js";
import { check } from "../../shared/errors.js";
import { page, queryOptions } from "../../shared/pagination.js";
import { id } from "../../shared/validation.js";
export function registerOrdersRoutes(route: Route, d: AppDependencies) {
  route("get", "/catalog", async (_a, r) =>
    d.bank.store.run(async (t) =>
      page(
        await t.list("catalog", { active: true }, queryOptions(r.query)),
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
      })
      .strict()
      .parse(r.body);
    return d.bank.store.run(async (t) => {
      await d.bank.authorize(t, a);
      const product = await t.get("catalog/" + x.productId);
      check(product?.active && product.demo === true, "PRODUCT_UNAVAILABLE");
      const oid = digest([a.uid, "ORDER", x.requestId]),
        old = await t.get("orders/" + oid),
        hash = digest(x);
      if (old) {
        check(old.digest === hash, "IDEMPOTENCY_CONFLICT", 409);
        return old;
      }
      const amountMinor = product.amountMinor * x.quantity;
      check(Number.isSafeInteger(amountMinor), "INVALID_AMOUNT");
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
      });
      return order;
    });
  });
  route("get", "/orders", async (a, r) =>
    page(await d.bank.listOwned(a, "orders", r.query), r.query),
  );
}
