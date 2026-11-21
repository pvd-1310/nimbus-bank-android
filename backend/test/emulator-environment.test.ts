import { it, expect } from "vitest";
import { requireEmulatorEnvironment } from "./support/emulator-environment.js";
const valid = {
  GCLOUD_PROJECT: "demo-schema-tests",
  FIREBASE_AUTH_EMULATOR_HOST: "127.0.0.1:19099",
  FIRESTORE_EMULATOR_HOST: "127.0.0.1:18080",
};
it("accepts the isolated local emulator project", () =>
  expect(requireEmulatorEnvironment(valid)).toBe("demo-schema-tests"));
it("rejects a real project even with emulator variables", () =>
  expect(() =>
    requireEmulatorEnvironment({
      ...valid,
      GCLOUD_PROJECT: "nimbus-bank-bdc93",
    }),
  ).toThrow());
it("rejects missing or nonlocal emulator hosts before SDK initialization", () => {
  for (const env of [
    { ...valid, FIREBASE_AUTH_EMULATOR_HOST: "" },
    { ...valid, FIRESTORE_EMULATOR_HOST: "" },
    { ...valid, FIRESTORE_EMULATOR_HOST: "firestore.googleapis.com:443" },
    { ...valid, FIREBASE_AUTH_EMULATOR_HOST: "127.0.0.1:99999" },
  ])
    expect(() => requireEmulatorEnvironment(env)).toThrow();
});
