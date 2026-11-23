import { GetObjectCommand, HeadObjectCommand, S3Client } from "@aws-sdk/client-s3";
import { beforeEach, expect, it, vi } from "vitest";
import { Banking } from "../src/core/banking.js";
import { type Actor } from "../src/modules/auth/auth.types.js";
import { Evidence } from "../src/modules/ekyc/ekyc.service.js";
import { S3Evidence } from "../src/modules/ekyc/s3.provider.js";
import { Payments } from "../src/modules/payments/payment.service.js";
import { MemoryStore } from "./support/memory-store.js";
const a: Actor = { uid: "alice", role: "CUSTOMER", permissions: [] };
let s: MemoryStore, b: Banking;
beforeEach(async () => {
  s = new MemoryStore();
  b = new Banking(s, { otpSecret: "test", deliver: async () => {} });
  await s.run(async (t) => {
    t.set("users/alice", { active: true, role: "CUSTOMER" });
    t.set("bills/bill", {
      ownerUid: "alice",
      amountMinor: 20000,
      currency: "VND",
      status: "UNPAID",
    });
  });
});
it("cancellation stays terminal and releases only its own invoice lock", async () => {
  const p = new Payments(b, undefined);
  await s.run(async (t) => {
    t.set("payments/p", {
      ownerUid: "alice",
      billId: "bill",
      amountMinor: 20000,
      currency: "VND",
      providerId: "pi_1",
      status: "PROCESSING",
    });
    t.set("billPaymentLocks/bill", { paymentId: "p" });
  });
  const event = {
    id: "evt_cancel",
    type: "payment_intent.canceled",
    data: {
      object: {
        id: "pi_1",
        amount: 20000,
        currency: "vnd",
        metadata: { paymentId: "p" },
        livemode: false,
      },
    },
  };
  await p.event(event);
  await p.event({
    ...event,
    id: "evt_old",
    type: "payment_intent.payment_failed",
  });
  expect((await s.run((t) => t.get("payments/p")))!.status).toBe("CANCELLED");
  expect((await s.run((t) => t.get("billPaymentLocks/bill")))!.released).toBe(
    true,
  );
});
it("evidence requires consent and refuses another owner", async () => {
  const e = new Evidence(b, undefined);
  await expect(e.create(a, false)).rejects.toThrow();
  const r = await e.create(a, true);
  await expect(
    e.ticket({ ...a, uid: "bob" }, r.id, "image/jpeg", 1000),
  ).rejects.toThrow();
  await expect(e.ticket(a, r.id, "image/jpeg", 1000)).rejects.toMatchObject({
    code: "SETUP_REQUIRED",
  });
});
it("cannot submit an eKYC request before validated evidence", async () => {
  const e = new Evidence(b, undefined),
    r = await e.create(a, true);
  await expect(e.submit(a, r.id)).rejects.toMatchObject({
    code: "EVIDENCE_REQUIRED",
  });
});

it("locks each evidence slot to its current ticket and freezes the submitted versions", async () => {
  const officer: Actor = { uid: "reviewer", role: "OFFICER", permissions: ["EKYC_REVIEW"] };
  await s.run(async (t) => {
    t.set("users/reviewer", { active: true, role: "OFFICER" });
    t.set("officers/reviewer", { active: true, permissions: ["EKYC_REVIEW"] });
    t.set("customers/profile", { uidRef: a.uid, kycStatus: "NOT_SUBMITTED" });
  });
  const e = new Evidence(b, {
    upload: async ({ key }) => ({ url: "https://example.invalid", fields: { key } }),
    verify: async ({ key }) => "version-" + key.split("/").at(-1),
    download: async ({ versionId }) => "https://example.invalid/" + versionId,
  });
  const draft = await e.create(a, true);
  expect((await e.create(a, true)).id).toBe(draft.id);
  const stale = await e.ticket(a, draft.id, "image/jpeg", 1000, 0);
  const front = await e.ticket(a, draft.id, "image/jpeg", 1000, 0);
  const selfie = await e.ticket(a, draft.id, "image/jpeg", 1000, 2);
  await expect(e.confirm(a, stale.id)).rejects.toMatchObject({ code: "UPLOAD_SUPERSEDED" });
  await e.confirm(a, selfie.id);
  await e.confirm(a, front.id);
  await expect(e.submit(a, draft.id)).rejects.toMatchObject({ code: "EVIDENCE_REQUIRED" });
  const back = await e.ticket(a, draft.id, "image/jpeg", 1000);
  await e.confirm(a, back.id);
  await expect(e.submit(a, draft.id)).rejects.toMatchObject({ code: "FACE_SETUP_REQUIRED" });
  expect((await s.run((t) => t.get("customers/profile")))?.kycStatus).toBe("REVIEWING");
  await expect(e.ticket(a, draft.id, "image/jpeg", 1000, 0)).rejects.toMatchObject({ code: "INVALID_EKYC_STATE" });
  await expect(e.evidence(a, draft.id, 0)).rejects.toMatchObject({ code: "FORBIDDEN" });
  expect(await e.evidence(officer, draft.id, 1)).toEqual({ url: "https://example.invalid/version-" + back.id });
  expect((await e.list(officer, {}, true)).items[0].storageRefs).toEqual(
    [0, 1, 2].map((index) => ({ index, mime: "image/jpeg", bytes: 1000 })),
  );
  expect((await e.list(a, { limit: 1 })).items[0]).toMatchObject({
    id: draft.id, reviewStatus: "REVIEWING",
  });
});

it("requires an immutable S3 version and signs evidence tickets for one exact object", async () => {
  const client = new S3Client({
    region: "us-east-1",
    credentials: { accessKeyId: "test", secretAccessKey: "test" },
  });
  const provider = new S3Evidence(client, "test-evidence");
  const image = { key: "ekyc/request/ticket", mime: "image/jpeg", bytes: 1000 } as const;
  const send = vi.spyOn(client, "send");
  send.mockResolvedValueOnce({ VersionId: "null", ContentType: image.mime, ContentLength: image.bytes });
  await expect(provider.verify(image)).rejects.toMatchObject({ code: "INVALID_EVIDENCE" });
  expect(send).toHaveBeenCalledTimes(1);

  const head = { VersionId: "version-1", ContentType: image.mime, ContentLength: image.bytes };
  const range = { VersionId: head.VersionId, ContentType: image.mime, ContentRange: "bytes 0-2/1000" };
  send.mockResolvedValueOnce(head).mockResolvedValueOnce({
    ...range, Body: { transformToByteArray: async () => Uint8Array.from([0, 1, 2]) },
  });
  await expect(provider.verify(image)).rejects.toMatchObject({ code: "INVALID_EVIDENCE" });
  send.mockResolvedValueOnce(head).mockResolvedValueOnce({
    ...range, Body: { transformToByteArray: async () => Uint8Array.from([0xff, 0xd8, 0xff]) },
  });
  expect(await provider.verify(image)).toBe(head.VersionId);
  expect(send.mock.calls[3][0]).toBeInstanceOf(HeadObjectCommand);
  const read = send.mock.calls[4][0];
  expect(read).toBeInstanceOf(GetObjectCommand);
  expect(read.input).toMatchObject({ Key: image.key, VersionId: head.VersionId, Range: "bytes=0-2" });

  const { fields } = await provider.upload(image);
  const policy = JSON.parse(Buffer.from(fields.Policy, "base64").toString());
  expect(policy.conditions).toEqual(expect.arrayContaining([
    ["eq", "$Content-Type", image.mime], ["content-length-range", image.bytes, image.bytes],
  ]));
  expect(fields.key).toBe(image.key);
  const download = new URL(await provider.download({ ...image, versionId: head.VersionId }));
  expect(download.searchParams.get("versionId")).toBe(head.VersionId);
  expect(download.searchParams.get("X-Amz-Expires")).toBe("120");
  expect(send).toHaveBeenCalledTimes(5);
});

it("missing Stripe config is explicit and does not mark invoice paid", async () => {
  const p = new Payments(b, undefined);
  await expect(p.create(a, "bill", "request-1")).rejects.toMatchObject({
    code: "SETUP_REQUIRED",
  });
  expect((await s.run((t) => t.get("bills/bill")))!.status).toBe("UNPAID");
});
it("webhooks deduplicate and cannot regress successful payment", async () => {
  const p = new Payments(b, undefined);
  await s.run(async (t) => {
    t.set("payments/p", {
      ownerUid: "alice",
      billId: "bill",
      amountMinor: 20000,
      currency: "VND",
      providerId: "pi_1",
      status: "PROCESSING",
    });
  });
  const event = {
    id: "evt_1",
    type: "payment_intent.succeeded",
    data: {
      object: {
        id: "pi_1",
        amount: 20000,
        currency: "vnd",
        metadata: { paymentId: "p" },
        livemode: false,
      },
    },
  };
  await p.event(event);
  await p.event(event);
  await p.event({
    ...event,
    id: "evt_2",
    type: "payment_intent.payment_failed",
  });
  expect((await s.run((t) => t.get("payments/p")))!.status).toBe("SUCCEEDED");
  expect(await s.run((t) => t.list("outbox"))).toHaveLength(1);
});
it("webhook amount mismatch never settles bill", async () => {
  const p = new Payments(b, undefined);
  await s.run(async (t) =>
    t.set("payments/p", {
      ownerUid: "alice",
      billId: "bill",
      amountMinor: 20000,
      currency: "VND",
      providerId: "pi_1",
      status: "PROCESSING",
    }),
  );
  await expect(
    p.event({
      id: "evt_bad",
      type: "payment_intent.succeeded",
      data: {
        object: {
          id: "pi_1",
          amount: 1,
          currency: "vnd",
          metadata: { paymentId: "p" },
          livemode: false,
        },
      },
    }),
  ).rejects.toThrow();
  expect((await s.run((t) => t.get("bills/bill")))!.status).toBe("UNPAID");
});
