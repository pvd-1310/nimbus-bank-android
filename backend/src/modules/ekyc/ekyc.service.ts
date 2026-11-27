import { randomUUID } from "node:crypto";
import type { Banking } from "../../core/banking.js";
import type { Doc, Tx } from "../../core/store.js";
import { check, Fault, key } from "../../shared/errors.js";
import { page, queryOptions } from "../../shared/pagination.js";
import { faceTemplatePath, type FaceService } from "../security/face.js";
import type { Actor } from "../auth/auth.types.js";

type ImageMime = "image/jpeg" | "image/png";
type CaptureMode = "FULL_ID" | "DEMO_SELFIE_ONLY";
type EvidenceStatus = "DRAFT" | "REVIEWING" | "VERIFIED" | "REJECTED" | "RESUBMIT_REQUIRED";
interface Image {
  key: string;
  mime: ImageMime;
  bytes: number;
}
interface VerifiedImage extends Image {
  versionId: string;
}
export interface EvidenceProvider {
  upload(image: Image): Promise<{ url: string; fields: Record<string, string> }>;
  verify(image: Image): Promise<string>;
  download(image: VerifiedImage): Promise<string>;
  read?(image: VerifiedImage): Promise<Buffer>;
}
interface UploadTicket extends Image {
  requestId: string;
  index: number;
  createdAt?: number;
  purging?: boolean;
}
interface Slot {
  ticketId: string;
  image?: VerifiedImage;
}
interface EvidenceRecord {
  requestId: string;
  customerUid: string;
  consentAt: number;
  createdAt: number;
  reviewStatus: EvidenceStatus;
  mode?: CaptureMode;
  slots: Record<number, Slot>;
  storageRefs: VerifiedImage[];
  submittedAt?: number;
  reviewedBy?: string | null;
  reviewedAt?: number | null;
  reason?: string;
  confirmedIdentity?: Doc;
}

const collection = "ekycRequests";
const requestPath = (id: string) => `${collection}/${id}`;
const ticketPath = (id: string) => `ekycUploadTickets/${id}`;
const indexes = [0, 1, 2] as const;
const maxTimestamp = 9_999_999_999_999;
const staleReviewMs = 60_000;
const validIndex = (index: number) => Number.isInteger(index) && index >= 0 && index < indexes.length;
const imageFailures = new Set([
  "INVALID_IMAGE", "INVALID_EVIDENCE", "INVALID_MIME", "INVALID_BYTES", "FACE_NOT_FOUND",
  "MULTIPLE_FACES", "FACE_LOW_QUALITY", "FACE_DETECTION_UNCERTAIN", "FACE_TOO_SMALL",
  "FACE_CROPPED", "FACE_LANDMARKS_INVALID", "FACE_EXPOSURE", "FACE_BLURRY",
  "FACE_EMBEDDING_INVALID",
]);

function response(record: EvidenceRecord, officer = false) {
  const { requestId, customerUid, consentAt, createdAt, reviewStatus, reason, submittedAt, reviewedBy, reviewedAt } = record;
  return {
    id: requestId, requestId, customerUid, consentAt, createdAt, reviewStatus, mode: record.mode ?? "FULL_ID",
    reason, submittedAt, reviewedBy, reviewedAt,
    ...(officer && { storageRefs: (record.storageRefs ?? []).map(({ mime, bytes }, index) => ({ index: record.mode === "DEMO_SELFIE_ONLY" ? 2 : index, mime, bytes })) }),
  };
}

export class Evidence {
  constructor(
    private bank: Banking,
    readonly provider: EvidenceProvider | undefined,
    private retentionMs = 90 * 86_400_000,
    readonly captureMode: CaptureMode = "FULL_ID",
  ) {}

  private async customer(t: Tx, a: Actor) {
    await this.bank.authorize(t, a, undefined, true);
    check(a.role === "CUSTOMER", "FORBIDDEN", 403);
  }

  private async get(t: Tx, id: string) {
    key(id);
    const record = (await t.get(requestPath(id))) as EvidenceRecord | undefined;
    check(record, "NOT_FOUND", 404);
    return record;
  }

  private async owned(t: Tx, a: Actor, id: string) {
    const record = await this.get(t, id);
    check(record.customerUid === a.uid, "FORBIDDEN", 403);
    return record;
  }

  async authorizeRecognition(a: Actor, id: string) {
    return this.bank.store.run(async (t) => {
      await this.customer(t, a);
      const record = await this.owned(t, a, id);
      check(record.reviewStatus === "DRAFT" && (record.mode ?? "FULL_ID") === "FULL_ID", "INVALID_EKYC_STATE", 409);
    });
  }

  private async listAll(t: Tx, name: string, where: Doc) {
    const rows: Doc[] = [];
    let after: string | undefined;
    while (true) {
      const page = await t.list(name, where, { limit: 100, ...(after ? { after } : {}) });
      rows.push(...page);
      if (page.length < 100) return rows;
      after = page[page.length - 1].id;
    }
  }

  async revokeFaceTemplate(a: Actor) {
    return this.bank.store.run(async (t) => {
      await this.customer(t, a);
      const now = this.bank.now();
      const headPath = `ekycCustomerHeads/${a.uid}`;
      const head = await t.get(headPath);
      const requests = await this.listAll(t, collection, { customerUid: a.uid });
      const profile = await this.bank.linkedProfile(t, "customers", a.uid);
      const template = await t.get(faceTemplatePath(a.uid));
      const latestId = head?.requestId ?? template?.requestId ?? requests[0]?.id;
      const tickets: Doc[] = [];
      for (const row of requests) {
        const requestId = row.requestId ?? row.id;
        tickets.push(...await this.listAll(t, "ekycUploadTickets", { requestId }));
      }

      const revokeTemplate = template?.status !== "REVOKED" || typeof template?.encrypted === "string";
      let changed = revokeTemplate;
      if (revokeTemplate) {
        t.set(faceTemplatePath(a.uid), {
          uid: a.uid,
          status: "REVOKED",
          revokedAt: now,
        });
      }

      for (const row of requests) {
        const requestId = row.requestId ?? row.id;
        const isLatest = requestId === latestId;
        const hasEvidence = Object.keys(row.slots ?? {}).length > 0 || (row.storageRefs ?? []).length > 0;
        if (hasEvidence || (isLatest && (row.reviewStatus !== "RESUBMIT_REQUIRED" || row.reason !== "FACE_TEMPLATE_REVOKED"))) {
          changed = true;
        }
        const { id: _id, _cursor, ...record } = row;
        t.set(requestPath(requestId), {
          ...record,
          ...(isLatest ? {
            reviewStatus: "RESUBMIT_REQUIRED",
            reason: "FACE_TEMPLATE_REVOKED",
            revokedAt: record.revokedAt ?? now,
          } : {}),
          slots: {},
          storageRefs: [],
          evidencePurgeRequestedAt: record.evidencePurgeRequestedAt ?? now,
        });
      }

      let objectsQueued = 0;
      for (const ticket of tickets) {
        objectsQueued++;
        if (ticket.purging !== true) changed = true;
        const ticketId = ticket.id;
        const { id: _id, _cursor, ...uploadTicket } = ticket;
        t.set(ticketPath(ticketId), {
          ...uploadTicket,
          purging: true,
          purgeRequestedAt: ticket.purgeRequestedAt ?? now,
        });
      }

      const ekycStatus = latestId ? "RESUBMIT_REQUIRED" : profile?.kycStatus ?? "NOT_SUBMITTED";
      if (latestId && profile && profile.kycStatus !== ekycStatus) {
        const { id, _cursor, ...data } = profile;
        t.set(`customers/${id}`, { ...data, kycStatus: ekycStatus });
        changed = true;
      }
      if (changed) {
        this.bank.audit(t, a, "FACE_TEMPLATE_REVOKED", a.uid, "", {
          before: {
            templateStatus: template?.status ?? "NOT_FOUND",
            ekycStatus: profile?.kycStatus ?? "NOT_FOUND",
          },
          after: { templateStatus: "REVOKED", ekycStatus, objectsQueued },
        });
        this.bank.notifications.emit(t, {
          ownerUid: a.uid,
          type: "SECURITY",
          title: "Dữ liệu khuôn mặt đã bị thu hồi",
          body: "Mẫu khuôn mặt eKYC của bạn đã được xóa. Vui lòng xác thực lại khi cần.",
          refId: a.uid,
        });
      }
      return {
        status: "REVOKED",
        ekycStatus,
        evidenceCleanup: {
          status: objectsQueued > 0 ? "PENDING" : "NOT_REQUIRED",
          objectsQueued,
        },
      };
    });
  }

  private s3() {
    check(this.provider, "SETUP_REQUIRED", 503);
    return this.provider;
  }

  async create(a: Actor, consented: boolean) {
    check(consented === true, "CONSENT_REQUIRED");
    return this.bank.store.run(async (t) => {
      await this.customer(t, a);
      const headPath = `ekycCustomerHeads/${a.uid}`;
      const head = await t.get(headPath);
      const latest = head && await this.owned(t, a, head.requestId);
      if (latest?.reviewStatus === "DRAFT") {
        if (latest.mode !== this.captureMode) {
          const migrated = { ...latest, mode: this.captureMode };
          t.set(requestPath(latest.requestId), migrated);
          return response(migrated);
        }
        return response(latest);
      }
      check(!latest || latest.reviewStatus === "REJECTED" || latest.reviewStatus === "RESUBMIT_REQUIRED", "INVALID_EKYC_STATE", 409);
      // Reverse, fixed-width timestamp gives newest-first scans using the store's document-ID cursor.
      const now = this.bank.now();
      const orderTime = Math.max(now, (head?.orderTime ?? -1) + 1);
      const requestId = `${String(maxTimestamp - orderTime).padStart(13, "0")}-${randomUUID()}`;
      const record: EvidenceRecord = {
        requestId, customerUid: a.uid, consentAt: now, createdAt: now,
        reviewStatus: "DRAFT", mode: this.captureMode, slots: {}, storageRefs: [],
      };
      t.set(requestPath(requestId), record);
      t.set(headPath, { requestId, orderTime });
      return response(record);
    });
  }

  async list(a: Actor, query: Doc, officer = false) {
    const options = queryOptions(query);
    return this.bank.store.run(async (t) => {
      if (officer) await this.bank.authorize(t, a, "EKYC_REVIEW");
      else await this.customer(t, a);
      const rows = await t.list(collection, officer ? {} : { customerUid: a.uid }, {
        limit: options.limit, after: options.after,
      });
      const items = rows.map((row) => {
        const record = row as EvidenceRecord;
        const current = !officer && record.reviewStatus === "DRAFT" && record.mode !== this.captureMode
          ? { ...record, mode: this.captureMode } : record;
        if (current !== record) t.set(requestPath(record.requestId), current);
        return { ...response(current, officer), _cursor: row._cursor };
      });
      const submissionStatus = !this.provider ? "STORAGE_SETUP_REQUIRED"
        : !this.bank.options.face ? "FACE_SETUP_REQUIRED" : "CONFIGURED";
      return { ...page(items, query), captureMode: this.captureMode, submissionStatus };
    });
  }

  async ticket(a: Actor, id: string, mime: string, bytes: number, index?: number) {
    check(mime === "image/jpeg" || mime === "image/png", "INVALID_MIME");
    check(Number.isInteger(bytes) && bytes > 0 && bytes <= 5_000_000, "INVALID_BYTES");
    const ticketId = randomUUID();
    const ticket = await this.bank.store.run(async (t) => {
      await this.customer(t, a);
      const record = await this.owned(t, a, id);
      check(record.reviewStatus === "DRAFT", "INVALID_EKYC_STATE", 409);
      this.s3();
      const slotIndex = index ?? (record.mode === "DEMO_SELFIE_ONLY" ? 2
        : indexes.find((i) => !record.slots[i]) ?? indexes.find((i) => !record.slots[i]?.image));
      check(slotIndex !== undefined && validIndex(slotIndex), "INVALID_EVIDENCE_INDEX");
      check((record.mode ?? "FULL_ID") !== "DEMO_SELFIE_ONLY" || slotIndex === 2, "INVALID_EVIDENCE_INDEX");
      const ticket: UploadTicket = { requestId: id, index: slotIndex, key: `ekyc/${id}/${ticketId}`, createdAt: this.bank.now(), mime, bytes };
      t.set(ticketPath(ticketId), ticket);
      t.set(requestPath(id), { ...record, slots: { ...record.slots, [slotIndex]: { ticketId } } });
      return ticket;
    });
    return { id: ticketId, upload: await this.s3().upload(ticket) };
  }

  private async pending(t: Tx, a: Actor, id: string) {
    await this.customer(t, a);
    key(id);
    const ticket = (await t.get(ticketPath(id))) as UploadTicket | undefined;
    check(ticket && !ticket.purging, "NOT_FOUND", 404);
    const record = await this.owned(t, a, ticket.requestId);
    const slot = record.slots[ticket.index];
    check(slot?.ticketId === id, "UPLOAD_SUPERSEDED", 409);
    return { ticket, record, slot };
  }

  async confirm(a: Actor, id: string) {
    const { ticket, slot, record } = await this.bank.store.run((t) => this.pending(t, a, id));
    if (slot.image) return { id, confirmed: true };
    check(record.reviewStatus === "DRAFT", "INVALID_EKYC_STATE", 409);
    const { key: objectKey, mime, bytes } = ticket;
    const versionId = await this.s3().verify(ticket);
    check(versionId && versionId !== "null", "INVALID_EVIDENCE");
    return this.bank.store.run(async (t) => {
      const { record, slot } = await this.pending(t, a, id);
      if (!slot.image) {
        check(record.reviewStatus === "DRAFT", "INVALID_EKYC_STATE", 409);
        const image: VerifiedImage = { key: objectKey, mime, bytes, versionId };
        t.set(requestPath(ticket.requestId), { ...record, slots: { ...record.slots, [ticket.index]: { ticketId: id, image } } });
      }
      return { id, confirmed: true };
    });
  }

  async submit(a: Actor, id: string, identity?: Doc) {
    const snapshot = await this.bank.store.run(async (t) => {
      await this.customer(t, a);
      const record = await this.owned(t, a, id);
      if (record.reviewStatus === "VERIFIED" || record.reviewStatus === "RESUBMIT_REQUIRED") return record;
      if (record.reviewStatus === "REVIEWING") {
        check(record.submittedAt && this.bank.now() - record.submittedAt >= staleReviewMs,
          "EKYC_REVIEW_IN_PROGRESS", 409);
        return record;
      }
      check(record.reviewStatus === "DRAFT", "INVALID_EKYC_STATE", 409);
      const front = record.slots[0]?.image;
      const back = record.slots[1]?.image;
      const selfie = record.slots[2]?.image;
      const mode = record.mode ?? "FULL_ID";
      check(selfie && (mode === "DEMO_SELFIE_ONLY" || (front && back)), "EVIDENCE_REQUIRED", 409);
      const next: EvidenceRecord = { ...record, mode, reviewStatus: "REVIEWING", submittedAt: this.bank.now(),
        storageRefs: mode === "DEMO_SELFIE_ONLY" ? [selfie] : [front!, back!, selfie] };
      await this.writeStatus(t, next);
      return next;
    });
    if (snapshot.reviewStatus !== "REVIEWING") return response(snapshot);

    const face = this.bank.options.face;
    const demo = snapshot.mode === "DEMO_SELFIE_ONLY";
    let enrollment: Awaited<ReturnType<FaceService["enroll"]>> | undefined;
    let failure: string | undefined;
    try {
      const storage = this.s3();
      check(face && storage.read, "FACE_SETUP_REQUIRED", 503);
      check(snapshot.storageRefs.length === (demo ? 1 : 3)
        && snapshot.storageRefs.every((item) => item.versionId), "EVIDENCE_REQUIRED", 409);
      const selfie = snapshot.storageRefs[demo ? 0 : 2];
      enrollment = await face.enroll(a.uid, id, selfie.mime, await storage.read(selfie), this.bank.now());
      check(enrollment, "FACE_SETUP_REQUIRED", 503);
    } catch (error) {
      if (!(error instanceof Fault) || !imageFailures.has(error.code)) throw error;
      failure = error.code;
    }

    return this.bank.store.run(async (t) => {
      await this.customer(t, a);
      const record = await this.owned(t, a, id);
      if (record.reviewStatus === "VERIFIED" || record.reviewStatus === "RESUBMIT_REQUIRED") return response(record);
      check(record.reviewStatus === "REVIEWING"
        && record.submittedAt === snapshot.submittedAt
        && JSON.stringify(record.storageRefs) === JSON.stringify(snapshot.storageRefs), "INVALID_EKYC_STATE", 409);
      if (failure) {
        const next: EvidenceRecord = { ...record, reviewStatus: "RESUBMIT_REQUIRED", reason: failure,
          reviewedBy: "SYSTEM", reviewedAt: this.bank.now() };
        await this.writeStatus(t, next);
        this.bank.audit(t, a, "EKYC_AUTO_REVIEW", id, "", {
          before: { reviewStatus: "REVIEWING" }, after: { reviewStatus: "RESUBMIT_REQUIRED", mode: record.mode, reason: failure },
        });
        this.bank.notifications.emit(t, {
          id: `ekyc_${id}_resubmit`,
          ownerUid: a.uid,
          type: "EKYC",
          title: "Hồ sơ eKYC cần chụp lại",
          body: `Hồ sơ xác thực chưa đạt (${failure}). Vui lòng thực hiện lại eKYC.`,
          refId: id,
        });
        return response(next);
      }
      const user = await t.get(`users/${a.uid}`);
      // An account may already be active through an earlier onboarding or migration.
      // Completing this verified selfie enrollment remains idempotent in that state.
      check(user?.role === "CUSTOMER" && ["ONBOARDING", "ACTIVE"].includes(user.status), "INVALID_CUSTOMER_STATE", 409);
      const customer = await this.bank.linkedProfile(t, "customers", a.uid);
      check(customer, "CUSTOMER_NOT_FOUND", 404);
      if (user.selfRegistered === true && user.status === "ONBOARDING") {
        const { id: customerId, _cursor, ...customerData } = customer;
        void _cursor;
        if (identity && typeof identity.nationalIdNumber === "string" && customerData.nationalIdNumber) {
          check(
            identity.nationalIdNumber === customerData.nationalIdNumber,
            "NATIONAL_ID_MISMATCH",
            409,
          );
        }
        const now = this.bank.now();
        const checkingAccounts = await t.list("accounts", { ownerUid: a.uid, type: "CHECKING" }, { limit: 100 });
        t.set(`users/${a.uid}`, {
          ...user,
          active: true,
          status: "ACTIVE",
        });
        t.set(`customers/${customerId}`, {
          ...customerData,
          ...(identity?.dateOfBirth ? { dateOfBirth: identity.dateOfBirth } : {}),
          ...(identity?.address ? { address: identity.address } : {}),
          ...(identity?.gender ? { gender: identity.gender } : {}),
          ...(identity?.nationality ? { nationality: identity.nationality } : {}),
          ...(identity?.identityDocumentExpiry ? { identityDocumentExpiry: identity.identityDocumentExpiry } : {}),
          status: "ACTIVE",
          kycStatus: "VERIFIED",
        });
        for (const account of checkingAccounts) {
          const { id: accountId, _cursor: accountCursor, ...accountData } = account;
          void accountCursor;
          t.set(`accounts/${accountId}`, {
            ...accountData,
            status: accountData.status === "CLOSED" ? "CLOSED" : "ACTIVE",
          });
        }
        t.set(faceTemplatePath(a.uid), enrollment!);
        const verified: EvidenceRecord = {
          ...record,
          reviewStatus: "VERIFIED",
          reviewedBy: "SYSTEM_SELF_REGISTER",
          reviewedAt: now,
        };
        await this.writeStatus(t, verified);
        this.bank.audit(t, a, "EKYC_AUTO_VERIFIED", id, "", {
          before: { reviewStatus: "DRAFT" }, after: { reviewStatus: "VERIFIED", mode: record.mode, selfRegistered: true },
        });
        this.bank.notifications.emit(t, {
          id: `ekyc_${id}_verified`,
          ownerUid: a.uid,
          type: "EKYC",
          title: "Xác thực eKYC thành công",
          body: "Hồ sơ định danh và tài khoản thanh toán của bạn đã được kích hoạt.",
          refId: id,
        });
        return response(verified);
      }
      const next: EvidenceRecord & { enrolledTemplate?: unknown } = {
        ...record,
        reviewStatus: "REVIEWING",
        enrolledTemplate: enrollment!,
      };
      await this.writeStatus(t, next);
      this.bank.audit(t, a, "EKYC_SUBMITTED", id, "", {
        before: { reviewStatus: "DRAFT" }, after: { reviewStatus: "REVIEWING", mode: record.mode, templateEnrolled: true },
      });
      this.bank.notifications.emit(t, {
        id: `ekyc_${id}_reviewing`,
        ownerUid: a.uid,
        type: "EKYC",
        title: "Hồ sơ eKYC đang chờ duyệt",
        body: "Hồ sơ xác thực của bạn đã được gửi và đang chờ nhân viên ngân hàng phê duyệt.",
        refId: id,
      });
      return response(next);
    });
  }

  async review(a: Actor, id: string, decision: "VERIFIED" | "RESUBMIT_REQUIRED" | "REJECTED", reason?: string) {
    return this.bank.store.run(async (t) => {
      await this.bank.authorize(t, a, "EKYC_REVIEW");
      const rawRecord = await this.get(t, id) as EvidenceRecord & { enrolledTemplate?: Doc };
      if (rawRecord.reviewStatus === decision) return response(rawRecord);
      check(rawRecord.reviewStatus === "REVIEWING", "INVALID_EKYC_STATE", 409);
      const customerUid = rawRecord.customerUid;
      const customer = await this.bank.linkedProfile(t, "customers", customerUid);
      check(customer, "CUSTOMER_NOT_FOUND", 404);
      const { enrolledTemplate, ...record } = rawRecord;
      const { id: customerId, _cursor, ...customerData } = customer;
      void _cursor;

      if (decision === "VERIFIED") {
        const user = await t.get(`users/${customerUid}`);
        check(user?.role === "CUSTOMER" && ["ONBOARDING", "ACTIVE"].includes(user.status), "INVALID_CUSTOMER_STATE", 409);
        const checkingAccounts = await t.list("accounts", { ownerUid: customerUid, type: "CHECKING" }, { limit: 100 });
        t.set(`users/${customerUid}`, {
          ...user,
          active: true,
          status: "ACTIVE",
        });
        t.set(`customers/${customerId}`, { ...customerData, status: "ACTIVE", kycStatus: "VERIFIED" });
        for (const account of checkingAccounts) {
          const { id: accountId, _cursor: accountCursor, ...accountData } = account;
          void accountCursor;
          t.set(`accounts/${accountId}`, {
            ...accountData,
            status: accountData.status === "CLOSED" ? "CLOSED" : "ACTIVE",
          });
        }
        if (enrolledTemplate) {
          t.set(faceTemplatePath(customerUid), enrolledTemplate);
        }
        const next: EvidenceRecord = { ...record, reviewStatus: "VERIFIED", reviewedBy: a.uid, reviewedAt: this.bank.now() };
        t.set(requestPath(id), next);
        this.bank.audit(t, a, "EKYC_OFFICER_REVIEW", id, "", {
          before: { reviewStatus: "REVIEWING" }, after: { reviewStatus: "VERIFIED", reviewedBy: a.uid },
        });
        this.bank.notifications.emit(t, {
          id: `ekyc_${id}_verified`,
          ownerUid: customerUid,
          type: "EKYC",
          title: "Xác thực eKYC thành công",
          body: "Hồ sơ định danh và tài khoản thanh toán của bạn đã được kích hoạt.",
          refId: id,
        });
        return response(next);
      }

      const cleanReason = (reason ?? "").trim() || (decision === "RESUBMIT_REQUIRED"
        ? "Ảnh chụp chưa rõ nét, vui lòng thực hiện lại eKYC."
        : "Hồ sơ eKYC không đạt yêu cầu xác thực.");
      const next: EvidenceRecord = {
        ...record,
        reviewStatus: decision,
        reason: cleanReason,
        reviewedBy: a.uid,
        reviewedAt: this.bank.now(),
      };
      t.set(`customers/${customerId}`, { ...customerData, kycStatus: decision });
      t.set(requestPath(id), next);
      this.bank.audit(t, a, "EKYC_OFFICER_REVIEW", id, cleanReason, {
        before: { reviewStatus: "REVIEWING" }, after: { reviewStatus: decision, reason: cleanReason, reviewedBy: a.uid },
      });
      this.bank.notifications.emit(t, {
        id: `ekyc_${id}_${decision.toLowerCase()}`,
        ownerUid: customerUid,
        type: "EKYC",
        title: decision === "RESUBMIT_REQUIRED" ? "Hồ sơ eKYC cần chụp lại" : "Hồ sơ eKYC bị từ chối",
        body: cleanReason,
        refId: id,
      });
      return response(next);
    });
  }

  private async writeStatus(t: Tx, record: EvidenceRecord) {
    const profile = await this.bank.linkedProfile(t, "customers", record.customerUid);
    if (profile) {
      const { id, _cursor, ...data } = profile;
      t.set(`customers/${id}`, { ...data, kycStatus: record.reviewStatus });
    }
    t.set(requestPath(record.requestId), record);
  }

  private retained(record: EvidenceRecord) {
    return !record.reviewedAt || this.bank.now() < record.reviewedAt + this.retentionMs;
  }

  async evidence(a: Actor, id: string, index: number, officer = true) {
    check(validIndex(index), "INVALID_EVIDENCE_INDEX");
    const image = await this.bank.store.run(async (t) => {
      if (officer) await this.bank.authorize(t, a, "EKYC_REVIEW");
      else await this.customer(t, a);
      const record = officer ? await this.get(t, id) : await this.owned(t, a, id);
      check(this.retained(record) && (!officer || record.reviewStatus !== "DRAFT"), "NOT_FOUND", 404);
      const image = record.reviewStatus === "DRAFT" ? record.slots[index]?.image
        : record.storageRefs[record.mode === "DEMO_SELFIE_ONLY" && index === 2 ? 0 : index];
      check(record.mode !== "DEMO_SELFIE_ONLY" || index === 2, "NOT_FOUND", 404);
      check(image?.versionId && image.versionId !== "null", "NOT_FOUND", 404);
      return image;
    });
    return { url: await this.s3().download(image) };
  }
}
