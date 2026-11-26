package com.example.final_term.ui.common;

import android.content.Context;
import android.content.DialogInterface;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.StringRes;
import androidx.appcompat.app.AlertDialog;
import androidx.core.content.ContextCompat;

import com.example.final_term.R;
import com.google.android.material.button.MaterialButton;

/**
 * Reusable frosted-glass AlertDialog builder matching the app's visual theme.
 */
public final class ThemedDialogBuilder {
    private final Context context;
    private CharSequence title;
    private CharSequence message;
    private View customView;
    private CharSequence[] items;
    private boolean singleChoice;
    private int checkedItem = -1;
    private DialogInterface.OnClickListener itemsListener;
    private CharSequence positiveText;
    private DialogInterface.OnClickListener positiveListener;
    private CharSequence negativeText;
    private DialogInterface.OnClickListener negativeListener;
    private CharSequence neutralText;
    private DialogInterface.OnClickListener neutralListener;
    private boolean neutralDestructive;
    private boolean cancelable = true;

    public ThemedDialogBuilder(@NonNull Context context) {
        this.context = context;
    }

    public ThemedDialogBuilder setTitle(@Nullable CharSequence title) {
        this.title = title;
        return this;
    }

    public ThemedDialogBuilder setTitle(@StringRes int titleRes) {
        this.title = context.getText(titleRes);
        return this;
    }

    public ThemedDialogBuilder setMessage(@Nullable CharSequence message) {
        this.message = message;
        return this;
    }

    public ThemedDialogBuilder setMessage(@StringRes int messageRes) {
        this.message = context.getText(messageRes);
        return this;
    }

    public ThemedDialogBuilder setView(@Nullable View view) {
        this.customView = view;
        return this;
    }

    public ThemedDialogBuilder setCancelable(boolean cancelable) {
        this.cancelable = cancelable;
        return this;
    }

    public ThemedDialogBuilder setItems(@NonNull CharSequence[] items,
                                       @Nullable DialogInterface.OnClickListener listener) {
        this.items = items;
        this.singleChoice = false;
        this.checkedItem = -1;
        this.itemsListener = listener;
        return this;
    }

    public ThemedDialogBuilder setSingleChoiceItems(@NonNull CharSequence[] items,
                                                  int checkedItem,
                                                  @Nullable DialogInterface.OnClickListener listener) {
        this.items = items;
        this.singleChoice = true;
        this.checkedItem = checkedItem;
        this.itemsListener = listener;
        return this;
    }

    public ThemedDialogBuilder setPositiveButton(@Nullable CharSequence text,
                                                 @Nullable DialogInterface.OnClickListener listener) {
        this.positiveText = text;
        this.positiveListener = listener;
        return this;
    }

    public ThemedDialogBuilder setNegativeButton(@Nullable CharSequence text,
                                                 @Nullable DialogInterface.OnClickListener listener) {
        this.negativeText = text;
        this.negativeListener = listener;
        return this;
    }

    public ThemedDialogBuilder setNeutralButton(@Nullable CharSequence text,
                                                @Nullable DialogInterface.OnClickListener listener) {
        this.neutralText = text;
        this.neutralListener = listener;
        this.neutralDestructive = false;
        return this;
    }

    public ThemedDialogBuilder setNeutralButtonDestructive(@Nullable CharSequence text,
                                                           @Nullable DialogInterface.OnClickListener listener) {
        this.neutralText = text;
        this.neutralListener = listener;
        this.neutralDestructive = true;
        return this;
    }

    public AlertDialog create() {
        LayoutInflater inflater = LayoutInflater.from(context);
        View root = inflater.inflate(R.layout.dialog_themed, null, false);

        TextView titleView = root.findViewById(R.id.dialog_title);
        TextView messageView = root.findViewById(R.id.dialog_message);
        LinearLayout itemsContainer = root.findViewById(R.id.dialog_items_container);
        FrameLayout customContainer = root.findViewById(R.id.dialog_custom_container);
        LinearLayout buttonsRow = root.findViewById(R.id.dialog_buttons_row);
        MaterialButton positiveBtn = root.findViewById(R.id.dialog_positive_button);
        MaterialButton negativeBtn = root.findViewById(R.id.dialog_negative_button);
        View buttonSpacer = root.findViewById(R.id.dialog_button_spacer);
        MaterialButton neutralBtn = root.findViewById(R.id.dialog_neutral_button);

        if (title != null && title.length() > 0) {
            titleView.setText(title);
            titleView.setVisibility(View.VISIBLE);
        }

        if (message != null && message.length() > 0) {
            messageView.setText(message);
            messageView.setVisibility(View.VISIBLE);
        }

        if (customView != null) {
            if (customView.getParent() instanceof ViewGroup) {
                ((ViewGroup) customView.getParent()).removeView(customView);
            }
            customContainer.addView(customView);
            customContainer.setVisibility(View.VISIBLE);
        }

        ThemedAlertDialog dialog = new ThemedAlertDialog(
                context, positiveBtn, negativeBtn, neutralBtn);
        dialog.setView(root);
        dialog.setCancelable(cancelable);
        dialog.setCanceledOnTouchOutside(cancelable);

        if (items != null && items.length > 0) {
            itemsContainer.setVisibility(View.VISIBLE);
            float density = context.getResources().getDisplayMetrics().density;
            for (int i = 0; i < items.length; i++) {
                final int index = i;
                View row = inflater.inflate(R.layout.item_themed_dialog_choice, itemsContainer, false);
                TextView label = row.findViewById(R.id.choice_label);
                ImageView checkIcon = row.findViewById(R.id.choice_check_icon);
                label.setText(items[i]);
                if (singleChoice) {
                    boolean selected = i == checkedItem;
                    checkIcon.setVisibility(selected ? View.VISIBLE : View.INVISIBLE);
                    label.setTextColor(ContextCompat.getColor(
                            context, selected ? R.color.navy_deep : R.color.ink));
                }
                row.setOnClickListener(v -> {
                    if (itemsListener != null) {
                        itemsListener.onClick(dialog, index);
                    } else {
                        dialog.dismiss();
                    }
                });
                itemsContainer.addView(row);
                if (i < items.length - 1) {
                    View divider = new View(context);
                    LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT, Math.max(1, (int) density));
                    divider.setLayoutParams(lp);
                    divider.setBackgroundColor(ContextCompat.getColor(context, R.color.glass_border_subtle));
                    itemsContainer.addView(divider);
                }
            }
        }

        boolean hasPos = positiveText != null && positiveText.length() > 0;
        boolean hasNeg = negativeText != null && negativeText.length() > 0;
        if (hasPos || hasNeg) {
            buttonsRow.setVisibility(View.VISIBLE);
            if (hasNeg) {
                negativeBtn.setText(negativeText);
                negativeBtn.setVisibility(View.VISIBLE);
                negativeBtn.setOnClickListener(v -> {
                    if (negativeListener != null) negativeListener.onClick(dialog, DialogInterface.BUTTON_NEGATIVE);
                    dialog.dismiss();
                });
            }
            if (hasPos && hasNeg) {
                buttonSpacer.setVisibility(View.VISIBLE);
            }
            if (hasPos) {
                positiveBtn.setText(positiveText);
                positiveBtn.setVisibility(View.VISIBLE);
                positiveBtn.setOnClickListener(v -> {
                    if (positiveListener != null) positiveListener.onClick(dialog, DialogInterface.BUTTON_POSITIVE);
                    dialog.dismiss();
                });
            }
        }

        if (neutralText != null && neutralText.length() > 0) {
            neutralBtn.setText(neutralText);
            neutralBtn.setTextColor(ContextCompat.getColor(
                    context, neutralDestructive ? R.color.danger : R.color.navy));
            neutralBtn.setVisibility(View.VISIBLE);
            neutralBtn.setOnClickListener(v -> {
                if (neutralListener != null) neutralListener.onClick(dialog, DialogInterface.BUTTON_NEUTRAL);
                dialog.dismiss();
            });
        }

        return dialog;
    }

    public AlertDialog show() {
        AlertDialog dialog = create();
        dialog.show();
        return dialog;
    }

    private static final class ThemedAlertDialog extends AlertDialog {
        private final Button positiveBtn;
        private final Button negativeBtn;
        private final Button neutralBtn;

        ThemedAlertDialog(@NonNull Context context,
                          Button positiveBtn,
                          Button negativeBtn,
                          Button neutralBtn) {
            super(context);
            this.positiveBtn = positiveBtn;
            this.negativeBtn = negativeBtn;
            this.neutralBtn = neutralBtn;
        }

        @Override
        protected void onStart() {
            super.onStart();
            Window window = getWindow();
            if (window != null) {
                window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
                window.setDimAmount(0.45f);
            }
        }

        @Override
        public Button getButton(int whichButton) {
            if (whichButton == DialogInterface.BUTTON_POSITIVE) return positiveBtn;
            if (whichButton == DialogInterface.BUTTON_NEGATIVE) return negativeBtn;
            if (whichButton == DialogInterface.BUTTON_NEUTRAL) return neutralBtn;
            return super.getButton(whichButton);
        }
    }
}
