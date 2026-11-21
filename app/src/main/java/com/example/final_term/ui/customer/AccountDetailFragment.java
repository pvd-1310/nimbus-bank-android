package com.example.final_term.ui.customer;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.navigation.Navigation;

import com.example.final_term.R;
import com.example.final_term.databinding.FragmentAccountDetailBinding;

import java.text.NumberFormat;
import java.util.Locale;

public class AccountDetailFragment extends Fragment {
    private FragmentAccountDetailBinding binding;

    @Nullable @Override public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                                                   @Nullable Bundle state) {
        binding = FragmentAccountDetailBinding.inflate(inflater, container, false);
        return binding.getRoot();
    }

    @Override public void onViewCreated(@NonNull View view, @Nullable Bundle state) {
        super.onViewCreated(view, state);
        Bundle args = getArguments();
        String type = args == null ? "UNKNOWN" : args.getString("accountType", "UNKNOWN");
        binding.titleText.setText(Phase2Presentation.accountTitle(type));
        binding.subtitleText.setText(Phase2Presentation.accountSubtitle(type));
        binding.balanceText.setText("—");
        binding.statusText.setText("Đang tải dữ liệu…");
        binding.detailText.setText(detailCopy(type));
        String accountId = args == null ? "" : args.getString("accountId", "");
        if (accountId.isEmpty()) {
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
            source.observe(getViewLifecycleOwner(), result -> renderAccount(result, accountId));
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
        if ("SAVINGS".equalsIgnoreCase(type)) return "Thông tin tiết kiệm\nTiền gốc: chưa có dữ liệu chi tiết\nLãi suất: chưa có dữ liệu\nKỳ hạn / ngày đáo hạn: chưa có dữ liệu\nLợi nhuận dự kiến: chưa khả dụng\n\nCần kết nối API chi tiết tiết kiệm. Không tự tính hoặc cộng lãi vào số dư.";
        if ("MORTGAGE".equalsIgnoreCase(type)) return "Thông tin khoản vay\nDư nợ gốc: chưa có dữ liệu chi tiết\nLãi suất / kỳ hạn: chưa có dữ liệu\nKỳ thanh toán tiếp theo: chưa có dữ liệu\nLịch trả góp: chưa khả dụng\n\nCần kết nối API chi tiết khoản vay. Trạng thái thanh toán do backend xác nhận.";
        return "Số dư và trạng thái được đọc từ nguồn dữ liệu được cấp quyền. Ứng dụng không tự thay đổi số dư.";
    }

    private String formatMoney(long amount, String currency) {
        return NumberFormat.getIntegerInstance(new Locale("vi", "VN")).format(amount) + " " + currency;
    }

    @Override public void onDestroyView() { super.onDestroyView(); binding = null; }
}
