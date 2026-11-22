package com.example.final_term.ui.quickactions;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.navigation.NavController;
import androidx.navigation.fragment.NavHostFragment;

import com.example.final_term.R;
import com.example.final_term.databinding.BottomSheetQuickActionsBinding;
import com.example.final_term.ui.utilities.UtilitiesHubDialogFragment;
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

        binding.qaTransfer.setOnClickListener(v -> {
            dismiss();
            nav().navigate(R.id.transfer);
        });

        binding.qaDepositWithdraw.setOnClickListener(v -> {
            dismiss();
            nav().navigate(R.id.moneyRequest);
        });

        binding.qaBills.setOnClickListener(v -> {
            dismiss();
            UtilitiesHubDialogFragment dialog = new UtilitiesHubDialogFragment();
            dialog.show(getParentFragmentManager(), "UtilitiesHub");
        });

        binding.qaTopup.setOnClickListener(v -> {
            dismiss();
            UtilitiesHubDialogFragment dialog = new UtilitiesHubDialogFragment();
            dialog.show(getParentFragmentManager(), "UtilitiesHub");
        });

        binding.qaBooking.setOnClickListener(v -> {
            dismiss();
            new MaterialAlertDialogBuilder(requireContext())
                    .setTitle("Đặt vé & Khách sạn")
                    .setMessage("Dịch vụ đặt vé máy bay, phòng khách sạn và vé xem phim đang được liên kết với các đối tác du lịch.")
                    .setPositiveButton("Đóng", null)
                    .show();
        });

        binding.qaBranch.setOnClickListener(v -> {
            dismiss();
            nav().navigate(R.id.branchLocator);
        });

        binding.qaAi.setOnClickListener(v -> {
            dismiss();
            new MaterialAlertDialogBuilder(requireContext())
                    .setTitle("Trợ lý AI Nimbus")
                    .setMessage("Trợ lý AI phân tích tài chính thông minh đang trong giai đoạn hoàn thiện mô hình cục bộ.")
                    .setPositiveButton("Đóng", null)
                    .show();
        });

        binding.qaHistory.setOnClickListener(v -> {
            dismiss();
            nav().navigate(R.id.transactionHistory);
        });
    }

    private NavController nav() {
        return NavHostFragment.findNavController(this);
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        binding = null;
    }
}
