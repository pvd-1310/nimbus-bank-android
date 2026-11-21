import { FieldPath } from "firebase-admin/firestore";
import type { Doc } from "../core/store.js";
import {
  applyMigrationPlan,
  buildMigrationPlan,
} from "../database/migration.js";
import { templateCollections } from "../database/template-schema.js";
import { runtime } from "../infrastructure/runtime.js";
import { check } from "../shared/errors.js";
const r = runtime(),
  apply = process.argv.includes("--apply");
try {
  const records: { path: string; data: Doc }[] = [];
  for (const collection of templateCollections) {
    let after: string | undefined;
    for (;;) {
      let query = r.db
        .collection(collection)
        .orderBy(FieldPath.documentId())
        .limit(100);
      if (after) query = query.startAfter(after);
      const snap = await query.get();
      for (const d of snap.docs)
        records.push({ path: d.ref.path, data: d.data() });
      if (snap.size < 100) break;
      after = snap.docs.at(-1)!.id;
    }
  }
  const plan = buildMigrationPlan(records);
  // Only field names and IDs are printed. No OTP hashes, personal values or credentials.
  console.log(
    JSON.stringify(
      {
        project: r.cfg.projectId,
        mode: r.cfg.mode,
        dryRun: !apply,
        planHash: plan.hash,
        documentsScanned: records.length,
        documentsToAddFields: plan.entries.length,
        summary: plan.summary,
        conflicts: plan.conflicts,
      },
      null,
      2,
    ),
  );
  if (apply) {
    check(
      r.cfg.mode === "emulator" ||
        process.env.CONFIRM_MIGRATION_PROJECT === r.cfg.projectId,
      "CONFIRM_MIGRATION_PROJECT_REQUIRED",
    );
    const i = process.argv.indexOf("--confirm-plan");
    check(i >= 0 && !!process.argv[i + 1], "MIGRATION_REVIEW_REQUIRED");
    console.log(
      await applyMigrationPlan(r.bank.store, plan, process.argv[i + 1]),
    );
  }
} finally {
  await r.db.terminate();
}
