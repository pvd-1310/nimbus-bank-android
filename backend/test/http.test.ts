import Stripe from "stripe";
import request from "supertest";
import { afterEach, expect, it, vi } from "vitest";
import { createApp } from "../src/app.js";
import { Banking } from "../src/core/banking.js";
import { Evidence } from "../src/modules/ekyc/ekyc.service.js";
import { Payments } from "../src/modules/payments/payment.service.js";
import { StripeGateway } from "../src/modules/payments/stripe.provider.js";
import { MemoryStore } from "./support/memory-store.js";
async function fixture() {
  const s = new MemoryStore();
  await s.run(async (t) => {
    t.set("users/alice", { role: "CUSTOMER", active: true });
    t.set("bankBranches/central", {
      active: true,
      name: "Central",
      latitude: 10.78,
      longitude: 106.7,
    });
    t.set("bankBranches/west", {
      active: true,
      name: "West",
      latitude: 10.77,
      longitude: 106.68,
    });
    t.set("accounts/a", {
      ownerUid: "alice",
      availableMinor: 100,
      bookedMinor: 100,
      currency: "VND",
      status: "ACTIVE",
    });
    t.set("accounts/b", {
      ownerUid: "bob",
      availableMinor: 0,
      bookedMinor: 0,
      currency: "VND",
      status: "ACTIVE",
      version: 0,
    });
  });
  const b = new Banking(s, { otpSecret: "test", deliver: async () => {} });
  return createApp({
    bank: b,
    evidence: new Evidence(b, undefined),
    payments: new Payments(b, undefined),
    authenticate: async (token) => {
      if (token !== "valid") throw Error("bad token");
      return { uid: "alice", role: "CUSTOMER", permissions: [] };
    },
  });
}

afterEach(() => {
  vi.unstubAllEnvs();
  vi.unstubAllGlobals();
});

it("returns a walking route to the branch selected on the map", async () => {
  vi.stubEnv("GOOGLE_ROUTES_API_KEY", "test-key");
  vi.stubGlobal("fetch", async (url: string, options: RequestInit) => {
    const body = JSON.parse(String(options.body));
    if (
      url !== "https://routes.googleapis.com/directions/v2:computeRoutes" ||
      body.travelMode !== "WALK" ||
      body.destination?.location?.latLng?.longitude !== 106.68
    ) {
      return new Response(null, { status: 400 });
    }
    return Response.json({
      routes: [
        {
          distanceMeters: 420,
          duration: "330s",
          polyline: { encodedPolyline: "_p~iF~ps|U_ulLnnqC" },
        },
      ],
    });
  });

  const response = await request(await fixture())
    .post("/branches/routes")
    .set("Authorization", "Bearer valid")
    .send({ latitude: 10.7769, longitude: 106.7009, branchId: "west" });

  expect(response.status).toBe(200);
  expect(response.body).toMatchObject({
    branch: { id: "west" },
    distanceMeters: 420,
    duration: "330s",
    encodedPolyline: "_p~iF~ps|U_ulLnnqC",
  });
});

it("selects the closest branch without calling Compute Route Matrix", async () => {
  vi.stubEnv("GOOGLE_ROUTES_API_KEY", "test-key");
  vi.stubGlobal("fetch", async (url: string, options: RequestInit) => {
    if (url.endsWith(":computeRouteMatrix"))
      return new Response(null, { status: 400 });
    const body = JSON.parse(String(options.body));
    if (body.destination?.location?.latLng?.longitude !== 106.7) {
      return new Response(null, { status: 400 });
    }
    return Response.json({
      routes: [
        {
          distanceMeters: 500,
          duration: "390s",
          polyline: { encodedPolyline: "_p~iF~ps|U_ulLnnqC" },
        },
      ],
    });
  });

  const response = await request(await fixture())
    .post("/branches/routes")
    .set("Authorization", "Bearer valid")
    .send({ latitude: 10.7769, longitude: 106.7009 });

  expect(response.status).toBe(200);
  expect(response.body.branch.id).toBe("central");
});
it("health is public but accounts require a verified token", async () => {
  const app = await fixture();
  expect((await request(app).get("/health")).status).toBe(200);
  expect((await request(app).get("/accounts")).status).toBe(401);
  expect(
    (await request(app).get("/accounts").set("Authorization", "Bearer fake"))
      .status,
  ).toBe(401);
  expect(
    (
      await request(app)
        .get("/accounts")
        .set("Authorization", "Bearer valid")
    ).body.items,
  ).toHaveLength(1);
});
it("accepts template transfer input and returns canonical intentId", async () => {
  const app = await fixture();
  const r = await request(app)
    .post("/transfers/intents")
    .set("Authorization", "Bearer valid")
    .send({
      requestId: "canonical-transfer",
      sourceId: "a",
      target: { kind: "ACCOUNT", id: "b" },
      amountMinor: 10,
      currency: "VND",
    });
  expect(r.status).toBe(200);
  expect(r.body.intentId).toBe(r.body.id);
});
it("rejects privileged profile fields", async () => {
  const app = await fixture();
  expect(
    (
      await request(app)
        .patch("/me")
        .set("Authorization", "Bearer valid")
        .send({ role: "OFFICER" })
    ).status,
  ).toBe(400);
});
it("reports deferred chatbot and missing providers", async () => {
  const app = await fixture();
  const r = await request(app)
    .get("/capabilities")
    .set("Authorization", "Bearer valid");
  expect(r.body.chatbot).toBe("DEFERRED");
  expect(r.body.s3).toBe("SETUP_REQUIRED");
  expect(r.body.stripe).toBe("SETUP_REQUIRED");
});
it("rejects forged Stripe events at the HTTP boundary", async () => {
  const app = await fixture();
  const r = await request(app)
    .post("/webhooks/stripe")
    .send({ type: "payment_intent.succeeded" });
  expect(r.status).toBe(503);
});
it("rejects malformed JSON with safe errors", async () => {
  const app = await fixture();
  const r = await request(app)
    .patch("/me")
    .set("Authorization", "Bearer valid")
    .set("Content-Type", "application/json")
    .send("{invalid");
  expect(r.status).toBe(400);
  expect(r.body.error).toBeDefined();
  expect(JSON.stringify(r.body)).not.toContain("SyntaxError");
});
it("configured webhook rejects a forged signature without processing data", async () => {
  const bank = new Banking(new MemoryStore(), {
      otpSecret: "test",
      deliver: async () => {},
    }),
    payments = new Payments(
      bank,
      new StripeGateway(new Stripe("sk_test_dummy"), "whsec_dummy"),
    );
  const app = createApp({
    bank,
    payments,
    evidence: new Evidence(bank, undefined),
    authenticate: async () => {
      throw Error("unused");
    },
  });
  const r = await request(app)
    .post("/webhooks/stripe")
    .set("Stripe-Signature", "t=1,v1=forged")
    .send({ id: "evt_bad" });
  expect(r.status).toBe(400);
  expect(r.body.error).toBe("INVALID_SIGNATURE");
  expect(await bank.store.run((t) => t.list("webhookEvents"))).toHaveLength(0);
});
