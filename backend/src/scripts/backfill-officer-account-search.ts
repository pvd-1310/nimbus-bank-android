import { runtime } from "../infrastructure/runtime.js";
import { check } from "../shared/errors.js";
import { normalizeSearchText } from "../shared/search.js";

const r = runtime();
const apply = process.argv.includes("--apply");
let scanned = 0;
let updated = 0;

try {
  if (apply) {
    check(r.cfg.mode === "emulator" || process.env.CONFIRM_BACKFILL_PROJECT === r.cfg.projectId,
      "CONFIRM_BACKFILL_PROJECT_REQUIRED");
  }
  let after: string | undefined;
  for (;;) {
    const batch = await r.bank.store.run(async (t) => {
      const accounts = await t.list("accounts", { type: "CHECKING" }, { limit: 100, ...(after ? { after } : {}) });
      let changed = 0;
      for (const account of accounts) {
        if (typeof account.ownerUid !== "string") continue;
        const owner = await t.get("users/" + account.ownerUid);
        if (typeof owner?.fullName !== "string") continue;
        const ownerNameSearch = normalizeSearchText(owner.fullName);
        if (account.ownerNameSearch === ownerNameSearch) continue;
        if (apply) {
          const { id, _cursor, ...data } = account;
          t.set("accounts/" + id, { ...data, ownerNameSearch });
        }
        changed++;
      }
      return { scanned: accounts.length, changed, after: accounts.at(-1)?._cursor };
    });
    scanned += batch.scanned;
    updated += batch.changed;
    if (batch.scanned < 100 || !batch.after) break;
    after = batch.after;
  }
  console.log(JSON.stringify({ project: r.cfg.projectId, mode: r.cfg.mode, dryRun: !apply, scanned, updated }));
} finally {
  await r.db.terminate();
}
