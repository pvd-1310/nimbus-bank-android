import { createHash } from "node:crypto";
import type { Doc, Store } from "../core/store.js";
import { check } from "../shared/errors.js";
import { migrationPatch, missingTemplateFields } from "./template-schema.js";
function stable(value: any): any {
  if (value === null || typeof value !== "object") return value;
  if (typeof value.toJSON === "function") return stable(value.toJSON());
  if (Array.isArray(value)) return value.map(stable);
  return Object.fromEntries(
    Object.keys(value)
      .sort()
      .map((k) => [k, stable(value[k])]),
  );
}
const fingerprint = (data: unknown) =>
  createHash("sha256")
    .update(JSON.stringify(stable(data)) ?? "undefined")
    .digest("hex");
interface RecordInput {
  path: string;
  data: Doc;
}
export function buildMigrationPlan(records: RecordInput[]) {
  const entries: { path: string; beforeHash: string; patch: Doc }[] = [],
    summary: { path: string; fields: string[]; missing: string[] }[] = [],
    conflicts: { path: string; code: string }[] = [];
  for (const { path, data } of records
    .slice()
    .sort((a, b) => a.path.localeCompare(b.path))) {
    try {
      const patch = migrationPatch(path, data);
      if (Object.keys(patch).length)
        entries.push({ path, beforeHash: fingerprint(data), patch });
      summary.push({
        path,
        fields: Object.keys(patch),
        missing: missingTemplateFields(path, { ...data, ...patch }),
      });
    } catch (e) {
      conflicts.push({
        path,
        code: e instanceof Error ? e.message : "SCHEMA_CONFLICT",
      });
    }
  }
  return { hash: fingerprint(entries), entries, summary, conflicts };
}
export async function applyMigrationPlan(
  store: Store,
  plan: ReturnType<typeof buildMigrationPlan>,
  confirmHash: string,
) {
  check(plan.hash === confirmHash, "MIGRATION_REVIEW_REQUIRED");
  check(plan.conflicts.length === 0, "MIGRATION_CONFLICTS");
  let updated = 0,
    skipped = 0;
  // Per-document transactions make interruption/retry safe and avoid oversized writes.
  // If a later document changes, earlier additions remain and a fresh preview is required.
  for (const entry of plan.entries) {
    const changed = await store.run(async (t) => {
      const current = await t.get(entry.path);
      check(current, "MIGRATION_DOCUMENT_REMOVED", 409);
      if (fingerprint(current) === entry.beforeHash) {
        t.set(entry.path, { ...current, ...entry.patch });
        return true;
      }
      const original = { ...current };
      let applied = true;
      for (const [k, v] of Object.entries(entry.patch)) {
        if (fingerprint(current[k]) !== fingerprint(v)) {
          applied = false;
          break;
        }
        delete original[k];
      }
      check(
        applied && fingerprint(original) === entry.beforeHash,
        "MIGRATION_DOCUMENT_CHANGED",
        409,
      );
      return false;
    });
    if (changed) updated++;
    else skipped++;
  }
  return { updated, skipped };
}
