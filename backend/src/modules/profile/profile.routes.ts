import { z } from "zod";
import type { AppDependencies, Route } from "../../core/http.js";
export function registerProfileRoutes(route: Route, d: AppDependencies) {
  route("get", "/me", (a) => d.bank.profiles.get(a));
  route("patch", "/me", (a, r) =>
    d.bank.profiles.update(
      a,
      z
        .object({
          contactEmail: z.email().optional(),
          contactPhone: z.string().max(30).optional(),
          preferences: z
            .object({
              language: z.enum(["vi", "en"]).optional(),
              notificationsEnabled: z.boolean().optional(),
            })
            .strict()
            .optional(),
        })
        .strict()
        .parse(r.body),
    ),
  );
}
