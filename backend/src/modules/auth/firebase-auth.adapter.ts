import type { Auth } from "firebase-admin/auth";
import { check } from "../../shared/errors.js";
export function createFirebaseAuthenticator(auth: Auth) {
  return async (token: string) => {
    const decoded = await auth.verifyIdToken(token);
    check(
      decoded.role === "CUSTOMER" || decoded.role === "OFFICER",
      "FORBIDDEN",
      403,
    );
    return {
      uid: decoded.uid,
      role: decoded.role,
      permissions: Array.isArray(decoded.permissions)
        ? decoded.permissions.filter((x: unknown) => typeof x === "string")
        : [],
    };
  };
}

export function createRegistrationIdentityVerifier(auth: Auth) {
  return async (token: string) => {
    const identity = await auth.verifyIdToken(token, true);
    return {
      uid: identity.uid,
      email: identity.email,
      emailVerified: identity.email_verified === true,
      role: identity.role,
    };
  };
}
