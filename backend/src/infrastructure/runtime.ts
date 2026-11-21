import { S3Client } from "@aws-sdk/client-s3";
import "dotenv/config";
import { applicationDefault, initializeApp } from "firebase-admin/app";
import { getAuth } from "firebase-admin/auth";
import { getFirestore } from "firebase-admin/firestore";
import { mkdir, writeFile } from "node:fs/promises";
import Stripe from "stripe";
import { Banking } from "../core/banking.js";
import { config } from "../core/config.js";
import { Evidence } from "../modules/ekyc/ekyc.service.js";
import { S3Evidence } from "../modules/ekyc/s3.provider.js";
import { Payments } from "../modules/payments/payment.service.js";
import { StripeGateway } from "../modules/payments/stripe.provider.js";
import { check } from "../shared/errors.js";
import { FirestoreStore } from "./firestore-store.js";
export function runtime() {
  const cfg = config();
  const app = initializeApp({
    projectId: cfg.projectId,
    ...(cfg.mode === "connected" ? { credential: applicationDefault() } : {}),
  });
  const auth = getAuth(app),
    db = getFirestore(app),
    store = new FirestoreStore(db);
  const bank = new Banking(store, {
    otpSecret: cfg.otpSecret,
    deliver: async (uid, code, intentId) => {
      check(
        cfg.host === "127.0.0.1" && cfg.mode === "emulator",
        "OTP_PROVIDER_REQUIRED",
        503,
      );
      await mkdir(".local/otp", { recursive: true, mode: 0o700 });
      await writeFile(
        ".local/otp/" + intentId + ".json",
        JSON.stringify({ uid, code, intentId, expiresIn: 300 }),
        { mode: 0o600 },
      );
    },
  });
  const s3 =
    process.env.S3_BUCKET && process.env.AWS_REGION
      ? new S3Evidence(
          new S3Client({ region: process.env.AWS_REGION }),
          process.env.S3_BUCKET,
        )
      : undefined;
  const stripe =
    process.env.STRIPE_SECRET_KEY && process.env.STRIPE_WEBHOOK_SECRET
      ? new StripeGateway(
          new Stripe(process.env.STRIPE_SECRET_KEY),
          process.env.STRIPE_WEBHOOK_SECRET,
        )
      : undefined;
  return {
    cfg,
    auth,
    db,
    bank,
    evidence: new Evidence(bank, s3),
    payments: new Payments(bank, stripe),
  };
}
