import { expect, it } from "vitest";
import { preserveExisting, preserveSeedAccount, seedClaims } from "../src/scripts/seed-preserve.js";

it("fills missing seed profile fields while preserving existing customer information", () => {
  expect(preserveExisting(
    { fullName: "Tên hiện tại", phoneMasked: "•••• 1111", address: "Địa chỉ hiện tại" },
    { fullName: "Tên seed", phoneMasked: "•••• 2222", customerNo: "KH-1" },
  )).toEqual({ fullName: "Tên hiện tại", phoneMasked: "•••• 1111", address: "Địa chỉ hiện tại", customerNo: "KH-1" });
});

it("keeps existing seeded account data and balances while filling only its search index", () => {
  expect(preserveSeedAccount(
    { ownerUid: "real-owner", type: "CHECKING", availableMinor: 700000, ownerNameSearch: undefined },
    { ownerUid: "seed-owner", type: "CHECKING", availableMinor: 1000, status: "ACTIVE" },
    "real owner",
  )).toEqual({ ownerUid: "real-owner", type: "CHECKING", availableMinor: 700000, status: "ACTIVE", ownerNameSearch: "real owner" });
});

it("preserves existing authentication claims and fills only missing role metadata", () => {
  expect(seedClaims({ role: "CUSTOMER", permissions: ["CUSTOMER_ADMIN"], region: "north" }, "OFFICER", ["AUDIT_READ"]))
    .toEqual({ role: "CUSTOMER", permissions: ["CUSTOMER_ADMIN"], region: "north" });
  expect(seedClaims({ region: "north" }, "OFFICER", ["AUDIT_READ"]))
    .toEqual({ role: "OFFICER", permissions: ["AUDIT_READ"], region: "north" });
});
