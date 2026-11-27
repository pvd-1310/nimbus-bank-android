import { runtime } from "../infrastructure/runtime.js";
import { generateAccountNumber } from "../shared/crypto.js";
import { check } from "../shared/errors.js";

const r = runtime();
const apply = process.argv.includes("--apply");
let scanned = 0;
let updated = 0;

try {
  if (apply) {
    check(
      r.cfg.mode === "emulator" || process.env.CONFIRM_BACKFILL_PROJECT === r.cfg.projectId,
      "CONFIRM_BACKFILL_PROJECT_REQUIRED",
    );
  }
  let after: string | undefined;
  for (;;) {
    const batch = await r.bank.store.run(async (t) => {
      const accounts = await t.list("accounts", {}, { limit: 100, ...(after ? { after } : {}) });
      let changed = 0;
      for (const account of accounts) {
        if (typeof account.id !== "string") continue;
        if (typeof account.accountNumber === "string" && /^\d{10,16}$/.test(account.accountNumber)) continue;
        const accountNumber = generateAccountNumber(account.id);
        if (apply) {
          const { id, _cursor, ...data } = account;
          void _cursor;
          t.set("accounts/" + id, { ...data, accountNumber });
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
} catch (error) {
  console.error(error);
  process.exitCode = 1;
}
