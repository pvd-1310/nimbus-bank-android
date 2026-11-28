package com.example.final_term.ui.auth;

import android.content.Context;
import android.os.CountDownTimer;
import android.os.SystemClock;

import androidx.annotation.NonNull;

import com.example.final_term.ui.transaction.OtpBottomSheetDialog;

import java.util.function.Consumer;

final class EmailOtpSheet {
    private final OtpBottomSheetDialog dialog;
    private CountDownTimer timer;
    private String challengeId;
    private long resendAt;

    EmailOtpSheet(Context context, String subtitle, Consumer<String> onSubmit, Runnable onResend) {
        dialog = new OtpBottomSheetDialog(context, new OtpBottomSheetDialog.Listener() {
            @Override public void onSubmitOtp(@NonNull String code) { onSubmit.accept(code); }
            @Override public void onResendRequested() { onResend.run(); }
            @Override public void onDismissRequested() { dismiss(); }
        });
        dialog.setSubtitle(subtitle);
    }

    void show(String id, int resendAfterSeconds) {
        challengeId = id;
        resendAt = SystemClock.elapsedRealtime() + resendAfterSeconds * 1000L;
        reopen();
    }

    void reopen() {
        dialog.clearDigits();
        dialog.setStatusMessage(null);
        dialog.setBusy(false);
        dialog.show();
        if (timer != null) timer.cancel();
        long duration = resendAt - SystemClock.elapsedRealtime();
        if (duration <= 0) {
            dialog.setCountdownText("Gửi lại mã", true);
            return;
        }
        timer = new CountDownTimer(duration, 1000) {
            @Override public void onTick(long remaining) {
                dialog.setCountdownText("Gửi lại sau " + ((remaining + 999) / 1000) + " giây", false);
            }
            @Override public void onFinish() { dialog.setCountdownText("Gửi lại mã", true); }
        }.start();
    }

    boolean isShowing() { return dialog.isShowing(); }
    String challengeId() { return challengeId; }

    void setBusy(boolean busy) { dialog.setBusy(busy); }

    void fail(String message) {
        dialog.setBusy(false);
        dialog.clearDigits();
        dialog.setStatusMessage(message);
    }

    void dismiss() {
        if (timer != null) timer.cancel();
        timer = null;
        dialog.dismiss();
    }

    static String responseError(String body) {
        try {
            return message(new org.json.JSONObject(body).optString("error"));
        } catch (org.json.JSONException invalid) {
            return message("");
        }
    }

    private static String message(String error) {
        switch (error) {
            case "INVALID_OTP": return "Mã OTP chưa đúng. Vui lòng kiểm tra email.";
            case "RESET_TOKEN_EXPIRED":
            case "OTP_EXPIRED": return "Mã OTP đã hết hạn. Hãy yêu cầu mã mới.";
            case "OTP_LOCKED": return "Đã nhập sai quá nhiều lần. Hãy yêu cầu mã mới.";
            case "OTP_RESEND_TOO_SOON": return "Vui lòng đợi 30 giây trước khi gửi lại.";
            case "RATE_LIMITED": return "Bạn đã gửi quá nhiều yêu cầu. Vui lòng thử lại sau.";
            case "EMAIL_OTP_UNAVAILABLE": return "Dịch vụ gửi mã email chưa khả dụng.";
            case "EMAIL_OTP_DELIVERY_FAILED": return "Chưa gửi được mã. Vui lòng thử lại.";
            default: return "Yêu cầu chưa hoàn tất. Vui lòng thử lại.";
        }
    }
}
