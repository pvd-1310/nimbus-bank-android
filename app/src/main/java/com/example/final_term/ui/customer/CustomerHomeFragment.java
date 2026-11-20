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
import com.example.final_term.navigation.BottomNavigationBinder;

import android.widget.TextView;
import android.widget.Toast;

import java.util.List;

public final class CustomerHomeFragment extends Fragment {
    private FragmentCustomerHomeBinding binding;

    @Nullable @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle state) {
        binding = FragmentCustomerHomeBinding.inflate(inflater, container, false);
        return binding.getRoot();
    }

    @Override public void onViewCreated(@NonNull View view, @Nullable Bundle state) {
        super.onViewCreated(view, state);
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
        });
        binding.accountButton.setOnClickListener(v -> Navigation.findNavController(v).navigate(R.id.accountList));
        binding.historyButton.setOnClickListener(v -> Navigation.findNavController(v).navigate(R.id.transactionHistory));
        View.OnClickListener unavailable = v -> Toast.makeText(requireContext(),
                "Tính năng này sẽ mở ở phase backend tương ứng.", Toast.LENGTH_SHORT).show();
        binding.quickTransfer.setOnClickListener(unavailable);
        binding.quickDeposit.setOnClickListener(unavailable);
        binding.quickPayment.setOnClickListener(unavailable);
        binding.quickQr.setOnClickListener(unavailable);
        binding.notificationButton.setOnClickListener(v -> Navigation.findNavController(v).navigate(R.id.accountList));
        BottomNavigationBinder.bind(binding.bottomNavigation.getRoot(), Navigation.findNavController(view),
                R.id.customerHome, R.id.customerHome);
    }

    private void renderAccounts(ReadModelResult<List<ReadModels.AccountSummary>> result) {
        if (result == null) return;
        if (result.getData() == null) {
            binding.balanceText.setText("Không có dữ liệu");
            binding.accountStatusText.setText(result.getMessage());
            return;
        }
        long total = 0L;
        for (ReadModels.AccountSummary account : result.getData()) total += account.balanceMinor;
        binding.balanceText.setText(ReadSummaryFormatter.money(total));
        if (result.getStatus() == ReadModelResult.Status.CACHED) {
            binding.accountStatusText.setText(result.getMessage() + " · chỉ đọc");
        } else if (result.getStatus() == ReadModelResult.Status.EMPTY) {
            binding.accountStatusText.setText("Chưa có tài khoản được seed trong Firestore.");
        } else if (result.getStatus() == ReadModelResult.Status.ERROR) {
            binding.accountStatusText.setText(result.getMessage());
        } else {
            binding.accountStatusText.setText(result.getData().size() + " tài khoản đang hoạt động");
        }
    }

    private void renderTransactions(ReadModelResult<List<ReadModels.TransactionSummary>> result) {
        if (result == null || result.getData() == null) return;
        binding.recentTransactionsContent.removeAllViews();
        if (result.getData().isEmpty()) {
            addReadLine(binding.recentTransactionsContent, "Chưa có giao dịch", "Lịch sử sẽ xuất hiện khi có dữ liệu.");
            return;
        }
        for (ReadModels.TransactionSummary transaction : result.getData()) {
            addReadLine(binding.recentTransactionsContent,
                    transaction.type + " · " + transaction.status,
                    ReadSummaryFormatter.signedMoney(transaction.amountMinor));
        }
    }

    private void renderNotifications(ReadModelResult<List<ReadModels.NotificationSummary>> result) {
        if (result == null || result.getData() == null) return;
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
