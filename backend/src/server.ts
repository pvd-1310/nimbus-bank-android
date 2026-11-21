import { createApp } from "./app.js";
import { runtime } from "./infrastructure/runtime.js";
import { createCustomerProvisioner } from "./modules/officer/firebase-customer.adapter.js";
import { createFirebaseAuthenticator } from "./modules/auth/firebase-auth.adapter.js";
const r = runtime();
const app = createApp({
  ...r,
  otpCapability:
    r.cfg.mode === "emulator" && r.cfg.host === "127.0.0.1"
      ? "LOCAL_DEMO_INBOX"
      : "SETUP_REQUIRED",
  provision: createCustomerProvisioner(r.bank, r.auth),
  authenticate: createFirebaseAuthenticator(r.auth),
});
const server = app.listen(r.cfg.port, r.cfg.host, () =>
  console.log(
    `Banking demo API http://${r.cfg.host}:${r.cfg.port} (${r.cfg.mode}); chatbot deferred`,
  ),
);
for (const signal of ["SIGINT", "SIGTERM"])
  process.on(signal, () => server.close(() => process.exit(0)));
