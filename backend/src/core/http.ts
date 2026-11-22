import type { Express, NextFunction, Request, Response } from "express";
import { randomUUID } from "node:crypto";
import { ZodError } from "zod";
import type { Actor } from "../modules/auth/auth.types.js";
import type { Evidence } from "../modules/ekyc/ekyc.service.js";
import type { Payments } from "../modules/payments/payment.service.js";
import { check, Fault } from "../shared/errors.js";
import type { Banking } from "./banking.js";
import type { Doc } from "./store.js";
export interface AppDependencies {
  otpCapability?: string;
  bank: Banking;
  evidence: Evidence;
  payments: Payments;
  authenticate: (token: string) => Promise<Actor>;
  provision?: (a: Actor, body: Doc) => Promise<unknown>;
}
export type Route = (
  method: "get" | "post" | "patch",
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
    check(token, "UNAUTHENTICATED", 401);
    try {
      res.locals.actor = await d.authenticate(token);
    } catch {
      throw new Fault("UNAUTHENTICATED", 401);
    }
    await d.bank.store.run((t) => d.bank.authorize(t, res.locals.actor));
    next();
  };
}
export function createRouteRegistrar(app: Express): Route {
  return (method, path, handler) => {
    app[method]("/v1" + path, async (req, res) => {
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
  const known = err instanceof Fault,
    malformed =
      err instanceof ZodError || (err instanceof SyntaxError && "body" in err);
  const requestId = res.locals.requestId;
  const errorCode = known ? err.code : malformed ? "INVALID_REQUEST" : "INTERNAL_ERROR";
  console.error("API request failed", {
    requestId, method: req.method, path: req.path, errorCode,
    errorName: err instanceof Error ? err.name : "UNKNOWN",
  });
  res.status(known ? err.status : malformed ? 400 : 500).json({
    error: known ? err.code : malformed ? "INVALID_REQUEST" : "INTERNAL_ERROR",
    requestId: res.locals.requestId,
  });
}
