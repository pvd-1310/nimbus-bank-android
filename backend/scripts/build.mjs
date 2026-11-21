import { rmSync } from "node:fs";
import { spawnSync } from "node:child_process";
import { fileURLToPath } from "node:url";
const backendRoot = fileURLToPath(new URL("../", import.meta.url));
// dist is generated output only; clean it so relocated source leaves no stale entrypoints.
rmSync(fileURLToPath(new URL("../dist/", import.meta.url)), {
  recursive: true,
  force: true,
});
const result = spawnSync(
  process.execPath,
  [
    fileURLToPath(
      new URL("../node_modules/typescript/bin/tsc", import.meta.url),
    ),
  ],
  { cwd: backendRoot, stdio: "inherit" },
);
if (result.error) throw result.error;
process.exitCode = result.status ?? 1;
