package com.example.final_term.ui.transaction;

import android.animation.ObjectAnimator;
import android.animation.ValueAnimator;
import android.app.Dialog;
import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.view.animation.AccelerateDecelerateInterpolator;
import android.widget.FrameLayout;
import android.widget.TextView;

import androidx.annotation.ColorRes;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;

import com.example.final_term.R;
import com.example.final_term.databinding.DialogOtpBottomSheetBinding;

/**
 * Full-screen dimmed bottom sheet dialog for 6-digit numeric OTP verification
 * featuring a custom 3x4 keypad, blinking cursor, and optional biometric fallback notice.
 */
public final class OtpBottomSheetDialog {
    private static final String BIOMETRIC_UNAVAILABLE_MESSAGE =
            "Xác thực sinh trắc học chưa khả dụng; vui lòng dùng mã OTP";
    private static final int EMPTY_DOT_COLOR = 0x669AA4BC;

    public interface Listener {
        void onSubmitOtp(@NonNull String code);

        void onResendRequested();

        void onDismissRequested();
    }

    private final Context context;
    private final Listener listener;
    private final Dialog dialog;
    private final DialogOtpBottomSheetBinding binding;
    private final OtpDigitController controller = new OtpDigitController();

    private final FrameLayout[] boxes;
    private final TextView[] digits;
    private final TextView[] cursors;
    private final View[] dots;
    private final View[] keypadInteractiveViews;

    @Nullable
    private ObjectAnimator cursorAnimator;
    private boolean canResend;
    private boolean busy;

    public OtpBottomSheetDialog(@NonNull Context context, @NonNull Listener listener) {
        this.context = context;
        this.listener = listener;
        this.binding = DialogOtpBottomSheetBinding.inflate(LayoutInflater.from(context));

        this.dialog = new Dialog(context, R.style.Theme_Final_term);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        dialog.setContentView(binding.getRoot());
        dialog.setCancelable(false);
        dialog.setCanceledOnTouchOutside(false);

        Window window = dialog.getWindow();
        if (window != null) {
            window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            window.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
            window.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
            WindowCompat.setDecorFitsSystemWindows(window, false);
        }

        final int baseBottomPadding = binding.otpSheetContainer.getPaddingBottom();
        ViewCompat.setOnApplyWindowInsetsListener(binding.getRoot(), (v, windowInsets) -> {
            Insets bars = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars());
            binding.otpSheetContainer.setPadding(
                    binding.otpSheetContainer.getPaddingLeft(),
                    binding.otpSheetContainer.getPaddingTop(),
                    binding.otpSheetContainer.getPaddingRight(),
                    baseBottomPadding + bars.bottom
            );
            return windowInsets;
        });

        boxes = new FrameLayout[]{
                binding.otpBox0, binding.otpBox1, binding.otpBox2,
                binding.otpBox3, binding.otpBox4, binding.otpBox5
        };
        digits = new TextView[]{
                binding.otpDigit0, binding.otpDigit1, binding.otpDigit2,
                binding.otpDigit3, binding.otpDigit4, binding.otpDigit5
        };
        cursors = new TextView[]{
                binding.otpCursor0, binding.otpCursor1, binding.otpCursor2,
                binding.otpCursor3, binding.otpCursor4, binding.otpCursor5
        };
        dots = new View[]{
                binding.otpDot0, binding.otpDot1, binding.otpDot2,
                binding.otpDot3, binding.otpDot4, binding.otpDot5
        };
        keypadInteractiveViews = new View[]{
                binding.otpKey0, binding.otpKey1, binding.otpKey2, binding.otpKey3,
                binding.otpKey4, binding.otpKey5, binding.otpKey6, binding.otpKey7,
                binding.otpKey8, binding.otpKey9, binding.otpKeyFingerprint, binding.otpKeyBackspace
        };

        for (View dot : dots) {
            GradientDrawable dotDrawable = new GradientDrawable();
            dotDrawable.setShape(GradientDrawable.OVAL);
            dotDrawable.setColor(EMPTY_DOT_COLOR);
            dot.setBackground(dotDrawable);
        }

        bindListeners();
        renderDigits();
    }

    private void bindListeners() {
        bindDigitKey(binding.otpKey0, '0');
        bindDigitKey(binding.otpKey1, '1');
        bindDigitKey(binding.otpKey2, '2');
        bindDigitKey(binding.otpKey3, '3');
        bindDigitKey(binding.otpKey4, '4');
        bindDigitKey(binding.otpKey5, '5');
        bindDigitKey(binding.otpKey6, '6');
        bindDigitKey(binding.otpKey7, '7');
        bindDigitKey(binding.otpKey8, '8');
        bindDigitKey(binding.otpKey9, '9');

        binding.otpKeyBackspace.setOnClickListener(v -> {
            if (busy) {
                return;
            }
            controller.backspace();
            clearStatusIfTransient();
            renderDigits();
        });

        binding.otpKeyFingerprint.setOnClickListener(v -> {
            if (busy) {
                return;
            }
            showStatus(BIOMETRIC_UNAVAILABLE_MESSAGE, R.color.ink_muted);
        });

        binding.otpConfirmBtn.setOnClickListener(v -> {
            if (!busy && controller.isComplete()) {
                listener.onSubmitOtp(controller.getCode());
            }
        });

        binding.otpResendRow.setOnClickListener(v -> {
            if (!busy && canResend) {
                listener.onResendRequested();
            }
        });

        binding.otpCloseBtn.setOnClickListener(v -> {
            if (!busy) {
                listener.onDismissRequested();
            }
        });

        dialog.setOnKeyListener((d, keyCode, event) -> {
            if (keyCode == KeyEvent.KEYCODE_BACK && event.getAction() == KeyEvent.ACTION_UP) {
                if (!busy) {
                    listener.onDismissRequested();
                }
                return true;
            }
            return false;
        });

        dialog.setOnDismissListener(d -> stopCursorAnimation());
    }

    private void bindDigitKey(@NonNull View view, char digit) {
        view.setOnClickListener(v -> {
            if (busy) {
                return;
            }
            controller.appendDigit(digit);
            clearStatusIfTransient();
            renderDigits();
        });
    }

    public void show() {
        if (!dialog.isShowing()) {
            dialog.show();
        }
        renderDigits();
    }

    public void dismiss() {
        stopCursorAnimation();
        if (dialog.isShowing()) {
            dialog.dismiss();
        }
    }

    public boolean isShowing() {
        return dialog.isShowing();
    }

    public void setDemoCode(@Nullable String code) {
        controller.setCode(code);
        renderDigits();
    }

    public void setCountdownText(@NonNull String text, boolean canResend) {
        this.canResend = canResend;
        binding.otpCountdownText.setText(text);
        int textColor = ContextCompat.getColor(context, canResend ? R.color.navy : R.color.ink_muted);
        binding.otpCountdownText.setTextColor(textColor);
        binding.otpCountdownIcon.setColorFilter(textColor);
        binding.otpResendRow.setEnabled(!busy && canResend);
    }

    public void setStatusMessage(@Nullable String message) {
        if (message == null || message.trim().isEmpty()) {
            binding.otpStatusText.setText("");
            binding.otpStatusText.setVisibility(View.GONE);
            return;
        }
        showStatus(message.trim(), R.color.danger);
    }

    public void setBusy(boolean busy) {
        this.busy = busy;
        binding.otpCloseBtn.setEnabled(!busy);
        binding.otpCloseBtn.setAlpha(busy ? 0.5f : 1f);
        binding.otpResendRow.setEnabled(!busy && canResend);
        for (View key : keypadInteractiveViews) {
            key.setEnabled(!busy);
        }
        binding.otpConfirmBtn.setText(busy ? "Đang xác thực..." : "Xác nhận");
        updateConfirmButtonState();
    }

    public void clearDigits() {
        controller.clear();
        renderDigits();
    }

    private void showStatus(@NonNull String message, @ColorRes int colorRes) {
        binding.otpStatusText.setText(message);
        binding.otpStatusText.setTextColor(ContextCompat.getColor(context, colorRes));
        binding.otpStatusText.setVisibility(View.VISIBLE);
    }

    private void clearStatusIfTransient() {
        if (binding.otpStatusText.getVisibility() == View.VISIBLE) {
            binding.otpStatusText.setText("");
            binding.otpStatusText.setVisibility(View.GONE);
        }
    }

    private void renderDigits() {
        stopCursorAnimation();
        int activeIndex = controller.getActiveIndex();
        for (int i = 0; i < OtpDigitController.OTP_LENGTH; i++) {
            char digit = controller.digitAt(i);
            if (digit != '\0') {
                boxes[i].setBackgroundResource(R.drawable.bg_otp_digit_filled);
                digits[i].setText(String.valueOf(digit));
                digits[i].setVisibility(View.VISIBLE);
                cursors[i].setVisibility(View.GONE);
                dots[i].setVisibility(View.GONE);
            } else if (i == activeIndex) {
                boxes[i].setBackgroundResource(R.drawable.bg_otp_digit_active);
                digits[i].setText("");
                digits[i].setVisibility(View.GONE);
                cursors[i].setVisibility(View.VISIBLE);
                dots[i].setVisibility(View.GONE);
                if (dialog.isShowing()) {
                    startCursorAnimation(cursors[i]);
                }
            } else {
                boxes[i].setBackgroundResource(R.drawable.bg_otp_digit_empty);
                digits[i].setText("");
                digits[i].setVisibility(View.GONE);
                cursors[i].setVisibility(View.GONE);
                dots[i].setVisibility(View.VISIBLE);
            }
        }
        updateConfirmButtonState();
    }

    private void updateConfirmButtonState() {
        boolean enabled = !busy && controller.isComplete();
        binding.otpConfirmBtn.setEnabled(enabled);
        binding.otpConfirmBtn.setAlpha(enabled ? 1f : 0.6f);
    }

    private void startCursorAnimation(@NonNull View cursor) {
        stopCursorAnimation();
        cursor.setAlpha(1f);
        cursorAnimator = ObjectAnimator.ofFloat(cursor, View.ALPHA, 1f, 0f);
        cursorAnimator.setDuration(500L);
        cursorAnimator.setRepeatMode(ValueAnimator.REVERSE);
        cursorAnimator.setRepeatCount(ValueAnimator.INFINITE);
        cursorAnimator.setInterpolator(new AccelerateDecelerateInterpolator());
        cursorAnimator.start();
    }

    private void stopCursorAnimation() {
        if (cursorAnimator != null) {
            cursorAnimator.cancel();
            cursorAnimator.removeAllListeners();
            cursorAnimator = null;
        }
        for (TextView cursor : cursors) {
            cursor.setAlpha(1f);
        }
    }
}
