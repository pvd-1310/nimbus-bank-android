import { FieldPath } from "firebase-admin/firestore";
import { runtime } from "../infrastructure/runtime.js";
import { check } from "../shared/errors.js";

const r = runtime();
const apply = process.argv.includes("--apply");
const summary = { eligible: 0, excluded: 0, ready: 0, pending: 0, failed: 0 };
try {
  const service = r.stripeSandboxAccounts;
  check(!apply || service, "STRIPE_TEST_KEY_REQUIRED");
  let after: string | undefined;
  for (;;) {
    let query = r.db.collection("accounts").where("type", "==", "CHECKING")
      .orderBy(FieldPath.documentId()).limit(100);
    if (after) query = query.startAfter(after);
    const page = await query.get();
    for (const account of page.docs) {
      const ownerUid = account.get("ownerUid");
      const owner = typeof ownerUid === "string" ? await r.db.doc("users/" + ownerUid).get() : undefined;
      if (owner?.get("role") !== "CUSTOMER") {
        summary.excluded++;
        continue;
      }
      summary.eligible++;
      try {
        if (apply) await service!.provision(account.id);
        const link = await r.db.doc("stripeSandboxAccountLinks/" + account.id).get();
        if (link.get("cardId")) summary.ready++;
        else summary.pending++;
      } catch (error) {
        summary.failed++;
        const code = (error as { code?: unknown }).code;
        console.warn("Stripe Sandbox link failed", typeof code === "string" ? code : "UNKNOWN_ERROR");
      }
    }
    if (page.size < 100) break;
    after = page.docs.at(-1)!.id;
  }
  console.log(JSON.stringify({ project: r.cfg.projectId, dryRun: !apply, ...summary }));
  if (summary.failed) process.exitCode = 1;
} finally {
  await r.db.terminate();
}
