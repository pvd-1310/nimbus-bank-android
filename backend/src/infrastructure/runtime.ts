import { S3Client } from "@aws-sdk/client-s3";
import "dotenv/config";
import { applicationDefault, cert, initializeApp } from "firebase-admin/app";
import { getAuth } from "firebase-admin/auth";
import { getFirestore } from "firebase-admin/firestore";
import Stripe from "stripe";
import { z } from "zod";
import { readFileSync } from "node:fs";
import { createChatbot, localChatbotEnabled } from "../modules/chatbot/chatbot.js";
import { Banking } from "../core/banking.js";
import { config } from "../core/config.js";
import { Evidence } from "../modules/ekyc/ekyc.service.js";
import { S3Evidence } from "../modules/ekyc/s3.provider.js";
import { Payments } from "../modules/payments/payment.service.js";
import { StripeGateway } from "../modules/payments/stripe.provider.js";
import { check } from "../shared/errors.js";
import { FirestoreStore } from "./firestore-store.js";

import { FaceService } from "../modules/security/face.js";

function faceProvider() {
  const url = process.env.FACE_API_BASE_URL;
  const key = process.env.FACE_TEMPLATE_KEY_HEX;
  if (!url || !key) return undefined;
  check(/^[0-9a-f]{64}$/i.test(key), "INVALID_FACE_TEMPLATE_KEY");
  const similarity = z.coerce.number().gt(0).lt(1).parse(process.env.FACE_SIMILARITY ?? "0.55");
  const margin = z.coerce.number().gt(0).lt(similarity).parse(process.env.FACE_MIN_MARGIN ?? "0.08");
  const days = z.coerce.number().int().min(1).max(3650).parse(process.env.FACE_RETENTION_DAYS ?? "365");
  const idSimilarity = z.coerce.number().gt(0).lt(1).parse(process.env.FACE_ID_SIMILARITY ?? "0.40");
  return new FaceService(url.replace(/\/+$/, ""), Buffer.from(key, "hex"), similarity, margin, days * 86_400_000, idSimilarity);
}

export function runtime() {
  const cfg = config();
  const credentialPath = process.env.GOOGLE_APPLICATION_CREDENTIALS;
  const credential = cfg.mode !== "connected"
    ? undefined
    : credentialPath
      ? cert(JSON.parse(readFileSync(credentialPath, "utf8")))
      : applicationDefault();
  const app = initializeApp({
    projectId: cfg.projectId,
    ...(credential ? { credential } : {}),
  });
  const auth = getAuth(app),
    db = getFirestore(app),
    store = new FirestoreStore(db);
  const face = faceProvider();
  const bank = new Banking(store, {
    otpSecret: cfg.otpSecret,
    face,
    faceThresholds: z.record(z.string().regex(/^[A-Z]{3}$/), z.number().int().positive())
      .parse(JSON.parse(process.env.FACE_THRESHOLDS_MINOR ?? '{"VND":100000000}')),
  });
  const s3 =
    process.env.S3_BUCKET && process.env.AWS_REGION
      ? new S3Evidence(
          new S3Client({
            region: process.env.AWS_REGION,
            endpoint: process.env.S3_ENDPOINT,
            forcePathStyle: process.env.S3_FORCE_PATH_STYLE === "true",
          }),
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
  const evidenceRetentionMs = z.coerce.number().int().min(1).max(3650)
    .parse(process.env.EKYC_RETENTION_DAYS ?? "90") * 86_400_000;
  return {
    cfg,
    s3,
    evidenceRetentionMs,
    auth,
    db,
    bank,
    evidence: new Evidence(bank, s3, evidenceRetentionMs,
      process.env.EKYC_DEMO_SELFIE_ONLY === "true" ? "DEMO_SELFIE_ONLY" : "FULL_ID"),
    payments: new Payments(bank, stripe),
    chatbot: createChatbot(process.env),
    ...(localChatbotEnabled(process.env) && { chatbotMode: "DEMO_ONLY" as const }),
  };
}
