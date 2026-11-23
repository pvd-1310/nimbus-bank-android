import { registerFaceLogin, registerFaceStepUp } from "./modules/security/face.routes.js";
import express from "express";
import {
  authenticateRequests,
  createRouteRegistrar,
  errorHandler,
  requestMetadata,
  type AppDependencies,
} from "./core/http.js";
import { openapi } from "./docs/openapi.js";
import { registerAccountsRoutes } from "./modules/accounts/accounts.routes.js";
import { registerBranchesRoutes } from "./modules/branches/branches.routes.js";
import { registerCapabilitiesRoutes } from "./modules/capabilities/capabilities.routes.js";
import { registerEkycRoutes } from "./modules/ekyc/ekyc.routes.js";
import { registerNotificationsRoutes } from "./modules/notifications/notifications.routes.js";
import { registerOfficerRoutes } from "./modules/officer/officer.routes.js";
import { registerOrdersRoutes } from "./modules/orders/orders.routes.js";
import { registerStripeWebhook } from "./modules/payments/payment.webhook.js";
import { registerPaymentsRoutes } from "./modules/payments/payments.routes.js";
import { registerProfileRoutes } from "./modules/profile/profile.routes.js";
import { registerRatesRoutes } from "./modules/rates/rates.routes.js";
import { registerRequestsRoutes } from "./modules/requests/requests.routes.js";
import { registerTransactionsRoutes } from "./modules/transactions/transactions.routes.js";
import { registerTransfersRoutes } from "./modules/transfers/transfers.routes.js";
export function createApp(d: AppDependencies) {
  const app = express();
  app.disable("x-powered-by");
  app.use(requestMetadata);
  app.get("/favicon.ico", (_req, res) => res.status(204).end());
  app.get("/health", (_req, res) => res.json({ status: "ok" }));
  app.get("/openapi.json", (_req, res) => res.json(openapi));
  registerStripeWebhook(app, d);
  registerFaceLogin(app, d);
  app.use(express.json({ limit: "64kb" }));
  app.use(authenticateRequests(d));
  registerFaceStepUp(app, d);
  const route = createRouteRegistrar(app);
  for (const register of [
    registerCapabilitiesRoutes,
    registerProfileRoutes,
    registerAccountsRoutes,
    registerTransactionsRoutes,
    registerTransfersRoutes,
    registerRequestsRoutes,
    registerNotificationsRoutes,
    registerEkycRoutes,
    registerPaymentsRoutes,
    registerOrdersRoutes,
    registerBranchesRoutes,
    registerRatesRoutes,
    registerOfficerRoutes,
  ])
    register(route, d);
  app.use(errorHandler);
  return app;
}
