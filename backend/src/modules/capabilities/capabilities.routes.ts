import type { AppDependencies, Route } from "../../core/http.js";
export function registerCapabilitiesRoutes(route: Route, d: AppDependencies) {
  route("get", "/capabilities", async () => ({
    firebase: "ENABLED",
    s3: d.evidence.provider ? "ENABLED" : "SETUP_REQUIRED",
    stripe: d.payments.provider ? "TEST_MODE" : "SETUP_REQUIRED",
    otp: d.otpCapability ?? "LOCAL_DEMO_INBOX",
    face: "SETUP_REQUIRED",
    externalTransfer: "SETUP_REQUIRED",
    routes: "SETUP_REQUIRED",
    chatbot: "DEFERRED",
  }));
}
