import { describe, expect, it } from "vitest";
import { config } from "../src/core/config.js";
import { MemoryStore } from "./support/memory-store.js";
describe("runtime", () => {
  it("paginates a collection beyond 500 records without losing records", async () => {
    const s = new MemoryStore();
    await s.run(async (t) => {
      for (let i = 0; i < 510; i++)
        t.set("rows/" + String(i).padStart(4, "0"), { ownerUid: "a" });
    });
    const first = await s.run((t) =>
      t.list("rows", { ownerUid: "a" }, { limit: 100 }),
    );
    expect(first).toHaveLength(100);
    const next = await s.run((t) =>
      t.list(
        "rows",
        { ownerUid: "a" },
        { limit: 100, after: first.at(-1)!.id },
      ),
    );
    expect(next[0].id).toBe("0100");
  });
  it("rejects partial emulator wiring", () =>
    expect(() =>
      config({
        APP_MODE: "emulator",
        FIREBASE_AUTH_EMULATOR_HOST: "127.0.0.1:9099",
      }),
    ).toThrow());
  it("requires explicit connected project", () =>
    expect(() => config({ APP_MODE: "connected" })).toThrow());
  it("rolls back failed transactions", async () => {
    const s = new MemoryStore();
    await expect(
      s.run(async (t) => {
        t.set("accounts/a", { amount: 3 });
        throw Error("rollback");
      }),
    ).rejects.toThrow();
    expect(await s.run((t) => t.get("accounts/a"))).toBeUndefined();
  });
  it("serializes concurrent updates", async () => {
    const s = new MemoryStore();
    await s.run(async (t) => t.set("accounts/a", { n: 0 }));
    await Promise.all(
      Array.from({ length: 10 }, () =>
        s.run(async (t) => {
          const d = await t.get("accounts/a");
          t.set("accounts/a", { n: d!.n + 1 });
        }),
      ),
    );
    expect((await s.run((t) => t.get("accounts/a")))!.n).toBe(10);
  });
});
