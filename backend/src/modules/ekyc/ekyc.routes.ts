import { z } from "zod";
import type { AppDependencies, Route } from "../../core/http.js";

const consent = z.object({ consented: z.boolean() }).strict();
const upload = z.object({
  mime: z.enum(["image/jpeg", "image/png"]),
  bytes: z.number().int().min(1).max(5_000_000),
  index: z.number().int().min(0).max(2).optional(),
}).strict();
const review = z.object({
  status: z.enum(["VERIFIED", "REJECTED", "RESUBMIT_REQUIRED"]),
  reason: z.string().trim().min(3).max(500),
}).strict();

export function registerEkycRoutes(route: Route, { evidence }: AppDependencies) {
  route("get", "/ekyc", (a, r) => evidence.list(a, r.query));
  route("post", "/ekyc", (a, r) => evidence.create(a, consent.parse(r.body).consented));
  route("post", "/ekyc/:id/uploads", (a, r) => {
    const { mime, bytes, index } = upload.parse(r.body);
    return evidence.ticket(a, String(r.params.id), mime, bytes, index);
  });
  route("post", "/uploads/:id/confirm", (a, r) => evidence.confirm(a, String(r.params.id)));
  route("post", "/ekyc/:id/submit", (a, r) => evidence.submit(a, String(r.params.id)));
  route("get", "/officer/ekyc", (a, r) => evidence.list(a, r.query, true));
  route("post", "/officer/ekyc/:id/review", (a, r) => {
    const { status, reason } = review.parse(r.body);
    return evidence.review(a, String(r.params.id), status, reason, r.res?.locals.requestId);
  });
  route("get", "/officer/ekyc/:id/evidence/:index", (a, r) => {
    const index = z.enum(["0", "1", "2"]).parse(r.params.index);
    return evidence.evidence(a, String(r.params.id), Number(index));
  });
}
