package com.example.final_term.ui.customer;

import android.graphics.Typeface;
import android.os.Bundle;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;
import androidx.navigation.Navigation;
import com.example.final_term.R;
import com.example.final_term.data.ReadSummaryFormatter;

/** Read-only, human-readable detail for a transaction or an officer action. */
public final class HistoryDetailFragment extends Fragment {
    @Nullable @Override public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup parent, @Nullable Bundle state) {
        Bundle a = getArguments() == null ? new Bundle() : getArguments();
        boolean audit = a.getBoolean("audit", false);
        ScrollView scroll = new ScrollView(requireContext());
        scroll.setBackgroundResource(R.drawable.background_gradient);
        LinearLayout page = new LinearLayout(requireContext()); page.setOrientation(LinearLayout.VERTICAL);
        page.setPadding(dp(20), dp(18), dp(20), dp(36)); scroll.addView(page);
        TextView back = text("‹     Quay lại", 16, R.color.navy); back.setTypeface(null, Typeface.BOLD);
        back.setPadding(0, dp(8), 0, dp(24)); back.setOnClickListener(v -> Navigation.findNavController(v).navigateUp()); page.addView(back);
        TextView eyebrow = text(audit ? "LỊCH SỬ THAO TÁC" : "LỊCH SỬ GIAO DỊCH", 12, R.color.navy);
        eyebrow.setTypeface(null, Typeface.BOLD); page.addView(eyebrow);
        TextView heading = text(a.getString("title", audit ? "Chi tiết thao tác" : "Chi tiết giao dịch"), 24, R.color.ink);
        heading.setTypeface(null, Typeface.BOLD); heading.setPadding(0, dp(6), 0, dp(8)); page.addView(heading);
        TextView status = text(a.getString("status", "Chỉ xem"), 14, R.color.success); status.setTypeface(null, Typeface.BOLD); page.addView(status);
        LinearLayout card = new LinearLayout(requireContext()); card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(18), dp(18), dp(18), dp(18)); card.setBackgroundResource(R.drawable.glass_panel_soft);
        LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(-1, -2); cp.topMargin = dp(20); page.addView(card, cp);
        if (!audit) {
            String amount = a.getString("amount", "");
            if (!amount.isEmpty()) { TextView hero = text(amount, 28, R.color.navy); hero.setTypeface(null, Typeface.BOLD); hero.setGravity(Gravity.CENTER); hero.setPadding(0, dp(4), 0, dp(18)); card.addView(hero); }
            field(card, "Loại giao dịch", a.getString("type", "Giao dịch"));
            field(card, "Ngày giờ", a.getString("time", "Chưa có thông tin"));
            field(card, "Tài khoản thực hiện", a.getString("account", "Tài khoản ngân hàng"));
            field(card, "Ngân hàng nhận", a.getString("bank", ""));
            field(card, "Số dư sau giao dịch", a.getString("balance", ""));
            field(card, "Thông tin", a.getString("note", ""));
        } else {
            field(card, "Nội dung thao tác", a.getString("description", "Thay đổi được ghi nhận trong hệ thống."));
            field(card, "Người thực hiện", a.getString("actor", "Nhân sự ngân hàng"));
            field(card, "Thời gian", a.getString("time", "Chưa có thông tin"));
            field(card, "Đối tượng liên quan", a.getString("target", "Hồ sơ khách hàng"));
            field(card, "Ghi chú thay đổi", a.getString("summary", ""));
        }
        TextView readonly = text("Thông tin chỉ xem", 12, R.color.ink_muted); readonly.setGravity(Gravity.CENTER); readonly.setPadding(0, dp(18), 0, 0); page.addView(readonly);
        return scroll;
    }
    private void field(LinearLayout card, String label, String value) {
        if (value == null || value.trim().isEmpty()) return;
        TextView l = text(label, 12, R.color.ink_muted); l.setPadding(0, dp(8), 0, dp(3)); card.addView(l);
        TextView v = text(value, 15, R.color.ink); v.setPadding(0, 0, 0, dp(7)); card.addView(v);
    }
    private TextView text(String s, int size, int color) { TextView t = new TextView(requireContext()); t.setText(s); t.setTextSize(size); t.setTextColor(ContextCompat.getColor(requireContext(), color)); return t; }
    private int dp(int v) { return (int)(v * getResources().getDisplayMetrics().density + .5f); }
}
