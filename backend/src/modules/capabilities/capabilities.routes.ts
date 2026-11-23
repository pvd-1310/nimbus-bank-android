import type { AppDependencies, Route } from "../../core/http.js";
export function registerCapabilitiesRoutes(route: Route, d: AppDependencies) {
  route("get", "/capabilities", async () => ({
    firebase: "ENABLED",
    s3: d.evidence.provider ? "ENABLED" : "SETUP_REQUIRED",
    stripe: d.payments.provider ? "TEST_MODE" : "SETUP_REQUIRED",
    otp: "DEMO_DISPLAY_CODE",
    face: await d.bank.options.face?.ready() ? "ENABLED" : "SETUP_REQUIRED",
    ekycMode: d.evidence.captureMode,
    externalTransfer: "DEMO_ONLY",
    routes: process.env.GOOGLE_ROUTES_API_KEY ? "ENABLED" : "SETUP_REQUIRED",
    chatbot: d.chatbot ? d.chatbotMode ?? "ENABLED" : "SETUP_REQUIRED",
  }));
}
