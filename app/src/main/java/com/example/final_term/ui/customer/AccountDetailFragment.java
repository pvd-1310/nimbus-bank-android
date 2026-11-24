package com.example.final_term.ui.customer;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.navigation.Navigation;

import com.example.final_term.databinding.FragmentAccountDetailBinding;
import com.example.final_term.core.FinalTermApplication;
import com.example.final_term.data.ReadSummaryFormatter;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.text.NumberFormat;
import java.util.Locale;

public class AccountDetailFragment extends Fragment {
    private FragmentAccountDetailBinding binding;
    private String currentAccountId = "";
    private String currentAccountType = "";
    private Long currentBalance = null;
    private String currentCurrency = "";
    private String loadedTerms = "";
    private String loadedStripeAccount = "";
    private int stripeSandboxRequest;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle state) {
        binding = FragmentAccountDetailBinding.inflate(inflater, container, false);
        return binding.getRoot();
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle state) {
        super.onViewCreated(view, state);
        Bundle args = getArguments();
        currentAccountType = args == null ? "UNKNOWN" : args.getString("accountType", "UNKNOWN");
        currentAccountId = args == null ? "" : args.getString("accountId", "");

        binding.titleText.setText(Phase2Presentation.accountTitle(currentAccountType));
        binding.subtitleText.setText(Phase2Presentation.accountSubtitle(currentAccountType));
        binding.balanceText.setText("—");
        binding.statusText.setText("Đang tải dữ liệu…");
        binding.detailText.setText("");
        loadedTerms = "";
        loadedStripeAccount = "";

        binding.backButton.setOnClickListener(v -> Navigation.findNavController(v).navigateUp());

        binding.shareButton.setOnClickListener(v -> {
            String info = "Tài khoản Nimbus Bank\nLoại: " + Phase2Presentation.accountTitle(currentAccountType)
                    + "\nMã tài khoản: " + currentAccountId
                    + (currentBalance != null ? "\nSố dư: " + formatMoney(currentBalance, currentCurrency) : "");
            Intent share = new Intent(Intent.ACTION_SEND);
            share.setType("text/plain");
            share.putExtra(Intent.EXTRA_TEXT, info);
            startActivity(Intent.createChooser(share, "Chia sẻ thông tin tài khoản"));
        });

        binding.copyInfoButton.setOnClickListener(v -> {
            ClipboardManager cm = (ClipboardManager) requireContext().getSystemService(Context.CLIPBOARD_SERVICE);
            if (cm != null && !currentAccountId.isEmpty()) {
                cm.setPrimaryClip(ClipData.newPlainText("Nimbus Account", currentAccountId));
                Toast.makeText(requireContext(), "Đã sao chép mã tài khoản: " + currentAccountId, Toast.LENGTH_SHORT).show();
            }
        });

        if (currentAccountId.isEmpty()) {
            binding.statusText.setText("Thiếu mã tài khoản. Vui lòng chọn lại từ danh sách.");
            return;
        }

        com.example.final_term.core.FinalTermApplication app =
                (com.example.final_term.core.FinalTermApplication) requireActivity().getApplication();
        app.getAppContainer().getAuthRepository().observeAuthState().observe(getViewLifecycleOwner(), auth -> {
            if (auth == null || auth.getUser() == null) {
                currentBalance = null;
                loadedTerms = "";
                loadedStripeAccount = "";
                binding.stripeSandboxPanel.setVisibility(View.GONE);
                binding.detailText.setText("");
                binding.balanceText.setText("—");
                binding.statusText.setText("Cần đăng nhập để tải tài khoản.");
                return;
            }
            com.example.final_term.data.FirestoreReadRepository repo = app.getAppContainer().getReadRepository();
            androidx.lifecycle.LiveData<com.example.final_term.data.ReadModelResult<java.util.List<com.example.final_term.data.ReadModels.AccountSummary>>> source =
                    auth.getUser().getRole() == com.example.final_term.auth.UserRole.OFFICER
                            ? repo.observeAllAccountsForOfficer() : repo.observeAccounts(auth.getUser().getUid());
            source.observe(getViewLifecycleOwner(), result -> renderAccount(result, currentAccountId));
        });
    }

    private void renderAccount(com.example.final_term.data.ReadModelResult<java.util.List<com.example.final_term.data.ReadModels.AccountSummary>> result,
                               String accountId) {
        var accounts = result.getData();
        if (accounts != null) for (var account : accounts) {
            if (!accountId.equals(account.id)) continue;
            binding.titleText.setText(Phase2Presentation.accountTitle(account.type));
            binding.subtitleText.setText(Phase2Presentation.accountSubtitle(account.type));
            loadTerms(account.id, account.type);
            if (!loadedStripeAccount.equals(account.id)) {
                loadStripeSandboxAccount(account.id);
            }
            return;
        }
        stripeSandboxRequest++;
        loadedStripeAccount = "";
        currentBalance = null;
        binding.balanceText.setText("—");
        binding.stripeSandboxPanel.setVisibility(View.GONE);
        binding.statusText.setText(result.getStatus() == com.example.final_term.data.ReadModelResult.Status.LOADING
                ? "Đang tải dữ liệu…" : result.getStatus() == com.example.final_term.data.ReadModelResult.Status.ERROR
                ? result.getMessage() : "Không có tài khoản hoặc bạn không có quyền xem.");
    }

    private void loadStripeSandboxAccount(String accountId) {
        loadedStripeAccount = accountId;
        int request = ++stripeSandboxRequest;
        var current = binding;
        currentBalance = null;
        currentCurrency = "USD";
        current.balanceText.setText("—");
        current.statusText.setText("Đang đọc số dư Stripe…");
        current.stripeSandboxSummary.setText("Đang đọc số dư Stripe…");
        var app = (FinalTermApplication) requireActivity().getApplication();
        app.getAppContainer().getBackendApiClient()
                .get("/accounts/" + android.net.Uri.encode(accountId) + "/stripe-sandbox")
                .addOnCompleteListener(task -> {
                    if (binding != current || request != stripeSandboxRequest || !loadedStripeAccount.equals(accountId)) return;
                    try {
                        if (!task.isSuccessful() || task.getResult().statusCode != 200) {
                            current.statusText.setText("Chưa đọc được số dư Stripe");
                            return;
                        }
                        JSONObject snapshot = new JSONObject(task.getResult().body);
                        if (!snapshot.has("availableMinor")) {
                            current.stripeSandboxPanel.setVisibility(View.GONE);
                            current.statusText.setText("unlinked".equals(snapshot.optString("status"))
                                    ? "Chưa liên kết Stripe" : "Đang thiết lập Stripe");
                            return;
                        }
                        currentBalance = snapshot.getLong("availableMinor");
                        current.balanceText.setText(ReadSummaryFormatter.formatCurrency(currentBalance, "USD"));
                        current.statusText.setText("Stripe Sandbox · VND");
                        current.stripeSandboxPanel.setVisibility(View.VISIBLE);
                        StringBuilder summary = new StringBuilder();
                        long held = snapshot.getLong("outboundPendingMinor");
                        if (held != 0) summary.append("Đang giữ  ").append(ReadSummaryFormatter.formatCurrency(held, "USD"));
                        JSONObject card = snapshot.optJSONObject("card");
                        if (card != null) {
                            summary.append("\nThẻ ").append(card.getString("brand")).append(" •••• ")
                                    .append(card.getString("last4"));
                        }
                        JSONArray transactions = snapshot.getJSONArray("transactions");
                        if (transactions.length() > 0) summary.append("\n\nGiao dịch Stripe gần đây");
                        for (int i = 0; i < transactions.length(); i++) {
                            JSONObject item = transactions.getJSONObject(i);
                            summary.append("\n").append(ReadSummaryFormatter.formatCurrency(item.getLong("amountMinor"), "USD"))
                                    .append(" · ").append(date(item, "createdAt"));
                            String description = item.optString("description");
                            if (!description.isEmpty()) summary.append(" · ").append(description);
                            String status = item.optString("status");
                            if ("open".equals(status)) summary.append(" · Đang xử lý");
                            if ("void".equals(status)) summary.append(" · Đã hủy");
                        }
                        current.stripeSandboxSummary.setText(summary);
                    } catch (JSONException error) {
                        current.stripeSandboxSummary.setText("Dữ liệu tài khoản Stripe chưa khả dụng.");
                    }
                });
    }

    @Override
    public void onResume() {
        super.onResume();
        if (binding != null && !loadedStripeAccount.isEmpty()) loadStripeSandboxAccount(loadedStripeAccount);
    }

    private void loadTerms(String accountId, String type) {
        final String endpoint;
        switch (type) {
            case "CHECKING": endpoint = "/details"; break;
            case "SAVINGS": endpoint = "/savings-estimate?days=30"; break;
            case "MORTGAGE": endpoint = "/mortgage-schedule"; break;
            default: endpoint = "";
        }
        if (endpoint.isEmpty()) {
            binding.detailText.setText("Mã tài khoản: " + accountId);
            return;
        }
        if (loadedTerms.equals(accountId)) return;
        loadedTerms = accountId;
        var current = binding;
        current.detailText.setText("Đang tải điều khoản tài khoản…");
        var app = (FinalTermApplication) requireActivity().getApplication();
        app.getAppContainer().getBackendApiClient()
                .get("/accounts/" + android.net.Uri.encode(accountId) + endpoint)
                .addOnCompleteListener(task -> {
                    if (binding != current || !loadedTerms.equals(accountId)) return;
                    try {
                        if (!task.isSuccessful() || task.getResult().statusCode != 200)
                            throw new JSONException("Unavailable");
                        var terms = new JSONObject(task.getResult().body);
                        current.detailText.setOnClickListener(null);
                        current.detailText.setText("Mã tài khoản: " + accountId + "\n\n" + formatTerms(terms, type));
                    } catch (JSONException error) {
                        loadedTerms = "";
                        current.detailText.setText("Chưa tải được điều khoản. Chạm để thử lại.");
                        current.detailText.setOnClickListener(v -> loadTerms(accountId, type));
                    }
                });
    }

    private String formatTerms(JSONObject terms, String type) throws JSONException {
        if ("CHECKING".equals(type)) {
            JSONObject details = terms.getJSONObject("details");
            String currency = terms.getJSONObject("account").getString("currency");
            return details.isNull("dailyLimitMinor") ? "Chưa có hạn mức ngày."
                    : "Hạn mức ngày: " + formatMoney(details.getLong("dailyLimitMinor"), currency);
        }
        String currency = terms.getString("currency");
        NumberFormat percent = NumberFormat.getPercentInstance(Locale.forLanguageTag("vi-VN"));
        percent.setMaximumFractionDigits(2);
        String rate = percent.format(terms.getInt("annualRateBasisPoints") / 10000.0);
        var text = new StringBuilder(terms.optBoolean("demo") ? "Dữ liệu mô phỏng\n" : "");
        text.append("Gốc: ").append(formatMoney(terms.getLong("principalMinor"), currency))
                .append("\nLãi suất năm: ").append(rate);
        if ("SAVINGS".equals(type)) {
            text.append("\nLãi dự kiến 30 ngày: ").append(formatMoney(terms.getLong("profitMinor"), currency))
                    .append("\nNgày mở: ").append(date(terms, "openedAt"))
                    .append("\nNgày đáo hạn: ").append(date(terms, "maturityAt"))
                    .append("\nChính sách: ").append(terms.getString("ratePolicyVersion"))
                    .append(" · Hiệu lực từ ").append(date(terms, "effectiveFrom"))
                    .append("\n").append(terms.getString("convention"));
            return text.toString();
        }
        text.append("\nDư nợ: ").append(terms.isNull("outstandingMinor") ? "Chưa có dữ liệu"
                        : formatMoney(terms.getLong("outstandingMinor"), currency))
                .append("\nKỳ hạn: ").append(terms.getInt("termMonths")).append(" tháng")
                .append("\nKỳ đầu nếu trả hàng tháng: ").append(formatMoney(terms.getLong("monthlyFirstPaymentMinor"), currency))
                .append("\nKỳ đầu nếu trả hai tuần một lần: ").append(formatMoney(terms.getLong("biweeklyFirstPaymentMinor"), currency))
                .append("\nTổng dự kiến theo hợp đồng: ").append(formatMoney(terms.getLong("totalMinor"), currency))
                .append("\nLịch dự kiến theo kỳ ")
                .append("BIWEEKLY".equals(terms.getString("paymentFrequency")) ? "hai tuần" : "tháng");
        JSONArray items = terms.getJSONArray("items");
        for (int i = 0; i < items.length(); i++) {
            JSONObject item = items.getJSONObject(i);
            text.append("\nKỳ ").append(item.getInt("installment")).append(" · ")
                    .append(formatMoney(item.getLong("totalMinor"), currency))
                    .append(" · Gốc còn lại ").append(formatMoney(item.getLong("remainingMinor"), currency));
        }
        return text.toString();
    }

    private String date(JSONObject data, String field) {
        if (data.isNull(field)) return "Chưa có dữ liệu";
        long value = data.optLong(field);
        return java.text.DateFormat.getDateInstance(java.text.DateFormat.MEDIUM, Locale.forLanguageTag("vi-VN"))
                .format(new java.util.Date(value));
    }

    private String formatMoney(long amount, String currency) {
        return ReadSummaryFormatter.formatCurrency(amount, currency);
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        binding = null;
    }
}
