package com.example.final_term.ui.common;

import android.content.Context;
import android.view.View;
import android.view.ViewGroup;
import com.google.android.material.bottomsheet.BottomSheetDialog;

/** Presents an existing transaction authorization view while preserving its listeners and state. */
public final class TransactionSheet {
    private final View content;
    private final ViewGroup parent;
    private final int index;
    private final ViewGroup.LayoutParams params;
    private final BottomSheetDialog dialog;

    public TransactionSheet(Context context, View content) {
        this.content = content;
        parent = (ViewGroup) content.getParent();
        index = parent.indexOfChild(content);
        params = content.getLayoutParams();
        parent.removeView(content);
        dialog = new BottomSheetDialog(context);
        dialog.setCancelable(false);
        dialog.setContentView(content);
        content.setVisibility(View.VISIBLE);
        dialog.show();
    }

    public void close() {
        dialog.dismiss();
        if (content.getParent() instanceof ViewGroup) ((ViewGroup) content.getParent()).removeView(content);
        parent.addView(content, Math.min(index, parent.getChildCount()), params);
        content.setVisibility(View.GONE);
    }
}
