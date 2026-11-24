package com.example.final_term.ui.utilities;

import android.os.Bundle;
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
import com.example.final_term.core.FinalTermApplication;
import com.example.final_term.data.BackendApiClient;
import com.example.final_term.data.ReadSummaryFormatter;
import com.example.final_term.databinding.FragmentBillPaymentBinding;
import com.google.android.material.button.MaterialButton;

import org.json.JSONArray;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;


public final class BillPaymentFragment extends Fragment {
    private FragmentBillPaymentBinding binding;
    private BackendApiClient api;
    private String serviceType;
    private String sourceAccountId;
    private JSONObject selectedBill;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        binding = FragmentBillPaymentBinding.inflate(inflater, container, false);
        return binding.getRoot();
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        serviceType = getArguments() == null ? "ELECTRICITY"
                : getArguments().getString("serviceType", "ELECTRICITY");
        sourceAccountId = getArguments() == null ? null : getArguments().getString("sourceAccountId");
        api = ((FinalTermApplication) requireActivity().getApplication())
                .getAppContainer().getBackendApiClient();
        boolean water = "WATER".equalsIgnoreCase(serviceType);
        boolean other = "OTHER".equalsIgnoreCase(serviceType);
        String title = water ? "Thanh toán tiền nước" : other ? "Thanh toán hóa đơn dịch vụ" : "Thanh toán tiền điện";
        binding.headerTitle.setText(title);
        binding.providerName.setText(water ? "SAWACO · Cấp nước Sài Gòn" : other ? "Nhà cung cấp dịch vụ" : "Điện lực TP. Hồ Chí Minh");
        binding.providerSubtitle.setText(water ? "Tổng công ty Cấp nước Sài Gòn" : other ? "Thanh toán hóa đơn tiện lợi" : "EVNHCMC · Điện lực TP. Hồ Chí Minh");
        binding.providerIcon.setImageResource(water ? R.drawable.ic_utility_water
                : other ? R.drawable.ic_receipt : R.drawable.ic_utility_electricity);
        binding.backButton.setOnClickListener(v -> Navigation.findNavController(v).popBackStack());
        binding.helpButton.setOnClickListener(v ->
                com.google.android.material.snackbar.Snackbar.make(binding.getRoot(),
                        "Nhập mã khách hàng hoặc mã hóa đơn in trên thông báo cước.",
                        com.google.android.material.snackbar.Snackbar.LENGTH_LONG).show());
        binding.lookupButton.setOnClickListener(v -> lookupBill(
                binding.customerCodeInput.getText() == null ? "" : binding.customerCodeInput.getText().toString().trim()));
        binding.billLookupHistory.setOnClickListener(v -> binding.scrollContent.smoothScrollTo(0, binding.billsList.getTop()));
        binding.payButton.setOnClickListener(v -> openCheckout());
        loadBills();
        if (getArguments() != null && getArguments().containsKey("billId")) {
            String billId = getArguments().getString("billId");
            binding.customerCodeInput.setText(billId);
            lookupBill(billId);
        }
    }

    private void loadBills() {
        api.get("/bills").addOnCompleteListener(task -> {
            if (binding == null || !isAdded()) return;
            try {
                if (!task.isSuccessful() || task.getResult() == null || task.getResult().statusCode != 200)
                    throw new IllegalStateException("BILLS_UNAVAILABLE");
                JSONArray items = new JSONObject(task.getResult().body).optJSONArray("items");
                binding.billsList.removeAllViews();
                int count = 0;
                if (items != null) for (int i = 0; i < items.length(); i++) {
                    JSONObject bill = items.getJSONObject(i);
                    if (!"OTHER".equalsIgnoreCase(serviceType)
                            && !serviceType.equalsIgnoreCase(bill.optString("serviceType"))) continue;
                    if (!"UNPAID".equalsIgnoreCase(bill.optString("status"))) continue;
                    addBillRow(bill);
                    count++;
                }
                if (count == 0) binding.billsList.addView(emptyRow("Chưa có hóa đơn chưa thanh toán."));
            } catch (Exception error) {
                binding.billsList.removeAllViews();
                binding.billsList.addView(emptyRow("Không tải được hóa đơn. Kiểm tra kết nối rồi thử lại."));
            }
        });
    }

    private void addBillRow(JSONObject bill) {
        LinearLayout row = new LinearLayout(requireContext());
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(android.view.Gravity.CENTER_VERTICAL);
        row.setPadding(dp(12), dp(12), dp(12), dp(12));
        row.setBackgroundResource(R.drawable.glass_panel_card);
        LinearLayout.LayoutParams rowParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        rowParams.bottomMargin = dp(8);
        row.setLayoutParams(rowParams);

        LinearLayout text = new LinearLayout(requireContext());
        text.setOrientation(LinearLayout.VERTICAL);
        text.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView name = new TextView(requireContext());
        name.setText(bill.optString("invoiceRef", "Hóa đơn"));
        name.setTextColor(ContextCompat.getColor(requireContext(), R.color.ink));
        name.setTextSize(13);
        name.setTypeface(null, android.graphics.Typeface.BOLD);
        text.addView(name);
        TextView amount = new TextView(requireContext());
        amount.setText(ReadSummaryFormatter.formatCurrency(bill.optLong("amountMinor"), bill.optString("currency", "VND")));
        amount.setTextColor(ContextCompat.getColor(requireContext(), R.color.navy));
        amount.setTextSize(14);
        amount.setTypeface(null, android.graphics.Typeface.BOLD);
        LinearLayout.LayoutParams amountParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        amountParams.topMargin = dp(3);
        text.addView(amount, amountParams);
        row.addView(text);

        MaterialButton choose = new MaterialButton(requireContext());
        choose.setText("Chọn");
        choose.setTextSize(12);
        choose.setTextColor(ContextCompat.getColor(requireContext(), R.color.navy));
        choose.setBackgroundTintList(ContextCompat.getColorStateList(requireContext(), R.color.bg_fog));
        choose.setOnClickListener(v -> {
            binding.customerCodeInput.setText(bill.optString("invoiceRef", bill.optString("id")));
            lookupBill(binding.customerCodeInput.getText().toString().trim());
        });
        row.addView(choose);
        binding.billsList.addView(row);
    }

    private void lookupBill(String reference) {
        if (reference.isEmpty()) {
            binding.customerCodeInput.setError("Nhập mã khách hàng hoặc mã hóa đơn.");
            return;
        }
        binding.customerCodeInput.setError(null);
        binding.statusText.setVisibility(View.VISIBLE);
        binding.statusText.setText("Đang tra cứu hóa đơn...");
        binding.billDetailsCard.setVisibility(View.GONE);
        binding.payButton.setEnabled(false);
        api.get("/bills/lookup/" + android.net.Uri.encode(reference)).addOnCompleteListener(task -> {
            if (binding == null || !isAdded()) return;
            if (!task.isSuccessful() || task.getResult() == null) {
                showStatus("Không thể tra cứu hóa đơn. Kiểm tra kết nối rồi thử lại.");
                return;
            }
            if (task.getResult().statusCode == 404) {
                showStatus("Không tìm thấy hóa đơn thuộc tài khoản của bạn.");
                return;
            }
            if (task.getResult().statusCode != 200) {
                showStatus("Chưa thể tra cứu hóa đơn. Vui lòng thử lại.");
                return;
            }
            try {
                JSONObject bill = new JSONObject(task.getResult().body);
                if (!"OTHER".equalsIgnoreCase(serviceType)
                        && !serviceType.equalsIgnoreCase(bill.optString("serviceType"))) {
                    showStatus("Mã này không thuộc hóa đơn " + ("WATER".equalsIgnoreCase(serviceType) ? "nước." : "điện."));
                    return;
                }
                selectedBill = bill;
                binding.billDetailsCard.setVisibility(View.VISIBLE);
                binding.billReference.setText(bill.optString("invoiceRef", bill.optString("id")));
                binding.billAmount.setText(ReadSummaryFormatter.formatCurrency(
                        bill.optLong("amountMinor"), bill.optString("currency", "VND")));
                boolean unpaid = "UNPAID".equalsIgnoreCase(bill.optString("status"));
                binding.billStatus.setText(unpaid ? "Chưa thanh toán" : "Hóa đơn " + statusLabel(bill.optString("status")));
                binding.billStatus.setTextColor(ContextCompat.getColor(requireContext(),
                        unpaid ? R.color.warning : R.color.success));
                bindBillDetails(bill, "WATER".equalsIgnoreCase(serviceType));
                binding.payButton.setEnabled(unpaid);
                binding.payButton.setText(unpaid
                        ? "Thanh toán · " + ReadSummaryFormatter.formatCurrency(bill.optLong("amountMinor"), bill.optString("currency", "VND"))
                        : statusLabel(bill.optString("status")));
                binding.statusText.setVisibility(View.GONE);
            } catch (Exception error) {
                showStatus("Dữ liệu hóa đơn không hợp lệ. Vui lòng thử lại.");
            }
        });
    }

    private void bindBillDetails(JSONObject bill, boolean water) {
        if (!bill.isNull("dueAt") && bill.optLong("dueAt") > 0) {
            String dueDate = new SimpleDateFormat("dd/MM/yyyy", new Locale("vi", "VN"))
                    .format(new Date(bill.optLong("dueAt")));
            binding.billDueDate.setText("Hạn thanh toán: " + dueDate);
            binding.billDueDate.setVisibility(View.VISIBLE);
        } else {
            binding.billDueDate.setVisibility(View.GONE);
        }

        String period = bill.optString("billingPeriod", "").trim();
        binding.billPeriod.setText("Kỳ hóa đơn: " + period);
        binding.billPeriod.setVisibility(period.isEmpty() ? View.GONE : View.VISIBLE);

        String previous = bill.optString("previousReading", "").trim();
        String current = bill.optString("currentReading", "").trim();
        if (!previous.isEmpty() && !current.isEmpty()) {
            String unit = water ? "m³" : "kWh";
            binding.billReading.setText("Chỉ số công tơ: " + previous + " → " + current + " " + unit);
            binding.billReading.setVisibility(View.VISIBLE);
        } else {
            binding.billReading.setVisibility(View.GONE);
        }

        if (!bill.isNull("consumption") && bill.optDouble("consumption", 0) > 0) {
            String unit = bill.optString("consumptionUnit", water ? "m³" : "kWh");
            binding.billConsumption.setText("Lượng tiêu thụ: " + bill.optString("consumption") + " " + unit);
            binding.billConsumption.setVisibility(View.VISIBLE);
        } else {
            binding.billConsumption.setVisibility(View.GONE);
        }
    }

    private void openCheckout() {
        if (selectedBill == null || !"UNPAID".equalsIgnoreCase(selectedBill.optString("status"))) return;
        UtilitiesHubDialogFragment checkout = UtilitiesHubDialogFragment.newCheckoutInstance(
                selectedBill.optString("id"), sourceAccountId);
        checkout.show(getParentFragmentManager(), "UtilityCheckout");
    }

    private void showStatus(String message) {
        binding.statusText.setText(message);
        binding.statusText.setVisibility(View.VISIBLE);
    }

    private String statusLabel(String status) {
        if ("PAID".equalsIgnoreCase(status) || "SETTLED".equalsIgnoreCase(status)) return "Đã thanh toán";
        if ("CANCELLED".equalsIgnoreCase(status)) return "Đã hủy";
        return "Đang xử lý";
    }

    private TextView emptyRow(String message) {
        TextView row = new TextView(requireContext());
        row.setText(message);
        row.setTextColor(ContextCompat.getColor(requireContext(), R.color.ink_muted));
        row.setTextSize(13);
        row.setGravity(android.view.Gravity.CENTER);
        row.setPadding(dp(12), dp(18), dp(12), dp(18));
        return row;
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
