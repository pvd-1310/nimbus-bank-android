import { runtime } from "../infrastructure/runtime.js";
import { check } from "../shared/errors.js";
import { phoneSearchTokens } from "../shared/search.js";

const r = runtime();
const apply = process.argv.includes("--apply");
let scanned = 0;
let updated = 0;

try {
  if (apply) check(r.cfg.mode === "emulator" || process.env.CONFIRM_BACKFILL_PROJECT === r.cfg.projectId,
    "CONFIRM_BACKFILL_PROJECT_REQUIRED");
  let after: string | undefined;
  for (;;) {
    const batch = await r.bank.store.run(async (t) => {
      const users = await t.list("users", { role: "CUSTOMER" }, { limit: 100, ...(after ? { after } : {}) });
      let changed = 0;
      for (const user of users) {
        if (typeof user.contactPhone !== "string" || typeof user.id !== "string") continue;
        const tokens = phoneSearchTokens(user.contactPhone);
        if (JSON.stringify(user.contactPhoneSearchTokens ?? []) === JSON.stringify(tokens)) continue;
        if (apply) {
          const { id, _cursor, ...data } = user;
          t.set("users/" + id, { ...data, contactPhoneSearchTokens: tokens });
        }
        changed++;
      }
      return { scanned: users.length, changed, after: users.at(-1)?._cursor };
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
