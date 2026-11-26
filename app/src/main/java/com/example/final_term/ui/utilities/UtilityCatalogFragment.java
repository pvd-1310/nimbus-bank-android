package com.example.final_term.ui.utilities;

import android.os.Bundle;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;
import androidx.navigation.Navigation;

import com.example.final_term.R;
import com.example.final_term.core.FinalTermApplication;
import com.example.final_term.data.BackendApiClient;
import com.example.final_term.data.ReadSummaryFormatter;
import com.example.final_term.databinding.FragmentUtilityCatalogBinding;
import com.example.final_term.ui.transaction.TransactionFlowRequest;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.card.MaterialCardView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

public final class UtilityCatalogFragment extends Fragment {
    private FragmentUtilityCatalogBinding binding;
    private BackendApiClient api;
    private String serviceType;
    private String sourceAccountId;
    private int requestVersion;
    private final List<JSONObject> catalogProducts = new ArrayList<>();
    private boolean flightsAscending = true;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        binding = FragmentUtilityCatalogBinding.inflate(inflater, container, false);
        return binding.getRoot();
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        serviceType = getArguments() == null ? "FLIGHT" : getArguments().getString("serviceType", "FLIGHT");
        sourceAccountId = getArguments() == null ? null : getArguments().getString("sourceAccountId");
        api = ((FinalTermApplication) requireActivity().getApplication())
                .getAppContainer().getBackendApiClient();
        String title = serviceTitle(serviceType);
        binding.headerTitle.setText(title);
        binding.catalogIntroTitle.setText(introTitle(serviceType));
        binding.catalogIcon.setImageResource(serviceIcon(serviceType));
        binding.catalogSectionTitle.setText(sectionTitle(serviceType));
        binding.backButton.setOnClickListener(v -> Navigation.findNavController(v).popBackStack());
        boolean flights = "FLIGHT".equals(serviceType);
        binding.flightSearchPanel.setVisibility(flights ? View.VISIBLE : View.GONE);
        binding.filterButton.setVisibility(flights ? View.VISIBLE : View.GONE);
        binding.catalogIntroSubtitle.setText(flights
                ? "Chọn hành trình phù hợp và xem giờ bay, hãng bay cùng giá vé."
                : "Chọn dịch vụ để xem giá và tiếp tục thanh toán.");
        if (flights) {
            binding.routeSwapButton.setOnClickListener(v -> swapRoute());
            binding.searchFlightsButton.setOnClickListener(v -> {
                renderFlightResults();
                binding.scrollContent.smoothScrollTo(0, binding.flightResultsHeader.getTop());
            });
            binding.filterButton.setOnClickListener(v -> {
                flightsAscending = !flightsAscending;
                binding.filterButton.setText(flightsAscending ? "Giá thấp nhất" : "Giá cao nhất");
                renderFlightResults();
            });
        }
        loadCatalog();
    }

    private void loadCatalog() {
        int version = ++requestVersion;
        binding.productList.removeAllViews();
        binding.catalogCount.setText("");
        showStatus("Đang tải danh sách...");
        api.get("/catalog?limit=100&serviceType=" + serviceType).addOnCompleteListener(task -> {
            if (binding == null || !isAdded() || version != requestVersion) return;
            try {
                if (!task.isSuccessful() || task.getResult() == null || task.getResult().statusCode != 200)
                    throw new IllegalStateException("CATALOG_UNAVAILABLE");
                JSONArray items = new JSONObject(task.getResult().body).optJSONArray("items");
                List<JSONObject> products = new ArrayList<>();
                if (items != null) for (int i = 0; i < items.length(); i++) {
                    JSONObject product = items.getJSONObject(i);
                    if (product.optBoolean("demo")) products.add(product);
                }
                catalogProducts.clear();
                catalogProducts.addAll(products);
                binding.catalogCount.setText(products.size() + ("FLIGHT".equals(serviceType) ? " chuyến" : " lựa chọn"));
                binding.productList.removeAllViews();
                if (products.isEmpty()) {
                    showStatus("Chưa có lựa chọn cho dịch vụ này.");
                    return;
                }
                binding.statusText.setVisibility(View.GONE);
                if ("FLIGHT".equals(serviceType)) renderFlightResults();
                else for (JSONObject product : products) addProductCard(product);
            } catch (Exception error) {
                showStatus("Không tải được danh sách. Hãy thử lại sau.");
            }
        });
    }

    private void renderFlightResults() {
        if (binding == null) return;
        String origin = binding.flightOriginCode.getText().toString().trim();
        String destination = binding.flightDestinationCode.getText().toString().trim();
        List<JSONObject> matching = new ArrayList<>();
        for (JSONObject product : catalogProducts) {
            String productOrigin = product.optString("origin", "").trim();
            String productDestination = product.optString("destination", "").trim();
            if (productOrigin.isEmpty() || productDestination.isEmpty()
                    || (origin.equalsIgnoreCase(productOrigin) && destination.equalsIgnoreCase(productDestination))) {
                matching.add(product);
            }
        }
        matching.sort(Comparator.comparingLong(product -> product.optLong("amountMinor")));
        if (!flightsAscending) Collections.reverse(matching);
        binding.productList.removeAllViews();
        binding.catalogCount.setText(matching.size() + " chuyến");
        if (matching.isEmpty()) {
            showStatus("Chưa có chuyến bay phù hợp cho tuyến " + origin + " – " + destination + ".");
            return;
        }
        binding.statusText.setVisibility(View.GONE);
        for (JSONObject product : matching) addFlightCard(product);
    }

    private void swapRoute() {
        CharSequence originCity = binding.flightOriginCity.getText();
        CharSequence originCode = binding.flightOriginCode.getText();
        binding.flightOriginCity.setText(binding.flightDestinationCity.getText());
        binding.flightOriginCode.setText(binding.flightDestinationCode.getText());
        binding.flightDestinationCity.setText(originCity);
        binding.flightDestinationCode.setText(originCode);
    }

    private void addFlightCard(JSONObject product) {
        MaterialCardView card = new MaterialCardView(requireContext());
        card.setRadius(dp(22));
        card.setCardElevation(0);
        card.setCardBackgroundColor(ContextCompat.getColor(requireContext(), R.color.glass_card));
        card.setStrokeColor(ContextCompat.getColor(requireContext(), R.color.glass_border));
        card.setStrokeWidth(dp(1));

        LinearLayout column = new LinearLayout(requireContext());
        column.setOrientation(LinearLayout.VERTICAL);
        column.setPadding(dp(15), dp(14), dp(15), dp(13));

        LinearLayout airlineRow = new LinearLayout(requireContext());
        airlineRow.setGravity(Gravity.CENTER_VERTICAL);
        TextView badge = new TextView(requireContext());
        String airline = product.optString("airline", "").trim();
        if (airline.isEmpty()) airline = product.optString("name", "Chuyến bay");
        String flightNumber = product.optString("flightNumber", "").trim();
        String airlineCode = flightNumber.isEmpty() ? airlineInitials(airline)
                : flightNumber.split("\\s+")[0].toUpperCase(java.util.Locale.ROOT);
        badge.setText(airlineCode);
        badge.setTextColor(ContextCompat.getColor(requireContext(), R.color.navy));
        badge.setTextSize(10);
        badge.setTypeface(null, android.graphics.Typeface.BOLD);
        badge.setGravity(Gravity.CENTER);
        badge.setBackgroundResource(R.drawable.utility_icon_circle);
        airlineRow.addView(badge, new LinearLayout.LayoutParams(dp(34), dp(34)));

        LinearLayout airlineDetails = new LinearLayout(requireContext());
        airlineDetails.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams airlineParams = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        airlineParams.setMarginStart(dp(10));
        airlineRow.addView(airlineDetails, airlineParams);
        TextView airlineName = flightText(airline, 13, R.color.ink, true);
        airlineDetails.addView(airlineName);
        String flightMeta = flightNumber;
        String aircraft = product.optString("aircraft", "").trim();
        String subtitle = flightMeta;
        if (!aircraft.isEmpty()) subtitle += (subtitle.isEmpty() ? "" : " · ") + aircraft;
        if (subtitle.isEmpty()) subtitle = "Thông tin chuyến bay";
        TextView meta = flightText(subtitle, 11, R.color.ink_muted, false);
        LinearLayout.LayoutParams metaParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        metaParams.topMargin = dp(3);
        airlineDetails.addView(meta, metaParams);

        column.addView(airlineRow);

        LinearLayout timeline = new LinearLayout(requireContext());
        timeline.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams timelineParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        timelineParams.topMargin = dp(16);
        column.addView(timeline, timelineParams);
        String departure = product.optString("departureTime", "—");
        String arrival = product.optString("arrivalTime", "—");
        String origin = product.optString("origin", "").trim();
        String destination = product.optString("destination", "").trim();
        timeline.addView(timeColumn(departure, origin, Gravity.START));

        LinearLayout middle = new LinearLayout(requireContext());
        middle.setOrientation(LinearLayout.VERTICAL);
        middle.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams middleParams = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        middleParams.setMargins(dp(8), 0, dp(8), 0);
        timeline.addView(middle, middleParams);
        String duration = product.optString("duration", "").trim();
        TextView durationText = flightText(duration.isEmpty() ? "Thời lượng chưa có" : duration + " · Bay thẳng",
                10, R.color.ink_muted, false);
        middle.addView(durationText);
        View track = new View(requireContext());
        track.setBackgroundColor(ContextCompat.getColor(requireContext(), R.color.bg_slate_soft));
        LinearLayout.LayoutParams trackParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(1));
        trackParams.topMargin = dp(7);
        middle.addView(track, trackParams);

        timeline.addView(timeColumn(arrival, destination, Gravity.END));

        String fareClass = product.optString("fareClass", "").trim();
        if (!fareClass.isEmpty()) {
            TextView fare = flightText("Hạng " + fareClass, 11, R.color.ink_muted, false);
            LinearLayout.LayoutParams fareParams = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            fareParams.topMargin = dp(8);
            column.addView(fare, fareParams);
        }

        View divider = new View(requireContext());
        divider.setBackgroundColor(ContextCompat.getColor(requireContext(), R.color.glass_border));
        LinearLayout.LayoutParams dividerParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(1));
        dividerParams.topMargin = dp(12);
        dividerParams.bottomMargin = dp(10);
        column.addView(divider, dividerParams);

        LinearLayout footer = new LinearLayout(requireContext());
        footer.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout priceColumn = new LinearLayout(requireContext());
        priceColumn.setOrientation(LinearLayout.VERTICAL);
        footer.addView(priceColumn, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        priceColumn.addView(flightText("Giá mỗi khách", 10, R.color.ink_muted, false));
        TextView price = flightText(ReadSummaryFormatter.formatCurrency(
                product.optLong("amountMinor"), product.optString("currency", "VND")), 18, R.color.navy, true);
        LinearLayout.LayoutParams priceParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        priceParams.topMargin = dp(2);
        priceColumn.addView(price, priceParams);
        MaterialButton choose = new MaterialButton(requireContext());
        choose.setText("Chọn chuyến");
        choose.setTextSize(12);
        choose.setTextColor(ContextCompat.getColor(requireContext(), R.color.white));
        choose.setBackgroundTintList(ContextCompat.getColorStateList(requireContext(), R.color.navy));
        choose.setCornerRadius(dp(18));
        footer.addView(choose);
        column.addView(footer);
        card.addView(column);
        LinearLayout.LayoutParams cardParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        cardParams.bottomMargin = dp(10);
        binding.productList.addView(card, cardParams);
        choose.setOnClickListener(v -> openCheckout(product));
        card.setOnClickListener(v -> openCheckout(product));
    }

    private LinearLayout timeColumn(String time, String airport, int gravity) {
        LinearLayout column = new LinearLayout(requireContext());
        column.setOrientation(LinearLayout.VERTICAL);
        column.setGravity(gravity);
        column.addView(flightText(time, 16, R.color.ink, true));
        TextView airportCode = flightText(airport.isEmpty() ? "—" : airport, 11, R.color.ink_muted, false);
        LinearLayout.LayoutParams airportParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        airportParams.topMargin = dp(3);
        column.addView(airportCode, airportParams);
        return column;
    }

    private TextView flightText(String value, float size, int color, boolean bold) {
        TextView text = new TextView(requireContext());
        text.setText(value);
        text.setTextColor(ContextCompat.getColor(requireContext(), color));
        text.setTextSize(size);
        if (bold) text.setTypeface(null, android.graphics.Typeface.BOLD);
        return text;
    }

    private String airlineInitials(String airline) {
        String[] words = airline.trim().split("\\s+");
        if (words.length == 1) return words[0].substring(0, Math.min(2, words[0].length())).toUpperCase(java.util.Locale.ROOT);
        return (words[0].substring(0, 1) + words[1].substring(0, 1)).toUpperCase(java.util.Locale.ROOT);
    }

    private void addProductCard(JSONObject product) {
        MaterialCardView card = new MaterialCardView(requireContext());
        card.setRadius(dp(22));
        card.setCardElevation(dp(1));
        card.setCardBackgroundColor(ContextCompat.getColor(requireContext(), R.color.glass_card));
        card.setStrokeColor(ContextCompat.getColor(requireContext(), R.color.glass_border));
        card.setStrokeWidth(dp(1));
        LinearLayout column = new LinearLayout(requireContext());
        column.setOrientation(LinearLayout.VERTICAL);
        column.setPadding(dp(16), dp(16), dp(16), dp(14));

        LinearLayout top = new LinearLayout(requireContext());
        top.setGravity(Gravity.CENTER_VERTICAL);
        ImageView icon = new ImageView(requireContext());
        icon.setImageResource(serviceIcon(serviceType));
        icon.setBackgroundResource(R.drawable.circle_action_button);
        icon.setColorFilter(ContextCompat.getColor(requireContext(), R.color.navy));
        icon.setPadding(dp(10), dp(10), dp(10), dp(10));
        top.addView(icon, new LinearLayout.LayoutParams(dp(44), dp(44)));

        LinearLayout titleColumn = new LinearLayout(requireContext());
        titleColumn.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams titleParams = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        titleParams.setMarginStart(dp(12));
        top.addView(titleColumn, titleParams);
        TextView name = new TextView(requireContext());
        name.setText(product.optString("name", serviceTitle(serviceType)));
        name.setTextColor(ContextCompat.getColor(requireContext(), R.color.ink));
        name.setTextSize(15);
        name.setTypeface(null, android.graphics.Typeface.BOLD);
        titleColumn.addView(name);
        TextView type = new TextView(requireContext());
        type.setText(serviceTitle(serviceType));
        type.setTextColor(ContextCompat.getColor(requireContext(), R.color.ink_muted));
        type.setTextSize(11);
        LinearLayout.LayoutParams typeParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        typeParams.topMargin = dp(3);
        titleColumn.addView(type, typeParams);
        column.addView(top);

        View divider = new View(requireContext());
        divider.setBackgroundColor(ContextCompat.getColor(requireContext(), R.color.glass_border));
        LinearLayout.LayoutParams dividerParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(1));
        dividerParams.topMargin = dp(14);
        dividerParams.bottomMargin = dp(12);
        column.addView(divider, dividerParams);

        LinearLayout bottom = new LinearLayout(requireContext());
        bottom.setGravity(Gravity.CENTER_VERTICAL);
        TextView price = new TextView(requireContext());
        price.setText(ReadSummaryFormatter.formatCurrency(product.optLong("amountMinor"), product.optString("currency", "VND")));
        price.setTextColor(ContextCompat.getColor(requireContext(), R.color.navy));
        price.setTextSize(18);
        price.setTypeface(null, android.graphics.Typeface.BOLD);
        bottom.addView(price, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        MaterialButton choose = new MaterialButton(requireContext());
        choose.setText("Chọn dịch vụ");
        choose.setTextColor(ContextCompat.getColor(requireContext(), R.color.white));
        choose.setBackgroundTintList(ContextCompat.getColorStateList(requireContext(), R.color.navy));
        choose.setCornerRadius(dp(14));
        bottom.addView(choose);
        column.addView(bottom);
        card.addView(column);
        LinearLayout.LayoutParams cardParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        cardParams.bottomMargin = dp(10);
        binding.productList.addView(card, cardParams);
        choose.setOnClickListener(v -> openCheckout(product));
        card.setOnClickListener(v -> openCheckout(product));
    }

    private void openCheckout(JSONObject product) {
        String productName = product.optString("name", serviceTitle(serviceType));
        String providerName = product.optString("airline", "").trim();
        if (providerName.isEmpty()) {
            providerName = serviceTitle(serviceType);
        }
        TransactionFlowRequest flowRequest = TransactionFlowRequest.orderPayment(
                serviceType,
                product.optString("id"),
                providerName,
                productName,
                null,
                null,
                null,
                null,
                product.optLong("amountMinor"),
                product.optString("currency", "VND"),
                sourceAccountId);
        Navigation.findNavController(requireView()).navigate(R.id.transactionFlow, flowRequest.toBundle());
    }

    private String serviceTitle(String type) {
        switch (type) {
            case "FLIGHT": return "Đặt vé máy bay";
            case "MOVIE": return "Vé xem phim";
            case "HOTEL": return "Đặt khách sạn";
            case "ECOMMERCE": return "Mua sắm";
            default: return "Dịch vụ";
        }
    }

    private String introTitle(String type) {
        switch (type) {
            case "FLIGHT": return "Chuyến bay đang có";
            case "MOVIE": return "Vé phim đang có";
            case "HOTEL": return "Lựa chọn lưu trú";
            case "ECOMMERCE": return "Sản phẩm đang có";
            default: return "Lựa chọn dành cho bạn";
        }
    }

    private String sectionTitle(String type) {
        switch (type) {
            case "FLIGHT": return "Chuyến bay khả dụng";
            case "MOVIE": return "Suất chiếu và vé";
            case "HOTEL": return "Phòng đang có";
            case "ECOMMERCE": return "Danh sách sản phẩm";
            default: return "Dịch vụ đang có";
        }
    }

    private int serviceIcon(String type) {
        switch (type) {
            case "FLIGHT": return R.drawable.ic_flight;
            case "MOVIE": return R.drawable.ic_utility_movie;
            case "HOTEL": return R.drawable.ic_utility_hotel;
            default: return R.drawable.ic_utility_shopping;
        }
    }

    private void showStatus(String message) {
        binding.statusText.setText(message);
        binding.statusText.setVisibility(View.VISIBLE);
    }

    private int dp(float value) {
        return (int) (value * getResources().getDisplayMetrics().density + 0.5f);
    }

    @Override
    public void onDestroyView() {
        requestVersion++;
        binding = null;
        super.onDestroyView();
    }
}
