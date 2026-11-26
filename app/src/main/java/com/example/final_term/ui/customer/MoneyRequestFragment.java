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
import androidx.navigation.Navigation;

import com.example.final_term.R;
import com.example.final_term.auth.AuthState;
import com.example.final_term.auth.UserRole;
import com.example.final_term.core.AppContainer;
import com.example.final_term.core.FinalTermApplication;
import com.example.final_term.data.ReadModelResult;
import com.example.final_term.data.ReadModels.AccountSummary;
import com.example.final_term.databinding.FragmentMoneyRequestBinding;
import com.example.final_term.ui.transaction.TransactionFlowRequest;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

public final class MoneyRequestFragment extends Fragment {
    private static final String DEPOSIT = "DEPOSIT";
    private static final String WITHDRAWAL = "WITHDRAWAL";
    private final List<String> accountIds = new ArrayList<>();
    private FragmentMoneyRequestBinding binding;
    private LiveData<ReadModelResult<List<AccountSummary>>> accounts;
    private String uid;

    @Nullable @Override public View onCreateView(@NonNull LayoutInflater inflater,
            @Nullable ViewGroup container, @Nullable Bundle state) {
        binding = FragmentMoneyRequestBinding.inflate(inflater, container, false);
        return binding.getRoot();
    }

    @Override public void onViewCreated(@NonNull View view, @Nullable Bundle state) {
        super.onViewCreated(view, state);
        AppContainer app = ((FinalTermApplication) requireActivity().getApplication()).getAppContainer();
        String operation = getArguments() == null ? DEPOSIT : getArguments().getString("operation", DEPOSIT);
        binding.operationInput.setAdapter(adapter(List.of("Nạp tiền", "Rút tiền")));
        binding.operationInput.setSelection(WITHDRAWAL.equals(operation) ? 1 : 0);

        binding.backButton.setOnClickListener(v -> androidx.navigation.Navigation.findNavController(v).navigateUp());
        binding.helpButton.setOnClickListener(v -> {
            binding.statusText.setText("Số dư thay đổi sau khi giao dịch hoàn tất.");
        });

        binding.tabDeposit.setOnClickListener(v -> {
            selectOperation(0);
        });

        binding.tabWithdrawal.setOnClickListener(v -> {
            selectOperation(1);
        });

        binding.chipReq500k.setOnClickListener(v -> appendRequestAmount(500000));
        binding.chipReq1m.setOnClickListener(v -> appendRequestAmount(1000000));
        binding.chipReq5m.setOnClickListener(v -> appendRequestAmount(5000000));

        binding.operationInput.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(AdapterView<?> parent, View selected, int position, long id) {
                if (binding != null) {
                    updateOperationUi();
                }
            }
            @Override public void onNothingSelected(AdapterView<?> parent) { }
        });
        binding.statusText.setText("Đang tải tài khoản…");
        binding.demoSettlementButton.setVisibility(View.GONE);
        updateOperationUi();
        binding.submitButton.setOnClickListener(v -> {
            boolean withdrawal = binding.operationInput.getSelectedItemPosition() == 1;
            int index = binding.accountInput.getSelectedItemPosition();
            if (index < 0 || index >= accountIds.size()) {
                binding.statusText.setText("Chọn tài khoản thanh toán khả dụng.");
                return;
            }
            String amount = binding.amountInput.getText().toString().trim();
            if (!Phase2Presentation.validRequestAmount(amount)) {
                binding.amountInput.setError("Nhập số tiền VND nguyên hợp lệ, lớn hơn 0.");
                return;
            }
            long amountMinor = Long.parseLong(amount);
            String accountId = accountIds.get(index);
            TransactionFlowRequest flowRequest = withdrawal
                    ? TransactionFlowRequest.withdrawal(accountId, amountMinor, "VND")
                    : TransactionFlowRequest.deposit(accountId, amountMinor, "VND");
            Navigation.findNavController(v).navigate(R.id.transactionFlow, flowRequest.toBundle());
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
            accountIds.clear();
            binding.accountInput.setAdapter(null);
            updateControls();
            if (uid == null) {
                binding.statusText.setText("Vui lòng đăng nhập bằng tài khoản khách hàng.");
                return;
            }
            binding.statusText.setText("Đang tải tài khoản…");
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
                    ? "Chưa có tài khoản thanh toán khả dụng."
                    : "Nạp tiền được ghi có sau khi thanh toán hoàn tất.");
        }
        updateControls();
    }

    private ArrayAdapter<String> adapter(List<String> labels) {
        return new ArrayAdapter<>(requireContext(), android.R.layout.simple_spinner_dropdown_item, labels);
    }

    private void updateControls() {
        binding.operationInput.setEnabled(true);
        binding.accountInput.setEnabled(true);
        binding.amountInput.setEnabled(true);
        binding.submitButton.setEnabled(uid != null && (binding.operationInput.getSelectedItemPosition() == 0 || !accountIds.isEmpty()));
        boolean withdrawal = binding.operationInput.getSelectedItemPosition() == 1;
        binding.submitButton.setText(withdrawal ? "Tiếp tục · rút tiền" : "Nạp tiền bằng thẻ");
        binding.historyButton.setEnabled(uid != null);
    }

    private void selectOperation(int position) {
        binding.operationInput.setSelection(position);
        updateOperationUi();
    }

    private void updateOperationUi() {
        int position = binding.operationInput.getSelectedItemPosition() == 1 ? 1 : 0;
        boolean withdrawal = Phase2Presentation.usesRequestForm(position == 1 ? WITHDRAWAL : DEPOSIT);
        binding.titleText.setText(operationLabel(withdrawal ? WITHDRAWAL : DEPOSIT));
        updateTabStyles(position);
        ((View) binding.accountInput.getParent()).setVisibility(View.VISIBLE);
        ((View) binding.amountInput.getParent()).setVisibility(View.VISIBLE);
        updateControls();
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


    @Override public void onDestroyView() {
        binding = null;
        accounts = null;
        uid = null;
        accountIds.clear();
        super.onDestroyView();
    }
}
