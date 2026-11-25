import { Banking } from "../../core/banking.js";
import type { Doc } from "../../core/store.js";
import { digest } from "../../shared/crypto.js";
import { check, Fault, key } from "../../shared/errors.js";
import { releaseContactPhone, reserveContactPhone } from "../profile/contact-phone-reservations.js";
import { normalizeSearchText } from "../../shared/search.js";
import { type Actor } from "../auth/auth.types.js";
interface IdentityAdmin {
  exists: (uid: string) => Promise<boolean>;
  create: (
    uid: string,
    email: string,
  ) => Promise<void>;
  ensureClaims: (uid: string) => Promise<void>;
}
export async function provisionCustomer(
  bank: Banking,
  auth: IdentityAdmin,
  a: Actor,
  input: Doc,
) {
  check(/^\d{12}$/.test(String(input.nationalIdNumber ?? "")), "INVALID_NATIONAL_ID");
  key(input.requestId);
  const uid = "customer_" + digest([a.uid, input.requestId]).slice(0, 40),
    accountId = "acct_" + digest(["checking", uid]).slice(0, 24),
    detailsPath = "checkingDetails/" + accountId,
    checkingDetails = { accountId, overdraftPolicy: "NONE" },
    hash = digest(input),
    customerNoKey = input.customerNo
      ? "customerNumberReservations/" + digest(String(input.customerNo).trim().toLowerCase())
      : undefined,
    path = "provisionRequests/" + uid;
  const completedStatus = await bank.store.run(async (t) => {
    await bank.authorize(t, a, "CUSTOMER_ADMIN");
    const old = await t.get(path);
    if (old) check(old.digest === hash, "IDEMPOTENCY_CONFLICT", 409);
    if (old?.status === "COMPLETE") {
      const user = await t.get("users/" + uid);
      const account = await t.get("accounts/" + accountId);
      if (account?.ownerUid === uid && account.type === "CHECKING" && !(await t.get(detailsPath)))
        t.set(detailsPath, checkingDetails);
      return user?.status ?? "ONBOARDING";
    }
    if (customerNoKey) {
      const reservation = await t.get(customerNoKey);
      check(!reservation || reservation.uid === uid || reservation.status === "RELEASED", "CUSTOMER_NUMBER_EXISTS", 409);
      if (!reservation || reservation.status === "RELEASED") {
        const duplicate = await t.list("customers", { customerNo: input.customerNo }, { limit: 1 });
        check(duplicate.length === 0, "CUSTOMER_NUMBER_EXISTS", 409);
        t.set(customerNoKey, { uid, customerNo: input.customerNo, status: "RESERVED", createdAt: bank.now() });
      }
    }
    if (input.phoneNumber) await reserveContactPhone(t, uid, String(input.phoneNumber), bank.now());
    t.set(path, {
      digest: hash,
      status: "PENDING",
      actorUid: a.uid,
      createdAt: bank.now(),
    });
    return null;
  });
  if (completedStatus) {
    await auth.ensureClaims(uid);
    return { uid, accountId, status: completedStatus };
  }
  if (!(await auth.exists(uid))) {
    try {
      await auth.create(
        uid,
        String(input.email),
      );
    } catch {
      await bank.store.run(async (t) => {
        const old = await t.get(path);
        t.set(path, { ...old, status: "REPAIR_REQUIRED" });
        if (customerNoKey) {
          const reservation = await t.get(customerNoKey);
          if (reservation?.uid === uid) t.set(customerNoKey, { ...reservation, status: "RELEASED", releasedAt: bank.now() });
        }
        await releaseContactPhone(t, uid, input.phoneNumber ? String(input.phoneNumber) : undefined, bank.now());
      });
      throw new Fault("PROVISION_IDENTITY_FAILED", 409);
    }
  }
  await auth.ensureClaims(uid);
  await bank.store.run(async (t) => {
    await bank.authorize(t, a, "CUSTOMER_ADMIN");
    const old = await t.get(path),
      u = await t.get("users/" + uid);
    if (old?.status === "COMPLETE") return;
    check(!u, "PROFILE_CONFLICT", 409);
    const existingAccount = await t.get("accounts/" + accountId);
    check(!existingAccount || existingAccount.ownerUid === uid, "ACCOUNT_CONFLICT", 409);
    const existingDetails = await t.get(detailsPath);
    t.set("users/" + uid, {
      uid,
      role: "CUSTOMER",
      active: false,
      status: "ONBOARDING",
      fullName: input.fullName,
      emailMasked: input.email.replace(/^(.).*(@.*)$/, "$1***$2"),
      phoneMasked: "••••" + String(input.phoneNumber ?? "").slice(-4),
      contactPhone: String(input.phoneNumber ?? ""),
      primaryCheckingAccountId: accountId,
      createdAt: bank.now(),
      createdBy: a.uid,
    });
    t.set("customers/" + uid, {
      uid,
      uidRef: uid,
      customerNo: input.customerNo ?? null,
      nationalIdNumber: String(input.nationalIdNumber),
      address: input.address ?? null,
      kycStatus: "NOT_SUBMITTED",
      status: "ONBOARDING",
      createdBy: a.uid,
    });
    t.set("accounts/" + accountId, {
      accountId,
      ownerUid: uid,
      type: "CHECKING",
      ownerNameSearch: normalizeSearchText(String(input.fullName)),
      currency: "VND",
      status: "ACTIVE",
      availableMinor: 0,
      bookedMinor: 0,
      balanceMinor: 0,
      version: 0,
      createdAt: bank.now(),
      createdBy: a.uid,
    });
    if (!existingDetails) t.set(detailsPath, checkingDetails);
    t.set(path, { ...old, status: "COMPLETE", completedAt: bank.now() });
    bank.audit(t, a, "CUSTOMER_PROVISION", uid, input.requestId);
  });
  return { uid, accountId, status: "ONBOARDING" };
}
