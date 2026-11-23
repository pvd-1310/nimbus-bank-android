import { z } from "zod";
import type { AppDependencies, Route } from "../../core/http.js";
import { check, Fault } from "../../shared/errors.js";
import { rate } from "../security/intent.js";

const topics = {
  TOPUP: { phrases: ["nạp điện thoại", "nạp tiền điện thoại"], text: "Mở tiện ích, chọn nạp điện thoại và kiểm tra mệnh giá trước khi thanh toán test." },
  BILLS: { phrases: ["hóa đơn", "thanh toán hóa đơn", "hóa đơn điện", "hóa đơn nước"], text: "Mở tiện ích để xem hóa đơn, đối chiếu mã và số tiền rồi xác nhận." },
  HISTORY: { phrases: ["lịch sử", "xem giao dịch", "giao dịch tháng này"], text: "Mở lịch sử giao dịch. Dùng bộ lọc ngày để xem giao dịch trong tháng." },
  TRANSFER: { phrases: ["chuyển tiền", "chuyển khoản"], text: "Mở chuyển khoản, kiểm tra người nhận, nguồn tiền và tổng tiền trước khi xác thực." },
  BRANCHES: { phrases: ["chi nhánh", "tìm chi nhánh"], text: "Mở danh sách chi nhánh để xem địa chỉ hoặc tìm đường đi bộ." },
  DEPOSIT: { phrases: ["nạp tiền", "nạp vào tài khoản"], text: "Mở yêu cầu nạp tiền và xem trạng thái xử lý trên màn hình giao dịch." },
} as const;
type Action = keyof typeof topics;
const fallback = {
  action: null,
  text: "Hãy hỏi về nạp tiền, nạp điện thoại, hóa đơn, giao dịch tháng này, chuyển khoản hoặc tìm chi nhánh.",
};
const responseSchema = z.object({
  status: z.literal("completed"),
  output: z.array(z.object({
    type: z.string(),
    content: z.array(z.object({ type: z.string(), text: z.string().optional() })).optional(),
  })),
});

function normalize(message: string) {
  return message.normalize("NFD").toLocaleLowerCase("vi")
    .replace(/\p{M}/gu, "").replace(/đ/g, "d")
    .trim().replace(/\s+/g, " ").replace(/[?.!]+$/, "")
    .replace(/^(lam sao de |cach |toi muon |cho toi |mo )/, "");
}

function topicFor(message: string) {
  const normalized = normalize(message);
  return (Object.keys(topics) as Action[]).find((id) =>
    topics[id].phrases.some((phrase) => normalize(phrase) === normalized),
  );
}

const greeting = {
  action: null,
  text: "Chào bạn! Tôi có thể hướng dẫn tra hóa đơn, xem lịch sử, chuyển khoản hoặc tìm chi nhánh.",
};

export function localChatbotEnabled(env: NodeJS.ProcessEnv) {
  return env.APP_MODE === "emulator" && env.CHATBOT_LOCAL_DEMO === "true";
}

export function createChatbot(env: NodeJS.ProcessEnv) {
  if (localChatbotEnabled(env)) {
    return async (message: string) => {
      if (/^(xin chao|chao|chao ban|hello|hi)$/.test(normalize(message))) return greeting;
      const action = topicFor(message);
      return action ? { action, text: topics[action].text } : fallback;
    };
  }
  const key = env.OPENAI_API_KEY?.trim();
  const model = env.OPENAI_MODEL?.trim();
  if (env.CHATBOT_ENABLED !== "true" || !key || !model) return undefined;
  return async (message: string) => {
    if (/^(xin chao|chao|chao ban|hello|hi)$/.test(normalize(message))) return greeting;
    const action = topicFor(message);
    if (!action) return fallback;
    // Only a server-owned topic crosses the provider boundary.
    const signal = AbortSignal.timeout(3500);
    try {
      const response = await fetch("https://api.openai.com/v1/responses", {
        method: "POST",
        redirect: "error",
        signal,
        headers: { Authorization: "Bearer " + key, "Content-Type": "application/json" },
        body: JSON.stringify({
          model, store: false, input: action, max_output_tokens: 128,
          instructions: "Return only the supplied banking navigation topic if it is TOPUP, BILLS, HISTORY, TRANSFER, BRANCHES, or DEPOSIT. Otherwise return NONE.",
        }),
      });
      if (!response.ok) throw new Fault("CHATBOT_UNAVAILABLE", 502);
      const parsed = responseSchema.safeParse(await response.json());
      if (!parsed.success) return fallback;
      const parts = parsed.data.output.filter((item) => item.type === "message")
        .flatMap((item) => item.content ?? []).filter((part) => part.type === "output_text");
      if (parts.length !== 1 || parts[0].text?.trim() !== action) return fallback;
      return { action, text: topics[action].text };
    } catch {
      throw new Fault(signal.aborted ? "CHATBOT_TIMEOUT" : "CHATBOT_UNAVAILABLE", signal.aborted ? 504 : 502);
    }
  };
}

export function registerChatbotRoutes(route: Route, d: AppDependencies) {
  route("post", "/chatbot", async (actor, request) => {
    check(actor.role === "CUSTOMER", "FORBIDDEN", 403);
    check(d.chatbot, "CHATBOT_SETUP_REQUIRED", 503);
    const { message } = z.object({ message: z.string().trim().min(1).max(300) }).strict().parse(request.body);
    await d.bank.store.run(async (tx) => {
      await d.bank.authorize(tx, actor);
      await rate(tx, "chatbot", actor.uid, d.bank.now(), 12, 60_000);
    });
    return d.chatbot(message);
  });
}
