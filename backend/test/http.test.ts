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
    t.set("customers/alice", { uidRef: "alice", fullName: "Alice" });
    t.set("users/officer", { role: "OFFICER", active: true });
    t.set("officers/officer", { active: true, permissions: ["CUSTOMER_ADMIN", "RATE_UPDATE"] });
    t.set("users/restricted", { role: "OFFICER", active: true });
    t.set("officers/restricted", { active: true, permissions: ["AUDIT_READ"] });
    t.set("savingsDetails/sav-account", { productId: "sav-standard" });
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
      if (token === "officer")
        return { uid: "officer", role: "OFFICER", permissions: ["CUSTOMER_ADMIN", "RATE_UPDATE"] };
      if (token === "restricted")
        return { uid: "restricted", role: "OFFICER", permissions: ["AUDIT_READ"] };
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

it("selects the shortest walking route even when another branch is closer geographically", async () => {
  vi.stubEnv("GOOGLE_ROUTES_API_KEY", "test-key");
  vi.stubGlobal("fetch", async (url: string, options: RequestInit) => {
    if (url.endsWith(":computeRouteMatrix"))
      return new Response(null, { status: 400 });
    const body = JSON.parse(String(options.body));
    return Response.json({
      routes: [
        {
          distanceMeters: body.destination.location.latLng.longitude === 106.7 ? 500 : 410,
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
  expect(response.body.branch.id).toBe("west");
  expect(response.body.selection).toBe("SHORTEST_WALKING_DISTANCE");
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
it("restricts rate policy catalog and listing to RATE_UPDATE officers and records reasons", async () => {
  const app = await fixture();
  await request(app).get("/officer/rate-types").set("Authorization", "Bearer valid").expect(403);
  await request(app).get("/officer/rate-types").set("Authorization", "Bearer officer").expect(200).expect(({ body }) => expect(body).toEqual([
    { rateType: "SAVINGS", name: "Lãi suất tiết kiệm" },
    { rateType: "LOAN", name: "Lãi vay" },
  ]));
  const payload = { rateType: "SAVINGS", annualRateBasisPoints: 650, effectiveFrom: Date.now() + 60_000, reason: "Điều chỉnh theo chính sách kỳ hạn" };
  const created = await request(app).post("/officer/rate-policies").set("Authorization", "Bearer officer").send(payload).expect(200);
  expect(created.body.version).toBe(1);
  await request(app).get("/officer/rate-policies").set("Authorization", "Bearer officer").expect(200).expect(({ body }) => expect(body.items[0]).toMatchObject({ ...payload, createdBy: "officer", rateType: "SAVINGS", status: "SCHEDULED" }));
  await request(app).post("/officer/rate-policies").set("Authorization", "Bearer officer").send({ ...payload, rateType: "UNKNOWN" }).expect(400);
  await request(app).post("/officer/rate-policies").set("Authorization", "Bearer officer").send({ ...payload, reason: " " }).expect(400);
  await request(app).get("/officer/rate-policies").set("Authorization", "Bearer restricted").expect(403);
  await request(app).post("/officer/rate-policies").set("Authorization", "Bearer restricted").send(payload).expect(403);
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
it("requires an authorized officer to update a customer through the unversioned PATCH route", async () => {
  const app = await fixture();
  const path = "/officer/customers/alice";
  const update = { fullName: "Alice Nguyen", reason: "Corrected legal name" };
  await request(app).patch(path).set("Authorization", "Bearer valid").send(update).expect(403);
  await request(app).patch("/v1" + path).set("Authorization", "Bearer officer").send(update).expect(404);
  await request(app).patch(path).set("Authorization", "Bearer officer").send(update).expect(200);
  const profile = await request(app).get("/me").set("Authorization", "Bearer valid").expect(200);
  expect(profile.body.fullName).toBe(update.fullName);
});
it("mounts the unversioned eKYC routes and reports missing S3 configuration", async () => {
  const app = await fixture();
  const draft = await request(app)
    .post("/ekyc")
    .set("Authorization", "Bearer valid")
    .send({ consented: true })
    .expect(200);
  const id = draft.body.id;
  expect(id).toEqual(expect.any(String));
  const own = await request(app)
    .get("/ekyc?limit=1")
    .set("Authorization", "Bearer valid")
    .expect(200);
  expect(own.body.items).toEqual([expect.objectContaining({ id, reviewStatus: "DRAFT" })]);
  expect(own.body.submissionStatus).toBe("STORAGE_SETUP_REQUIRED");
  const ticket = await request(app)
    .post(`/ekyc/${id}/uploads`)
    .set("Authorization", "Bearer valid")
    .send({ mime: "image/jpeg", bytes: 1000, index: 0 })
    .expect(503);
  expect(ticket.body.error).toBe("SETUP_REQUIRED");
  await request(app).get("/officer/ekyc").set("Authorization", "Bearer officer").expect(403);
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
it("reports unconfigured chatbot and missing providers", async () => {
  const app = await fixture();
  const r = await request(app)
    .get("/capabilities")
    .set("Authorization", "Bearer valid");
  expect(r.body.chatbot).toBe("SETUP_REQUIRED");
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
