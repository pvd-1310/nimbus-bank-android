import { randomUUID } from "node:crypto";
import type { Banking } from "../../core/banking.js";
import type { Doc, Tx } from "../../core/store.js";
import { check, key } from "../../shared/errors.js";
import { page, queryOptions } from "../../shared/pagination.js";
import type { Actor } from "../auth/auth.types.js";

type ImageMime = "image/jpeg" | "image/png";
type Decision = "VERIFIED" | "REJECTED" | "RESUBMIT_REQUIRED";
type EvidenceStatus = "DRAFT" | "REVIEWING" | Decision;
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
}
interface UploadTicket extends Image {
  requestId: string;
  index: number;
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
  slots: Record<number, Slot>;
  storageRefs: VerifiedImage[];
  submittedAt?: number;
  reviewedBy?: string | null;
  reviewedAt?: number | null;
  reason?: string;
}

const collection = "ekycRequests";
const requestPath = (id: string) => `${collection}/${id}`;
const ticketPath = (id: string) => `ekycUploadTickets/${id}`;
const indexes = [0, 1, 2] as const;
const maxTimestamp = 9_999_999_999_999;
const validIndex = (index: number) => Number.isInteger(index) && index >= 0 && index < indexes.length;

function response(record: EvidenceRecord, officer = false) {
  const { requestId, customerUid, consentAt, createdAt, reviewStatus, reason, submittedAt, reviewedBy, reviewedAt } = record;
  return {
    id: requestId, requestId, customerUid, consentAt, createdAt, reviewStatus, reason, submittedAt, reviewedBy, reviewedAt,
    ...(officer && { storageRefs: record.storageRefs.map(({ mime, bytes }, index) => ({ index, mime, bytes })) }),
  };
}

export class Evidence {
  constructor(
    private bank: Banking,
    readonly provider: EvidenceProvider | undefined,
  ) {}

  private async customer(t: Tx, a: Actor) {
    await this.bank.authorize(t, a);
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
      if (latest?.reviewStatus === "DRAFT") return response(latest);
      check(!latest || latest.reviewStatus === "REJECTED" || latest.reviewStatus === "RESUBMIT_REQUIRED", "INVALID_EKYC_STATE", 409);
      // Reverse, fixed-width timestamp gives newest-first scans using the store's document-ID cursor.
      const now = this.bank.now();
      const orderTime = Math.max(now, (head?.orderTime ?? -1) + 1);
      const requestId = `${String(maxTimestamp - orderTime).padStart(13, "0")}-${randomUUID()}`;
      const record: EvidenceRecord = {
        requestId, customerUid: a.uid, consentAt: now, createdAt: now,
        reviewStatus: "DRAFT", slots: {}, storageRefs: [],
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
      return page(rows.map((row) => ({ ...response(row as EvidenceRecord, officer), _cursor: row._cursor })), query);
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
      const slotIndex = index ?? indexes.find((i) => !record.slots[i]) ?? indexes.find((i) => !record.slots[i]?.image);
      check(slotIndex !== undefined && validIndex(slotIndex), "INVALID_EVIDENCE_INDEX");
      const ticket: UploadTicket = { requestId: id, index: slotIndex, key: `ekyc/${id}/${ticketId}`, mime, bytes };
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
    check(ticket, "NOT_FOUND", 404);
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

  async submit(a: Actor, id: string) {
    return this.bank.store.run(async (t) => {
      await this.customer(t, a);
      const record = await this.owned(t, a, id);
      if (record.reviewStatus === "REVIEWING") return response(record);
      check(record.reviewStatus === "DRAFT", "INVALID_EKYC_STATE", 409);
      const front = record.slots[0]?.image;
      const back = record.slots[1]?.image;
      const selfie = record.slots[2]?.image;
      check(front && back, "EVIDENCE_REQUIRED", 409);
      const next: EvidenceRecord = { ...record, reviewStatus: "REVIEWING", submittedAt: this.bank.now(), storageRefs: selfie ? [front, back, selfie] : [front, back] };
      await this.writeStatus(t, next);
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

  async review(a: Actor, id: string, status: Decision, reason: string, requestId = "") {
    check(status === "VERIFIED" || status === "REJECTED" || status === "RESUBMIT_REQUIRED", "INVALID_REVIEW_STATUS");
    reason = reason.trim();
    check(reason.length >= 3 && reason.length <= 500, "INVALID_REVIEW_REASON");
    return this.bank.store.run(async (t) => {
      await this.bank.authorize(t, a, "EKYC_REVIEW");
      const record = await this.get(t, id);
      check(record.customerUid !== a.uid, "FORBIDDEN", 403);
      check(record.reviewStatus === "REVIEWING", "INVALID_EKYC_STATE", 409);
      const next: EvidenceRecord = { ...record, reviewStatus: status, reason, reviewedBy: a.uid, reviewedAt: this.bank.now() };
      await this.writeStatus(t, next);
      this.bank.audit(t, a, "EKYC_REVIEW", id, requestId, { before: { reviewStatus: record.reviewStatus }, after: { reviewStatus: status, reason } });
      return response(next, true);
    });
  }

  async evidence(a: Actor, id: string, index: number) {
    check(validIndex(index), "INVALID_EVIDENCE_INDEX");
    const image = await this.bank.store.run(async (t) => {
      await this.bank.authorize(t, a, "EKYC_REVIEW");
      const record = await this.get(t, id);
      check(record.reviewStatus !== "DRAFT", "NOT_FOUND", 404);
      const image = record.storageRefs[index];
      check(image?.versionId && image.versionId !== "null", "NOT_FOUND", 404);
      return image;
    });
    return { url: await this.s3().download(image) };
  }
}
