import { z } from "zod";
import { check, Fault } from "../../shared/errors.js";

const responseSchema = z.object({ errorCode: z.number(), data: z.array(z.record(z.string(), z.unknown())).optional() });
const clean = (value: unknown) => typeof value === "string" && value.trim().toUpperCase() !== "N/A" ? value.trim() : "";
function isoDate(value: string) {
  const match = /^(\d{2})[/-](\d{2})[/-](\d{4})$/.exec(value);
  const candidate = match ? `${match[3]}-${match[2]}-${match[1]}` : value;
  return z.iso.date().safeParse(candidate).success ? candidate : "";
}

export async function recognizeIdCard(image: Buffer, mime: string) {
  const apiKey = process.env.FPT_AI_KEY;
  check(apiKey, "ID_RECOGNITION_SETUP_REQUIRED", 503);
  check(image.length > 0 && image.length <= 5_000_000, "INVALID_BYTES", 400);
  const form = new FormData();
  form.append("image", new Blob([new Uint8Array(image)], { type: mime }), "id-card.jpg");
  const result = await fetch("https://api.fpt.ai/vision/idr/vnm/", {
    method: "POST", headers: { "api-key": apiKey }, body: form,
    signal: AbortSignal.timeout(25000), redirect: "error",
  }).catch(() => { throw new Fault("ID_RECOGNITION_UNAVAILABLE", 502); });
  if (!result.ok) throw new Fault("ID_RECOGNITION_UNAVAILABLE", 502);
  const raw: unknown = await result.json().catch(() => { throw new Fault("ID_RECOGNITION_UNAVAILABLE", 502); });
  const parsed = responseSchema.safeParse(raw);
  if (!parsed.success) throw new Fault("ID_RECOGNITION_UNAVAILABLE", 502);
  if (parsed.data.errorCode !== 0 || !parsed.data.data?.length) throw new Fault("ID_RECOGNITION_FAILED", 422);
  const row = parsed.data.data[0];
  if (clean(row.type).toLowerCase().includes("back")) throw new Fault("ID_FRONT_REQUIRED", 422);
  return {
    nationalIdNumber: clean(row.id), fullName: clean(row.name), dateOfBirth: isoDate(clean(row.dob)),
    gender: clean(row.sex), nationality: clean(row.nationality), address: clean(row.address),
    identityDocumentExpiry: isoDate(clean(row.doe)),
  };
}
