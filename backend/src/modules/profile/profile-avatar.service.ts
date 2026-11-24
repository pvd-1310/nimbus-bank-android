import { randomUUID } from "node:crypto";
import type { Banking } from "../../core/banking.js";
import type { Doc } from "../../core/store.js";
import { check } from "../../shared/errors.js";
import type { EvidenceProvider } from "../ekyc/ekyc.service.js";
import type { Actor } from "../auth/auth.types.js";

type ImageMime = "image/jpeg" | "image/png";
interface Avatar extends Doc {
  key: string;
  mime: ImageMime;
  bytes: number;
  versionId: string;
}
interface UploadTicket extends Doc {
  uid: string;
  key: string;
  mime: ImageMime;
  bytes: number;
  expiresAt: number;
  used?: boolean;
}
type AvatarProvider = EvidenceProvider & { removeVersions?(key: string): Promise<void> };

const ticketPath = (id: string) => `profileAvatarUploads/${id}`;
const MAX_AVATAR_BYTES = 5_000_000;

/** Private, version-verified avatar uploads stored in the configured evidence bucket. */
export class ProfileAvatarService {
  constructor(private bank: Banking, private provider?: AvatarProvider) {}

  private storage() {
    check(this.provider, "SETUP_REQUIRED", 503);
    return this.provider;
  }

  async start(a: Actor, mime: ImageMime, bytes: number) {
    check(bytes >= 3 && bytes <= MAX_AVATAR_BYTES, "INVALID_BYTES");
    const id = randomUUID();
    // Keep avatar objects under the existing narrowly scoped ekyc/* IAM prefix.
    const key = `ekyc/profile-avatars/${a.uid}/${id}`;
    const upload = await this.storage().upload({ key, mime, bytes });
    await this.bank.store.run(async (t) => {
      await this.bank.authorize(t, a, undefined, true);
      t.set(ticketPath(id), {
        uid: a.uid, key, mime, bytes, expiresAt: this.bank.now() + 180_000,
      } satisfies UploadTicket);
    });
    return { id, ...upload };
  }

  async confirm(a: Actor, id: string) {
    const snapshot = await this.bank.store.run(async (t) => {
      await this.bank.authorize(t, a, undefined, true);
      const ticket = await t.get(ticketPath(id)) as UploadTicket | undefined;
      check(ticket && ticket.uid === a.uid && !ticket.used, "INVALID_UPLOAD", 404);
      check(ticket.expiresAt > this.bank.now(), "UPLOAD_EXPIRED", 409);
      return ticket;
    });

    const versionId = await this.storage().verify(snapshot);
    let previous: Avatar | undefined;
    await this.bank.store.run(async (t) => {
      await this.bank.authorize(t, a, undefined, true);
      const ticket = await t.get(ticketPath(id)) as UploadTicket | undefined;
      check(ticket && ticket.uid === a.uid && !ticket.used
        && ticket.key === snapshot.key && ticket.expiresAt > this.bank.now(), "INVALID_UPLOAD", 409);
      const user = await t.get(`users/${a.uid}`);
      previous = user?.avatar as Avatar | undefined;
      t.set(`users/${a.uid}`, {
        ...user,
        avatar: { key: snapshot.key, mime: snapshot.mime, bytes: snapshot.bytes, versionId } satisfies Avatar,
      });
      t.set(ticketPath(id), { ...ticket, used: true });
      this.bank.audit(t, a, "PROFILE_AVATAR_UPDATED", a.uid, id, {
        changedFields: ["avatar"],
        before: { avatar: previous ? "SET" : "EMPTY" },
        after: { avatar: "SET", mime: snapshot.mime, bytes: snapshot.bytes },
      });
    });

    if (previous?.key && previous.key !== snapshot.key) {
      await this.provider?.removeVersions?.(previous.key).catch(() => undefined);
    }
    return { updated: true };
  }

  async url(a: Actor) {
    const avatar = await this.bank.store.run(async (t) => {
      await this.bank.authorize(t, a, undefined, true);
      const user = await t.get(`users/${a.uid}`);
      return user?.avatar as Avatar | undefined;
    });
    if (!avatar) return { url: null };
    return { url: await this.storage().download(avatar) };
  }
}
