import type { Doc, Store, Tx } from "../../src/core/store.js";
import { normalizeDocument } from "../../src/database/template-schema.js";
import { cursor, decode, decodePrefixCursor, prefixCursor } from "../../src/shared/pagination.js";
export class MemoryStore implements Store {
  private docs = new Map<string, Doc>();
  private tail: Promise<unknown> = Promise.resolve();
  async run<T>(work: (tx: Tx) => Promise<T>): Promise<T> {
    const run = this.tail.then(async () => {
      const next = structuredClone(this.docs);
      const result = await work({
        get: async (p) => structuredClone(next.get(p)),
        list: async (c, w = {}, o = {}) => {
          const dated = o.from !== undefined || o.to !== undefined;
          const after = o.after && dated ? decode(o.after) : undefined;
          const afterPrefix = o.after && o.prefix ? decodePrefixCursor(o.after) : undefined;
          return [...next]
            .filter(
              ([p, d]) =>
                p.startsWith(c + "/") &&
                p.split("/").length === 2 &&
                Object.entries(w).every(([k, v]) => d[k] === v),
            )
            .map(
              ([p, d]) =>
                ({ ...structuredClone(d), id: p.split("/")[1] }) as Doc,
            )
            .filter(
              (d) =>
                (o.from === undefined || d.committedAt >= o.from) &&
                (o.to === undefined || d.committedAt <= o.to) &&
                (!o.prefix || (typeof d[o.prefix.field] === "string" && d[o.prefix.field].startsWith(o.prefix.value))),
            )
            .sort((a, b) =>
              o.prefix
                ? String(a[o.prefix.field]).localeCompare(String(b[o.prefix.field])) || a.id.localeCompare(b.id)
                : dated ? a.committedAt - b.committedAt || a.id.localeCompare(b.id) : a.id.localeCompare(b.id),
            )
            .filter(
              (d) =>
                !o.after ||
                (dated
                  ? d.committedAt > after![0] ||
                    (d.committedAt === after![0] && d.id > after![1])
                  : o.prefix
                    ? String(d[o.prefix.field]) > afterPrefix![0] || (String(d[o.prefix.field]) === afterPrefix![0] && d.id > afterPrefix![1])
                    : d.id > o.after),
            )
            .slice(0, o.limit ?? 100)
            .map((d) => ({ ...d, _cursor: o.prefix ? prefixCursor(d, o.prefix.field) : cursor(d, dated) }));
        },
        set: (p, d) => {
          next.set(p, structuredClone(normalizeDocument(p, d)));
        },
      });
      this.docs = next;
      return result;
    });
    this.tail = run.catch(() => {});
    return run;
  }
}
