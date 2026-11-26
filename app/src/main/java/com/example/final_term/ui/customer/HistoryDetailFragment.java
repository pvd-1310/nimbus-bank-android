package com.example.final_term.ui.customer;

import android.graphics.Typeface;
import android.os.Bundle;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;
import androidx.navigation.Navigation;

import com.example.final_term.R;
import com.example.final_term.databinding.FragmentHistoryDetailBinding;

/**
 * Read-only, human-readable detail for a transaction or an officer action.
 */
public final class HistoryDetailFragment extends Fragment {
    private FragmentHistoryDetailBinding binding;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup parent,
                             @Nullable Bundle state) {
        binding = FragmentHistoryDetailBinding.inflate(inflater, parent, false);
        return binding.getRoot();
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        Bundle a = getArguments() == null ? new Bundle() : getArguments();
        boolean audit = a.getBoolean("audit", false);

        binding.backButton.setOnClickListener(v -> Navigation.findNavController(v).navigateUp());
        binding.titleText.setText(audit ? "Chi tiết thao tác" : "Chi tiết giao dịch");
        binding.eyebrowText.setText(audit ? "LỊCH SỬ THAO TÁC" : "LỊCH SỬ GIAO DỊCH");
        binding.headingText.setText(a.getString("title", audit ? "Chi tiết thao tác" : "Chi tiết giao dịch"));

        String status = a.getString("status", "Chỉ xem");
        bindStatusBadge(status);

        binding.detailsContainer.removeAllViews();
        if (!audit) {
            String amount = a.getString("amount", "").trim();
            if (!amount.isEmpty()) {
                binding.amountText.setVisibility(View.VISIBLE);
                binding.amountText.setText(amount);
                int amountColor = amount.startsWith("+") ? R.color.success : R.color.navy;
                binding.amountText.setTextColor(ContextCompat.getColor(requireContext(), amountColor));
            } else {
                binding.amountText.setVisibility(View.GONE);
            }
            addDetailRow("Loại giao dịch", a.getString("type", "Giao dịch"));
            addDetailRow("Ngày giờ", a.getString("time", "Chưa có thông tin"));
            addDetailRow("Tài khoản thực hiện", a.getString("account", "Tài khoản ngân hàng"));
            addDetailRow("Ngân hàng nhận", a.getString("bank", ""));
            addDetailRow("Số dư sau giao dịch", a.getString("balance", ""));
            addDetailRow("Thông tin", a.getString("note", ""));
        } else {
            binding.amountText.setVisibility(View.GONE);
            addDetailRow("Nội dung thao tác", a.getString("description", "Thay đổi được ghi nhận trong hệ thống."));
            addDetailRow("Người thực hiện", a.getString("actor", "Nhân sự ngân hàng"));
            addDetailRow("Thời gian", a.getString("time", "Chưa có thông tin"));
            addDetailRow("Đối tượng liên quan", a.getString("target", "Hồ sơ khách hàng"));
            addDetailRow("Ghi chú thay đổi", a.getString("summary", ""));
        }
    }

    private void bindStatusBadge(String status) {
        String label = (status == null || status.trim().isEmpty()) ? "Chỉ xem" : status.trim();
        binding.statusBadge.setText(label);
        String lower = label.toLowerCase();
        if (lower.contains("thất bại") || lower.contains("hủy") || lower.contains("từ chối") || lower.contains("lỗi")) {
            binding.statusBadge.setBackgroundResource(R.drawable.badge_status_danger);
            binding.statusBadge.setTextColor(ContextCompat.getColor(requireContext(), R.color.danger));
        } else if (lower.contains("chờ") || lower.contains("đang")) {
            binding.statusBadge.setBackgroundResource(R.drawable.badge_status_warning);
            binding.statusBadge.setTextColor(ContextCompat.getColor(requireContext(), R.color.warning));
        } else if (lower.contains("chỉ xem")) {
            binding.statusBadge.setBackgroundResource(R.drawable.badge_status_neutral);
            binding.statusBadge.setTextColor(ContextCompat.getColor(requireContext(), R.color.ink_muted));
        } else {
            binding.statusBadge.setBackgroundResource(R.drawable.badge_status_success);
            binding.statusBadge.setTextColor(ContextCompat.getColor(requireContext(), R.color.success));
        }
    }

    private void addDetailRow(String label, String value) {
        if (value == null || value.trim().isEmpty()) return;

        if (binding.detailsContainer.getChildCount() > 0) {
            View divider = new View(requireContext());
            LinearLayout.LayoutParams dividerParams = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, dp(1));
            dividerParams.topMargin = dp(2);
            dividerParams.bottomMargin = dp(2);
            divider.setLayoutParams(dividerParams);
            divider.setBackgroundColor(ContextCompat.getColor(requireContext(), R.color.glass_border_subtle));
            binding.detailsContainer.addView(divider);
        }

        LinearLayout row = new LinearLayout(requireContext());
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(10), 0, dp(10));

        TextView labelView = new TextView(requireContext());
        labelView.setText(label);
        labelView.setTextSize(13);
        labelView.setTextColor(ContextCompat.getColor(requireContext(), R.color.ink_muted));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 0.44f);
        lp.setMarginEnd(dp(12));
        row.addView(labelView, lp);

        TextView valueView = new TextView(requireContext());
        valueView.setText(value.trim());
        valueView.setTextSize(14);
        valueView.setTypeface(null, Typeface.BOLD);
        valueView.setGravity(Gravity.END);
        valueView.setTextColor(ContextCompat.getColor(requireContext(), R.color.ink));
        LinearLayout.LayoutParams vp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 0.56f);
        row.addView(valueView, vp);

        binding.detailsContainer.addView(row);
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        binding = null;
    }
}
