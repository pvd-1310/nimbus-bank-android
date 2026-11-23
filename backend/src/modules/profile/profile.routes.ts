import { z } from "zod";
import type { AppDependencies, Route } from "../../core/http.js";
export function registerProfileRoutes(route: Route, d: AppDependencies) {
  route("get", "/me", (a) => d.bank.profiles.get(a));
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
