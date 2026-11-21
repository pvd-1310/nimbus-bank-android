import type { Banking } from "../../core/banking.js";
import { check, key } from "../../shared/errors.js";
import type { Actor } from "../auth/auth.types.js";
export class NotificationService {
  constructor(private bank: Banking) {}
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
}
