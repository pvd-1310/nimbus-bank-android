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

import java.text.NumberFormat;
import java.util.Locale;

public class AccountDetailFragment extends Fragment {
    private FragmentAccountDetailBinding binding;
    private String currentAccountId = "";
    private String currentAccountType = "";
    private Long currentBalance = null;

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
        binding.detailText.setText(detailCopy(currentAccountType));

        binding.backButton.setOnClickListener(v -> Navigation.findNavController(v).navigateUp());

        binding.shareButton.setOnClickListener(v -> {
            String info = "Tài khoản Nimbus Bank\nLoại: " + Phase2Presentation.accountTitle(currentAccountType)
                    + "\nMã tài khoản: " + currentAccountId
                    + (currentBalance != null ? "\nSố dư: " + formatMoney(currentBalance, "VND") : "");
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
        binding.balanceText.setText("—");
        binding.statusText.setText(result.getStatus() == com.example.final_term.data.ReadModelResult.Status.LOADING
                ? "Đang tải dữ liệu…" : result.getStatus() == com.example.final_term.data.ReadModelResult.Status.ERROR
                ? result.getMessage() : "Không có tài khoản hoặc bạn không có quyền xem.");
        if (result.getData() == null) return;
        for (com.example.final_term.data.ReadModels.AccountSummary account : result.getData()) {
            if (!accountId.equals(account.id)) continue;
            currentBalance = account.balanceMinor;
            binding.titleText.setText(Phase2Presentation.accountTitle(account.type));
            binding.subtitleText.setText(Phase2Presentation.accountSubtitle(account.type));
            binding.balanceText.setText(formatMoney(account.balanceMinor, account.currency));
            binding.statusText.setText(account.status + " · chỉ đọc" +
                    (result.isReadOnly() ? " · " + result.getMessage() : ""));
            binding.detailText.setText("Mã tài khoản: " + account.id + "\n\n" + detailCopy(account.type));
            return;
        }
    }

    private String detailCopy(String type) {
        if ("SAVINGS".equalsIgnoreCase(type))
            return "Thông tin tiết kiệm Nimbus\n• Tiền gửi gốc: Được bảo hiểm tiền gửi\n• Lãi suất hiện hành: 5.8%/năm\n• Lợi nhuận dự kiến: Chưa cộng dồn vào số dư gốc\n• Kỳ hạn: 12 tháng\n\nLưu ý: Dữ liệu tính lãi do backend ngân hàng xác nhận, ứng dụng không tự động cộng lãi.";
        if ("MORTGAGE".equalsIgnoreCase(type))
            return "Thông tin khoản vay thế chấp\n• Dư nợ gốc hiện tại: Đang được cập nhật từ hệ thống tín dụng\n• Lãi suất vay: 8.5%/năm\n• Hình thức trả: Hàng tháng / 2 tuần\n• Trạng thái kỳ trả nợ: Đúng hạn\n\nLưu ý: Lịch thanh toán nợ do backend xác nhận.";
        return "Số dư và trạng thái được đọc từ nguồn dữ liệu được cấp quyền. Ứng dụng không tự thay đổi số dư.";
    }

    private String formatMoney(long amount, String currency) {
        return NumberFormat.getIntegerInstance(new Locale("vi", "VN")).format(amount) + " " + currency;
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        binding = null;
    }
}
