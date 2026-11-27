import { randomUUID } from "node:crypto";
import { AccountService } from "../modules/accounts/accounts.service.js";
import type { Actor } from "../modules/auth/auth.types.js";
import { NotificationService } from "../modules/notifications/notifications.service.js";
import { ProfileService } from "../modules/profile/profile.service.js";
import { RateService } from "../modules/rates/rates.service.js";
import { RequestService } from "../modules/requests/requests.service.js";
import { TransactionService } from "../modules/transactions/transactions.service.js";
import { TransferService } from "../modules/transfers/transfers.service.js";
import { check, key } from "../shared/errors.js";
import { queryOptions } from "../shared/pagination.js";
import type { Doc, Store, Tx } from "./store.js";
import { IntentSecurity } from "../modules/security/intent.js";
import type { FaceService } from "../modules/security/face.js";
interface Options {
  otpSecret: string;
  now?: () => number;
  face?: FaceService;
  faceThresholds?: Record<string, number>;
  rateLimits?: { minBasisPoints: number; maxBasisPoints: number };
}
export class Banking {
  readonly security: IntentSecurity;
  readonly now: () => number;
  readonly accounts: AccountService;
  readonly profiles: ProfileService;
  readonly transactions: TransactionService;
  readonly transfers: TransferService;
  readonly requests: RequestService;
  readonly rates: RateService;
  readonly notifications: NotificationService;
  constructor(
    readonly store: Store,
    readonly options: Options,
  ) {
    this.security = new IntentSecurity(this);
    this.now = options.now ?? Date.now;
    this.accounts = new AccountService(this);
    this.profiles = new ProfileService(this);
    this.transactions = new TransactionService(this);
    this.transfers = new TransferService(this);
    this.requests = new RequestService(this);
    this.rates = new RateService(this);
    this.notifications = new NotificationService(this);
  }
  async authorize(t: Tx, a: Actor, permission?: string, allowOnboarding = false) {
    key(a.uid);
    const u = await t.get("users/" + a.uid);
    const active = u && (u.status === "ACTIVE"
      ? u.active !== false
      : u.status === "ONBOARDING"
        ? allowOnboarding && a.role === "CUSTOMER"
        : u.status === undefined && u.active === true);
    check(active && u!.role === a.role, "FORBIDDEN", 403);
    if (permission) {
      check(
        a.role === "OFFICER" && a.permissions.includes(permission),
        "FORBIDDEN",
        403,
      );
      const o = await this.linkedProfile(t, "officers", a.uid);
      check(
        o?.active === true && o.permissions?.includes(permission),
        "FORBIDDEN",
        403,
      );
    }
    return u!;
  }
  async linkedProfile(
    t: Tx,
    collection: "customers" | "officers",
    uid: string,
  ): Promise<Doc | undefined> {
    const direct = await t.get(collection + "/" + uid);
    if (direct) {
      check(
        (direct.uidRef ?? direct.uid ?? uid) === uid,
        "PROFILE_LINK_CONFLICT",
        403,
      );
      return { ...direct, id: uid };
    }
    const matches = await t.list(collection, { uidRef: uid }, { limit: 2 });
    check(matches.length <= 1, "PROFILE_LINK_CONFLICT", 403);
    return matches[0];
  }
  async owned(t: Tx, a: Actor, id: string): Promise<Doc> {
    key(id);
    const d = await t.get("accounts/" + id);
    check(d, "NOT_FOUND", 404);
    check(d.ownerUid === a.uid, "FORBIDDEN", 403);
    return { ...d, id };
  }
  audit(
    t: Tx,
    a: Actor,
    action: string,
    resourceId: string,
    requestId = "",
    beforeAfterSummary: Doc = { before: null, after: null },
  ) {
    t.set("auditLogs/" + randomUUID(), {
      actorUid: a.uid,
      action,
      resourceId,
      requestId,
      timestamp: this.now(),
      beforeAfterSummary,
    });
  }
  read<T>(work: (tx: Tx) => Promise<T>): Promise<T> {
    return this.store.read ? this.store.read(work) : this.store.run(work);
  }
  async listOwned(a: Actor, collection: string, q: Doc = {}) {
    return this.read(async (t) => {
      await this.authorize(t, a);
      return t.list(collection, { ownerUid: a.uid }, queryOptions(q));
    });
  }
  async scoped(a: Actor, collection: string, id: string): Promise<Doc> {
    key(id);
    return this.read(async (t) => {
      await this.authorize(t, a);
      const d = await t.get(collection + "/" + id);
      check(d, "NOT_FOUND", 404);
      check((d.ownerUid ?? d.customerUid) === a.uid, "FORBIDDEN", 403);
      return { ...d, id };
    });
  }
}
