import {
  FieldPath,
  type CollectionReference,
  type QueryDocumentSnapshot,
} from "firebase-admin/firestore";
import { runtime } from "../infrastructure/runtime.js";
import { check } from "../shared/errors.js";

const { db, s3, evidenceRetentionMs } = runtime();
const now = Date.now();

async function scan(
  collection: CollectionReference,
  visit: (row: QueryDocumentSnapshot) => Promise<void>,
) {
  let previous: QueryDocumentSnapshot | undefined;
  const query = collection.orderBy(FieldPath.documentId()).limit(100);
  while (true) {
    const page = await (previous ? query.startAfter(previous) : query).get();
    for (const row of page.docs) await visit(row);
    if (page.size < 100) return;
    previous = page.docs.at(-1);
  }
}

function removable(
  ticket: FirebaseFirestore.DocumentData,
  record: FirebaseFirestore.DocumentData | undefined,
  ticketId: string,
) {
  if (!record || (record.reviewStatus === "REVIEWING" && ticket.purging !== true)) return false;
  if (ticket.purging !== true && ticket.createdAt && ticket.createdAt > now - 300_000) return false;
  if (
    ["VERIFIED", "REJECTED", "RESUBMIT_REQUIRED"].includes(record.reviewStatus)
  )
    return (
      typeof record.reviewedAt === "number" &&
      record.reviewedAt + evidenceRetentionMs <= now
    );
  const slot = record.slots?.[ticket.index];
  if (record.reviewStatus !== "DRAFT" || typeof ticket.createdAt !== "number")
    return false;
  const activeConfirmed = slot?.ticketId === ticketId && slot?.image;
  return ticket.createdAt <= now - (activeConfirmed ? 30 : 1) * 86_400_000;
}

let images = 0;
let templates = 0;

try {
  if (s3) {
    await scan(db.collection("ekycUploadTickets"), async (row) => {
      const ticket = row.data();
      const request = db.collection("ekycRequests").doc(ticket.requestId);
      const claimed = await db.runTransaction(async (t) => {
        const fresh = (await t.get(row.ref)).data();
        const record = (await t.get(request)).data();
        if (!fresh || (!fresh.purging && !removable(fresh, record, row.id)))
          return false;
        check(
          fresh.key === `ekyc/${fresh.requestId}/${row.id}`,
          "INVALID_STORAGE_KEY",
        );
        if (
          record?.reviewStatus === "DRAFT" &&
          record.slots?.[fresh.index]?.ticketId === row.id
        ) {
          const slots = { ...record.slots };
          delete slots[fresh.index];
          t.update(request, { slots });
        }
        t.update(row.ref, { purging: true });
        return true;
      });
      if (!claimed) return;
      await s3.removeVersions(ticket.key);
      await row.ref.delete();
      images++;
    });
  }
  await scan(db.collection("faceTemplates"), async (row) => {
    const removed = await db.runTransaction(async (t) => {
      const current = (await t.get(row.ref)).data();
      if (!current || current.status !== "VERIFIED" || current.expiresAt > now)
        return false;
      t.set(row.ref, { uid: row.id, status: "EXPIRED", expiredAt: now });
      return true;
    });
    if (removed) templates++;
  });
  console.log("Verification retention cleanup", {
    images,
    templates,
    storageConfigured: !!s3,
  });
} catch {
  console.error("Verification retention cleanup failed");
  process.exitCode = 1;
} finally {
  await db.terminate();
}
