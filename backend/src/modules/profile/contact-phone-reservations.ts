import type { Tx } from "../../core/store.js";
import { digest } from "../../shared/crypto.js";
import { check } from "../../shared/errors.js";

const reservationPath = (phone: string) =>
  "contactPhoneReservations/" + digest(phone.trim());

export async function reserveContactPhone(
  t: Tx,
  uid: string,
  phone: string,
  now: number,
) {
  const path = reservationPath(phone);
  const old = await t.get(path);
  check(!old || old.uid === uid || old.released === true, "CONTACT_PHONE_EXISTS", 409);
  if (!old || old.released === true) {
    const users = await t.list("users", { contactPhone: phone }, { limit: 20 });
    check(users.every((user) => user.id === uid), "CONTACT_PHONE_EXISTS", 409);
  }
  t.set(path, { uid, released: false, updatedAt: now });
}

export async function releaseContactPhone(
  t: Tx,
  uid: string,
  phone: string | undefined,
  now: number,
) {
  if (!phone) return;
  const path = reservationPath(phone);
  const old = await t.get(path);
  if (old?.uid === uid && old.released !== true)
    t.set(path, { ...old, released: true, releasedAt: now });
}
