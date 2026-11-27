package com.example.final_term.ui.auth;

import android.app.Dialog;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.DialogFragment;

import com.example.final_term.R;
import com.example.final_term.databinding.DialogWelcomeGuideBinding;

public final class WelcomeGuide extends DialogFragment {
    private static final String SEEN = "welcome_guide_seen";

    private static final String[] TITLES = {
            "Ngân hàng số\nĐơn giản hơn",
            "Trung tâm\nTăng trưởng tài chính",
            "Thanh toán hóa đơn\nNhanh chóng tức thì"
    };
    private static final String[] SUBTITLES = {
            "Quản lý tiền, thẻ, hóa đơn và chuyển khoản\ntừ một ví di động bảo mật.",
            "Quản lý tiền, thẻ, hóa đơn và chuyển khoản\ntừ một ví di động bảo mật.",
            "Thanh toán an toàn mọi lúc với danh sách hóa đơn\nđã lưu cho giao dịch hằng ngày nhanh chóng."
    };
    private static final String[] PILLS = {
            "Luôn bảo mật",
            "Thống kê dòng tiền",
            "Thanh toán dễ dàng"
    };
    private static final String[] SECTIONS = {
            "Tiền của bạn, quyền kiểm soát của bạn",
            "Theo dõi tiến độ tài chính",
            "Thanh toán hóa đơn an toàn"
    };

    private DialogWelcomeGuideBinding binding;
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
        binding = DialogWelcomeGuideBinding.inflate(LayoutInflater.from(requireContext()));
        dialog.setContentView(binding.getRoot());

        binding.btnSkip.setOnClickListener(v -> finishGuide());
        binding.btnNext.setOnClickListener(v -> {
            if (page < TITLES.length - 1) {
                page++;
                renderPage();
            } else {
                finishGuide();
            }
        });

        renderPage();
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

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        binding = null;
    }

    private void renderPage() {
        if (binding == null) return;
        int activeColor = ContextCompat.getColor(requireContext(), R.color.navy);
        int inactiveColor = 0x262F3E6E;

        binding.seg1.setBackgroundColor(page >= 0 ? activeColor : inactiveColor);
        binding.seg2.setBackgroundColor(page >= 1 ? activeColor : inactiveColor);
        binding.seg3.setBackgroundColor(page >= 2 ? activeColor : inactiveColor);
        binding.stepCounter.setText((page + 1) + "/" + TITLES.length);

        binding.onboardingTitle.setText(TITLES[page]);
        binding.onboardingSubtitle.setText(SUBTITLES[page]);
        binding.onboardingPill.setText(PILLS[page]);
        binding.onboardingSectionTitle.setText(SECTIONS[page]);

        binding.page1Preview.setVisibility(page == 0 ? View.VISIBLE : View.GONE);
        binding.page2Preview.setVisibility(page == 1 ? View.VISIBLE : View.GONE);
        binding.page3Preview.setVisibility(page == 2 ? View.VISIBLE : View.GONE);

        binding.btnNext.setText(page < TITLES.length - 1 ? "Tiếp theo" : "Bắt đầu");
    }

    private void finishGuide() {
        requireActivity().getPreferences(0).edit().putBoolean(SEEN, true).apply();
        dismiss();
    }
}
