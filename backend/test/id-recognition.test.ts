import { afterEach, expect, it, vi } from "vitest";
import { recognizeIdCard } from "../src/modules/ekyc/id-recognition.js";

afterEach(() => { vi.unstubAllEnvs(); vi.unstubAllGlobals(); });

it("maps FPT identity fields and normalizes dates without exposing the key", async () => {
  vi.stubEnv("FPT_AI_KEY", "test-secret");
  vi.stubGlobal("fetch", async (url: string, options: RequestInit) => {
    expect(url).toBe("https://api.fpt.ai/vision/idr/vnm/");
    expect((options.headers as Record<string, string>)["api-key"]).toBe("test-secret");
    expect(options.body).toBeInstanceOf(FormData);
    return Response.json({ errorCode: 0, data: [{ type: "new", id: "000000000000", name: "Nguyễn Văn A", dob: "01/01/2000", sex: "Nam", nationality: "Việt Nam", address: "N/A", doe: "N/A" }] });
  });
  expect(await recognizeIdCard(Buffer.from("image"), "image/jpeg")).toEqual({
    nationalIdNumber: "000000000000", fullName: "Nguyễn Văn A", dateOfBirth: "2000-01-01",
    gender: "Nam", nationality: "Việt Nam", address: "", identityDocumentExpiry: "",
  });
});

it("rejects provider errors and back-side results", async () => {
  vi.stubEnv("FPT_AI_KEY", "test-secret");
  vi.stubGlobal("fetch", async () => Response.json({ errorCode: 3, data: [] }));
  await expect(recognizeIdCard(Buffer.from("image"), "image/jpeg")).rejects.toMatchObject({ code: "ID_RECOGNITION_FAILED" });
  vi.stubGlobal("fetch", async () => Response.json({ errorCode: 0, data: [{ type: "new_back" }] }));
  await expect(recognizeIdCard(Buffer.from("image"), "image/jpeg")).rejects.toMatchObject({ code: "ID_FRONT_REQUIRED" });
});
