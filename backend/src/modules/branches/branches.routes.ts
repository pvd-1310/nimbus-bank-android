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
  });
  if (!response.ok) {
    const payload = await response.json().catch(() => null);
    const error = (Array.isArray(payload) ? payload[0] : payload)?.error;
    console.error("Google Routes API request failed", {
      path,
      httpStatus: response.status,
      providerStatus: error?.status ?? "UNKNOWN",
      providerMessage: error?.message ?? "No provider message",
    });
    throw new Fault("ROUTES_PROVIDER_UNAVAILABLE", 502);
  }
  return response.json() as Promise<any>;
}

function branchCoordinates(branch: Doc) {
  return coordinatesSchema.safeParse(branch).success;
}

function nearestBranchByStraightLine(
  origin: z.infer<typeof coordinatesSchema>,
  destinations: Doc[],
) {
  if (!destinations.length) throw new Fault("BRANCHES_UNAVAILABLE", 404);
  const toRadians = (degrees: number) => (degrees * Math.PI) / 180;
  const originLat = toRadians(origin.latitude);
  return destinations
    .map((branch) => {
      const latitudeDelta = toRadians(branch.latitude) - originLat;
      const longitudeDelta = toRadians(branch.longitude - origin.longitude);
      const haversine =
        Math.sin(latitudeDelta / 2) ** 2 +
        Math.cos(originLat) *
          Math.cos(toRadians(branch.latitude)) *
          Math.sin(longitudeDelta / 2) ** 2;
      return { branch, distance: haversine };
    })
    .sort((first, second) => first.distance - second.distance)[0].branch;
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
    let branch: Doc;
    if (branchId) {
      const selected = await d.bank.store.run((t) =>
        t.get(`bankBranches/${branchId}`),
      );
      if (
        !selected ||
        selected.active !== true ||
        !branchCoordinates(selected)
      ) {
        throw new Fault("BRANCHES_UNAVAILABLE", 404);
      }
      branch = { ...selected, id: branchId };
    } else {
      const branches = await d.bank.store.run((t) =>
        t.list("bankBranches", { active: true }, { limit: 100 }),
      );
      branch = nearestBranchByStraightLine(
        origin,
        branches.filter(branchCoordinates),
      );
    }
    const routeResult = await googlePost(
      "/directions/v2:computeRoutes",
      key,
      {
        origin: { location: { latLng: origin } },
        destination: {
          location: {
            latLng: {
              latitude: branch.latitude,
              longitude: branch.longitude,
            },
          },
        },
        travelMode: "WALK",
        polylineQuality: "OVERVIEW",
        languageCode: "vi",
      },
      "routes.distanceMeters,routes.duration,routes.polyline.encodedPolyline",
    );
    const best = routeResult.routes?.[0];
    const encodedPolyline = best?.polyline?.encodedPolyline;
    if (!encodedPolyline) throw new Fault("WALKING_ROUTE_UNAVAILABLE", 404);
    return {
      branch,
      distanceMeters: best.distanceMeters ?? 0,
      duration: best.duration,
      encodedPolyline,
    };
  });
}
