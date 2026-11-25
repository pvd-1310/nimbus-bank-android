import type { Doc } from "../core/store.js";

export function preserveExisting(existing: Doc | undefined, seeded: Doc): Doc {
  const merged: Doc = { ...seeded };
  for (const [field, value] of Object.entries(existing ?? {})) {
    if (value !== undefined) merged[field] = value;
  }
  return merged;
}

export function preserveSeedAccount(existing: Doc | undefined, seeded: Doc, ownerNameSearch?: string): Doc {
  const merged = preserveExisting(existing, seeded);
  if (typeof existing?.ownerNameSearch !== "string" && ownerNameSearch)
    merged.ownerNameSearch = ownerNameSearch;
  return merged;
}

export function seedClaims(existing: Doc | undefined, role: string, permissions: string[]): Doc {
  return {
    ...(existing ?? {}),
    role: existing?.role ?? role,
    permissions: existing?.permissions ?? permissions,
  };
}
