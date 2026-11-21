import { S3Client } from "@aws-sdk/client-s3";
import "dotenv/config";
import { applicationDefault, initializeApp } from "firebase-admin/app";
import { getAuth } from "firebase-admin/auth";
import { getFirestore } from "firebase-admin/firestore";
import { mkdir, writeFile } from "node:fs/promises";
import Stripe from "stripe";
import { z } from "zod";
import { Banking } from "../core/banking.js";
import { config } from "../core/config.js";
import { Evidence } from "../modules/ekyc/ekyc.service.js";
import { S3Evidence } from "../modules/ekyc/s3.provider.js";
import { Payments } from "../modules/payments/payment.service.js";
import { StripeGateway } from "../modules/payments/stripe.provider.js";
import { check } from "../shared/errors.js";
import { FirestoreStore } from "./firestore-store.js";

const demoScope = z.object({
  projectId: z.string().min(1),
  customerUid: z.string().min(1),
  sourceId: z.string().min(1),
  recipientId: z.string().min(1),
  phoneNumber: z.string().regex(/^\+[1-9]\d{7,14}$/),
}).strict();

export function runtime() {
  const cfg = config();
  const app = initializeApp({
    projectId: cfg.projectId,
    ...(cfg.mode === "connected" ? { credential: applicationDefault() } : {}),
  });
  const auth = getAuth(app),
    db = getFirestore(app),
    store = new FirestoreStore(db);
  const demo = process.env.FIREBASE_PHONE_DEMO_SCOPE
    ? demoScope.parse(JSON.parse(process.env.FIREBASE_PHONE_DEMO_SCOPE))
    : undefined;
  if (demo) check(cfg.mode === "connected" && cfg.projectId === demo.projectId
    && demo.sourceId !== demo.recipientId, "INVALID_FIREBASE_PHONE_DEMO_SCOPE");
  const bank = new Banking(store, {
    otpSecret: cfg.otpSecret,
    demoPhone: demo && {
      ...demo,
      verify: async (token) => {
        const decoded = await auth.verifyIdToken(token, true).catch(() => null);
        check(decoded && decoded.firebase.sign_in_provider === "phone"
          && decoded.phone_number === demo.phoneNumber, "INVALID_OTP");
        return { uid: decoded.uid, authTime: decoded.auth_time };
      },
    },
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
