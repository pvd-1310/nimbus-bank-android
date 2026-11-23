import type { AppDependencies, Route } from "../../core/http.js";
import type { Doc } from "../../core/store.js";
import { Fault } from "../../shared/errors.js";
import { page, queryOptions } from "../../shared/pagination.js";
import { z } from "zod";

const coordinatesSchema = z.object({
  latitude: z.number().min(-90).max(90),
  longitude: z.number().min(-180).max(180),
});
const routeSchema = coordinatesSchema.extend({
  branchId: z
    .string()
    .min(1)
    .max(1500)
    .regex(/^(?!\.{1,2}$|__.*__$)[^/]+$/)
    .optional(),
});

function googleKey() {
  const key = process.env.GOOGLE_ROUTES_API_KEY;
  if (!key) throw new Fault("ROUTES_API_NOT_CONFIGURED", 503);
  return key;
}

async function googlePost(
  path: string,
  key: string,
  body: unknown,
  fields: string,
) {
  const response = await fetch(`https://routes.googleapis.com${path}`, {
    method: "POST",
    headers: {
      "Content-Type": "application/json",
      "X-Goog-Api-Key": key,
      "X-Goog-FieldMask": fields,
    },
    body: JSON.stringify(body),
    signal: AbortSignal.timeout(3500),
    redirect: "error",
  }).catch(() => { throw new Fault("ROUTES_PROVIDER_UNAVAILABLE", 502); });
  if (!response.ok) {
    throw new Fault("ROUTES_PROVIDER_UNAVAILABLE", 502);
  }
  return response.json().catch(() => { throw new Fault("ROUTES_PROVIDER_UNAVAILABLE", 502); }) as Promise<unknown>;
}

function branchCoordinates(branch: Doc) {
  return coordinatesSchema.safeParse(branch).success;
}

const maxCandidates = 20;
const routeResultSchema = z.object({
  routes: z.array(z.object({
    distanceMeters: z.number().int().nonnegative(),
    duration: z.string().regex(/^\d+(?:\.\d+)?s$/),
    polyline: z.object({ encodedPolyline: z.string().min(1) }),
  })).optional(),
});

async function walkingRoute(key: string, origin: z.infer<typeof coordinatesSchema>, branch: Doc) {
  const raw = await googlePost(
    "/directions/v2:computeRoutes", key,
    {
      origin: { location: { latLng: origin } },
      destination: { location: { latLng: coordinatesSchema.parse(branch) } },
      travelMode: "WALK",
      polylineQuality: "OVERVIEW",
      languageCode: "vi",
    },
    "routes.distanceMeters,routes.duration,routes.polyline.encodedPolyline",
  );
  const response = routeResultSchema.safeParse(raw);
  if (!response.success) throw new Fault("ROUTES_PROVIDER_UNAVAILABLE", 502);
  return response.data.routes?.map((route) => ({
    branch,
    distanceMeters: route.distanceMeters,
    duration: route.duration,
    encodedPolyline: route.polyline.encodedPolyline,
  })) ?? [];
}

export function registerBranchesRoutes(route: Route, d: AppDependencies) {
  route("get", "/branches", async (_a, r) =>
    d.bank.store.run(async (t) =>
      page(
        await t.list("bankBranches", { active: true }, queryOptions(r.query)),
        r.query,
      ),
    ),
  );
  route("post", "/branches/routes", async (_actor, request) => {
    const { branchId, ...origin } = routeSchema.parse(request.body);
    const key = googleKey();
    const branches = await d.bank.store.run(async (t) => {
      if (branchId) {
        const selected = await t.get("bankBranches/" + branchId);
        return selected?.active === true ? [{ ...selected, id: branchId }] : [];
      }
      return t.list("bankBranches", { active: true }, { limit: maxCandidates + 1 });
    });
    if (branches.length > maxCandidates) throw new Fault("SELECT_BRANCH_REQUIRED", 409);
    const candidates = branches.filter(branchCoordinates);
    if (!candidates.length) throw new Fault("BRANCHES_UNAVAILABLE", 404);
    const routes = (await Promise.all(candidates.map((branch) => walkingRoute(key, origin, branch)))).flat();
    routes.sort((a, b) => a.distanceMeters - b.distanceMeters || parseFloat(a.duration) - parseFloat(b.duration));
    if (!routes.length) throw new Fault("WALKING_ROUTE_UNAVAILABLE", 404);
    return {
      ...routes[0],
      selection: branchId ? "SELECTED_BRANCH" : "SHORTEST_WALKING_DISTANCE",
      candidatesCompared: candidates.length,
    };
  });
}
