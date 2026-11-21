import type { Doc, ListOptions } from "../core/store.js";
import { check, Fault } from "./errors.js";
export function queryOptions(q: Doc = {}): ListOptions {
  const limit = Number(q.limit ?? 25);
  check(Number.isInteger(limit) && limit > 0 && limit <= 100, "INVALID_LIMIT");
  check(
    !q.cursor ||
      (typeof q.cursor === "string" && /^[A-Za-z0-9_-]{1,256}$/.test(q.cursor)),
    "INVALID_CURSOR",
  );
  return { limit: limit + 1, after: q.cursor, from: q.from, to: q.to };
}
export function decode(after: string) {
  try {
    const d = JSON.parse(Buffer.from(after, "base64url").toString());
    check(
      Array.isArray(d) &&
        d.length === 2 &&
        Number.isSafeInteger(d[0]) &&
        typeof d[1] === "string",
      "INVALID_CURSOR",
    );
    return d as [number, string];
  } catch {
    throw new Fault("INVALID_CURSOR");
  }
}
export const cursor = (d: Doc, dated: boolean) =>
  dated
    ? Buffer.from(JSON.stringify([d.committedAt, d.id])).toString("base64url")
    : d.id;
export function page(rows: Doc[], q: Doc) {
  const limit = Number(q.limit ?? 25);
  check(Number.isInteger(limit) && limit > 0 && limit <= 100, "INVALID_LIMIT");
  return {
    items: rows.slice(0, limit).map(({ _cursor, ...row }) => row),
    nextCursor:
      rows.length > limit
        ? (rows[limit - 1]._cursor ?? rows[limit - 1].id)
        : null,
  };
}
