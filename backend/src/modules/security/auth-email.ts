import type { EmailOtpPurpose } from "./email-otp.routes.js";

export interface AuthEmail {
  to: string;
  purpose: EmailOtpPurpose;
  code: string;
  id: string;
}

export function authEmailContent(purpose: EmailOtpPurpose, code: string) {
  const subject = purpose === "REGISTRATION" ? "Xác minh email đăng ký" : "Đặt lại mật khẩu";
  const action = purpose === "REGISTRATION" ? "xác minh email đăng ký" : "đặt lại mật khẩu";
  const instruction = "Mở ứng dụng Nimbus Bank và nhập mã này. Mã có hiệu lực trong 5 phút.";
  const footer = "Nếu bạn không yêu cầu mã này, bạn có thể bỏ qua email.";
  return {
    subject: "Nimbus Bank | " + subject,
    text: [subject, "Mã " + action + " của bạn là " + code + ".", instruction, footer].join("\n\n"),
    html:
      '<!doctype html><html lang="vi"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width"></head>' +
      '<body style="margin:0;background:#eef2f8;color:#142442;font-family:Arial,sans-serif">' +
      '<table role="presentation" width="100%" cellpadding="0" cellspacing="0"><tr><td align="center" style="padding:28px 12px">' +
      '<table role="presentation" width="100%" cellpadding="0" cellspacing="0" style="max-width:520px;background:#fff;border-radius:14px;overflow:hidden">' +
      '<tr><td style="padding:20px 24px;background:#12274a;color:#fff;font-size:19px;font-weight:bold">Nimbus Bank</td></tr>' +
      '<tr><td style="padding:24px"><h1 style="margin:0 0 14px;font-size:24px">' + subject + '</h1>' +
      '<p style="margin:0 0 16px;line-height:1.5">Mã ' + action + ' của bạn</p>' +
      '<p style="margin:0 0 18px;padding:16px 12px;background:#eef2f8;border-radius:10px;text-align:center;font-size:30px;letter-spacing:6px;font-weight:bold">' + code + '</p>' +
      '<p style="margin:0 0 16px;line-height:1.5">' + instruction + '</p>' +
      '<p style="margin:0;color:#495b76;font-size:14px;line-height:1.5">' + footer + '</p>' +
      '</td></tr></table></td></tr></table></body></html>',
  };
}

export function createAuthEmailSender(apiKey?: string, from?: string) {
  if (!apiKey?.trim() || !from?.trim()) return undefined;
  return async ({ to, purpose, code, id }: AuthEmail) => {
    const response = await fetch("https://api.resend.com/emails", {
      method: "POST",
      headers: {
        Authorization: "Bearer " + apiKey,
        "Content-Type": "application/json",
        "Idempotency-Key": "nimbus-auth/" + id,
      },
      body: JSON.stringify({ from, to: [to], ...authEmailContent(purpose, code) }),
      signal: AbortSignal.timeout(8_000),
    });
    if (!response.ok) throw new Error("EMAIL_DELIVERY_FAILED");
    void response.body?.cancel().catch(() => undefined);
  };
}
