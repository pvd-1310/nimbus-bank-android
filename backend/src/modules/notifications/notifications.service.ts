import type { Banking } from "../../core/banking.js";
import type { Tx } from "../../core/store.js";
import { digest } from "../../shared/crypto.js";
import { check, key } from "../../shared/errors.js";
import type { Actor } from "../auth/auth.types.js";

export type NotificationType = "TRANSACTION" | "EKYC" | "SECURITY" | "SYSTEM";

export function formatVnd(minor: number): string {
  return new Intl.NumberFormat("vi-VN").format(Math.round(minor)) + " ₫";
}

export class NotificationService {
  constructor(private bank: Banking) {}

  async isTransactionEnabled(t: Tx, uid: string): Promise<boolean> {
    const pref = await t.get("preferences/" + uid);
    return pref?.notificationsEnabled !== false;
  }

  emit(
    t: Tx,
    input: {
      id?: string;
      ownerUid: string;
      type: NotificationType;
      title: string;
      body: string;
      refId?: string;
    },
    enabled = true,
  ) {
    if (!enabled) return;
    const now = this.bank.now();
    const id =
      input.id ??
      digest([input.ownerUid, input.type, input.refId ?? "", String(now), input.title]);
    t.set("notifications/" + id, {
      ownerUid: input.ownerUid,
      type: input.type,
      title: input.title,
      message: input.body,
      body: input.body,
      ...(input.refId ? { refId: input.refId } : {}),
      read: false,
      createdAt: now,
    });
  }

  async markRead(a: Actor, id: string) {
    key(id);
    return this.bank.store.run(async (t) => {
      await this.bank.authorize(t, a);
      const d = await t.get("notifications/" + id);
      check(d && d.ownerUid === a.uid, "FORBIDDEN", 403);
      t.set("notifications/" + id, {
        ...d,
        read: true,
        readAt: this.bank.now(),
      });
      return { updated: true };
    });
  }

  async markAllRead(a: Actor) {
    return this.bank.store.run(async (t) => {
      await this.bank.authorize(t, a);
      const rows = await t.list("notifications", { ownerUid: a.uid }, { limit: 200 });
      const now = this.bank.now();
      let updated = 0;
      for (const row of rows) {
        if (row.read === true) continue;
        const { id, _cursor, ...data } = row;
        t.set("notifications/" + id, {
          ...data,
          read: true,
          readAt: now,
        });
        updated++;
      }
      return { updated };
    });
  }
}
