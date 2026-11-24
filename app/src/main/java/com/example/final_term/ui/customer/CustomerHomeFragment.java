package com.example.final_term.ui.customer;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;
import androidx.navigation.Navigation;

import com.example.final_term.R;
import com.example.final_term.auth.AuthState;
import com.example.final_term.auth.UserRole;
import com.example.final_term.core.FinalTermApplication;
import com.example.final_term.data.FirestoreReadRepository;
import com.example.final_term.data.ReadModelResult;
import com.example.final_term.data.ReadModels;
import com.example.final_term.data.ReadSummaryFormatter;
import com.example.final_term.databinding.FragmentCustomerHomeBinding;
import com.example.final_term.databinding.ItemTransactionRowBinding;
import com.example.final_term.ui.quickactions.QuickActionsBottomSheet;
import com.example.final_term.ui.utilities.UtilitiesHubDialogFragment;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

public final class CustomerHomeFragment extends Fragment {
    private FragmentCustomerHomeBinding binding;
    private final List<ReadModels.AccountSummary> balanceAccounts = new ArrayList<>();
    private String selectedAccountId;
    private String currentBalanceCurrency = "VND";
    private boolean isBalanceHidden = false;
    private Long currentBalanceMinor = null;
    private int balanceRequest;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle state) {
        binding = FragmentCustomerHomeBinding.inflate(inflater, container, false);
        return binding.getRoot();
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle state) {
        super.onViewCreated(view, state);
        if (state != null) {
            selectedAccountId = state.getString("balanceAccountId");
            isBalanceHidden = state.getBoolean("isBalanceHidden", false);
        }

        binding.toggleBalanceButton.setOnClickListener(v -> {
            isBalanceHidden = !isBalanceHidden;
            updateBalanceDisplay();
        });

        binding.accountDetailsArrowBtn.setOnClickListener(v -> {
            if (selectedAccountId != null && !balanceAccounts.isEmpty()) {
                Bundle args = new Bundle();
                args.putString("accountId", selectedAccountId);
                ReadModels.AccountSummary acc = getSelectedAccount();
                args.putString("accountType", acc != null ? acc.type : "CHECKING");
                Navigation.findNavController(v).navigate(R.id.accountDetail, args);
            } else {
                Navigation.findNavController(v).navigate(R.id.accountList);
            }
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
            String displayName = (fullName == null || fullName.trim().isEmpty())
                    ? authState.getUser().getEmail() : fullName;
            binding.identityText.setText(displayName);

            // Compute Initials for Avatar
            String initials = computeInitials(displayName);
            binding.avatarText.setText(initials);

            FirestoreReadRepository reads = app.getAppContainer().getReadRepository();
            reads.observeAccounts(authState.getUser().getUid()).observe(getViewLifecycleOwner(), this::renderAccounts);
            reads.observeTransactions(authState.getUser().getUid()).observe(getViewLifecycleOwner(), this::renderTransactions);
            reads.observeNotifications(authState.getUser().getUid()).observe(getViewLifecycleOwner(), this::renderNotifications);

            app.getAppContainer().getBackendApiClient().get("/accounts")
                    .addOnSuccessListener(response -> {
                        if (binding == null) return;
                        binding.backendStatusText.setText(
                                response.statusCode >= 200 && response.statusCode < 300
                                        ? "Backend đồ án: đã xác thực quyền truy cập"
                                        : "Backend đồ án: HTTP " + response.statusCode);
                    })
                    .addOnFailureListener(error -> {
                        if (binding == null) return;
                        binding.backendStatusText.setText(
                                "Backend đồ án chưa kết nối");
                    });
        });

        // Navigation CTA handlers
        binding.accountButton.setOnClickListener(v -> Navigation.findNavController(v).navigate(R.id.accountList));
        binding.historyButton.setOnClickListener(v -> Navigation.findNavController(v).navigate(R.id.transactionHistory));
        binding.branchLocatorButton.setOnClickListener(v -> Navigation.findNavController(v).navigate(R.id.branchLocator));

        binding.chatButton.setOnClickListener(v ->
                new ChatbotDialogFragment().show(getChildFragmentManager(), "Chatbot"));

        // Quick action round buttons
        binding.quickTransfer.setOnClickListener(v -> Navigation.findNavController(v).navigate(R.id.transfer));
        binding.quickDeposit.setOnClickListener(v -> {
            Bundle args = new Bundle();
            args.putString("operation", "DEPOSIT");
            Navigation.findNavController(v).navigate(R.id.moneyRequest, args);
        });

        binding.quickPayment.setOnClickListener(v -> {
            UtilitiesHubDialogFragment dialog = new UtilitiesHubDialogFragment();
            Bundle arguments = new Bundle();
            ReadModels.AccountSummary account = getSelectedAccount();
            if (account != null && "CHECKING".equals(account.type))
                arguments.putString("sourceAccountId", account.id);
            dialog.setArguments(arguments);
            dialog.show(getChildFragmentManager(), "UtilitiesHub");
        });

        binding.quickQr.setOnClickListener(v -> {
            new MaterialAlertDialogBuilder(requireContext())
                    .setTitle("Quét mã QR")
                    .setMessage("Tính năng Quét QR Pay & Nhận tiền chuẩn bị khả dụng trên backend production.")
                    .setPositiveButton("Đóng", null)
                    .show();
        });

        binding.notificationButton.setOnClickListener(v -> {
            Bundle args = new Bundle();
            args.putInt("activeNavItem", R.id.nav_accounts);
            Navigation.findNavController(v).navigate(R.id.accountList, args);
        });
    }

    private String computeInitials(String name) {
        if (name == null || name.trim().isEmpty()) return "NB";
        String[] parts = name.trim().split("\\s+");
        if (parts.length == 1) {
            return parts[0].substring(0, Math.min(2, parts[0].length())).toUpperCase();
        }
        return ("" + parts[0].charAt(0) + parts[parts.length - 1].charAt(0)).toUpperCase();
    }

    private ReadModels.AccountSummary getSelectedAccount() {
        for (ReadModels.AccountSummary acc : balanceAccounts) {
            if (acc.id.equals(selectedAccountId)) return acc;
        }
        return balanceAccounts.isEmpty() ? null : balanceAccounts.get(0);
    }

    private void renderAccounts(ReadModelResult<List<ReadModels.AccountSummary>> result) {
        if (result == null || binding == null) return;
        balanceAccounts.clear();
        if (result.getData() == null) {
            balanceRequest++;
            currentBalanceMinor = null;
            binding.accountStatusText.setVisibility(View.GONE);
            binding.balanceText.setText(result.getStatus() == ReadModelResult.Status.LOADING ? "Đang tải…" : "—");
            return;
        }
        for (ReadModels.AccountSummary account : result.getData()) {
            if ("ACTIVE".equalsIgnoreCase(account.status)) balanceAccounts.add(account);
        }
        balanceAccounts.sort((left, right) -> {
            int paymentFirst = Boolean.compare(!"CHECKING".equalsIgnoreCase(left.type),
                    !"CHECKING".equalsIgnoreCase(right.type));
            return paymentFirst != 0 ? paymentFirst : left.id.compareTo(right.id);
        });

        if (balanceAccounts.isEmpty()) {
            balanceRequest++;
            currentBalanceMinor = null;
            binding.balanceText.setText("—");
            binding.accountStatusText.setVisibility(View.GONE);
            return;
        }
        ReadModels.AccountSummary paymentAccount = null;
        for (ReadModels.AccountSummary account : balanceAccounts) {
            if ("CHECKING".equalsIgnoreCase(account.type)) {
                paymentAccount = account;
                break;
            }
        }
        if (paymentAccount == null) {
            selectedAccountId = null;
            currentBalanceMinor = null;
            binding.balanceText.setText("—");
            binding.accountStatusText.setVisibility(View.GONE);
            return;
        }
        selectedAccountId = paymentAccount.id;
        currentBalanceMinor = paymentAccount.availableMinor != null ? paymentAccount.availableMinor : paymentAccount.balanceMinor;
        currentBalanceCurrency = paymentAccount.currency;
        binding.accountStatusText.setVisibility(View.GONE);
        updateBalanceDisplay();
        balanceRequest++;
    }

    private void loadStripeBalance(String accountId) {
        FragmentCustomerHomeBinding current = binding;
        if (current == null) return;
        int request = ++balanceRequest;
        currentBalanceMinor = null;
        currentBalanceCurrency = "USD";
        current.accountStatusText.setVisibility(View.GONE);
        updateBalanceDisplay();
        FinalTermApplication app = (FinalTermApplication) requireActivity().getApplication();
        app.getAppContainer().getBackendApiClient()
                .get("/accounts/" + android.net.Uri.encode(accountId) + "/stripe-sandbox")
                .addOnCompleteListener(task -> {
                    if (binding != current || request != balanceRequest || !accountId.equals(selectedAccountId)) return;
                    if (!task.isSuccessful() || task.getResult() == null || task.getResult().statusCode != 200) {
                        current.accountStatusText.setVisibility(View.GONE);
                        return;
                    }
                    try {
                        JSONObject snapshot = new JSONObject(task.getResult().body);
                        if (snapshot.has("availableMinor")) {
                            currentBalanceMinor = snapshot.getLong("availableMinor");
                            currentBalanceCurrency = snapshot.optString("currency", "USD");
                            updateBalanceDisplay();
                            return;
                        }
                        if ("pending".equals(snapshot.optString("status"))) {
                            current.accountStatusText.postDelayed(() -> {
                                if (binding == current && isResumed() && request == balanceRequest)
                                    loadStripeBalance(accountId);
                            }, 5000);
                        }
                    } catch (JSONException ignored) {
                        current.accountStatusText.setVisibility(View.GONE);
                    }
                });
    }

    @Override
    public void onResume() {
        super.onResume();
        if (binding != null) updateBalanceDisplay();
    }

    private void updateBalanceDisplay() {
        if (binding == null) return;
        if (isBalanceHidden) {
            binding.balanceText.setText("••••••••");
            binding.toggleBalanceButton.setImageResource(R.drawable.ic_eye_slash);
        } else {
            binding.toggleBalanceButton.setImageResource(R.drawable.ic_eye);
            if (currentBalanceMinor == null) {
                binding.balanceText.setText("—");
            } else {
                binding.balanceText.setText(ReadSummaryFormatter.formatCurrency(currentBalanceMinor,
                        currentBalanceCurrency));
            }
        }
    }

    @Override
    public void onSaveInstanceState(@NonNull Bundle state) {
        super.onSaveInstanceState(state);
        state.putString("balanceAccountId", selectedAccountId);
        state.putBoolean("isBalanceHidden", isBalanceHidden);
    }

    private void renderTransactions(ReadModelResult<List<ReadModels.TransactionSummary>> result) {
        if (result == null || result.getData() == null || binding == null) return;
        binding.recentTransactionsContent.removeAllViews();
        if (result.isReadOnly()) {
            TextView status = new TextView(requireContext());
            status.setText(result.getMessage());
            binding.recentTransactionsContent.addView(status);
        }
        List<ReadModels.TransactionSummary> ledgerRows = result.getData();
        if (ledgerRows.isEmpty()) {
            TextView emptyText = new TextView(requireContext());
            emptyText.setText("Chưa có giao dịch gần đây.");
            emptyText.setTextColor(ContextCompat.getColor(requireContext(), R.color.ink_muted));
            emptyText.setPadding(0, 16, 0, 16);
            binding.recentTransactionsContent.addView(emptyText);
            return;
        }
        // Take at most 4 recent transactions for Home preview
        int limit = Math.min(4, ledgerRows.size());
        for (int i = 0; i < limit; i++) {
            ReadModels.TransactionSummary tx = ledgerRows.get(i);
            ItemTransactionRowBinding rowBinding = ItemTransactionRowBinding.inflate(
                    getLayoutInflater(), binding.recentTransactionsContent, false);

            boolean isIncoming = tx.isIncoming();
            rowBinding.titleText.setText(ReadSummaryFormatter.transactionTitle(tx));
            rowBinding.subtitleText.setText(tx.id);

            String amountFormatted = ReadSummaryFormatter.formatCurrency(tx.amountMinor, tx.currency);
            if (isIncoming) {
                rowBinding.amountText.setText("+" + amountFormatted);
                rowBinding.amountText.setTextColor(ContextCompat.getColor(requireContext(), R.color.success));
                rowBinding.transactionIcon.setImageResource(R.drawable.ic_trending_up);
                rowBinding.transactionIcon.setColorFilter(ContextCompat.getColor(requireContext(), R.color.success));
            } else {
                rowBinding.amountText.setText("−" + amountFormatted);
                rowBinding.amountText.setTextColor(ContextCompat.getColor(requireContext(), R.color.ink));
                rowBinding.transactionIcon.setImageResource(R.drawable.ic_nav_send);
                rowBinding.transactionIcon.setColorFilter(ContextCompat.getColor(requireContext(), R.color.navy));
            }

            binding.recentTransactionsContent.addView(rowBinding.getRoot());
        }
    }

    private void renderNotifications(ReadModelResult<List<ReadModels.NotificationSummary>> result) {
        if (result == null || result.getData() == null || binding == null) return;
        binding.notificationsContent.removeAllViews();
        int unreadCount = 0;
        for (ReadModels.NotificationSummary notification : result.getData()) {
            if (!notification.read) unreadCount++;
        }
        binding.notificationBadge.setVisibility(unreadCount > 0 ? View.VISIBLE : View.GONE);
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        binding = null;
    }
}
