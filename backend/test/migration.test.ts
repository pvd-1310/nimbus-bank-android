import { expect, it } from "vitest";
import type { Doc, Store } from "../src/core/store.js";
import {
  applyMigrationPlan,
  buildMigrationPlan,
} from "../src/database/migration.js";
// A legacy persistence fixture intentionally bypasses the new write normalizer.
function legacy(
  initial: Record<string, Doc>,
): Store & { data: Map<string, Doc> } {
  const data = new Map(Object.entries(initial));
  return {
    data,
    run: async (work) => {
      const pending = new Map<string, Doc>();
      const result = await work({
        get: async (p) => structuredClone(pending.get(p) ?? data.get(p)),
        list: async () => [],
        set: (p, d) => {
          pending.set(p, structuredClone(d));
        },
      });
      for (const [p, d] of pending) data.set(p, d);
      return result;
    },
  };
}
it("preview does not mutate input and never prints private values", () => {
  const data = { billId: "bill", providerId: "pi_private", amountMinor: 20000 };
  const plan = buildMigrationPlan([{ path: "payments/p", data }]);
  expect(data).not.toHaveProperty("paymentId");
  expect(plan.entries[0].patch).toMatchObject({
    paymentId: "p",
    gateway: "STRIPE",
    billOrOrderRef: "bill",
  });
  expect(plan.summary[0].fields).toContain("providerTxnRef");
  expect(JSON.stringify(plan.summary)).not.toContain("pi_private");
});
it("apply preserves existing values and retry is safe", async () => {
  const store = legacy({
    "accounts/a": { ownerUid: "u", bookedMinor: 123, availableMinor: 123 },
  });
  const plan = buildMigrationPlan([
    { path: "accounts/a", data: store.data.get("accounts/a")! },
  ]);
  await applyMigrationPlan(store, plan, plan.hash);
  expect(store.data.get("accounts/a")).toMatchObject({
    accountId: "a",
    bookedMinor: 123,
  });
  await applyMigrationPlan(store, plan, plan.hash);
  expect(store.data.get("accounts/a")!.availableMinor).toBe(123);
});
it("requires the reviewed plan hash and refuses concurrent data changes", async () => {
  const store = legacy({ "accounts/a": { ownerUid: "u", bookedMinor: 123 } });
  const plan = buildMigrationPlan([
    { path: "accounts/a", data: store.data.get("accounts/a")! },
  ]);
  await expect(applyMigrationPlan(store, plan, "wrong")).rejects.toThrow();
  store.data.set("accounts/a", { ownerUid: "u", bookedMinor: 999 });
  await expect(applyMigrationPlan(store, plan, plan.hash)).rejects.toThrow();
  expect(store.data.get("accounts/a")!.accountId).toBeUndefined();
});
it("reports conflicting field names and missing business data instead of guessing", () => {
  const plan = buildMigrationPlan([
    { path: "customers/c", data: { uid: "u" } },
    {
      path: "ratePolicies/r",
      data: { productId: "one", accountProduct: "two" },
    },
  ]);
  expect(plan.summary[0].missing).toContain("customerNo");
  expect(plan.conflicts).toHaveLength(1);
});
