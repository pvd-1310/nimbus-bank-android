package com.example.final_term.ui.customer;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import com.example.final_term.R;
import com.example.final_term.auth.AuthState;
import com.example.final_term.auth.UserRole;
import com.example.final_term.core.FinalTermApplication;
import com.example.final_term.data.FirestoreReadRepository;
import com.example.final_term.data.ReadModelResult;
import com.example.final_term.data.ReadModels;
import com.example.final_term.data.ReadSummaryFormatter;
import com.example.final_term.databinding.FragmentCustomerHomeBinding;
import androidx.navigation.Navigation;

import android.widget.TextView;
import android.widget.Toast;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

public final class CustomerHomeFragment extends Fragment {
    private FragmentCustomerHomeBinding binding;
    private final List<ReadModels.AccountSummary> balanceAccounts = new ArrayList<>();
    private String selectedAccountId;

    @Nullable @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle state) {
        binding = FragmentCustomerHomeBinding.inflate(inflater, container, false);
        return binding.getRoot();
    }

    @Override public void onViewCreated(@NonNull View view, @Nullable Bundle state) {
        super.onViewCreated(view, state);
        if (state != null) selectedAccountId = state.getString("balanceAccountId");
        binding.balanceAccountSelector.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(AdapterView<?> parent, View item, int position, long id) {
                showAccountBalance(position);
            }

            @Override public void onNothingSelected(AdapterView<?> parent) { }
        });
        FinalTermApplication app = (FinalTermApplication) requireActivity().getApplication();
        app.getAppContainer().getAuthRepository().observeAuthState().observe(getViewLifecycleOwner(), authState -> {
            if (authState == null || authState.getStatus() != AuthState.Status.SIGNED_IN
                    || authState.getUser() == null || authState.getUser().getRole() != UserRole.CUSTOMER) {
                requireActivity().getOnBackPressedDispatcher().onBackPressed();
                return;
            }
            binding.roleText.setText(getString(R.string.role_customer));
            String fullName = authState.getUser().getFullName();
            binding.identityText.setText(fullName == null || fullName.trim().isEmpty()
                    ? authState.getUser().getEmail() : fullName);
            FirestoreReadRepository reads = app.getAppContainer().getReadRepository();
            reads.observeAccounts(authState.getUser().getUid()).observe(getViewLifecycleOwner(), this::renderAccounts);
            reads.observeTransactions(authState.getUser().getUid()).observe(getViewLifecycleOwner(), this::renderTransactions);
            reads.observeNotifications(authState.getUser().getUid()).observe(getViewLifecycleOwner(), this::renderNotifications);
            app.getAppContainer().getBackendApiClient().get("/v1/accounts")
                    .addOnSuccessListener(response -> {
                        if (binding == null) return;
                        binding.backendStatusText.setText(
                                response.statusCode >= 200 && response.statusCode < 300
                                        ? "Backend production: đã xác thực quyền truy cập"
                                        : "Backend production: HTTP " + response.statusCode);
                    })
                    .addOnFailureListener(error -> {
                        if (binding == null) return;
                        binding.backendStatusText.setText(
                                "Backend production chưa kết nối · vui lòng thử lại sau");
                    });
        });
        binding.accountButton.setOnClickListener(v -> Navigation.findNavController(v).navigate(R.id.accountList));
        binding.historyButton.setOnClickListener(v -> Navigation.findNavController(v).navigate(R.id.transactionHistory));
        binding.quickTransfer.setOnClickListener(v -> Navigation.findNavController(v).navigate(R.id.transfer));
        binding.quickDeposit.setOnClickListener(v -> {
            Bundle args = new Bundle();
            args.putString("operation", "DEPOSIT");
            Navigation.findNavController(v).navigate(R.id.moneyRequest, args);
        });
        View.OnClickListener unavailable = v -> Toast.makeText(requireContext(),
                "Chức năng này chưa được bật trên backend production.", Toast.LENGTH_SHORT).show();
        binding.quickPayment.setOnClickListener(unavailable);
        binding.quickQr.setOnClickListener(unavailable);
        binding.notificationButton.setOnClickListener(v -> Navigation.findNavController(v).navigate(R.id.accountList));
    }

    private void renderAccounts(ReadModelResult<List<ReadModels.AccountSummary>> result) {
        if (result == null || binding == null) return;
        balanceAccounts.clear();
        if (result.getData() == null) {
            binding.balanceAccountSelector.setVisibility(View.GONE);
            binding.balanceText.setText(result.getStatus() == ReadModelResult.Status.LOADING ? "Đang tải…" : "—");
            binding.accountStatusText.setText(result.getMessage());
            return;
        }
        for (ReadModels.AccountSummary account : result.getData()) {
            if ("ACTIVE".equalsIgnoreCase(account.status)) balanceAccounts.add(account);
        }
        balanceAccounts.sort(Comparator
                .comparing((ReadModels.AccountSummary account) -> !"CHECKING".equalsIgnoreCase(account.type))
                .thenComparing(account -> account.id));
        if (balanceAccounts.isEmpty()) {
            binding.balanceAccountSelector.setVisibility(View.GONE);
            binding.balanceText.setText("—");
            binding.accountStatusText.setText("Chưa có tài khoản hoạt động.");
            return;
        }
        List<String> labels = new ArrayList<>();
        int selected = 0;
        for (int i = 0; i < balanceAccounts.size(); i++) {
            ReadModels.AccountSummary account = balanceAccounts.get(i);
            labels.add(account.id + " · " + account.currency);
            if (account.id.equals(selectedAccountId)) selected = i;
        }
        ArrayAdapter<String> adapter = new ArrayAdapter<>(requireContext(), android.R.layout.simple_spinner_item, labels);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        binding.balanceAccountSelector.setAdapter(adapter);
        binding.balanceAccountSelector.setSelection(selected, false);
        binding.balanceAccountSelector.setVisibility(View.VISIBLE);
        showAccountBalance(selected);
        if (result.getStatus() == ReadModelResult.Status.CACHED) {
            binding.accountStatusText.setText(result.getMessage() + " · chỉ đọc");
        } else {
            binding.accountStatusText.setText(balanceAccounts.size() + " tài khoản đang hoạt động");
        }
    }

    private void showAccountBalance(int position) {
        if (binding == null || position < 0 || position >= balanceAccounts.size()) return;
        ReadModels.AccountSummary account = balanceAccounts.get(position);
        selectedAccountId = account.id;
        binding.balanceText.setText(account.availableMinor == null ? "—" :
                ReadSummaryFormatter.formatCurrency(account.availableMinor, account.currency));
    }

    @Override public void onSaveInstanceState(@NonNull Bundle state) {
        super.onSaveInstanceState(state);
        state.putString("balanceAccountId", selectedAccountId);
    }

    private void renderTransactions(ReadModelResult<List<ReadModels.TransactionSummary>> result) {
        if (result == null || result.getData() == null || binding == null) return;
        binding.recentTransactionsContent.removeAllViews();
        if (result.getData().isEmpty()) {
            addReadLine(binding.recentTransactionsContent, "Chưa có giao dịch", "Lịch sử sẽ xuất hiện khi có dữ liệu.");
            return;
        }
        for (ReadModels.TransactionSummary transaction : result.getData()) {
            addReadLine(binding.recentTransactionsContent,
                    ReadSummaryFormatter.transactionTitle(transaction),
                    ReadSummaryFormatter.transactionDetail(transaction));
        }
    }

    private void renderNotifications(ReadModelResult<List<ReadModels.NotificationSummary>> result) {
        if (result == null || result.getData() == null || binding == null) return;
        binding.notificationsContent.removeAllViews();
        if (result.getData().isEmpty()) {
            addReadLine(binding.notificationsContent, "Chưa có thông báo", "");
            return;
        }
        for (ReadModels.NotificationSummary notification : result.getData()) {
            addReadLine(binding.notificationsContent,
                    (notification.read ? "Đã đọc · " : "Mới · ") + notification.title,
                    notification.body);
        }
    }

    private void addReadLine(android.widget.LinearLayout container, String title, String detail) {
        TextView line = new TextView(requireContext());
        line.setText(title + (detail.isEmpty() ? "" : "\n" + detail));
        line.setTextColor(requireContext().getColor(R.color.ink));
        line.setTextSize(15);
        line.setPadding(14, 14, 14, 14);
        line.setBackgroundResource(R.drawable.glass_tile);
        container.addView(line);
    }

    @Override public void onDestroyView() { super.onDestroyView(); binding = null; }
}
