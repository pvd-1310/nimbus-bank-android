import { z } from "zod";
import type { AppDependencies, Route } from "../../core/http.js";
import type { Tx } from "../../core/store.js";
import { Fault, check } from "../../shared/errors.js";
import { rate } from "../security/intent.js";

const ACTIONS = [
  "TOPUP",
  "ELECTRICITY",
  "WATER",
  "BILLS",
  "HISTORY",
  "TRANSFER",
  "DEPOSIT",
  "WITHDRAWAL",
  "SAVINGS",
  "LOAN",
  "MOVIE",
  "HOTEL",
  "FLIGHT",
  "BRANCHES",
] as const;
type Action = (typeof ACTIONS)[number];

type HistoryTurn = { role: "user" | "assistant"; text: string };
type ChatbotOptions = { history?: HistoryTurn[]; context?: string };

const topics: Record<Action, { keywords: string[]; text: string }> = {
  ELECTRICITY: {
    keywords: ["tien dien", "hoa don dien", "evn", "dong dien"],
    text: "Bạn có thể kiểm tra và thanh toán hóa đơn tiền điện trực tiếp trong mục Tiền điện.",
  },
  WATER: {
    keywords: ["tien nuoc", "hoa don nuoc", "sawaco", "dong nuoc"],
    text: "Bạn có thể tra cứu mã khách hàng và thanh toán tiền nước trong mục Tiền nước.",
  },
  TOPUP: {
    keywords: ["nap dien thoai", "nap card", "the cao", "topup", "tien dien thoai"],
    text: "Mở mục Nạp điện thoại để chọn nhà mạng, nhập số điện thoại và chọn mệnh giá.",
  },
  BILLS: {
    keywords: ["hoa don", "tien ich", "dich vu"],
    text: "Bạn có thể mở Tiện ích để thanh toán điện, nước, nạp điện thoại hoặc đặt vé dịch vụ.",
  },
  HISTORY: {
    keywords: ["lich su", "giao dich", "chi tieu", "thang nay", "sao ke", "bien lai"],
    text: "Mở Lịch sử giao dịch và dùng bộ lọc tháng để theo dõi chi tiêu cùng biên lai chi tiết.",
  },
  TRANSFER: {
    keywords: ["chuyen tien", "chuyen khoan", "gui tien", "han muc"],
    text: "Mở Chuyển tiền để thực hiện giao dịch nội bộ hoặc liên ngân hàng và xác thực Smart OTP.",
  },
  DEPOSIT: {
    keywords: ["nap tien", "nap vao", "gui tien vao vi"],
    text: "Mở Yêu cầu nạp tiền để tạo lệnh nạp vào tài khoản Nimbus của bạn.",
  },
  WITHDRAWAL: {
    keywords: ["rut tien", "rut ve"],
    text: "Mở Yêu cầu rút tiền để tạo lệnh rút từ tài khoản thanh toán.",
  },
  SAVINGS: {
    keywords: ["tiet kiem", "gui tiet kiem", "so tiet kiem", "lai suat tiet kiem"],
    text: "Bạn có thể xem số dư, lãi suất và mở tài khoản tiết kiệm trong mục Tài khoản.",
  },
  LOAN: {
    keywords: ["vay", "khoan vay", "the chap", "lai suat vay", "ho so vay"],
    text: "Bạn có thể theo dõi khoản vay hiện tại hoặc đăng ký hồ sơ vay mới trong mục Tài khoản.",
  },
  MOVIE: {
    keywords: ["xem phim", "ve phim", "rap chieu", "cgv", "lotte", "galaxy"],
    text: "Mở Vé xem phim để chọn phim, ngày chiếu, rạp và ghế ngồi hoặc xem Vé của tôi.",
  },
  HOTEL: {
    keywords: ["khach san", "dat phong", "luu tru", "resort"],
    text: "Mở Đặt khách sạn để chọn ngày nhận trả phòng, số khách và đặt phòng.",
  },
  FLIGHT: {
    keywords: ["may bay", "ve may bay", "chuyen bay", "hang bay"],
    text: "Mở Đặt vé máy bay để tìm chuyến bay phù hợp và thanh toán trực tiếp.",
  },
  BRANCHES: {
    keywords: ["chi nhanh", "atm", "phong giao dich", "dia chi ngan hang"],
    text: "Mở Chi nhánh để xem các điểm giao dịch Nimbus Bank gần bạn trên bản đồ.",
  },
};

const fallback = {
  action: null,
  text: "Tôi có thể hỗ trợ tra số dư, chi tiêu tháng này, hóa đơn chưa thanh toán, tư vấn tiết kiệm/vay và mở nhanh các tính năng trên Nimbus Bank. Bạn muốn hỏi nội dung nào?",
};

const responseSchema = z.object({
  status: z.literal("completed"),
  output: z.array(
    z.object({
      type: z.string(),
      content: z
        .array(z.object({ type: z.string(), text: z.string().optional() }))
        .optional(),
    }),
  ),
});

function normalize(message: string) {
  return message
    .normalize("NFD")
    .toLocaleLowerCase("vi")
    .replace(/\p{M}/gu, "")
    .replace(/đ/g, "d")
    .replace(/[^\p{L}\p{N}\s]/gu, " ")
    .trim()
    .replace(/\s+/g, " ");
}

function topicFor(message: string): Action | undefined {
  const normalized = normalize(message);
  return ACTIONS.find((id) =>
    topics[id].keywords.some((kw) => normalized.includes(kw)),
  );
}

function formatVnd(amountMinor: number, currency = "VND") {
  return `${Math.round(amountMinor).toLocaleString("vi-VN")} ${currency === "VND" ? "₫" : currency}`;
}

async function buildCustomerContext(tx: Tx, uid: string, now: number): Promise<string> {
  const [accounts, transactions, bills, orders] = await Promise.all([
    tx.list("accounts", { ownerUid: uid }, { limit: 20 }),
    tx.list("transactions", { ownerUid: uid }, { limit: 100 }),
    tx.list("bills", { ownerUid: uid }, { limit: 30 }),
    tx.list("orders", { ownerUid: uid }, { limit: 30 }),
  ]);

  const accountSummary = accounts.map((a) => {
    const type = String(a.type ?? a.accountType ?? "CHECKING");
    const typeLabel =
      type === "SAVINGS" ? "Tiết kiệm" : type === "MORTGAGE" ? "Khoản vay" : "Thanh toán";
    const bal = formatVnd(Number(a.balanceMinor ?? 0), String(a.currency ?? "VND"));
    const rate = a.interestRateBps ? ` (lãi ${(Number(a.interestRateBps) / 100).toFixed(2)}%/năm)` : "";
    return `${typeLabel} (${String(a.accountNumber ?? a.id ?? "")}): ${bal}${rate}`;
  });

  const vnOffsetMs = 7 * 3600_000;
  const d = new Date(now + vnOffsetMs);
  const m = d.getUTCMonth();
  const y = d.getUTCFullYear();
  let monthOut = 0;
  let monthIn = 0;
  let monthCount = 0;
  for (const t of transactions) {
    if (String(t.status ?? "") === "FAILED") continue;
    const created = Number(t.committedAt ?? t.createdAt ?? 0);
    if (!created) continue;
    const td = new Date(created + vnOffsetMs);
    if (td.getUTCMonth() !== m || td.getUTCFullYear() !== y) continue;
    monthCount++;
    const amt = Number(t.amountMinor ?? 0);
    const kind = String(t.type ?? "");
    if (
      kind === "DEPOSIT" ||
      kind === "DEMO_DEPOSIT" ||
      kind === "LOAN_DISBURSEMENT" ||
      kind === "TRANSFER_RECEIVED" ||
      kind === "DEMO_INTERNAL_TRANSFER_IN"
    ) {
      monthIn += amt;
    } else {
      monthOut += amt;
    }
  }

  const orderServiceTypes = new Set(["ECOMMERCE", "MOVIE", "HOTEL", "FLIGHT", "TOPUP"]);
  const unpaidBills = bills.filter(
    (b) => String(b.status ?? "") === "UNPAID" && !orderServiceTypes.has(String(b.serviceType ?? "")),
  );
  const unpaidTotal = unpaidBills.reduce((s, b) => s + Number(b.amountMinor ?? 0), 0);
  const unpaidDetail = unpaidBills
    .slice(0, 5)
    .map((b) => `${String(b.serviceType ?? "BILL")} (${String(b.invoiceRef ?? b.id)}): ${formatVnd(Number(b.amountMinor ?? 0))}`);

  const paidOrders = orders.filter(
    (o) => String(o.fulfillmentStatus ?? "") === "DEMO_FULFILLED" || Boolean(o.paymentId),
  );
  const movieCount = paidOrders.filter((o) => o.serviceType === "MOVIE").length;
  const hotelCount = paidOrders.filter((o) => o.serviceType === "HOTEL").length;
  const flightCount = paidOrders.filter((o) => o.serviceType === "FLIGHT").length;

  return [
    `Tài khoản hiện có (${accounts.length}): ${accountSummary.join("; ") || "Chưa có"}.`,
    `Tháng ${m + 1}/${y}: ${monthCount} giao dịch thành công, tổng chi ${formatVnd(monthOut)}, tổng nhận ${formatVnd(monthIn)}.`,
    `Hóa đơn chưa thanh toán: ${unpaidBills.length} hóa đơn (tổng ${formatVnd(unpaidTotal)})${unpaidDetail.length ? ` — ${unpaidDetail.join("; ")}` : ""}.`,
    `Đặt chỗ đã tạo: ${movieCount} vé xem phim, ${hotelCount} đặt phòng khách sạn, ${flightCount} vé máy bay.`,
  ].join("\n");
}

function parseModelJson(raw: string): { text: string; action: Action | null } | undefined {
  const cleaned = raw
    .trim()
    .replace(/^```(?:json)?\s*/i, "")
    .replace(/\s*```$/, "");
  try {
    const obj = JSON.parse(cleaned) as { text?: unknown; action?: unknown };
    if (typeof obj.text !== "string" || !obj.text.trim()) return undefined;
    const act =
      typeof obj.action === "string" && (ACTIONS as readonly string[]).includes(obj.action)
        ? (obj.action as Action)
        : null;
    return { text: obj.text.trim(), action: act };
  } catch {
    return undefined;
  }
}

const greeting = {
  action: null,
  text: "Chào bạn! Tôi là trợ lý AI của Nimbus Bank. Tôi có thể kiểm tra số dư, chi tiêu tháng này, hóa đơn chưa thanh toán, tư vấn tiết kiệm/vay vốn hoặc mở nhanh các tính năng cho bạn.",
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

  return async (message: string, options?: ChatbotOptions) => {
    if (/^(xin chao|chao|chao ban|hello|hi)$/.test(normalize(message))) return greeting;
    const signal = AbortSignal.timeout(15_000);
    const historyLines = (options?.history ?? [])
      .slice(-6)
      .map((h) => `${h.role === "user" ? "Khách" : "Nimbus AI"}: ${h.text}`)
      .join("\n");

    const promptInput = [
      options?.context ? `DỮ LIỆU TÀI KHOẢN CỦA KHÁCH (CHỈ ĐỌC):\n${options.context}` : "",
      historyLines ? `LỊCH SỬ HỘI THOẠI GẦN ĐÂY:\n${historyLines}` : "",
      `CÂU HỎI HIỆN TẠI:\n${message}`,
    ]
      .filter(Boolean)
      .join("\n\n");

    try {
      const response = await fetch("https://api.openai.com/v1/responses", {
        method: "POST",
        redirect: "error",
        signal,
        headers: {
          Authorization: "Bearer " + key,
          "Content-Type": "application/json",
        },
        body: JSON.stringify({
          model,
          store: false,
          input: promptInput,
          max_output_tokens: 320,
          instructions: [
            "Bạn là Nimbus AI, trợ lý ảo thông minh của ngân hàng số Nimbus Bank.",
            "Trả lời bằng tiếng Việt tự nhiên, súc tích (2-4 câu), hữu ích và thân thiện.",
            "Bạn có thể: (1) Trả lời dựa trên DỮ LIỆU TÀI KHOẢN CỦA KHÁCH (số dư, chi tiêu tháng, hóa đơn chưa đóng, vé đã đặt) — tuyệt đối không bịa số liệu; (2) Tư vấn kiến thức tài chính/ngân hàng (lãi suất tiết kiệm, cách tính lãi vay, chuyển khoản, bảo mật OTP/eKYC, phòng tránh lừa đảo); (3) Hướng dẫn thao tác trong app.",
            "Không bao giờ tự thực hiện giao dịch thay khách; chỉ gợi ý mở đúng màn hình qua trường action.",
            `Luôn trả về DUY NHẤT một JSON object hợp lệ dạng {"text":"...","action":null|"CODE"} với CODE thuộc: ${ACTIONS.join(", ")}.`,
            "Nếu câu hỏi chỉ mang tính tư vấn chung hoặc hỏi thông tin mà không cần mở màn hình, đặt action là null.",
          ].join(" "),
        }),
      });
      if (!response.ok) throw new Fault("CHATBOT_UNAVAILABLE", 502);
      const parsed = responseSchema.safeParse(await response.json());
      if (parsed.success) {
        const textOut = parsed.data.output
          .filter((item) => item.type === "message")
          .flatMap((item) => item.content ?? [])
          .filter((part) => part.type === "output_text")
          .map((part) => part.text ?? "")
          .join("\n");
        const structured = parseModelJson(textOut);
        if (structured) return structured;
      }
      const fallbackAction = topicFor(message);
      return fallbackAction ? { action: fallbackAction, text: topics[fallbackAction].text } : fallback;
    } catch (err) {
      if (err instanceof Fault) throw err;
      throw new Fault(signal.aborted ? "CHATBOT_TIMEOUT" : "CHATBOT_UNAVAILABLE", signal.aborted ? 504 : 502);
    }
  };
}

export function registerChatbotRoutes(route: Route, d: AppDependencies) {
  route("post", "/chatbot", async (actor, request) => {
    check(actor.role === "CUSTOMER", "FORBIDDEN", 403);
    check(d.chatbot, "CHATBOT_SETUP_REQUIRED", 503);
    const { message, history } = z
      .object({
        message: z.string().trim().min(1).max(300),
        history: z
          .array(
            z.object({
              role: z.enum(["user", "assistant"]),
              text: z.string().trim().min(1).max(500),
            }),
          )
          .max(10)
          .optional(),
      })
      .strict()
      .parse(request.body);

    const context = await d.bank.store.run(async (tx) => {
      await d.bank.authorize(tx, actor);
      await rate(tx, "chatbot", actor.uid, d.bank.now(), 12, 60_000);
      return buildCustomerContext(tx, actor.uid, d.bank.now());
    });
    return d.chatbot(message, { history, context });
  });
}
