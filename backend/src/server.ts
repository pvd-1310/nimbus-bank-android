import { Fault } from "./shared/errors.js";
import { createApp } from "./app.js";
import { runtime } from "./infrastructure/runtime.js";
import { createCustomerProvisioner } from "./modules/officer/firebase-customer.adapter.js";
import { createFirebaseAuthenticator } from "./modules/auth/firebase-auth.adapter.js";
const r = runtime();
const provisionCustomer = createCustomerProvisioner(r.bank, r.auth);
const app = createApp({
  ...r,
  createFaceToken: async (uid) => {
    const account = await r.auth.getUser(uid).catch(() => null);
    if (!account || account.disabled || account.customClaims?.role !== "CUSTOMER") throw new Fault("FACE_LOGIN_FAILED", 401);
    return r.auth.createCustomToken(uid);
  },
  provision: async (actor, input) => {
    return provisionCustomer(actor, input);
  },
  lookupUserByEmail: async (email) => {
    const account = await r.auth.getUserByEmail(email).catch(() => null);
    return account ? { uid: account.uid, disabled: Boolean(account.disabled) } : null;
  },
  updateUserPassword: async (uid, password) => {
    await r.auth.updateUser(uid, { password });
  },
  authenticate: createFirebaseAuthenticator(r.auth),
});
const server = app.listen(r.cfg.port, r.cfg.host, () =>
  console.log(
    `Banking API listening on ${r.cfg.host}:${r.cfg.port} (${r.cfg.mode})`,
  ),
);
for (const signal of ["SIGINT", "SIGTERM"])
  process.on(signal, () => server.close(() => process.exit(0)));
