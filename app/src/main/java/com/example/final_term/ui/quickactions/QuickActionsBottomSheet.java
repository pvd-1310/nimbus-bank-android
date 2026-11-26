package com.example.final_term.ui.quickactions;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.text.Editable;
import android.text.TextWatcher;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.navigation.NavController;
import androidx.navigation.Navigation;

import com.example.final_term.R;
import com.example.final_term.databinding.BottomSheetQuickActionsBinding;
import com.google.android.material.bottomsheet.BottomSheetDialogFragment;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

public class QuickActionsBottomSheet extends BottomSheetDialogFragment {
    private BottomSheetQuickActionsBinding binding;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        binding = BottomSheetQuickActionsBinding.inflate(inflater, container, false);
        return binding.getRoot();
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        binding.closeSheetBtn.setOnClickListener(v -> dismiss());
        setupSearch();

        binding.qaTransfer.setOnClickListener(v -> navigateTo(R.id.transfer));
        binding.qaDepositWithdraw.setOnClickListener(v -> navigateTo(R.id.moneyRequest));
        binding.qaBills.setOnClickListener(v -> navigateTo(R.id.utilitiesHub));
        binding.qaTopup.setOnClickListener(v -> navigateTo(R.id.mobileTopup));
        binding.qaBooking.setOnClickListener(v -> navigateTo(R.id.flightBooking));
        binding.qaBranch.setOnClickListener(v -> navigateTo(R.id.branchLocator));

        binding.qaAi.setOnClickListener(v -> {
            var manager = getParentFragmentManager();
            dismiss();
            new com.example.final_term.ui.customer.ChatbotDialogFragment().show(manager, "Chatbot");
        });

        binding.qaHistory.setOnClickListener(v -> navigateTo(R.id.transactionHistory));
    }

    private void navigateTo(int destinationId) {
        NavController controller = nav();
        dismiss();
        controller.navigate(destinationId);
    }

    private NavController nav() {
        return Navigation.findNavController(requireActivity(), R.id.nav_host_fragment);
    }

    private void setupSearch() {
        View[] actions = {binding.qaTransfer, binding.qaDepositWithdraw, binding.qaBills,
                binding.qaTopup, binding.qaBooking, binding.qaBranch, binding.qaAi, binding.qaHistory};
        String[] labels = {getString(R.string.quick_action_transfer), getString(R.string.quick_action_deposit_withdraw),
                getString(R.string.quick_action_bill), getString(R.string.quick_action_topup),
                getString(R.string.quick_action_booking), getString(R.string.quick_action_branch),
                getString(R.string.quick_action_ai), getString(R.string.quick_action_history)};
        binding.quickActionSearch.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) { }
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {
                String query = s.toString().trim().toLowerCase(java.util.Locale.ROOT);
                for (int i = 0; i < actions.length; i++) {
                    actions[i].setVisibility(query.isEmpty()
                            || labels[i].toLowerCase(java.util.Locale.ROOT).contains(query)
                            ? View.VISIBLE : View.GONE);
                }
            }
            @Override public void afterTextChanged(Editable s) { }
        });
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        binding = null;
    }
}
