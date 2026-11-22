import express, { type Express } from "express";
import type { AppDependencies } from "../../core/http.js";
import { check, Fault } from "../../shared/errors.js";
export function registerStripeWebhook(app: Express, d: AppDependencies) {
  app.post(
    "/webhooks/stripe",
    express.raw({ type: "application/json", limit: "1mb" }),
    async (req, res) => {
      check(d.payments.provider, "SETUP_REQUIRED", 503);
      let event;
      try {
        event = d.payments.provider.parse(
          req.body,
          req.get("stripe-signature") ?? "",
        );
      } catch {
        throw new Fault("INVALID_SIGNATURE", 400);
      }
      res.json(await d.payments.event(event));
    },
  );
}
