import type { AppDependencies } from "../../core/http.js";
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

export function createFirebaseEmailOtpAccounts(auth: Auth): Required<Pick<AppDependencies,
  "markRegistrationEmailVerified" | "lookupPasswordResetUser" | "updatePasswordAfterEmailOtp">> {
  return {
    markRegistrationEmailVerified: async (uid, email) => {
      const account = await auth.getUser(uid);
      check(!account.disabled && account.email === email && account.customClaims?.role === undefined, "FORBIDDEN", 403);
      await auth.updateUser(uid, { emailVerified: true });
    },
    lookupPasswordResetUser: async (email) => {
      try {
        const account = await auth.getUserByEmail(email);
        return account.disabled || !account.providerData.some((provider) => provider.providerId === "password")
          ? null : account.uid;
      } catch (error) {
        if (typeof error === "object" && error !== null && "code" in error && error.code === "auth/user-not-found") return null;
        throw error;
      }
    },
    updatePasswordAfterEmailOtp: async (uid, password, matchesEmail) => {
      const account = await auth.getUser(uid);
      check(!account.disabled && account.email && matchesEmail(account.email) &&
        account.providerData.some((provider) => provider.providerId === "password"), "INVALID_OTP", 400);
      await auth.updateUser(uid, { password });
    },
  };
}
