import { FieldPath, type Firestore } from "firebase-admin/firestore";
import type { Doc, Store, Tx } from "../core/store.js";
import { normalizeDocument } from "../database/template-schema.js";
import { cursor, decode, decodePrefixCursor, prefixCursor } from "../shared/pagination.js";
export class FirestoreStore implements Store {
  constructor(private db: Firestore) {}
  run<T>(work: (tx: Tx) => Promise<T>): Promise<T> {
    return this.db.runTransaction(async (native) => {
      const writes = new Map<string, Doc>();
      const result = await work({
        get: async (p) => {
          if (writes.has(p)) return writes.get(p);
          const d = await native.get(this.db.doc(p));
          return d.exists ? d.data() : undefined;
        },
        list: async (c, w = {}, o = {}) => {
          let q: FirebaseFirestore.Query = this.db.collection(c);
          for (const [k, v] of Object.entries(w)) q = q.where(k, "==", v);
          if (o.arrayContains) q = q.where(o.arrayContains.field, "array-contains", o.arrayContains.value);
          if (o.prefix) {
            q = q.where(o.prefix.field, ">=", o.prefix.value)
              .where(o.prefix.field, "<=", o.prefix.value + "\uf8ff")
              .orderBy(o.prefix.field);
          }
          const dated = o.from !== undefined || o.to !== undefined;
          if (o.from !== undefined) q = q.where("committedAt", ">=", o.from);
          if (o.to !== undefined) q = q.where("committedAt", "<=", o.to);
          if (dated) q = q.orderBy("committedAt");
          q = q.orderBy(FieldPath.documentId());
          if (o.after) {
            if (o.prefix) q = q.startAfter(...decodePrefixCursor(o.after));
            else q = dated ? q.startAfter(...decode(o.after)) : q.startAfter(o.after);
          }
          const snap = await native.get(q.limit(o.limit ?? 100));
          return snap.docs.map((d) => {
            const data = { ...d.data(), id: d.id };
            return { ...data, _cursor: o.prefix ? prefixCursor(data, o.prefix.field) : cursor(data, dated) };
          });
        },
        set: (p, d) => {
          writes.set(p, normalizeDocument(p, d));
        },
      });
      for (const [p, d] of writes) native.set(this.db.doc(p), d);
      return result;
    });
  }
}
