package com.example.final_term.ui.utilities;

import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.GridLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;
import androidx.navigation.NavController;
import androidx.navigation.Navigation;

import com.example.final_term.R;
import com.example.final_term.core.FinalTermApplication;
import com.example.final_term.data.BackendApiClient;
import com.example.final_term.data.ReadSummaryFormatter;
import com.example.final_term.databinding.FragmentUtilitiesHubBinding;
import com.google.android.material.card.MaterialCardView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public final class UtilitiesHubFragment extends Fragment {
    private FragmentUtilitiesHubBinding binding;
    private BackendApiClient api;
    private final List<UtilityItem> utilityItems = new ArrayList<>();

    private static final class UtilityItem {
        final String title;
        final String keywords;
        final int icon;
        final int destination;

        UtilityItem(String title, String keywords, int icon, int destination) {
            this.title = title;
            this.keywords = keywords;
            this.icon = icon;
            this.destination = destination;
        }
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        binding = FragmentUtilitiesHubBinding.inflate(inflater, container, false);
        return binding.getRoot();
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        api = ((FinalTermApplication) requireActivity().getApplication())
                .getAppContainer().getBackendApiClient();
        setupServices();
        renderServices("");
        binding.utilitySearchInput.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) { }
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {
                renderServices(s.toString().trim().toLowerCase(Locale.ROOT));
            }
            @Override public void afterTextChanged(Editable s) { }
        });
        binding.utilityHistoryButton.setOnClickListener(v ->
                Navigation.findNavController(v).navigate(R.id.transactionHistory));
        binding.utilitySeeHistory.setOnClickListener(v ->
                Navigation.findNavController(v).navigate(R.id.transactionHistory));
        loadRecentBills();
    }

    private void setupServices() {
        utilityItems.clear();
        utilityItems.add(new UtilityItem("Tiền điện", "dien electricity evn", R.drawable.ic_utility_electricity, R.id.electricityBill));
        utilityItems.add(new UtilityItem("Tiền nước", "nuoc water", R.drawable.ic_utility_water, R.id.waterBill));
        utilityItems.add(new UtilityItem("Nạp điện thoại", "nap dien thoai topup", R.drawable.ic_phone_topup, R.id.mobileTopup));
        utilityItems.add(new UtilityItem("Vé máy bay", "ve may bay flight", R.drawable.ic_flight, R.id.flightBooking));
        utilityItems.add(new UtilityItem("Vé xem phim", "ve phim movie", R.drawable.ic_utility_movie, R.id.movieTickets));
        utilityItems.add(new UtilityItem("Khách sạn", "khach san hotel", R.drawable.ic_utility_hotel, R.id.hotelBooking));
        utilityItems.add(new UtilityItem("Khác", "khac internet hoa don dich vu", R.drawable.ic_utility_all, R.id.otherUtilities));
    }

    private void renderServices(String query) {
        if (binding == null) return;
        GridLayout grid = binding.utilityGrid;
        grid.removeAllViews();
        for (UtilityItem item : utilityItems) {
            if (!query.isEmpty() && !item.keywords.contains(query)
                    && !item.title.toLowerCase(new Locale("vi", "VN")).contains(query)) continue;
            LinearLayout tile = new LinearLayout(requireContext());
            tile.setOrientation(LinearLayout.VERTICAL);
            tile.setGravity(Gravity.CENTER_HORIZONTAL);
            tile.setPadding(dp(4), dp(8), dp(4), dp(8));
            android.util.TypedValue outValue = new android.util.TypedValue();
            requireContext().getTheme().resolveAttribute(
                    android.R.attr.selectableItemBackgroundBorderless, outValue, true);
            tile.setBackgroundResource(outValue.resourceId);

            ImageView icon = new ImageView(requireContext());
            icon.setImageResource(item.icon);
            icon.setBackgroundResource(R.drawable.circle_action_button);
            icon.setElevation(dp(3));
            icon.setPadding(dp(16), dp(16), dp(16), dp(16));
            icon.setColorFilter(ContextCompat.getColor(requireContext(), R.color.navy));
            LinearLayout.LayoutParams iconParams = new LinearLayout.LayoutParams(dp(56), dp(56));
            tile.addView(icon, iconParams);

            TextView label = new TextView(requireContext());
            label.setText(item.title);
            label.setTextColor(ContextCompat.getColor(requireContext(), R.color.ink));
            label.setTextSize(12);
            label.setGravity(Gravity.CENTER);
            label.setMaxLines(2);
            LinearLayout.LayoutParams labelParams = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            labelParams.topMargin = dp(6);
            tile.addView(label, labelParams);

            tile.setClickable(true);
            tile.setFocusable(true);
            tile.setContentDescription(item.title);
            tile.setOnClickListener(v -> openUtility(item));
            GridLayout.LayoutParams params = new GridLayout.LayoutParams(
                    GridLayout.spec(GridLayout.UNDEFINED, 1f), GridLayout.spec(GridLayout.UNDEFINED, 1f));
            params.width = 0;
            params.height = ViewGroup.LayoutParams.WRAP_CONTENT;
            params.setMargins(dp(4), dp(4), dp(4), dp(6));
            grid.addView(tile, params);
        }
    }

    private void openUtility(UtilityItem item) {
        Bundle args = new Bundle();
        args.putString("serviceType", serviceTypeForDestination(item.destination));
        if (getArguments() != null && getArguments().containsKey("sourceAccountId"))
            args.putString("sourceAccountId", getArguments().getString("sourceAccountId"));
        NavController nav = Navigation.findNavController(requireView());
        nav.navigate(item.destination, args);
    }

    private String serviceTypeForDestination(int destination) {
        if (destination == R.id.electricityBill) return "ELECTRICITY";
        if (destination == R.id.waterBill) return "WATER";
        if (destination == R.id.flightBooking) return "FLIGHT";
        if (destination == R.id.movieTickets) return "MOVIE";
        if (destination == R.id.hotelBooking) return "HOTEL";
        if (destination == R.id.otherUtilities) return "OTHER";
        return "ECOMMERCE";
    }

    private void loadRecentBills() {
        binding.utilityRecentEmpty.setText("Đang tải hóa đơn...");
        api.get("/bills").addOnCompleteListener(task -> {
            if (binding == null || !isAdded()) return;
            try {
                if (!task.isSuccessful() || task.getResult() == null || task.getResult().statusCode != 200)
                    throw new IllegalStateException("BILL_LIST_UNAVAILABLE");
                JSONArray items = new JSONObject(task.getResult().body).optJSONArray("items");
                binding.utilityRecentList.removeAllViews();
                int count = 0;
                if (items != null) {
                    for (int i = 0; i < items.length() && count < 5; i++) {
                        JSONObject bill = items.getJSONObject(i);
                        addBillRow(bill);
                        count++;
                    }
                }
                if (count == 0) {
                    binding.utilityRecentList.addView(emptyRow("Chưa có hóa đơn hoặc dịch vụ gần đây."));
                }
            } catch (Exception error) {
                binding.utilityRecentList.removeAllViews();
                binding.utilityRecentList.addView(emptyRow("Không tải được dữ liệu. Hãy mở lại màn hình để thử lại."));
            }
        });
    }

    private void addBillRow(JSONObject bill) {
        String type = bill.optString("serviceType", "");
        String title = serviceName(type);
        String reference = bill.optString("invoiceRef", "");
        LinearLayout row = new LinearLayout(requireContext());
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(dp(8), dp(12), dp(8), dp(12));
        ImageView icon = new ImageView(requireContext());
        int iconRes = "ELECTRICITY".equalsIgnoreCase(type) ? R.drawable.ic_utility_electricity
                : "WATER".equalsIgnoreCase(type) ? R.drawable.ic_utility_water
                : "TOPUP".equalsIgnoreCase(type) ? R.drawable.ic_phone_topup : R.drawable.ic_receipt;
        icon.setImageResource(iconRes);
        icon.setPadding(dp(10), dp(10), dp(10), dp(10));
        icon.setBackgroundResource(R.drawable.circle_action_button);
        icon.setColorFilter(ContextCompat.getColor(requireContext(), R.color.navy));
        row.addView(icon, new LinearLayout.LayoutParams(dp(44), dp(44)));
        LinearLayout details = new LinearLayout(requireContext());
        details.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams detailParams = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        detailParams.setMarginStart(dp(12));
        row.addView(details, detailParams);
        TextView name = new TextView(requireContext());
        name.setText(title);
        name.setTextColor(ContextCompat.getColor(requireContext(), R.color.ink));
        name.setTextSize(14);
        name.setTypeface(null, android.graphics.Typeface.BOLD);
        details.addView(name);
        TextView sub = new TextView(requireContext());
        sub.setText(reference + " · " + statusName(bill.optString("status")));
        sub.setTextColor(ContextCompat.getColor(requireContext(), R.color.ink_muted));
        sub.setTextSize(12);
        LinearLayout.LayoutParams subParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        subParams.topMargin = dp(3);
        details.addView(sub, subParams);
        TextView amount = new TextView(requireContext());
        amount.setText(ReadSummaryFormatter.formatCurrency(bill.optLong("amountMinor"), bill.optString("currency", "VND")));
        amount.setTextColor(ContextCompat.getColor(requireContext(), R.color.navy));
        amount.setTextSize(13);
        amount.setTypeface(null, android.graphics.Typeface.BOLD);
        row.addView(amount);
        row.setBackgroundResource(android.R.drawable.list_selector_background);
        row.setClickable(true);
        row.setFocusable(true);
        row.setOnClickListener(v -> openBill(bill));
        binding.utilityRecentList.addView(row);
    }

    private void openBill(JSONObject bill) {
        String type = bill.optString("serviceType", "");
        int destination = "WATER".equalsIgnoreCase(type) ? R.id.waterBill
                : "ELECTRICITY".equalsIgnoreCase(type) ? R.id.electricityBill : R.id.otherUtilities;
        Bundle args = new Bundle();
        args.putString("serviceType", type.isEmpty() ? "OTHER" : type);
        args.putString("billId", bill.optString("id"));
        if (getArguments() != null && getArguments().containsKey("sourceAccountId"))
            args.putString("sourceAccountId", getArguments().getString("sourceAccountId"));
        NavController nav = Navigation.findNavController(requireView());
        nav.navigate(destination, args);
    }

    private TextView emptyRow(String message) {
        TextView text = new TextView(requireContext());
        text.setText(message);
        text.setTextColor(ContextCompat.getColor(requireContext(), R.color.ink_muted));
        text.setTextSize(13);
        text.setGravity(Gravity.CENTER);
        text.setPadding(dp(12), dp(20), dp(12), dp(20));
        return text;
    }

    private String serviceName(String type) {
        switch (type.toUpperCase(Locale.ROOT)) {
            case "ELECTRICITY": return "Hóa đơn tiền điện";
            case "WATER": return "Hóa đơn tiền nước";
            case "TOPUP": return "Nạp điện thoại";
            case "FLIGHT": return "Vé máy bay";
            case "MOVIE": return "Vé xem phim";
            case "HOTEL": return "Khách sạn";
            case "ECOMMERCE": return "Mua sắm";
            default: return "Dịch vụ";
        }
    }

    private String statusName(String status) {
        if ("PAID".equalsIgnoreCase(status) || "SETTLED".equalsIgnoreCase(status)) return "Đã thanh toán";
        if ("UNPAID".equalsIgnoreCase(status)) return "Chưa thanh toán";
        return "Đang xử lý";
    }

    private int dp(float value) {
        return (int) (value * getResources().getDisplayMetrics().density + 0.5f);
    }

    @Override
    public void onDestroyView() {
        binding = null;
        super.onDestroyView();
    }
}
