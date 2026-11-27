package com.example.final_term.ui.utilities;

import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.HorizontalScrollView;
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
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

public final class UtilityCatalogFragment extends Fragment {
    private FragmentUtilityCatalogBinding binding;
    private BackendApiClient api;
    private String serviceType;
    private String sourceAccountId;
    private int requestVersion;
    private final List<JSONObject> catalogProducts = new ArrayList<>();
    private boolean flightsAscending = true;
    private boolean showingMyBookings = false;

    // Hotel state
    private String hotelQuery = "";
    private long hotelCheckInMillis = System.currentTimeMillis();
    private long hotelCheckOutMillis = System.currentTimeMillis() + 86_400_000L;
    private int hotelNights = 1;
    private int hotelGuests = 2;
    private int hotelRooms = 1;

    // Movie state (0 = movie list, 1 = date & showtimes, 2 = seat picker)
    private int movieStep = 0;
    private int selectedMovieIndex = -1;
    private int selectedMovieDayOffset = 0;
    private String selectedMovieCinema = "CGV Crescent";
    private String selectedMovieTime = "19:30";
    private final Set<String> selectedSeats = new LinkedHashSet<>(Arrays.asList("B3", "B4"));

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
        binding.catalogSectionTitle.setText(sectionTitle(serviceType));
        binding.backButton.setOnClickListener(v -> {
            if (showingMyBookings) {
                exitMyBookings();
            } else if ("MOVIE".equals(serviceType) && movieStep > 0) {
                movieStep--;
                renderMovieCatalog();
            } else {
                Navigation.findNavController(v).popBackStack();
            }
        });
        boolean hasBookings = "MOVIE".equals(serviceType) || "HOTEL".equals(serviceType) || "FLIGHT".equals(serviceType);
        binding.myBookingsButton.setVisibility(hasBookings ? View.VISIBLE : View.GONE);
        binding.myBookingsButton.setText("HOTEL".equals(serviceType) ? "Phòng đã đặt" : "Vé của tôi");
        binding.myBookingsButton.setOnClickListener(v -> {
            showingMyBookings = true;
            renderMyBookings();
        });
        applyCatalogChrome();
        if ("MOVIE".equals(serviceType)) {
            binding.scrollContent.setPadding(dp(20), dp(10), dp(20), dp(20));
        }
        if ("FLIGHT".equals(serviceType)) {
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

    private void applyCatalogChrome() {
        boolean flights = "FLIGHT".equals(serviceType);
        boolean redesignedUtility = "HOTEL".equals(serviceType) || "MOVIE".equals(serviceType);
        binding.headerTitle.setText(serviceTitle(serviceType));
        binding.flightResultsHeader.setVisibility(redesignedUtility ? View.GONE : View.VISIBLE);
        binding.flightSearchPanel.setVisibility(flights ? View.VISIBLE : View.GONE);
        binding.filterButton.setVisibility(flights ? View.VISIBLE : View.GONE);
        binding.movieBottomBar.setVisibility("MOVIE".equals(serviceType) && movieStep == 2 && selectedMovieIndex >= 0
                ? View.VISIBLE : View.GONE);
        boolean hasBookings = "MOVIE".equals(serviceType) || "HOTEL".equals(serviceType) || "FLIGHT".equals(serviceType);
        binding.myBookingsButton.setVisibility(hasBookings ? View.VISIBLE : View.GONE);
    }

    private void exitMyBookings() {
        showingMyBookings = false;
        binding.statusText.setVisibility(View.GONE);
        applyCatalogChrome();
        renderActiveCatalog();
    }

    private void renderMyBookings() {
        if (binding == null) return;
        binding.headerTitle.setText("HOTEL".equals(serviceType) ? "Phòng đã đặt" : "Vé của tôi");
        binding.myBookingsButton.setVisibility(View.GONE);
        binding.flightResultsHeader.setVisibility(View.GONE);
        binding.flightSearchPanel.setVisibility(View.GONE);
        binding.movieBottomBar.setVisibility(View.GONE);
        binding.productList.removeAllViews();
        showStatus("Đang tải dữ liệu đã đặt...");

        int version = ++requestVersion;
        api.get("/orders?limit=50").addOnCompleteListener(task -> {
            if (binding == null || !isAdded() || version != requestVersion || !showingMyBookings) return;
            try {
                if (!task.isSuccessful() || task.getResult() == null || task.getResult().statusCode != 200)
                    throw new IllegalStateException("ORDERS_UNAVAILABLE");
                JSONArray items = new JSONObject(task.getResult().body).optJSONArray("items");
                List<JSONObject> orders = new ArrayList<>();
                if (items != null) {
                    for (int i = 0; i < items.length(); i++) {
                        JSONObject o = items.getJSONObject(i);
                        if (!serviceType.equalsIgnoreCase(o.optString("serviceType"))) continue;
                        String payStatus = o.optString("paymentStatus", "");
                        if ("PAID".equalsIgnoreCase(payStatus) || "PENDING".equalsIgnoreCase(payStatus)) {
                            orders.add(o);
                        }
                    }
                }
                binding.statusText.setVisibility(View.GONE);
                binding.productList.removeAllViews();
                if (orders.isEmpty()) {
                    LinearLayout emptyCard = createGlassCard();
                    emptyCard.setGravity(Gravity.CENTER_HORIZONTAL);
                    emptyCard.addView(flightText(
                            "HOTEL".equals(serviceType) ? "Bạn chưa có phòng đặt nào." : "Bạn chưa có vé nào đã đặt.",
                            14, R.color.ink, true));
                    TextView sub = flightText("Các đơn đã thanh toán thành công sẽ hiển thị tại đây.", 12, R.color.ink_muted, false);
                    LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                    sp.topMargin = dp(6);
                    sp.bottomMargin = dp(12);
                    emptyCard.addView(sub, sp);
                    MaterialButton backBtn = createPrimaryButton("HOTEL".equals(serviceType) ? "Tìm phòng ngay" : "Đặt vé ngay");
                    backBtn.setOnClickListener(v -> exitMyBookings());
                    emptyCard.addView(backBtn);
                    addCardToList(emptyCard);
                    return;
                }
                for (JSONObject order : orders) {
                    if ("HOTEL".equals(serviceType)) {
                        addCardToList(buildHotelBookingCard(order));
                    } else {
                        addCardToList(buildTicketCard(order));
                    }
                }
            } catch (Exception error) {
                showStatus("Không tải được danh sách đã đặt. Thử lại sau.");
            }
        });
    }

    private LinearLayout buildHotelBookingCard(JSONObject order) {
        LinearLayout card = createGlassCard();
        String orderId = order.optString("id", order.optString("orderId", ""));
        String bookingCode = "HT-" + shortBookingCode(orderId);
        boolean paid = "PAID".equalsIgnoreCase(order.optString("paymentStatus"));
        String details = order.optString("customerDetails", "Đặt phòng khách sạn");
        int nights = Math.max(1, order.optInt("quantity", 1));

        LinearLayout top = new LinearLayout(requireContext());
        top.setGravity(Gravity.CENTER_VERTICAL);
        top.addView(flightText("MÃ ĐẶT PHÒNG · " + bookingCode, 11, R.color.navy, true),
                new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView badge = createChip(paid ? "Đã xác nhận" : "Đang xử lý", false);
        badge.setTextSize(11);
        badge.setPadding(dp(10), dp(4), dp(10), dp(4));
        badge.setTextColor(ContextCompat.getColor(requireContext(), paid ? R.color.success : R.color.warning));
        top.addView(badge);
        card.addView(top);

        // Extract hotel name (last segment if formatted with ·) or show full details
        String[] parts = details.split("\\s*·\\s*");
        String hotelName = parts.length >= 4 ? parts[parts.length - 1] : details;
        TextView title = flightText(hotelName, 16, R.color.ink, true);
        LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        tp.topMargin = dp(8);
        card.addView(title, tp);

        addDivider(card);
        addBookingInfoRow(card, "Thông tin lưu trú", details);
        addBookingInfoRow(card, "Số đêm", nights + " đêm");
        addBookingInfoRow(card, "Ngày đặt", formatOrderDate(order.optLong("createdAt")));
        addBookingInfoRow(card, "Tổng thanh toán", ReadSummaryFormatter.formatCurrency(
                order.optLong("amountMinor"), order.optString("currency", "VND")));
        return card;
    }

    private LinearLayout buildTicketCard(JSONObject order) {
        LinearLayout card = new LinearLayout(requireContext());
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackgroundResource(R.drawable.glass_panel_card);

        String orderId = order.optString("id", order.optString("orderId", ""));
        String bookingCode = ("MOVIE".equals(serviceType) ? "MV-" : "FL-") + shortBookingCode(orderId);
        boolean paid = "PAID".equalsIgnoreCase(order.optString("paymentStatus"));
        String details = order.optString("customerDetails", "").trim();
        int qty = Math.max(1, order.optInt("quantity", 1));

        // Top navy ticket header
        LinearLayout header = new LinearLayout(requireContext());
        header.setOrientation(LinearLayout.VERTICAL);
        header.setPadding(dp(16), dp(14), dp(16), dp(14));
        android.graphics.drawable.GradientDrawable headerBg = new android.graphics.drawable.GradientDrawable(
                android.graphics.drawable.GradientDrawable.Orientation.TL_BR,
                new int[]{0xFF1E294D, 0xFF2F3E6E});
        float r = dp(23);
        headerBg.setCornerRadii(new float[]{r, r, r, r, 0, 0, 0, 0});
        header.setBackground(headerBg);

        LinearLayout eyebrowRow = new LinearLayout(requireContext());
        eyebrowRow.setGravity(Gravity.CENTER_VERTICAL);
        TextView brand = flightText("MOVIE".equals(serviceType) ? "NIMBUS CINEMA · E-TICKET" : "NIMBUS AIRWAYS · BOARDING PASS",
                10, R.color.white, true);
        brand.setAlpha(0.8f);
        eyebrowRow.addView(brand, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView statusPill = new TextView(requireContext());
        statusPill.setText(paid ? "ĐÃ XÁC NHẬN" : "ĐANG XỬ LÝ");
        statusPill.setTextSize(10);
        statusPill.setTypeface(null, android.graphics.Typeface.BOLD);
        statusPill.setTextColor(ContextCompat.getColor(requireContext(), R.color.white));
        statusPill.setPadding(dp(8), dp(3), dp(8), dp(3));
        android.graphics.drawable.GradientDrawable stBg = new android.graphics.drawable.GradientDrawable();
        stBg.setColor(paid ? 0xFF1B9B6C : 0xFFD97706);
        stBg.setCornerRadius(dp(99));
        statusPill.setBackground(stBg);
        eyebrowRow.addView(statusPill);
        header.addView(eyebrowRow);

        String[] parts = details.split("\\s*·\\s*");
        String mainTitle = parts.length > 0 && !parts[0].isEmpty()
                ? parts[0] : ("MOVIE".equals(serviceType) ? "Vé xem phim" : "Vé máy bay");
        TextView titleView = flightText(mainTitle, 17, R.color.white, true);
        LinearLayout.LayoutParams tvp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        tvp.topMargin = dp(6);
        header.addView(titleView, tvp);
        card.addView(header);

        // Middle ticket body
        LinearLayout body = new LinearLayout(requireContext());
        body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(dp(16), dp(14), dp(16), dp(10));

        if ("MOVIE".equals(serviceType) && parts.length >= 4) {
            String cinema = parts[1];
            String showtime = parts[2];
            String seats = parts[3].replace("Ghế:", "").trim();
            body.addView(buildTicketPairRow("RẠP CHIẾU", cinema, "SUẤT CHIẾU", showtime));
            LinearLayout row2 = buildTicketPairRow("GHẾ NGỒI", seats, "SỐ VÉ", qty + " vé");
            LinearLayout.LayoutParams r2p = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            r2p.topMargin = dp(10);
            body.addView(row2, r2p);
        } else {
            addBookingInfoRow(body, "Chi tiết vé", details.isEmpty() ? mainTitle : details);
            addBookingInfoRow(body, "Số lượng", qty + ("FLIGHT".equals(serviceType) ? " hành khách" : " vé"));
        }
        LinearLayout priceRow = buildTicketPairRow(
                "NGÀY ĐẶT", formatOrderDate(order.optLong("createdAt")),
                "TỔNG TIỀN", ReadSummaryFormatter.formatCurrency(order.optLong("amountMinor"), order.optString("currency", "VND")));
        LinearLayout.LayoutParams prp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        prp.topMargin = dp(10);
        body.addView(priceRow, prp);
        card.addView(body);

        // Perforated tear line with side notches
        card.addView(buildTicketTearLine());

        // Bottom barcode stub
        LinearLayout stub = new LinearLayout(requireContext());
        stub.setOrientation(LinearLayout.VERTICAL);
        stub.setGravity(Gravity.CENTER_HORIZONTAL);
        stub.setPadding(dp(16), dp(10), dp(16), dp(16));

        LinearLayout barcodeBox = new LinearLayout(requireContext());
        barcodeBox.setOrientation(LinearLayout.VERTICAL);
        barcodeBox.setGravity(Gravity.CENTER_HORIZONTAL);
        barcodeBox.setPadding(dp(14), dp(10), dp(14), dp(8));
        android.graphics.drawable.GradientDrawable boxBg = new android.graphics.drawable.GradientDrawable();
        boxBg.setColor(0xFFFFFFFF);
        boxBg.setStroke(dp(1), 0x1F2F3E6E);
        boxBg.setCornerRadius(dp(12));
        barcodeBox.setBackground(boxBg);

        View barcodeView = new BarcodeView(requireContext(), orderId);
        barcodeBox.addView(barcodeView, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(48)));

        TextView codeText = flightText(bookingCode, 12, R.color.ink, true);
        codeText.setLetterSpacing(0.22f);
        codeText.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams ctp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        ctp.topMargin = dp(6);
        barcodeBox.addView(codeText, ctp);
        stub.addView(barcodeBox, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView hint = flightText("Đưa mã vạch này tại quầy để soát vé check-in", 11, R.color.ink_muted, false);
        hint.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams hp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        hp.topMargin = dp(8);
        stub.addView(hint, hp);
        card.addView(stub);
        return card;
    }

    private LinearLayout buildTicketPairRow(String leftLabel, String leftVal, String rightLabel, String rightVal) {
        LinearLayout row = new LinearLayout(requireContext());
        row.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout left = new LinearLayout(requireContext());
        left.setOrientation(LinearLayout.VERTICAL);
        left.addView(flightText(leftLabel, 10, R.color.ink_muted, true));
        TextView lv = flightText(leftVal, 13, R.color.ink, true);
        LinearLayout.LayoutParams lvp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lvp.topMargin = dp(2);
        left.addView(lv, lvp);
        row.addView(left, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        LinearLayout right = new LinearLayout(requireContext());
        right.setOrientation(LinearLayout.VERTICAL);
        right.setGravity(Gravity.END);
        right.addView(flightText(rightLabel, 10, R.color.ink_muted, true));
        TextView rv = flightText(rightVal, 13, R.color.navy, true);
        LinearLayout.LayoutParams rvp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        rvp.topMargin = dp(2);
        right.addView(rv, rvp);
        row.addView(right, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        return row;
    }

    private void addBookingInfoRow(LinearLayout parent, String label, String value) {
        LinearLayout row = new LinearLayout(requireContext());
        row.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        rp.bottomMargin = dp(6);
        TextView l = flightText(label, 12, R.color.ink_muted, false);
        TextView v = flightText(value, 12.5f, R.color.ink, true);
        v.setGravity(Gravity.END);
        row.addView(l, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        LinearLayout.LayoutParams vp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        vp.leftMargin = dp(12);
        row.addView(v, vp);
        parent.addView(row, rp);
    }

    private View buildTicketTearLine() {
        return new View(requireContext()) {
            private final android.graphics.Paint dashPaint = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
            private final android.graphics.Paint notchPaint = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
            {
                dashPaint.setColor(0x332F3E6E);
                dashPaint.setStrokeWidth(dp(1.5f));
                dashPaint.setStyle(android.graphics.Paint.Style.STROKE);
                dashPaint.setPathEffect(new android.graphics.DashPathEffect(new float[]{dp(5), dp(4)}, 0));
                notchPaint.setColor(0xFFDCE2F0);
                notchPaint.setStyle(android.graphics.Paint.Style.FILL);
            }
            @Override
            protected void onDraw(android.graphics.Canvas canvas) {
                super.onDraw(canvas);
                float cy = getHeight() / 2f;
                float r = dp(9);
                canvas.drawLine(r + dp(4), cy, getWidth() - r - dp(4), cy, dashPaint);
                canvas.drawCircle(0, cy, r, notchPaint);
                canvas.drawCircle(getWidth(), cy, r, notchPaint);
            }
            @Override
            protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
                setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), dp(22));
            }
        };
    }

    private static final class BarcodeView extends View {
        private final String seed;
        private final android.graphics.Paint paint = new android.graphics.Paint();

        BarcodeView(android.content.Context context, String seed) {
            super(context);
            this.seed = seed == null || seed.isEmpty() ? "NIMBUS-TICKET" : seed;
            paint.setColor(0xFF1B2340);
            paint.setStyle(android.graphics.Paint.Style.FILL);
        }

        @Override
        protected void onDraw(android.graphics.Canvas canvas) {
            super.onDraw(canvas);
            int w = getWidth();
            int h = getHeight();
            if (w <= 0 || h <= 0) return;
            long hash = 1125899906842597L;
            for (int i = 0; i < seed.length(); i++) {
                hash = 31 * hash + seed.charAt(i);
            }
            // Start guard bars
            int totalUnits = 92;
            float unitW = (float) w / totalUnits;
            int pos = 0;
            // Guard pattern: 1 0 1
            canvas.drawRect(pos * unitW, 0, (pos + 1.5f) * unitW, h, paint); pos += 3;
            canvas.drawRect(pos * unitW, 0, (pos + 1.5f) * unitW, h, paint); pos += 3;
            while (pos < totalUnits - 6) {
                hash = hash * 6364136223846793005L + 1442695040888963407L;
                int barUnits = 1 + (int) ((hash >>> 16) & 3);
                int gapUnits = 1 + (int) ((hash >>> 24) & 2);
                if (pos + barUnits > totalUnits - 6) break;
                canvas.drawRect(pos * unitW, 0, (pos + barUnits) * unitW, h, paint);
                pos += barUnits + gapUnits;
            }
            // End guard bars
            canvas.drawRect((totalUnits - 5) * unitW, 0, (totalUnits - 3.5f) * unitW, h, paint);
            canvas.drawRect((totalUnits - 2) * unitW, 0, (totalUnits - 0.5f) * unitW, h, paint);
        }
    }

    private String shortBookingCode(String id) {
        if (id == null || id.trim().isEmpty()) return "000000";
        String clean = id.replaceAll("[^A-Za-z0-9]", "").toUpperCase(Locale.ROOT);
        return clean.substring(0, Math.min(8, clean.length()));
    }

    private String formatOrderDate(long millis) {
        if (millis <= 0) return "Vừa đặt";
        return new java.text.SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.getDefault()).format(new java.util.Date(millis));
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
                renderActiveCatalog();
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
        openCheckout(product, 1, null);
    }

    private void openCheckout(JSONObject product, int quantity, @Nullable String customerDetails) {
        int safeQty = Math.max(1, Math.min(10, quantity));
        long unitAmount = product.optLong("amountMinor");
        long totalAmount = unitAmount * safeQty;
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
                customerDetails,
                null,
                customerDetails,
                totalAmount,
                safeQty,
                product.optString("currency", "VND"),
                sourceAccountId);
        Navigation.findNavController(requireView()).navigate(R.id.transactionFlow, flowRequest.toBundle());
    }

    private void renderActiveCatalog() {
        if (binding == null) return;
        binding.productList.removeAllViews();
        switch (serviceType) {
            case "FLIGHT":
                renderFlightResults();
                break;
            case "HOTEL":
                renderHotelCatalog();
                break;
            case "MOVIE":
                renderMovieCatalog();
                break;
            default:
                for (JSONObject product : catalogProducts) addProductCard(product);
                break;
        }
    }

    private void renderHotelCatalog() {
        LinearLayout filterCard = createGlassCard();
        LinearLayout filterCol = createCardColumn(filterCard);

        EditText searchInput = createSearchInput("Tìm thành phố, khách sạn...", hotelQuery, text -> {
            hotelQuery = text;
            renderHotelResultsInto(filterCard);
        });
        filterCol.addView(searchInput);

        LinearLayout metaGrid = new LinearLayout(requireContext());
        metaGrid.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams mgParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        mgParams.topMargin = dp(8);

        LinearLayout metaLeft = createMetaPill("Nhận – Trả phòng", formatStayRange());
        metaLeft.setOnClickListener(v -> openHotelDateRangePicker());
        LinearLayout metaRight = createMetaPill("Số khách", hotelGuests + " người · " + hotelRooms + " phòng");
        metaRight.setOnClickListener(v -> openGuestPickerDialog());
        LinearLayout.LayoutParams mlParams = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        mlParams.rightMargin = dp(8);
        metaGrid.addView(metaLeft, mlParams);
        metaGrid.addView(metaRight, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        filterCol.addView(metaGrid, mgParams);

        addCardToList(filterCard);
        renderHotelResultsInto(filterCard);
    }

    private String formatStayRange() {
        java.text.SimpleDateFormat fmt = new java.text.SimpleDateFormat("dd/MM", Locale.getDefault());
        return fmt.format(new java.util.Date(hotelCheckInMillis)) + " → "
                + fmt.format(new java.util.Date(hotelCheckOutMillis)) + " (" + hotelNights + " đêm)";
    }

    private void openHotelDateRangePicker() {
        long today = com.google.android.material.datepicker.MaterialDatePicker.todayInUtcMilliseconds();
        com.google.android.material.datepicker.CalendarConstraints constraints =
                new com.google.android.material.datepicker.CalendarConstraints.Builder()
                        .setValidator(com.google.android.material.datepicker.DateValidatorPointForward.from(today))
                        .build();
        com.google.android.material.datepicker.MaterialDatePicker<androidx.core.util.Pair<Long, Long>> picker =
                com.google.android.material.datepicker.MaterialDatePicker.Builder.dateRangePicker()
                        .setTitleText("Chọn ngày nhận và trả phòng")
                        .setCalendarConstraints(constraints)
                        .setSelection(new androidx.core.util.Pair<>(hotelCheckInMillis, hotelCheckOutMillis))
                        .build();
        picker.addOnPositiveButtonClickListener(selection -> {
            if (selection == null || selection.first == null || selection.second == null) return;
            long diffDays = Math.round((selection.second - selection.first) / 86_400_000.0);
            if (diffDays < 1) diffDays = 1;
            if (diffDays > 10) {
                diffDays = 10;
                android.widget.Toast.makeText(requireContext(), "Tối đa 10 đêm mỗi lần đặt.", android.widget.Toast.LENGTH_SHORT).show();
            }
            hotelCheckInMillis = selection.first;
            hotelCheckOutMillis = selection.first + diffDays * 86_400_000L;
            hotelNights = (int) diffDays;
            renderHotelCatalog();
        });
        picker.show(getParentFragmentManager(), "hotel_date_range");
    }

    private void openGuestPickerDialog() {
        LinearLayout content = new LinearLayout(requireContext());
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(20), dp(14), dp(20), dp(8));

        final int[] tempGuests = {hotelGuests};
        final int[] tempRooms = {hotelRooms};

        content.addView(createDialogStepperRow("Số người", tempGuests, 1, 8));
        LinearLayout roomsRow = createDialogStepperRow("Số phòng", tempRooms, 1, 4);
        LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        rp.topMargin = dp(12);
        content.addView(roomsRow, rp);

        new com.google.android.material.dialog.MaterialAlertDialogBuilder(requireContext())
                .setTitle("Chọn khách và phòng")
                .setView(content)
                .setNegativeButton("Hủy", null)
                .setPositiveButton("Xác nhận", (dialog, which) -> {
                    hotelGuests = tempGuests[0];
                    hotelRooms = tempRooms[0];
                    renderHotelCatalog();
                })
                .show();
    }

    private LinearLayout createDialogStepperRow(String label, int[] valueHolder, int min, int max) {
        LinearLayout row = new LinearLayout(requireContext());
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.addView(flightText(label, 14, R.color.ink, true),
                new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView minus = createStepperButton("−");
        TextView valueView = flightText(String.valueOf(valueHolder[0]), 15, R.color.ink, true);
        valueView.setPadding(dp(14), 0, dp(14), 0);
        TextView plus = createStepperButton("+");

        minus.setOnClickListener(v -> {
            if (valueHolder[0] > min) {
                valueHolder[0]--;
                valueView.setText(String.valueOf(valueHolder[0]));
            }
        });
        plus.setOnClickListener(v -> {
            if (valueHolder[0] < max) {
                valueHolder[0]++;
                valueView.setText(String.valueOf(valueHolder[0]));
            }
        });

        row.addView(minus);
        row.addView(valueView);
        row.addView(plus);
        return row;
    }

    private void renderHotelResultsInto(View filterCard) {
        int index = binding.productList.indexOfChild(filterCard);
        while (binding.productList.getChildCount() > index + 1) {
            binding.productList.removeViewAt(index + 1);
        }
        String q = hotelQuery.trim().toLowerCase(Locale.ROOT);
        List<JSONObject> matching = new ArrayList<>();
        for (JSONObject product : catalogProducts) {
            String hay = (product.optString("name") + " " + product.optString("description")).toLowerCase(Locale.ROOT);
            if (q.isEmpty() || hay.contains(q)) matching.add(product);
        }

        // Section head: Phòng gợi ý | N lựa chọn
        LinearLayout secHead = new LinearLayout(requireContext());
        secHead.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams shParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        shParams.topMargin = dp(4);
        shParams.bottomMargin = dp(10);
        secHead.addView(flightText("Phòng gợi ý", 14, R.color.ink, true),
                new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        secHead.addView(flightText(matching.size() + " lựa chọn", 12, R.color.ink_muted, false));
        binding.productList.addView(secHead, shParams);

        if (matching.isEmpty()) {
            TextView empty = flightText("Không tìm thấy khách sạn phù hợp.", 13, R.color.ink_muted, false);
            empty.setPadding(dp(4), dp(8), dp(4), dp(8));
            binding.productList.addView(empty);
            return;
        }

        for (int i = 0; i < matching.size(); i++) {
            JSONObject product = matching.get(i);
            LinearLayout card = createGlassCard();
            LinearLayout col = createCardColumn(card);

            col.addView(flightText(product.optString("name", "Khách sạn"), 15, R.color.ink, true));
            String desc = product.optString("description", "").trim();
            if (!desc.isEmpty()) {
                TextView sub = flightText(desc, 12, R.color.ink_muted, false);
                LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                sp.topMargin = dp(3);
                col.addView(sub, sp);
            }

            addDivider(col);

            long total = product.optLong("amountMinor") * hotelNights;
            String totalLabel = ReadSummaryFormatter.formatCurrency(total, product.optString("currency", "VND"));
            LinearLayout footer = new LinearLayout(requireContext());
            footer.setGravity(Gravity.CENTER_VERTICAL);
            LinearLayout priceCol = new LinearLayout(requireContext());
            priceCol.setOrientation(LinearLayout.VERTICAL);
            priceCol.addView(flightText(totalLabel, 16, R.color.navy, true));
            priceCol.addView(flightText("Tổng cho " + hotelNights + " đêm", 11, R.color.ink_muted, false));
            footer.addView(priceCol, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

            MaterialButton bookBtn = createPrimaryButton("Đặt phòng");
            String details = formatStayRange() + " · " + hotelGuests + " người · " + hotelRooms + " phòng · " + product.optString("name", "Khách sạn");
            bookBtn.setOnClickListener(v -> openCheckout(product, hotelNights, details));
            card.setOnClickListener(v -> openCheckout(product, hotelNights, details));
            footer.addView(bookBtn);
            col.addView(footer);
            addCardToList(card);
        }
    }

    private void renderMovieCatalog() {
        binding.productList.removeAllViews();
        if (catalogProducts.isEmpty()) return;

        if (movieStep == 0 || selectedMovieIndex < 0 || selectedMovieIndex >= catalogProducts.size()) {
            movieStep = 0;
            binding.headerTitle.setText("Vé xem phim");
            binding.movieBottomBar.setVisibility(View.GONE);
            for (int i = 0; i < catalogProducts.size(); i++) {
                final int idx = i;
                JSONObject movie = catalogProducts.get(i);
                LinearLayout card = createGlassCard();
                card.addView(buildMovieSummaryRow(movie, i));
                card.setOnClickListener(v -> {
                    selectedMovieIndex = idx;
                    movieStep = 1;
                    renderMovieCatalog();
                    binding.scrollContent.post(() -> binding.scrollContent.smoothScrollTo(0, 0));
                });
                addCardToList(card);
            }
            return;
        }

        JSONObject activeMovie = catalogProducts.get(selectedMovieIndex);
        if (movieStep == 1) {
            binding.headerTitle.setText("Chọn suất chiếu");
            binding.movieBottomBar.setVisibility(View.GONE);

            // Compact selected movie card with "Đổi phim"
            LinearLayout movieCard = createGlassCard();
            movieCard.addView(buildMovieSummaryRow(activeMovie, selectedMovieIndex));
            TextView changeMovie = flightText("Đổi phim khác", 12, R.color.navy, true);
            LinearLayout.LayoutParams cmp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            cmp.topMargin = dp(10);
            changeMovie.setOnClickListener(v -> {
                movieStep = 0;
                renderMovieCatalog();
            });
            movieCard.addView(changeMovie, cmp);
            addCardToList(movieCard);

            // 7-day date badges starting from today
            HorizontalScrollView dateScroll = new HorizontalScrollView(requireContext());
            dateScroll.setHorizontalScrollBarEnabled(false);
            LinearLayout dateRow = new LinearLayout(requireContext());
            dateRow.setOrientation(LinearLayout.HORIZONTAL);
            for (int d = 0; d < 7; d++) {
                final int offset = d;
                TextView chip = createChip(formatMovieDayLabel(d), d == selectedMovieDayOffset);
                chip.setOnClickListener(v -> {
                    selectedMovieDayOffset = offset;
                    renderMovieCatalog();
                });
                LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                cp.rightMargin = dp(6);
                dateRow.addView(chip, cp);
            }
            dateScroll.addView(dateRow);
            LinearLayout.LayoutParams dsParams = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            dsParams.bottomMargin = dp(12);
            binding.productList.addView(dateScroll, dsParams);

            // Showtimes grouped by cinema
            String[] cinemas = {"CGV Crescent", "Lotte Q7", "Galaxy Nguyễn Du"};
            String[] cinemaAddrs = {"Crescent Mall, Q.7", "Lotte Mart, Q.7", "116 Nguyễn Du, Q.1"};
            String[][] slotsByCinema = {
                    {"15:30", "17:45", "19:30", "21:45"},
                    {"16:00", "18:15", "20:00", "22:10"},
                    {"15:15", "17:30", "19:45", "21:30"}
            };
            for (int c = 0; c < cinemas.length; c++) {
                final String cinemaName = cinemas[c];
                LinearLayout cinemaCard = createGlassCard();
                LinearLayout header = new LinearLayout(requireContext());
                header.setGravity(Gravity.CENTER_VERTICAL);
                header.addView(flightText(cinemaName, 14, R.color.ink, true),
                        new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
                header.addView(flightText(cinemaAddrs[c], 11, R.color.ink_muted, false));
                cinemaCard.addView(header);

                LinearLayout timeGrid = new LinearLayout(requireContext());
                timeGrid.setOrientation(LinearLayout.HORIZONTAL);
                LinearLayout.LayoutParams tgParams = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                tgParams.topMargin = dp(10);
                String[] slots = slotsByCinema[c];
                for (int t = 0; t < slots.length; t++) {
                    final String time = slots[t];
                    boolean activeSlot = cinemaName.equals(selectedMovieCinema) && time.equals(selectedMovieTime);
                    TextView timePill = new TextView(requireContext());
                    timePill.setText(time);
                    timePill.setTextSize(12);
                    timePill.setTypeface(null, android.graphics.Typeface.BOLD);
                    timePill.setGravity(Gravity.CENTER);
                    timePill.setPadding(dp(4), dp(9), dp(4), dp(9));
                    timePill.setBackground(createRoundedBg(
                            activeSlot ? R.color.navy : R.color.glass_card,
                            activeSlot ? R.color.navy : R.color.glass_border, 11));
                    timePill.setTextColor(ContextCompat.getColor(requireContext(),
                            activeSlot ? R.color.white : R.color.ink));
                    timePill.setOnClickListener(v -> {
                        selectedMovieCinema = cinemaName;
                        selectedMovieTime = time;
                        movieStep = 2;
                        renderMovieCatalog();
                        binding.scrollContent.post(() -> binding.scrollContent.smoothScrollTo(0, 0));
                    });
                    LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
                    if (t < slots.length - 1) tp.rightMargin = dp(6);
                    timeGrid.addView(timePill, tp);
                }
                cinemaCard.addView(timeGrid, tgParams);
                addCardToList(cinemaCard);
            }
            return;
        }

        // Step 2: Seat Picker + Sticky Bottom Bar
        binding.headerTitle.setText("Chọn ghế ngồi");
        binding.movieBottomBar.setVisibility(View.VISIBLE);

        String dayLabel = formatMovieDayLabel(selectedMovieDayOffset);
        LinearLayout infoCard = createGlassCard();
        LinearLayout infoHead = new LinearLayout(requireContext());
        infoHead.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout infoText = new LinearLayout(requireContext());
        infoText.setOrientation(LinearLayout.VERTICAL);
        infoText.addView(flightText(activeMovie.optString("name", "Phim chiếu rạp"), 14, R.color.ink, true));
        TextView subInfo = flightText(selectedMovieCinema + " · " + dayLabel + " · " + selectedMovieTime, 12, R.color.ink_muted, false);
        LinearLayout.LayoutParams sip = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        sip.topMargin = dp(3);
        infoText.addView(subInfo, sip);
        infoHead.addView(infoText, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView changeShow = flightText("Đổi suất", 12, R.color.navy, true);
        changeShow.setOnClickListener(v -> {
            movieStep = 1;
            renderMovieCatalog();
        });
        infoHead.addView(changeShow);
        infoCard.addView(infoHead);
        addCardToList(infoCard);

        LinearLayout seatCard = createGlassCard();
        LinearLayout seatCol = createCardColumn(seatCard);
        LinearLayout seatHeader = new LinearLayout(requireContext());
        seatHeader.setGravity(Gravity.CENTER_VERTICAL);
        seatHeader.addView(flightText("Chọn ghế ngồi", 13, R.color.ink, true),
                new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        String seatList = selectedSeats.isEmpty() ? "Chưa chọn" : String.join(", ", selectedSeats);
        seatHeader.addView(flightText("Đã chọn: " + seatList, 11, R.color.ink_muted, false));
        seatCol.addView(seatHeader);

        View screenBar = new View(requireContext());
        android.graphics.drawable.GradientDrawable screenBg = new android.graphics.drawable.GradientDrawable(
                android.graphics.drawable.GradientDrawable.Orientation.LEFT_RIGHT,
                new int[]{0x00697AA3, 0xFF697AA3, 0x00697AA3});
        screenBg.setCornerRadius(dp(99));
        screenBar.setBackground(screenBg);
        LinearLayout.LayoutParams sbParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(5));
        sbParams.topMargin = dp(10);
        sbParams.leftMargin = dp(24);
        sbParams.rightMargin = dp(24);
        seatCol.addView(screenBar, sbParams);

        TextView screenLabel = flightText("MÀN HÌNH", 10, R.color.ink_muted, false);
        screenLabel.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams sclParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        sclParams.topMargin = dp(4);
        sclParams.bottomMargin = dp(8);
        seatCol.addView(screenLabel, sclParams);

        Set<String> occupied = new LinkedHashSet<>(Arrays.asList("A3", "A4", "C5", "C6"));
        String[] rows = {"A", "B", "C"};
        for (String r : rows) {
            LinearLayout rowLayout = new LinearLayout(requireContext());
            rowLayout.setOrientation(LinearLayout.HORIZONTAL);
            LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            rp.bottomMargin = dp(6);
            for (int c = 1; c <= 6; c++) {
                String seatCode = r + c;
                boolean isOccupied = occupied.contains(seatCode);
                boolean isPicked = selectedSeats.contains(seatCode);
                TextView seatView = new TextView(requireContext());
                seatView.setText(seatCode);
                seatView.setTextSize(10);
                seatView.setTypeface(null, android.graphics.Typeface.BOLD);
                seatView.setGravity(Gravity.CENTER);
                if (isOccupied) {
                    android.graphics.drawable.GradientDrawable takenBg = new android.graphics.drawable.GradientDrawable();
                    takenBg.setColor(0x385F6B88);
                    takenBg.setCornerRadius(dp(8));
                    seatView.setBackground(takenBg);
                    seatView.setTextColor(0x8C5F6B88);
                } else if (isPicked) {
                    seatView.setBackground(createRoundedBg(R.color.navy, R.color.navy, 8));
                    seatView.setTextColor(ContextCompat.getColor(requireContext(), R.color.white));
                } else {
                    seatView.setBackground(createRoundedBg(R.color.glass_card, R.color.glass_border, 8));
                    seatView.setTextColor(ContextCompat.getColor(requireContext(), R.color.ink));
                }
                if (!isOccupied) {
                    seatView.setOnClickListener(v -> {
                        if (selectedSeats.contains(seatCode)) {
                            if (selectedSeats.size() > 1) selectedSeats.remove(seatCode);
                        } else if (selectedSeats.size() < 6) {
                            selectedSeats.add(seatCode);
                        }
                        renderMovieCatalog();
                    });
                }
                LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(0, dp(30), 1f);
                if (c < 6) sp.rightMargin = dp(6);
                rowLayout.addView(seatView, sp);
            }
            seatCol.addView(rowLayout, rp);
        }
        addCardToList(seatCard);

        int seatCount = selectedSeats.size();
        long total = activeMovie.optLong("amountMinor") * Math.max(1, seatCount);
        binding.movieBarSummary.setText(seatCount + " vé · " + dayLabel + " · " + selectedMovieTime);
        binding.movieBarTotal.setText(ReadSummaryFormatter.formatCurrency(total, activeMovie.optString("currency", "VND")));
        binding.movieBarButton.setEnabled(seatCount > 0);
        binding.movieBarButton.setOnClickListener(v -> {
            if (selectedSeats.isEmpty()) return;
            String details = activeMovie.optString("name") + " · " + selectedMovieCinema + " · "
                    + dayLabel + " " + selectedMovieTime + " · Ghế: " + String.join(", ", selectedSeats);
            openCheckout(activeMovie, selectedSeats.size(), details);
        });
    }

    private LinearLayout buildMovieSummaryRow(JSONObject movie, int index) {
        LinearLayout movieRow = new LinearLayout(requireContext());
        movieRow.setGravity(Gravity.CENTER_VERTICAL);

        LinearLayout poster = new LinearLayout(requireContext());
        poster.setOrientation(LinearLayout.VERTICAL);
        poster.setPadding(dp(7), dp(7), dp(7), dp(7));
        android.graphics.drawable.GradientDrawable posterBg = new android.graphics.drawable.GradientDrawable(
                android.graphics.drawable.GradientDrawable.Orientation.TL_BR,
                new int[]{0xFF1E294D, 0xFF4E639B});
        posterBg.setCornerRadius(dp(14));
        poster.setBackground(posterBg);

        TextView ageTag = new TextView(requireContext());
        ageTag.setText(index == 0 ? "T16" : (index == 1 ? "T13" : "P"));
        ageTag.setTextSize(10);
        ageTag.setTextColor(ContextCompat.getColor(requireContext(), R.color.white));
        ageTag.setTypeface(null, android.graphics.Typeface.BOLD);
        ageTag.setPadding(dp(6), dp(2), dp(6), dp(2));
        android.graphics.drawable.GradientDrawable ageBg = new android.graphics.drawable.GradientDrawable();
        ageBg.setColor(0xFFD97706);
        ageBg.setCornerRadius(dp(6));
        ageTag.setBackground(ageBg);
        poster.addView(ageTag, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        poster.addView(new View(requireContext()), new LinearLayout.LayoutParams(1, 0, 1f));
        TextView formatText = flightText("2D Phụ đề", 10, R.color.white, false);
        formatText.setAlpha(0.9f);
        poster.addView(formatText);

        LinearLayout.LayoutParams posterParams = new LinearLayout.LayoutParams(dp(64), dp(88));
        posterParams.rightMargin = dp(12);
        movieRow.addView(poster, posterParams);

        LinearLayout detailCol = new LinearLayout(requireContext());
        detailCol.setOrientation(LinearLayout.VERTICAL);
        detailCol.addView(flightText(movie.optString("name", "Phim chiếu rạp"), 15, R.color.ink, true));
        String desc = movie.optString("description", "").trim();
        if (!desc.isEmpty()) {
            TextView sub = flightText(desc, 12, R.color.ink_muted, false);
            LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            sp.topMargin = dp(3);
            detailCol.addView(sub, sp);
        }
        String unitPriceStr = ReadSummaryFormatter.formatCurrency(
                movie.optLong("amountMinor"), movie.optString("currency", "VND")) + "/vé";
        detailCol.addView(createTagsRow(Arrays.asList("2D Phụ đề", unitPriceStr)));
        movieRow.addView(detailCol, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        return movieRow;
    }

    private String formatMovieDayLabel(int offset) {
        java.util.Calendar cal = java.util.Calendar.getInstance();
        cal.add(java.util.Calendar.DAY_OF_MONTH, offset);
        java.text.SimpleDateFormat dateFmt = new java.text.SimpleDateFormat("dd/MM", Locale.getDefault());
        String dateStr = dateFmt.format(cal.getTime());
        if (offset == 0) return "Hôm nay · " + dateStr;
        int dow = cal.get(java.util.Calendar.DAY_OF_WEEK);
        String dowLabel = dow == java.util.Calendar.SUNDAY ? "CN" : "T" + dow;
        return dowLabel + " · " + dateStr;
    }

    private LinearLayout createMetaPill(String label, String value) {
        LinearLayout box = new LinearLayout(requireContext());
        box.setOrientation(LinearLayout.VERTICAL);
        box.setBackground(createRoundedBg(R.color.glass_card, R.color.glass_border, 12));
        box.setPadding(dp(10), dp(8), dp(10), dp(8));
        box.addView(flightText(label, 11, R.color.ink_muted, false));
        TextView val = flightText(value, 12, R.color.ink, true);
        LinearLayout.LayoutParams vp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        vp.topMargin = dp(2);
        box.addView(val, vp);
        return box;
    }

    private LinearLayout createTagsRow(List<String> tags) {
        LinearLayout row = new LinearLayout(requireContext());
        row.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        rp.topMargin = dp(8);
        row.setLayoutParams(rp);
        for (int i = 0; i < tags.size(); i++) {
            TextView tag = new TextView(requireContext());
            tag.setText(tags.get(i));
            tag.setTextSize(10.5f);
            tag.setTextColor(ContextCompat.getColor(requireContext(), R.color.navy));
            tag.setTypeface(null, android.graphics.Typeface.BOLD);
            tag.setPadding(dp(8), dp(4), dp(8), dp(4));
            android.graphics.drawable.GradientDrawable bg = new android.graphics.drawable.GradientDrawable();
            bg.setColor(0x142F3E6E);
            bg.setCornerRadius(dp(8));
            tag.setBackground(bg);
            LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            if (i < tags.size() - 1) tp.rightMargin = dp(6);
            row.addView(tag, tp);
        }
        return row;
    }

    private LinearLayout createGlassCard() {
        LinearLayout card = new LinearLayout(requireContext());
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackgroundResource(R.drawable.glass_panel_card);
        card.setPadding(dp(14), dp(14), dp(14), dp(14));
        return card;
    }

    private LinearLayout createCardColumn(LinearLayout card) {
        return card;
    }

    private void addCardToList(LinearLayout card) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dp(12);
        binding.productList.addView(card, lp);
    }

    private void addDivider(LinearLayout column) {
        View divider = new View(requireContext());
        divider.setBackgroundColor(0x142F3E6E);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1));
        lp.topMargin = dp(12);
        lp.bottomMargin = dp(10);
        column.addView(divider, lp);
    }

    private TextView createChip(String label, boolean selected) {
        TextView chip = new TextView(requireContext());
        chip.setText(label);
        chip.setTextSize(12);
        chip.setPadding(dp(12), dp(7), dp(12), dp(7));
        if (selected) {
            chip.setBackground(createRoundedBg(R.color.navy, R.color.navy, 99));
            chip.setTextColor(ContextCompat.getColor(requireContext(), R.color.white));
            chip.setTypeface(null, android.graphics.Typeface.BOLD);
        } else {
            chip.setBackground(createRoundedBg(R.color.glass_card, R.color.glass_border, 99));
            chip.setTextColor(ContextCompat.getColor(requireContext(), R.color.ink));
            chip.setTypeface(null, android.graphics.Typeface.BOLD);
        }
        return chip;
    }

    private android.graphics.drawable.GradientDrawable createRoundedBg(int fillColorRes, int strokeColorRes, int radiusDp) {
        android.graphics.drawable.GradientDrawable bg = new android.graphics.drawable.GradientDrawable();
        bg.setColor(ContextCompat.getColor(requireContext(), fillColorRes));
        bg.setStroke(dp(1), ContextCompat.getColor(requireContext(), strokeColorRes));
        bg.setCornerRadius(dp(radiusDp));
        return bg;
    }

    private TextView createStepperButton(String symbol) {
        TextView btn = new TextView(requireContext());
        btn.setText(symbol);
        btn.setTextSize(14);
        btn.setGravity(Gravity.CENTER);
        android.graphics.drawable.GradientDrawable bg = new android.graphics.drawable.GradientDrawable();
        bg.setColor(0x1A2F3E6E);
        bg.setCornerRadius(dp(99));
        btn.setBackground(bg);
        btn.setTextColor(ContextCompat.getColor(requireContext(), R.color.navy));
        btn.setTypeface(null, android.graphics.Typeface.BOLD);
        btn.setWidth(dp(24));
        btn.setHeight(dp(24));
        return btn;
    }

    private MaterialButton createPrimaryButton(String label) {
        MaterialButton btn = new MaterialButton(requireContext());
        btn.setText(label);
        btn.setTextSize(12);
        btn.setTextColor(ContextCompat.getColor(requireContext(), R.color.white));
        btn.setBackgroundTintList(ContextCompat.getColorStateList(requireContext(), R.color.navy));
        btn.setCornerRadius(dp(99));
        return btn;
    }

    private interface TextChangedCallback {
        void onChanged(String value);
    }

    private EditText createSearchInput(String hint, String initialValue, TextChangedCallback callback) {
        EditText input = new EditText(requireContext());
        input.setHint(hint);
        input.setText(initialValue);
        input.setSelection(initialValue.length());
        input.setTextSize(13);
        input.setTextColor(ContextCompat.getColor(requireContext(), R.color.ink));
        input.setHintTextColor(ContextCompat.getColor(requireContext(), R.color.ink_muted));
        input.setBackground(createRoundedBg(R.color.glass_card, R.color.glass_border, 14));
        input.setPadding(dp(12), dp(10), dp(12), dp(10));
        input.setSingleLine(true);
        android.graphics.drawable.Drawable searchIcon = ContextCompat.getDrawable(requireContext(), R.drawable.ic_search);
        if (searchIcon != null) {
            searchIcon = androidx.core.graphics.drawable.DrawableCompat.wrap(searchIcon.mutate());
            androidx.core.graphics.drawable.DrawableCompat.setTint(searchIcon, ContextCompat.getColor(requireContext(), R.color.navy));
            searchIcon.setBounds(0, 0, dp(18), dp(18));
            input.setCompoundDrawablesRelative(searchIcon, null, null, null);
            input.setCompoundDrawablePadding(dp(8));
        }
        input.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {}

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {}

            @Override
            public void afterTextChanged(Editable s) {
                callback.onChanged(s == null ? "" : s.toString());
            }
        });
        return input;
    }

    private String introSubtitle(String type) {
        switch (type) {
            case "FLIGHT": return "Chọn hành trình phù hợp và xem giờ bay, hãng bay cùng giá vé.";
            case "HOTEL": return "Tìm khách sạn, chọn số đêm lưu trú và xem giá phòng rõ ràng.";
            case "MOVIE": return "Chọn rạp, suất chiếu và ghế ngồi trước khi thanh toán.";
            default: return "Chọn dịch vụ để xem giá và tiếp tục thanh toán.";
        }
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
