import { z } from "zod";
import type { AppDependencies, Route } from "../../core/http.js";
import { check } from "../../shared/errors.js";
export function registerProfileRoutes(route: Route, d: AppDependencies) {
  route("get", "/me", (a) => d.bank.profiles.get(a));
  route("get", "/me/avatar", (a) => {
    check(d.profileAvatar, "SETUP_REQUIRED", 503);
    return d.profileAvatar.url(a);
  });
  route("post", "/me/avatar", (a, r) => {
    check(d.profileAvatar, "SETUP_REQUIRED", 503);
    const { mime, bytes } = z.object({
      mime: z.enum(["image/jpeg", "image/png"]),
      bytes: z.number().int().min(3).max(5_000_000),
    }).strict().parse(r.body);
    return d.profileAvatar.start(a, mime, bytes);
  });
  route("post", "/me/avatar/:id/confirm", (a, r) => {
    check(d.profileAvatar, "SETUP_REQUIRED", 503);
    const id = z.string().uuid().parse(r.params.id);
    return d.profileAvatar.confirm(a, id);
  });
  route("patch", "/me", (a, r) =>
    d.bank.profiles.update(
      a,
      z
        .object({
          contactEmail: z.email().max(254).optional(),
          contactPhone: z.string().trim().regex(/^\+[1-9][0-9]{7,14}$/).optional(),
          preferences: z
            .object({
              language: z.enum(["vi", "en"]).optional(),
              notificationsEnabled: z.boolean().optional(),
            })
            .strict()
            .optional(),
        })
        .strict()
        .refine((patch) => Object.keys(patch).length > 0, "NO_FIELDS_TO_UPDATE")
        .parse(r.body),
      r.res?.locals.requestId,
    ),
  );
}
