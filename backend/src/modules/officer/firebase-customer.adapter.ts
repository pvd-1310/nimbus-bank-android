import type { Auth } from "firebase-admin/auth";
import type { Banking } from "../../core/banking.js";
import type { Actor } from "../auth/auth.types.js";
import type { Doc } from "../../core/store.js";
import { provisionCustomer } from "./customer-provisioning.service.js";
export function createCustomerProvisioner(bank: Banking, auth: Auth) {
  const identities = {
    exists: async (uid: string) => {
      try {
        await auth.getUser(uid);
        return true;
      } catch (e) {
        if ((e as { code?: string }).code === "auth/user-not-found")
          return false;
        throw e;
      }
    },
    create: async (uid: string, email: string) => {
      await auth.createUser({ uid, email });
    },
    ensureClaims: async (uid: string) => {
      await auth.setCustomUserClaims(uid, {
        role: "CUSTOMER",
        permissions: [],
      });
    },
  };
  return (actor: Actor, input: Doc) =>
    provisionCustomer(bank, identities, actor, input);
}
