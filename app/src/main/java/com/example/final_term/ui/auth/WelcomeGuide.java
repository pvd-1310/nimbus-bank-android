package com.example.final_term.ui.auth;

import android.app.Dialog;
import android.os.Bundle;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.fragment.app.DialogFragment;

import com.example.final_term.R;
import com.google.android.material.button.MaterialButton;

public final class WelcomeGuide extends DialogFragment {
    private static final String SEEN = "welcome_guide_seen";
    private static final int[] ICONS = {R.drawable.ic_nav_wallet, R.drawable.ic_shield, R.drawable.ic_ai_spark};
    private static final String[] TITLES = {
            "Quản lý tài chính trong tầm tay",
            "Bảo mật đa lớp với eKYC và OTP",
            "Trợ lý AI luôn bên bạn"
    };
    private static final String[] DESCRIPTIONS = {
            "Theo dõi số dư, dòng tiền và lịch sử giao dịch.",
            "Định danh bằng giấy tờ và khuôn mặt. Xác nhận giao dịch bằng mã OTP.",
            "Hỏi trợ lý cách sử dụng các tính năng ngân hàng khi dịch vụ khả dụng."
    };
    private int page;

    static void showIfNeeded(LoginFragment login) {
        var manager = login.getChildFragmentManager();
        if (!login.requireActivity().getPreferences(0).getBoolean(SEEN, false)
                && manager.findFragmentByTag(SEEN) == null && !manager.isStateSaved()) {
            new WelcomeGuide().showNow(manager, SEEN);
        }
    }

    @NonNull
    @Override
    public Dialog onCreateDialog(Bundle savedInstanceState) {
        if (savedInstanceState != null) page = savedInstanceState.getInt("page");
        setCancelable(false);
        Dialog dialog = new Dialog(requireContext(), android.R.style.Theme_Material_Light_NoActionBar);
        render(dialog);
        return dialog;
    }

    @Override
    public void onSaveInstanceState(@NonNull Bundle state) {
        super.onSaveInstanceState(state);
        state.putInt("page", page);
    }

    private void render(Dialog dialog) {
        ScrollView scroll = new ScrollView(requireContext());
        scroll.setFillViewport(true);
        scroll.setBackgroundResource(R.drawable.background_gradient);
        LinearLayout content = new LinearLayout(requireContext());
        content.setOrientation(LinearLayout.VERTICAL);
        content.setGravity(Gravity.CENTER_HORIZONTAL | Gravity.CENTER_VERTICAL);
        content.setPadding(dp(24), dp(32), dp(24), dp(32));
        scroll.addView(content);

        MaterialButton skip = new MaterialButton(requireContext(), null,
                com.google.android.material.R.attr.materialButtonOutlinedStyle);
        skip.setText("Bỏ qua");
        skip.setOnClickListener(view -> finishGuide());
        content.addView(skip);

        ImageView illustration = new ImageView(requireContext());
        illustration.setImageResource(ICONS[page]);
        illustration.setImageTintList(requireContext().getColorStateList(R.color.navy));
        illustration.setBackgroundResource(R.drawable.glass_panel_card);
        illustration.setPadding(dp(50), dp(50), dp(50), dp(50));
        illustration.setImportantForAccessibility(ImageView.IMPORTANT_FOR_ACCESSIBILITY_NO);
        LinearLayout.LayoutParams art = new LinearLayout.LayoutParams(dp(180), dp(180));
        art.topMargin = dp(24);
        content.addView(illustration, art);

        addText(content, TITLES[page], 24, R.color.ink, 24);
        addText(content, DESCRIPTIONS[page], 15, R.color.ink_muted, 12);
        addText(content, "Bước " + (page + 1) + " / " + TITLES.length, 13, R.color.ink, 20);

        MaterialButton next = new MaterialButton(requireContext());
        next.setText(page == TITLES.length - 1 ? "Bắt đầu" : "Tiếp tục");
        next.setCornerRadius(dp(20));
        next.setMinHeight(dp(56));
        next.setOnClickListener(view -> {
            if (page == TITLES.length - 1) finishGuide();
            else {
                page++;
                render(dialog);
            }
        });
        LinearLayout.LayoutParams action = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        action.topMargin = dp(12);
        content.addView(next, action);
        dialog.setContentView(scroll);
    }

    private void addText(LinearLayout content, String label, int size, int color, int margin) {
        TextView text = new TextView(requireContext());
        text.setText(label);
        text.setTextSize(size);
        text.setTextColor(requireContext().getColor(color));
        text.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.topMargin = dp(margin);
        content.addView(text, params);
    }

    private void finishGuide() {
        requireActivity().getPreferences(0).edit().putBoolean(SEEN, true).apply();
        dismiss();
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
