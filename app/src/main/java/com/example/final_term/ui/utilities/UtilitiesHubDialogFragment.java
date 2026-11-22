package com.example.final_term.ui.utilities;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.DialogFragment;

import com.example.final_term.core.FinalTermApplication;
import com.example.final_term.databinding.DialogUtilitiesHubBinding;

public class UtilitiesHubDialogFragment extends DialogFragment {
    private DialogUtilitiesHubBinding binding;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        binding = DialogUtilitiesHubBinding.inflate(inflater, container, false);
        return binding.getRoot();
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        binding.closeDialogBtn.setOnClickListener(v -> dismiss());

        binding.utilElectricity.setOnClickListener(v -> {
            binding.lookupTitle.setText("Tra cứu hóa đơn điện (EVN)");
            binding.customerCodeInput.setHint("Nhập mã khách hàng (VD: PE01000123456)");
        });

        binding.utilWater.setOnClickListener(v -> {
            binding.lookupTitle.setText("Tra cứu hóa đơn nước sạch (Sawaco)");
            binding.customerCodeInput.setHint("Nhập mã danh bộ (VD: 0102030405)");
        });

        binding.utilMobile.setOnClickListener(v -> {
            binding.lookupTitle.setText("Nạp tiền điện thoại trực tiếp");
            binding.customerCodeInput.setHint("Nhập số điện thoại (VD: 0987654321)");
        });

        binding.utilInternet.setOnClickListener(v -> {
            binding.lookupTitle.setText("Thanh toán cước Internet (VNPT / Viettel / FPT)");
            binding.customerCodeInput.setHint("Nhập mã hợp đồng Internet");
        });

        binding.lookupButton.setOnClickListener(v -> {
            String code = binding.customerCodeInput.getText() == null ? "" : binding.customerCodeInput.getText().toString().trim();
            if (code.isEmpty()) {
                binding.customerCodeInput.setError("Vui lòng nhập mã tra cứu");
                return;
            }
            binding.lookupResultText.setVisibility(View.VISIBLE);
            binding.lookupResultText.setText("Đang kiểm tra hóa đơn từ cổng thanh toán…");

            FinalTermApplication app = (FinalTermApplication) requireActivity().getApplication();
            app.getAppContainer().getBackendApiClient().get("/v1/capabilities")
                    .addOnSuccessListener(resp -> {
                        if (binding == null) return;
                        binding.lookupResultText.setText("Không tìm thấy kỳ hóa đơn chưa thanh toán cho mã: " + code + "\n(Cổng thanh toán: đối tác đang đồng bộ)");
                    })
                    .addOnFailureListener(err -> {
                        if (binding == null) return;
                        binding.lookupResultText.setText("Cổng thanh toán dịch vụ tiện ích đang bảo trì hoặc chưa kết nối backend.");
                    });
        });
    }

    @Override
    public void onStart() {
        super.onStart();
        if (getDialog() != null && getDialog().getWindow() != null) {
            getDialog().getWindow().setLayout(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
            );
            getDialog().getWindow().setBackgroundDrawableResource(android.R.color.transparent);
        }
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        binding = null;
    }
}
