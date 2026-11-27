import { createHash } from "node:crypto";
export const digest = (value: unknown) =>
  createHash("sha256").update(JSON.stringify(value)).digest("hex");

export const generateAccountNumber = (accountId: string) => {
  const n = BigInt("0x" + digest(["account-number", accountId]).slice(0, 12)) % 100_000_000n;
  return "1903" + n.toString().padStart(8, "0");
};
