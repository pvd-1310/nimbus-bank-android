package com.example.final_term.ui.common;

import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;

/** Applies system bars, cutouts and the keyboard to a full-screen dialog's content. */
public final class FullscreenInsets {
    private FullscreenInsets() { }

    public static void apply(Window window, View content) {
        WindowCompat.setDecorFitsSystemWindows(window, false);
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        final int left = content.getPaddingLeft();
        final int top = content.getPaddingTop();
        final int right = content.getPaddingRight();
        final int bottom = content.getPaddingBottom();
        ViewCompat.setOnApplyWindowInsetsListener(content, (view, insets) -> {
            Insets safe = insets.getInsets(WindowInsetsCompat.Type.systemBars()
                    | WindowInsetsCompat.Type.displayCutout());
            Insets keyboard = insets.getInsets(WindowInsetsCompat.Type.ime());
            view.setPadding(left + safe.left, top + safe.top, right + safe.right,
                    bottom + Math.max(safe.bottom, keyboard.bottom));
            return insets;
        });
        ViewCompat.requestApplyInsets(content);
    }
}
