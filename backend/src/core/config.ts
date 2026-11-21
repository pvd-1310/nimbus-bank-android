import { check } from "../shared/errors.js";
export function config(env: NodeJS.ProcessEnv = process.env) {
  const mode = env.APP_MODE ?? "emulator";
  check(["emulator", "connected"].includes(mode), "INVALID_MODE");
  const auth = env.FIREBASE_AUTH_EMULATOR_HOST,
    firestore = env.FIRESTORE_EMULATOR_HOST;
  if (mode === "emulator")
    check(
      auth &&
        firestore &&
        /^demo-[a-z0-9-]+$/.test(
          env.FIREBASE_PROJECT_ID ?? "demo-mobile-banking",
        ),
      "EMULATORS_REQUIRED",
    );
  else
    check(
      !auth &&
        !firestore &&
        env.FIREBASE_PROJECT_ID &&
        !env.FIREBASE_PROJECT_ID.startsWith("demo-"),
      "EXPLICIT_PROJECT_REQUIRED",
    );
  const otpSecret = env.OTP_SECRET ?? "";
  check(otpSecret.length >= 32, "OTP_SECRET_REQUIRED");
  const port = Number(env.PORT ?? 3000);
  check(Number.isInteger(port) && port > 0 && port < 65536, "INVALID_PORT");
  check(
    !env.STRIPE_SECRET_KEY || env.STRIPE_SECRET_KEY.startsWith("sk_test_"),
    "STRIPE_TEST_KEY_REQUIRED",
  );
  return {
    mode,
    projectId: env.FIREBASE_PROJECT_ID ?? "demo-mobile-banking",
    host: env.HOST ?? "127.0.0.1",
    port,
    otpSecret,
  };
}
