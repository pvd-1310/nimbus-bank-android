import { Banking } from "../../core/banking.js";
import type { Doc } from "../../core/store.js";
import { digest } from "../../shared/crypto.js";
import { check, key } from "../../shared/errors.js";
import { type Actor } from "../auth/auth.types.js";
interface IdentityAdmin {
  exists: (uid: string) => Promise<boolean>;
  create: (uid: string, email: string) => Promise<void>;
  ensureClaims: (uid: string) => Promise<void>;
}
export async function provisionCustomer(
  bank: Banking,
  auth: IdentityAdmin,
  a: Actor,
  input: Doc,
) {
  key(input.requestId);
  const uid = "customer_" + digest([a.uid, input.requestId]).slice(0, 40),
    hash = digest(input),
    path = "provisionRequests/" + uid;
  const completed = await bank.store.run(async (t) => {
    await bank.authorize(t, a, "CUSTOMER_ADMIN");
    const old = await t.get(path);
    if (old) check(old.digest === hash, "IDEMPOTENCY_CONFLICT", 409);
    if (old?.status === "COMPLETE") return true;
    t.set(path, {
      digest: hash,
      status: "PENDING",
      actorUid: a.uid,
      createdAt: bank.now(),
    });
    return false;
  });
  if (completed) {
    await auth.ensureClaims(uid);
    return { uid, status: "ONBOARDING" };
  }
  if (!(await auth.exists(uid))) {
    try {
      await auth.create(uid, input.email);
    } catch {
      await bank.store.run(async (t) => {
        const old = await t.get(path);
        t.set(path, { ...old, status: "REPAIR_REQUIRED" });
      });
      throw Error("PROVISION_IDENTITY_FAILED");
    }
  }
  await auth.ensureClaims(uid);
  await bank.store.run(async (t) => {
    await bank.authorize(t, a, "CUSTOMER_ADMIN");
    const old = await t.get(path),
      u = await t.get("users/" + uid);
    if (old?.status === "COMPLETE") return;
    check(!u, "PROFILE_CONFLICT", 409);
    t.set("users/" + uid, {
      uid,
      role: "CUSTOMER",
      active: true,
      status: "ONBOARDING",
      fullName: input.fullName,
      emailMasked: input.email.replace(/^(.).*(@.*)$/, "$1***$2"),
      phoneMasked: null,
      createdAt: bank.now(),
      createdBy: a.uid,
    });
    t.set("customers/" + uid, {
      uid,
      uidRef: uid,
      customerNo: input.customerNo ?? null,
      address: input.address ?? null,
      kycStatus: "NOT_SUBMITTED",
      status: "ONBOARDING",
      createdBy: a.uid,
    });
    t.set(path, { ...old, status: "COMPLETE", completedAt: bank.now() });
    bank.audit(t, a, "CUSTOMER_PROVISION", uid, input.requestId);
  });
  return { uid, status: "ONBOARDING" };
}
