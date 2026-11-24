package com.example.final_term.ui.customer;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.LiveData;

import com.example.final_term.R;
import com.example.final_term.auth.AuthState;
import com.example.final_term.auth.UserRole;
import com.example.final_term.core.AppContainer;
import com.example.final_term.core.FinalTermApplication;
import com.example.final_term.data.BackendApiClient;
import com.example.final_term.data.ReadModelResult;
import com.example.final_term.data.ReadModels.AccountSummary;
import com.example.final_term.data.ReadSummaryFormatter;
import com.example.final_term.databinding.FragmentMoneyRequestBinding;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

public final class MoneyRequestFragment extends Fragment {
    private static final String PATH = "/money-requests";
    private static final String DEPOSIT = "DEPOSIT";
    private static final String WITHDRAWAL = "WITHDRAWAL";
    private final List<String> accountIds = new ArrayList<>();
    private FragmentMoneyRequestBinding binding;
    private LiveData<ReadModelResult<List<AccountSummary>>> accounts;
    private BackendApiClient api;
    private String uid;
    private String pendingUid;
    private String pendingBody;
    private boolean sending;
    private boolean readingHistory;

    @Override public void onCreate(@Nullable Bundle state) {
        super.onCreate(state);
        if (state != null) {
            pendingUid = state.getString("pendingUid");
            pendingBody = state.getString("pendingBody");
        }
    }

    @Nullable @Override public View onCreateView(@NonNull LayoutInflater inflater,
            @Nullable ViewGroup container, @Nullable Bundle state) {
        binding = FragmentMoneyRequestBinding.inflate(inflater, container, false);
        return binding.getRoot();
    }

    @Override public void onViewCreated(@NonNull View view, @Nullable Bundle state) {
        super.onViewCreated(view, state);
        AppContainer app = ((FinalTermApplication) requireActivity().getApplication()).getAppContainer();
        api = app.getBackendApiClient();
        String operation = getArguments() == null ? DEPOSIT : getArguments().getString("operation", DEPOSIT);
        binding.titleText.setText(operationLabel(operation));
        binding.operationInput.setAdapter(adapter(List.of("Nạp tiền", "Rút tiền")));
        binding.operationInput.setSelection(WITHDRAWAL.equals(operation) ? 1 : 0);
        updateTabStyles(WITHDRAWAL.equals(operation) ? 1 : 0);

        binding.backButton.setOnClickListener(v -> androidx.navigation.Navigation.findNavController(v).navigateUp());
        binding.helpButton.setOnClickListener(v -> {
            new MaterialAlertDialogBuilder(requireContext())
                    .setTitle("Quy trình Nạp / Rút tiền")
                    .setMessage("Yêu cầu nạp hoặc rút tiền của bạn sẽ được gửi tới backend ngân hàng để kiểm tra tính hợp lệ.\n\nSố dư tài khoản chỉ thay đổi khi backend chính thức duyệt và ghi nhận giao dịch.")
                    .setPositiveButton("Đã hiểu", null)
                    .show();
        });

        binding.tabDeposit.setOnClickListener(v -> {
            binding.operationInput.setSelection(0);
            updateTabStyles(0);
        });

        binding.tabWithdrawal.setOnClickListener(v -> {
            binding.operationInput.setSelection(1);
            updateTabStyles(1);
        });

        binding.chipReq500k.setOnClickListener(v -> appendRequestAmount(500000));
        binding.chipReq1m.setOnClickListener(v -> appendRequestAmount(1000000));
        binding.chipReq5m.setOnClickListener(v -> appendRequestAmount(5000000));

        binding.operationInput.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(AdapterView<?> parent, View selected, int position, long id) {
                if (binding != null) {
                    binding.titleText.setText(operationLabel(position == 1 ? WITHDRAWAL : DEPOSIT));
                    updateTabStyles(position);
                }
            }
            @Override public void onNothingSelected(AdapterView<?> parent) { }
        });
        binding.statusText.setText(pendingBody == null ? "Đang tải tài khoản…" :
                "Chưa xác nhận được yêu cầu trước. Gửi lại để kiểm tra cùng mã yêu cầu.");
        binding.submitButton.setText(WITHDRAWAL.equals(operation) ? "Tiếp tục · rút tiền demo" : "Nạp tiền bằng thẻ test");
        binding.demoSettlementButton.setVisibility(View.GONE);
        ((View) binding.accountInput.getParent()).setVisibility(View.GONE);
        ((View) binding.amountInput.getParent()).setVisibility(View.GONE);
        binding.submitButton.setOnClickListener(v -> {
            if (pendingBody != null) {
                submit();
                return;
            }
            Bundle args = new Bundle();
            args.putString("demoCashOperation", binding.operationInput.getSelectedItemPosition() == 1 ? WITHDRAWAL : DEPOSIT);
            androidx.navigation.Navigation.findNavController(v).navigate(R.id.transfer, args);
        });
        binding.historyButton.setOnClickListener(v ->
                androidx.navigation.Navigation.findNavController(v).navigate(R.id.transactionHistory));
        updateControls();
        app.getAuthRepository().observeAuthState().observe(getViewLifecycleOwner(), auth -> {
            String currentUid = auth != null && auth.getStatus() == AuthState.Status.SIGNED_IN
                    && auth.getUser() != null && auth.getUser().getRole() == UserRole.CUSTOMER
                    ? auth.getUser().getUid() : null;
            if (uid != null && Objects.equals(uid, currentUid)) return;
            if (accounts != null) accounts.removeObservers(getViewLifecycleOwner());
            uid = currentUid;
            if (uid != null && !Objects.equals(uid, pendingUid)) clearPending();
            accountIds.clear();
            binding.accountInput.setAdapter(null);
            sending = false;
            readingHistory = false;
            updateControls();
            if (uid == null) {
                binding.statusText.setText("Vui lòng đăng nhập bằng tài khoản khách hàng.");
                return;
            }
            binding.statusText.setText(pendingBody == null ? "Đang tải tài khoản…" :
                    "Chưa xác nhận được yêu cầu trước. Gửi lại để kiểm tra cùng mã yêu cầu.");
            accounts = app.getReadRepository().observeAccounts(uid);
            accounts.observe(getViewLifecycleOwner(), this::renderAccounts);
        });
    }

    private void renderAccounts(ReadModelResult<List<AccountSummary>> result) {
        int previousIndex = binding.accountInput.getSelectedItemPosition();
        String previousId = previousIndex >= 0 && previousIndex < accountIds.size()
                ? accountIds.get(previousIndex) : null;
        accountIds.clear();
        List<String> labels = new ArrayList<>();
        if (result.getStatus() == ReadModelResult.Status.SUCCESS && result.getData() != null) {
            for (AccountSummary account : result.getData()) {
                if ("CHECKING".equalsIgnoreCase(account.type) && "ACTIVE".equalsIgnoreCase(account.status)
                        && "VND".equalsIgnoreCase(account.currency)) {
                    accountIds.add(account.id);
                    labels.add(account.id + " · VND");
                }
            }
        }
        binding.accountInput.setAdapter(adapter(labels.isEmpty()
                ? List.of("Không có tài khoản thanh toán khả dụng") : labels));
        int newIndex = accountIds.indexOf(previousId);
        if (newIndex >= 0) binding.accountInput.setSelection(newIndex);
        if ("Đang tải tài khoản…".contentEquals(binding.statusText.getText())) {
            binding.statusText.setText(accountIds.isEmpty()
                    ? "Cần tài khoản thanh toán đã đồng bộ."
                    : "Nạp tiền được ghi có sau khi Stripe xác nhận thanh toán; rút tiền là luồng demo.");
        }
        updateControls();
    }

    private void submit() {
        if (sending || uid == null) return;
        boolean retry = pendingBody != null;
        if (!retry) {
            int index = binding.accountInput.getSelectedItemPosition();
            if (index < 0 || index >= accountIds.size()) return;
            String amount = binding.amountInput.getText().toString().trim();
            if (!Phase2Presentation.validRequestAmount(amount)) {
                binding.amountInput.setError("Nhập số tiền VND nguyên hợp lệ, lớn hơn 0.");
                return;
            }
            try {
                pendingBody = new JSONObject()
                        .put("requestId", UUID.randomUUID().toString())
                        .put("accountId", accountIds.get(index))
                        .put("amountMinor", Long.parseLong(amount))
                        .put("currency", "VND")
                        .put("type", binding.operationInput.getSelectedItemPosition() == 1 ? WITHDRAWAL : DEPOSIT)
                        .toString();
                pendingUid = uid;
            } catch (JSONException error) {
                binding.statusText.setText("Không thể tạo dữ liệu yêu cầu.");
                return;
            }
        }

        FragmentMoneyRequestBinding currentView = binding;
        String currentUid = uid;
        String requestBody = pendingBody;
        sending = true;
        binding.statusText.setText(retry ? "Đang tra cứu yêu cầu cũ…" : "Đang gửi yêu cầu…");
        updateControls();
        com.google.android.gms.tasks.Task<BackendApiClient.Response> submission;
        if (retry) {
            try {
                String requestId = new JSONObject(requestBody).getString("requestId");
                submission = api.get(PATH + "/by-request/" + requestId).continueWithTask(task -> {
                    if (!task.isSuccessful()) throw task.getException();
                    return task.getResult().statusCode == 404 ? api.post(PATH, requestBody)
                            : com.google.android.gms.tasks.Tasks.forResult(task.getResult());
                });
            } catch (JSONException error) {
                sending = false;
                clearPending();
                updateControls();
                binding.statusText.setText("Không đọc được mã yêu cầu cũ.");
                return;
            }
        } else submission = api.post(PATH, requestBody);
        submission.addOnCompleteListener(task -> {
            if (binding != currentView || pendingBody != requestBody || !Objects.equals(uid, currentUid)) return;
            sending = false;
            if (task.isSuccessful()) {
                BackendApiClient.Response response = task.getResult();
                if (response.statusCode >= 200 && response.statusCode < 300) {
                    try {
                        JSONObject receipt = new JSONObject(response.body);
                        String requestId = receipt.getString("requestId");
                        String id = receipt.getString("id");
                        if (!id.isEmpty() && "REQUESTED".equals(receipt.getString("status"))
                                && requestId.equals(new JSONObject(requestBody).getString("requestId"))) {
                            clearPending();
                            binding.amountInput.setText("");
                            binding.statusText.setText("Đã gửi yêu cầu · Đã tiếp nhận (REQUESTED)\nMã: " + id);
                            updateControls();
                            return;
                        }
                    } catch (JSONException ignored) {
                        // Retry with the same request ID when the receipt is invalid.
                    }
                } else if (response.statusCode >= 400 && response.statusCode < 500
                        && response.statusCode != 408 && response.statusCode != 429) {
                    clearPending();
                    binding.statusText.setText("Backend từ chối yêu cầu (HTTP " + response.statusCode + ").");
                    updateControls();
                    return;
                }
            }
            binding.statusText.setText("Chưa xác nhận được kết quả. Gửi lại sẽ dùng cùng mã yêu cầu.");
            updateControls();
        });
    }

    private void openHistory() {
        if (uid == null || readingHistory) return;
        FragmentMoneyRequestBinding currentView = binding;
        String currentUid = uid;
        readingHistory = true;
        updateControls();
        api.get(PATH + "?limit=25").addOnCompleteListener(task -> {
            if (binding != currentView || !Objects.equals(uid, currentUid)) return;
            readingHistory = false;
            updateControls();
            String message = "Không thể tải yêu cầu đã gửi. Vui lòng thử lại.";
            if (task.isSuccessful()) {
                BackendApiClient.Response response = task.getResult();
                if (response.statusCode >= 200 && response.statusCode < 300) {
                    try {
                        JSONArray items = new JSONObject(response.body).getJSONArray("items");
                        List<String> rows = new ArrayList<>();
                        for (int index = 0; index < items.length(); index++) {
                            JSONObject row = items.getJSONObject(index);
                            rows.add(operationLabel(row.getString("type")) + " · "
                                    + ReadSummaryFormatter.money(row.getLong("amountMinor")) + "\n"
                                    + row.getString("status") + " · " + row.getString("accountId"));
                        }
                        message = rows.isEmpty() ? "Chưa có yêu cầu nạp/rút." : String.join("\n\n", rows);
                    } catch (JSONException ignored) {
                        // Keep the error message for an invalid response.
                    }
                }
            }
            new MaterialAlertDialogBuilder(requireContext())
                    .setTitle("Yêu cầu đã gửi")
                    .setMessage(message)
                    .setPositiveButton("Đóng", null)
                    .show();
        });
    }

    private ArrayAdapter<String> adapter(List<String> labels) {
        return new ArrayAdapter<>(requireContext(), android.R.layout.simple_spinner_dropdown_item, labels);
    }

    private void updateControls() {
        boolean retry = pendingBody != null;
        binding.operationInput.setEnabled(!retry);
        binding.accountInput.setEnabled(!retry);
        binding.amountInput.setEnabled(!retry);
        binding.submitButton.setEnabled(uid != null && !sending && (retry || !accountIds.isEmpty()));
        boolean withdrawal = binding.operationInput.getSelectedItemPosition() == 1;
        binding.submitButton.setText(retry && !sending ? "Gửi lại yêu cầu"
                : withdrawal ? "Tiếp tục · rút tiền demo" : "Nạp tiền bằng thẻ test");
        binding.historyButton.setEnabled(uid != null && !readingHistory);
    }

    private void clearPending() {
        pendingBody = null;
        pendingUid = null;
    }

    private static String operationLabel(String type) {
        return WITHDRAWAL.equals(type) ? "Rút tiền" : "Nạp tiền";
    }

    private void updateTabStyles(int position) {
        if (binding == null) return;
        boolean isDeposit = position == 0;
        binding.tabDeposit.setBackgroundResource(isDeposit ? R.drawable.navy_button_pill : 0);
        binding.tabDeposit.setTextColor(requireContext().getColor(isDeposit ? R.color.white : R.color.ink_muted));
        binding.tabDeposit.setTypeface(null, isDeposit ? android.graphics.Typeface.BOLD : android.graphics.Typeface.NORMAL);

        binding.tabWithdrawal.setBackgroundResource(!isDeposit ? R.drawable.navy_button_pill : 0);
        binding.tabWithdrawal.setTextColor(requireContext().getColor(!isDeposit ? R.color.white : R.color.ink_muted));
        binding.tabWithdrawal.setTypeface(null, !isDeposit ? android.graphics.Typeface.BOLD : android.graphics.Typeface.NORMAL);
    }

    private void appendRequestAmount(long delta) {
        if (binding == null) return;
        String cur = binding.amountInput.getText().toString().trim();
        long currentVal = 0;
        try {
            if (!cur.isEmpty()) currentVal = Long.parseLong(cur);
        } catch (Exception ignored) { }
        binding.amountInput.setText(String.valueOf(currentVal + delta));
    }

    @Override public void onSaveInstanceState(@NonNull Bundle state) {
        super.onSaveInstanceState(state);
        state.putString("pendingUid", pendingUid);
        state.putString("pendingBody", pendingBody);
    }

    @Override public void onDestroyView() {
        binding = null;
        accounts = null;
        uid = null;
        accountIds.clear();
        sending = false;
        readingHistory = false;
        super.onDestroyView();
    }
}
