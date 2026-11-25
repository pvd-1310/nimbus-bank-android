package com.example.final_term.ui.transaction;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/**
 * Pure state controller for a 6-digit numeric OTP input.
 * Clamps input to 6 ASCII digits ('0'..'9') and tracks the active cursor slot.
 */
public final class OtpDigitController {
    public static final int OTP_LENGTH = 6;

    private final StringBuilder digits = new StringBuilder(OTP_LENGTH);

    public void appendDigit(char digit) {
        if (digit < '0' || digit > '9' || digits.length() >= OTP_LENGTH) {
            return;
        }
        digits.append(digit);
    }

    public void backspace() {
        if (digits.length() > 0) {
            digits.deleteCharAt(digits.length() - 1);
        }
    }

    public void setCode(@Nullable String code) {
        digits.setLength(0);
        if (code == null) {
            return;
        }
        for (int i = 0; i < code.length() && digits.length() < OTP_LENGTH; i++) {
            char c = code.charAt(i);
            if (c >= '0' && c <= '9') {
                digits.append(c);
            }
        }
    }

    public void clear() {
        digits.setLength(0);
    }

    @NonNull
    public String getCode() {
        return digits.toString();
    }

    public int getLength() {
        return digits.length();
    }

    public boolean isComplete() {
        return digits.length() == OTP_LENGTH;
    }

    public int getActiveIndex() {
        return digits.length() < OTP_LENGTH ? digits.length() : -1;
    }

    public char digitAt(int index) {
        if (index < 0 || index >= digits.length()) {
            return '\0';
        }
        return digits.charAt(index);
    }
}
