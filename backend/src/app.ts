import { registerFaceLogin, registerFaceStepUp } from "./modules/security/face.routes.js";
import { registerPasswordResetRoutes } from "./modules/security/password-reset.routes.js";
import { registerSelfRegistrationRoutes } from "./modules/security/register.routes.js";
import express from "express";
import { registerChatbotRoutes } from "./modules/chatbot/chatbot.js";
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
import { recognizeIdCard } from "./modules/ekyc/id-recognition.js";
import { check } from "./shared/errors.js";
import { registerNotificationsRoutes } from "./modules/notifications/notifications.routes.js";
import { registerOfficerRoutes } from "./modules/officer/officer.routes.js";
import { registerProductsRoutes } from "./modules/accounts/products.routes.js";
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
  registerPasswordResetRoutes(app, d);
  registerSelfRegistrationRoutes(app, d);
  app.use(authenticateRequests(d));
  let recognitionRequests = 0;
  app.post("/ekyc/:id/recognize", express.raw({ type: ["image/jpeg", "image/png"], limit: "5mb", inflate: false }), async (req, res) => {
    await d.evidence.authorizeRecognition(res.locals.actor, String(req.params.id));
    check(Buffer.isBuffer(req.body), "INVALID_MIME", 415);
    check(recognitionRequests < 4, "ID_RECOGNITION_BUSY", 429);
    recognitionRequests++;
    try { res.json(await recognizeIdCard(req.body, req.is("image/png") ? "image/png" : "image/jpeg")); }
    finally { recognitionRequests--; }
  });
  registerFaceStepUp(app, d);
  const route = createRouteRegistrar(app);
  for (const register of [
    registerCapabilitiesRoutes,
    registerChatbotRoutes,
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
    registerProductsRoutes,
  ])
    register(route, d);
  app.use(errorHandler);
  return app;
}
