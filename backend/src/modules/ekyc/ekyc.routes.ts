import { z } from "zod";
import type { AppDependencies, Route } from "../../core/http.js";

const consent = z.object({ consented: z.boolean() }).strict();
const identity = z.object({
  fullName: z.string().trim().min(2).max(120),
  nationalIdNumber: z.string().regex(/^\d{12}$/),
  dateOfBirth: z.iso.date(),
  address: z.string().trim().min(1).max(300),
  gender: z.string().trim().max(40).optional(),
  nationality: z.string().trim().max(80).optional(),
  identityDocumentExpiry: z.union([z.iso.date(), z.literal("")]).optional(),
}).strict();
const upload = z.object({
  mime: z.enum(["image/jpeg", "image/png"]),
  bytes: z.number().int().min(1).max(5_000_000),
  index: z.number().int().min(0).max(2).optional(),
}).strict();

export function registerEkycRoutes(route: Route, { evidence }: AppDependencies) {
  route("get", "/ekyc", (a, r) => evidence.list(a, r.query));
  route("post", "/ekyc", (a, r) => evidence.create(a, consent.parse(r.body).consented));
  route("post", "/ekyc/:id/uploads", (a, r) => {
    const { mime, bytes, index } = upload.parse(r.body);
    return evidence.ticket(a, String(r.params.id), mime, bytes, index);
  });
  route("post", "/uploads/:id/confirm", (a, r) => evidence.confirm(a, String(r.params.id)));
  route("post", "/ekyc/:id/submit", (a, r) => {
    const body = z.object({ identity: identity.optional() }).strict().parse(r.body);
    return evidence.submit(a, String(r.params.id), body.identity);
  });
  route("get", "/officer/ekyc", (a, r) => evidence.list(a, r.query, true));
  route("get", "/ekyc/:id/evidence/:index", (a, r) => {
    const index = z.enum(["0", "1", "2"]).parse(r.params.index);
    return evidence.evidence(a, String(r.params.id), Number(index), false);
  });
  route("get", "/officer/ekyc/:id/evidence/:index", (a, r) => {
    const index = z.enum(["0", "1", "2"]).parse(r.params.index);
    return evidence.evidence(a, String(r.params.id), Number(index));
  });
  route("post", "/officer/ekyc/:id/review", (a, r) => {
    const body = z.object({
      decision: z.enum(["VERIFIED", "RESUBMIT_REQUIRED", "REJECTED"]),
      reason: z.string().trim().max(500).optional(),
    }).strict().parse(r.body);
    return evidence.review(a, String(r.params.id), body.decision, body.reason);
  });
}
