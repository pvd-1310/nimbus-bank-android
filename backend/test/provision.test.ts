import { expect, it } from "vitest";
import { Banking } from "../src/core/banking.js";
import { type Actor } from "../src/modules/auth/auth.types.js";
import { provisionCustomer } from "../src/modules/officer/customer-provisioning.service.js";
import { MemoryStore } from "./support/memory-store.js";
it("repairs missing custom claims on provisioning retry", async () => {
  const s = new MemoryStore(),
    b = new Banking(s, { otpSecret: "test", deliver: async () => {} }),
    a: Actor = {
      uid: "officer",
      role: "OFFICER",
      permissions: ["CUSTOMER_ADMIN"],
    };
  await s.run(async (t) => {
    t.set("users/officer", { role: "OFFICER", active: true });
    t.set("officers/officer", {
      active: true,
      permissions: ["CUSTOMER_ADMIN"],
    });
  });
  let exists = false,
    claims = false,
    fail = true;
  const auth = {
    exists: async () => exists,
    create: async () => {
      exists = true;
    },
    ensureClaims: async () => {
      if (fail) {
        fail = false;
        throw Error("temporary claim failure");
      }
      claims = true;
    },
  };
  const input = {
    requestId: "repair",
    email: "test@example.com",
    fullName: "Test User",
    nationalIdNumber: "012345678901",
  };
  await expect(provisionCustomer(b, auth, a, input)).rejects.toThrow();
  await provisionCustomer(b, auth, a, input);
  expect(claims).toBe(true);
});
it("provisioning requires server officer permission and is idempotent", async () => {
  const s = new MemoryStore(),
    b = new Banking(s, { otpSecret: "test", deliver: async () => {} });
  const a: Actor = {
    uid: "officer",
    role: "OFFICER",
    permissions: ["CUSTOMER_ADMIN"],
  };
  await s.run(async (t) => {
    t.set("users/officer", { active: true, role: "OFFICER" });
    t.set("officers/officer", {
      active: true,
      permissions: ["CUSTOMER_ADMIN"],
    });
  });
  const users = new Map<string, unknown>();
  const auth = {
    ensureClaims: async () => {},
    create: async (uid: string, email: string) => {
      users.set(uid, email);
    },
    exists: async (uid: string) => users.has(uid),
  };
  const body = {
    requestId: "request",
    email: "customer@example.com",
    fullName: "Customer",
    nationalIdNumber: "012345678901",
  };
  const r = await provisionCustomer(b, auth, a, body);
  expect(await s.run(t => t.get("checkingDetails/" + r.accountId))).toMatchObject({
    accountId: r.accountId, overdraftPolicy: "NONE",
  });
  expect(await s.run(t => t.get("accounts/" + r.accountId))).toMatchObject({ ownerNameSearch: "customer" });
  expect(await provisionCustomer(b, auth, a, body)).toEqual(r);
  expect(users.size).toBe(1);
  expect((await s.run((t) => t.get("users/" + r.uid)))!.role).toBe("CUSTOMER");
  expect((await s.run((t) => t.get("customers/" + r.uid)))!.nationalIdNumber).toBe("012345678901");
  expect(JSON.stringify(r)).not.toContain("012345678901");
  await expect(
    provisionCustomer(b, auth, { ...a, permissions: [] }, body),
  ).rejects.toThrow();
});

it("rejects malformed CCCD before creating a customer identity", async () => {
  const s = new MemoryStore(), b = new Banking(s, { otpSecret: "test", deliver: async () => {} });
  const a: Actor = { uid: "officer", role: "OFFICER", permissions: ["CUSTOMER_ADMIN"] };
  await s.run(async (t) => {
    t.set("users/officer", { active: true, role: "OFFICER" });
    t.set("officers/officer", { active: true, permissions: ["CUSTOMER_ADMIN"] });
  });
  const auth = { exists: async () => false, create: async () => {}, ensureClaims: async () => {} };
  await expect(provisionCustomer(b, auth, a, { requestId: "bad-cccd", email: "bad@example.com", fullName: "Bad Customer", nationalIdNumber: "123" })).rejects.toThrow();
});
