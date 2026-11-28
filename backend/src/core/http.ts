import type { Express, NextFunction, Request, Response } from "express";
import { randomUUID } from "node:crypto";
import { ZodError } from "zod";
import type { AuthEmail } from "../modules/security/auth-email.js";
import type { Actor } from "../modules/auth/auth.types.js";
import type { StripeSandboxAccounts } from "../modules/accounts/stripe-sandbox-accounts.service.js";
import type { StripeSandboxMovements } from "../modules/accounts/stripe-sandbox-movements.service.js";
import type { Evidence } from "../modules/ekyc/ekyc.service.js";
import type { ProfileAvatarService } from "../modules/profile/profile-avatar.service.js";
import type { Payments } from "../modules/payments/payment.service.js";
import { Fault } from "../shared/errors.js";
import type { Banking } from "./banking.js";
import type { Doc } from "./store.js";
export interface AppDependencies {
  chatbotMode?: "DEMO_ONLY";
  chatbot?: (
    message: string,
    options?: {
      history?: Array<{ role: "user" | "assistant"; text: string }>;
      context?: string;
    },
  ) => Promise<{ action: string | null; text: string }>;
  createFaceToken?: (uid: string) => Promise<string>;
  bank: Banking;
  evidence: Evidence;
  profileAvatar?: ProfileAvatarService;
  payments: Payments;
  stripeSandboxAccounts?: StripeSandboxAccounts;
  stripeSandboxMovements?: StripeSandboxMovements;
  authenticate: (token: string) => Promise<Actor>;
  verifyRegistrationIdentity?: (token: string) => Promise<{
    uid: string; email?: string; emailVerified: boolean; role?: unknown;
  }>;
  sendAuthEmail?: (email: AuthEmail) => Promise<void>;
  markRegistrationEmailVerified?: (uid: string, email: string) => Promise<void>;
  lookupPasswordResetUser?: (email: string) => Promise<string | null>;
  updatePasswordAfterEmailOtp?: (uid: string, password: string, matchesEmail: (email: string) => boolean) => Promise<void>;
  grantCustomerRole?: (uid: string) => Promise<void>;
  provision?: (a: Actor, body: Doc) => Promise<unknown>;
}
export type Route = (
  method: "get" | "post" | "patch" | "delete",
  path: string,
  handler: (actor: Actor, request: Request) => Promise<unknown>,
) => void;
export function requestMetadata(
  _req: Request,
  res: Response,
  next: NextFunction,
) {
  res.locals.requestId = randomUUID();
  res.setHeader("X-Request-Id", res.locals.requestId);
  res.setHeader("Cache-Control", "no-store");
  res.setHeader("X-Content-Type-Options", "nosniff");
  next();
}
export function authenticateRequests(d: AppDependencies) {
  return async (req: Request, res: Response, next: NextFunction) => {
    const token = req.get("authorization")?.match(/^Bearer (.+)$/)?.[1];
    if (!token) {
      console.warn("API authentication failed", {
        requestId: res.locals.requestId,
        method: req.method,
        route: typeof req.route?.path === "string" ? req.route.path : "unmatched",
        reason: "MISSING_BEARER_TOKEN",
      });
      throw new Fault("UNAUTHENTICATED", 401);
    }
    try {
      res.locals.actor = await d.authenticate(token);
    } catch (error) {
      const authErrorCode =
        typeof error === "object" && error !== null && "code" in error &&
        typeof error.code === "string"
          ? error.code
          : "UNKNOWN_AUTH_ERROR";
      console.warn("API authentication failed", {
        requestId: res.locals.requestId,
        method: req.method,
        route: typeof req.route?.path === "string" ? req.route.path : "unmatched",
        reason: authErrorCode,
      });
      throw new Fault("UNAUTHENTICATED", 401);
    }
    const onboardingPath = res.locals.actor.role === "CUSTOMER" && (
      req.path === "/me" || req.path === "/me/avatar" || req.path === "/ekyc" ||
      /^\/me\/avatar\/[^/]+\/confirm$/.test(req.path) ||
      /^\/ekyc\/[^/]+\/(uploads|recognize|submit|evidence\/[012])$/.test(req.path) ||
      /^\/uploads\/[^/]+\/confirm$/.test(req.path)
    );
    await d.bank.read((t) => d.bank.authorize(t, res.locals.actor, undefined, onboardingPath));
    next();
  };
}
export function createRouteRegistrar(app: Express): Route {
  return (method, path, handler) => {
    app[method](path, async (req, res) => {
      res.json(await handler(res.locals.actor, req));
    });
  };
}
export function errorHandler(
  err: unknown,
  req: Request,
  res: Response,
  _next: NextFunction,
) {
  if (typeof err === "object" && err !== null && "type" in err) {
    if (err.type === "entity.too.large") err = new Fault("INVALID_BYTES", 413);
    else if (err.type === "encoding.unsupported") err = new Fault("INVALID_IMAGE", 415);
  }
  const fault = err instanceof Fault ? err : undefined,
    malformed =
      err instanceof ZodError || (err instanceof SyntaxError && "body" in err);
  const requestId = res.locals.requestId;
  const errorCode = fault ? fault.code : malformed ? "INVALID_REQUEST" : "INTERNAL_ERROR";
  console.error("API request failed", {
    requestId, method: req.method,
    route: typeof req.route?.path === "string" ? req.route.path : "unmatched",
    errorCode,
  });
  res.status(fault ? fault.status : malformed ? 400 : 500).json({
    error: fault ? fault.code : malformed ? "INVALID_REQUEST" : "INTERNAL_ERROR",
    requestId: res.locals.requestId,
  });
}
