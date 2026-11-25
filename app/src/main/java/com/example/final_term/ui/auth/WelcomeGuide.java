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
    private static final String[] PREVIEWS = {
            "Tài khoản thanh toán · Tiết kiệm · Khoản vay",
            "Định danh eKYC · Xác thực Smart OTP",
            "Hỏi đáp & hướng dẫn giao dịch nhanh"
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
        Dialog dialog = new Dialog(requireContext(), R.style.Theme_Final_term);
        render(dialog);
        return dialog;
    }

    @Override
    public void onStart() {
        super.onStart();
        if (getDialog() != null && getDialog().getWindow() != null) {
            getDialog().getWindow().setLayout(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
        }
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

        // Top Bar: Nimbus badge + Step Pill + Skip
        LinearLayout topBar = new LinearLayout(requireContext());
        topBar.setOrientation(LinearLayout.HORIZONTAL);
        topBar.setGravity(Gravity.CENTER_VERTICAL);
        TextView brand = new TextView(requireContext());
        brand.setText("Nimbus");
        brand.setTextSize(15);
        brand.setTypeface(null, android.graphics.Typeface.BOLD);
        brand.setTextColor(requireContext().getColor(R.color.navy));
        topBar.addView(brand, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView stepPill = new TextView(requireContext());
        stepPill.setText((page + 1) + " / " + TITLES.length);
        stepPill.setTextSize(12);
        stepPill.setTextColor(requireContext().getColor(R.color.navy));
        stepPill.setBackgroundResource(R.drawable.glass_pill_bg);
        stepPill.setPadding(dp(12), dp(4), dp(12), dp(4));
        topBar.addView(stepPill);

        MaterialButton skip = new MaterialButton(requireContext(), null,
                com.google.android.material.R.attr.materialButtonOutlinedStyle);
        skip.setText("Bỏ qua");
        skip.setOnClickListener(view -> finishGuide());
        LinearLayout.LayoutParams skipParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        skipParams.leftMargin = dp(8);
        topBar.addView(skip, skipParams);
        content.addView(topBar, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        // Hero Artwork Card (Matches Onboarding_1/2/3.html)
        LinearLayout heroCard = new LinearLayout(requireContext());
        heroCard.setOrientation(LinearLayout.VERTICAL);
        heroCard.setGravity(Gravity.CENTER);
        heroCard.setBackgroundResource(R.drawable.glass_panel_card);
        heroCard.setPadding(dp(20), dp(20), dp(20), dp(20));
        LinearLayout.LayoutParams art = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(200));
        art.topMargin = dp(20);

        if (page == 0) {
            // Slide 1: Account & Card Overview
            LinearLayout miniVisa = new LinearLayout(requireContext());
            miniVisa.setOrientation(LinearLayout.VERTICAL);
            miniVisa.setBackgroundResource(R.drawable.navy_button_pill);
            miniVisa.setPadding(dp(18), dp(14), dp(18), dp(14));
            TextView visaBrand = new TextView(requireContext());
            visaBrand.setText("NIMBUS BANK");
            visaBrand.setTextColor(requireContext().getColor(R.color.white));
            visaBrand.setTextSize(12);
            visaBrand.setTypeface(null, android.graphics.Typeface.BOLD);
            TextView visaBalance = new TextView(requireContext());
            visaBalance.setText("Quản lý dòng tiền thông minh");
            visaBalance.setTextColor(requireContext().getColor(R.color.white));
            visaBalance.setTextSize(16);
            visaBalance.setTypeface(null, android.graphics.Typeface.BOLD);
            visaBalance.setPadding(0, dp(8), 0, 0);
            miniVisa.addView(visaBrand);
            miniVisa.addView(visaBalance);
            heroCard.addView(miniVisa, new LinearLayout.LayoutParams(dp(240), ViewGroup.LayoutParams.WRAP_CONTENT));
        } else if (page == 1) {
            // Slide 2: eKYC Shield
            ImageView illustration = new ImageView(requireContext());
            illustration.setImageResource(ICONS[page]);
            illustration.setImageTintList(requireContext().getColorStateList(R.color.navy));
            illustration.setImportantForAccessibility(ImageView.IMPORTANT_FOR_ACCESSIBILITY_NO);
            heroCard.addView(illustration, new LinearLayout.LayoutParams(dp(72), dp(72)));
            TextView aesBadge = new TextView(requireContext());
            aesBadge.setText("Bảo mật đa tầng · eKYC & OTP");
            aesBadge.setTextColor(requireContext().getColor(R.color.success));
            aesBadge.setTextSize(12);
            aesBadge.setTypeface(null, android.graphics.Typeface.BOLD);
            aesBadge.setPadding(0, dp(10), 0, 0);
            heroCard.addView(aesBadge);
        } else {
            // Slide 3: AI Orb
            ImageView illustration = new ImageView(requireContext());
            illustration.setImageResource(ICONS[page]);
            illustration.setImageTintList(requireContext().getColorStateList(R.color.navy));
            illustration.setBackgroundResource(R.drawable.circle_action_button);
            illustration.setPadding(dp(16), dp(16), dp(16), dp(16));
            illustration.setImportantForAccessibility(ImageView.IMPORTANT_FOR_ACCESSIBILITY_NO);
            heroCard.addView(illustration, new LinearLayout.LayoutParams(dp(72), dp(72)));
            TextView insight = new TextView(requireContext());
            insight.setText("Trợ lý hướng dẫn tính năng ngân hàng");
            insight.setTextColor(requireContext().getColor(R.color.navy));
            insight.setTextSize(12);
            insight.setBackgroundResource(R.drawable.glass_pill_bg);
            insight.setPadding(dp(12), dp(6), dp(12), dp(6));
            LinearLayout.LayoutParams ip = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            ip.topMargin = dp(12);
            heroCard.addView(insight, ip);
        }
        content.addView(heroCard, art);

        TextView preview = new TextView(requireContext());
        preview.setText(PREVIEWS[page]);
        preview.setTextSize(12);
        preview.setTextColor(requireContext().getColor(R.color.navy));
        preview.setBackgroundResource(R.drawable.glass_pill_bg);
        preview.setPadding(dp(14), dp(6), dp(14), dp(6));
        preview.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams previewParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        previewParams.topMargin = dp(14);
        content.addView(preview, previewParams);

        addText(content, TITLES[page], 24, R.color.ink, 20);
        addText(content, DESCRIPTIONS[page], 15, R.color.ink_muted, 10);

        // Capsule/dot pagination indicator
        LinearLayout dots = new LinearLayout(requireContext());
        dots.setOrientation(LinearLayout.HORIZONTAL);
        dots.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams dotsParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        dotsParams.topMargin = dp(20);
        for (int i = 0; i < TITLES.length; i++) {
            android.view.View dot = new android.view.View(requireContext());
            android.graphics.drawable.GradientDrawable shape = new android.graphics.drawable.GradientDrawable();
            shape.setCornerRadius(dp(4));
            shape.setColor(requireContext().getColor(i == page ? R.color.navy : R.color.bg_slate_soft));
            dot.setBackground(shape);
            LinearLayout.LayoutParams dpParams = new LinearLayout.LayoutParams(dp(i == page ? 24 : 8), dp(8));
            dpParams.setMargins(dp(4), 0, dp(4), 0);
            dots.addView(dot, dpParams);
        }
        content.addView(dots, dotsParams);

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
