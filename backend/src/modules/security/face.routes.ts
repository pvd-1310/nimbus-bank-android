import express, {
  type Express,
  type RequestHandler,
  type Request,
} from "express";
import type { AppDependencies } from "../../core/http.js";
import { check } from "../../shared/errors.js";
import { maxImageBytes } from "./face.js";

let uploads = 0;
const limitUploads: RequestHandler = (_req, res, next) => {
  check(uploads < 4, "FACE_BUSY", 429);
  uploads++;
  res.setTimeout(15_000, () => res.destroy());
  res.once("close", () => uploads--);
  next();
};

const rawPhoto = express.raw({
  type: ["image/jpeg", "image/png"],
  limit: maxImageBytes,
  inflate: false,
});

function photo(r: Request) {
  check(Buffer.isBuffer(r.body), "INVALID_MIME", 415);
  return [r.is("image/jpeg") ? "image/jpeg" : "image/png", r.body] as const;
}

export function registerFaceLogin(app: Express, d: AppDependencies) {
  app.post("/auth/face-login", limitUploads, rawPhoto, async (req, res) => {
    const face = d.bank.options.face;
    check(face && d.createFaceToken, "FACE_SETUP_REQUIRED", 503);
    res.json(
      await face.signIn(
        d.bank.store,
        req.ip ?? "unknown",
        ...photo(req),
        d.createFaceToken,
      ),
    );
  });
}

export function registerFaceStepUp(app: Express, d: AppDependencies) {
  app.post("/me/face/revoke", async (_req, res) => {
    res.json(await d.evidence.revokeFaceTemplate(res.locals.actor));
  });
  app.post("/intents/:id/face", limitUploads, rawPhoto, async (req, res) => {
    res.json(
      await d.bank.security.stepUp(
        res.locals.actor,
        String(req.params.id),
        ...photo(req),
      ),
    );
  });
}
