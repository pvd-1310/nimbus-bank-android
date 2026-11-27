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

import java.util.List;
import java.util.Objects;

public final class MoneyRequestFragment extends Fragment {
    private static final String DEPOSIT = "DEPOSIT";
    private static final String WITHDRAWAL = "WITHDRAWAL";
    private FragmentMoneyRequestBinding binding;
    private TransactionAccountPicker accountPicker;
    private String selectedAccountId;
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
        if (state != null) selectedAccountId = state.getString("selectedAccountId");
        accountPicker = new TransactionAccountPicker(binding.transactionAccount, binding.amountInput,
                binding.amountWarning, selectedAccountId, this::updateControls);
        String operation = getArguments() == null ? DEPOSIT : getArguments().getString("operation", DEPOSIT);
        binding.operationInput.setAdapter(adapter(List.of("Nạp tiền", "Rút tiền")));
        binding.operationInput.setSelection(WITHDRAWAL.equals(operation) ? 1 : 0);

        binding.backButton.setOnClickListener(v -> androidx.navigation.Navigation.findNavController(v).navigateUp());
        binding.helpButton.setOnClickListener(v -> {
            binding.statusText.setText(withdrawalSelected()
                    ? "Số dư giảm khi giao dịch rút mô phỏng hoàn tất."
                    : "Tiền được ghi có sau khi thanh toán thẻ hoàn tất.");
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
        updateOperationUi();
        binding.submitButton.setOnClickListener(v -> {
            AccountSummary account = accountPicker.selectedAccount();
            if (account == null) return;
            String amount = binding.amountInput.getText().toString().trim();
            if (!Phase2Presentation.validRequestAmount(amount)) {
                binding.amountInput.setError("Nhập số tiền VND nguyên hợp lệ, lớn hơn 0.");
                return;
            }
            long amountMinor = Long.parseLong(amount);
            if (accountPicker.exceedsAvailable(amountMinor)) return;
            TransactionFlowRequest flowRequest = withdrawalSelected()
                    ? TransactionFlowRequest.withdrawal(account.id, amountMinor, "VND")
                    : TransactionFlowRequest.deposit(account.id, amountMinor, "VND");
            Navigation.findNavController(v).navigate(R.id.transactionFlow,
                    flowRequest.withSourceLabel(accountPicker.label(account)).toBundle());
        });
        updateControls();
        app.getAuthRepository().observeAuthState().observe(getViewLifecycleOwner(), auth -> {
            String currentUid = auth != null && auth.getStatus() == AuthState.Status.SIGNED_IN
                    && auth.getUser() != null && auth.getUser().getRole() == UserRole.CUSTOMER
                    ? auth.getUser().getUid() : null;
            if (uid != null && Objects.equals(uid, currentUid)) {
                accountPicker.setOwnerName(auth.getUser().getFullName());
                return;
            }
            if (accounts != null) accounts.removeObservers(getViewLifecycleOwner());
            if (uid != null) accountPicker.clear();
            uid = currentUid;
            updateControls();
            if (uid == null) {
                binding.statusText.setText("Vui lòng đăng nhập bằng tài khoản khách hàng.");
                return;
            }
            accountPicker.setOwnerName(auth.getUser().getFullName());
            updateOperationStatus();
            accounts = app.getReadRepository().observeAccounts(uid);
            accounts.observe(getViewLifecycleOwner(), accountPicker::setAccounts);
        });
    }

    private ArrayAdapter<String> adapter(List<String> labels) {
        ArrayAdapter<String> adapter = new ArrayAdapter<>(requireContext(), R.layout.item_nimbus_spinner, labels);
        adapter.setDropDownViewResource(R.layout.item_nimbus_dropdown);
        return adapter;
    }

    private void updateControls() {
        binding.submitButton.setEnabled(uid != null && accountPicker.selectedAccount() != null);
        binding.submitButton.setText(withdrawalSelected() ? "Rút tiền" : "Nạp tiền");
    }

    private void selectOperation(int position) {
        binding.operationInput.setSelection(position);
        updateOperationUi();
    }

    private void updateOperationUi() {
        int position = binding.operationInput.getSelectedItemPosition() == 1 ? 1 : 0;
        boolean withdrawal = position == 1;
        binding.titleText.setText(operationLabel(withdrawal ? WITHDRAWAL : DEPOSIT));
        updateTabStyles(position);
        accountPicker.setRole(!withdrawal);
        updateOperationStatus();
    }

    private boolean withdrawalSelected() {
        return binding.operationInput.getSelectedItemPosition() == 1;
    }

    private void updateOperationStatus() {
        if (uid != null) {
            binding.statusText.setText(withdrawalSelected() ? "Giao dịch rút tiền mô phỏng." : "");
        }
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
        state.putString("selectedAccountId", accountPicker == null ? selectedAccountId : accountPicker.selectedId());
    }

    @Override public void onDestroyView() {
        selectedAccountId = accountPicker.selectedId();
        accountPicker.dismiss();
        accountPicker = null;
        binding = null;
        accounts = null;
        uid = null;
        super.onDestroyView();
    }
}
