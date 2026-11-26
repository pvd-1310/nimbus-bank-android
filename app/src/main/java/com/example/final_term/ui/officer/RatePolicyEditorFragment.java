package com.example.final_term.ui.officer;

import android.app.DatePickerDialog;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.EditText;
import android.widget.Spinner;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;
import androidx.navigation.Navigation;
import com.google.android.material.textfield.TextInputLayout;
import com.example.final_term.R;
import com.example.final_term.auth.AuthState;
import com.example.final_term.auth.UserRole;
import com.example.final_term.core.FinalTermApplication;
import com.example.final_term.data.BackendApiClient;
import org.json.JSONArray;
import org.json.JSONObject;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.Locale;

public final class RatePolicyEditorFragment extends Fragment {
    private Spinner rateTypeSpinner;
    private EditText rateInput, dateInput, reasonInput;
    private TextInputLayout dateInputLayout;
    private TextView status, rateTypeError, rateError, dateError, reasonError;
    private final ArrayList<String> rateTypes = new ArrayList<>();
    private final ArrayList<String> rateTypeLabels = new ArrayList<>();
    private BackendApiClient api;
    private long selectedEffectiveFromUtc = -1L;
    private boolean selectedDateIsToday;

    @Nullable @Override public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle state) {
        return inflater.inflate(R.layout.fragment_rate_policy_editor, container, false);
    }

    @Override public void onViewCreated(@NonNull View view, @Nullable Bundle state) {
        rateTypeSpinner = view.findViewById(R.id.rate_type_spinner);
        rateInput = view.findViewById(R.id.rate_input);
        dateInput = view.findViewById(R.id.date_input);
        dateInputLayout = view.findViewById(R.id.date_input_layout);
        reasonInput = view.findViewById(R.id.reason_input);
        status = view.findViewById(R.id.status_text);
        rateTypeError = view.findViewById(R.id.rate_type_error);
        rateError = view.findViewById(R.id.rate_error);
        dateError = view.findViewById(R.id.date_error);
        reasonError = view.findViewById(R.id.reason_error);
        view.findViewById(R.id.back_button).setOnClickListener(v -> Navigation.findNavController(v).navigateUp());
        dateInput.setOnClickListener(v -> showDatePicker());
        dateInputLayout.setEndIconOnClickListener(v -> showDatePicker());
        FinalTermApplication app = (FinalTermApplication) requireActivity().getApplication();
        app.getAppContainer().getAuthRepository().observeAuthState().observe(getViewLifecycleOwner(), auth -> {
            if (auth == null || auth.getStatus() != AuthState.Status.SIGNED_IN || auth.getUser() == null || auth.getUser().getRole() != UserRole.OFFICER) {
                requireActivity().getOnBackPressedDispatcher().onBackPressed(); return;
            }
            api = app.getAppContainer().getBackendApiClient();
            showRateTypes();
        });
        view.findViewById(R.id.save_button).setOnClickListener(v -> save());
    }

    private void showRateTypes() {
        rateTypes.clear();
        rateTypeLabels.clear();
        rateTypes.add("SAVINGS");
        rateTypeLabels.add("Lãi suất tiết kiệm");
        rateTypes.add("LOAN");
        rateTypeLabels.add("Lãi vay");
        ArrayAdapter<String> adapter = new ArrayAdapter<>(requireContext(), android.R.layout.simple_spinner_item, rateTypeLabels);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        rateTypeSpinner.setAdapter(adapter);
        status.setText("Chọn hôm nay để áp dụng ngay, hoặc chọn ngày tương lai để đặt lịch cho toàn ngân hàng.");
    }

    private void save() {
        clearErrors();
        if (rateTypes.isEmpty() || rateTypeSpinner.getSelectedItemPosition() < 0) { rateTypeError.setText("Chọn lãi suất tiết kiệm hoặc lãi vay."); return; }
        double percent;
        try { percent = Double.parseDouble(rateInput.getText().toString().trim().replace(',', '.')); if (!Double.isFinite(percent) || percent < 0) throw new Exception(); }
        catch (Exception e) { rateError.setText("Nhập lãi suất năm lớn hơn hoặc bằng 0."); return; }
        long effectiveFrom = selectedDateIsToday ? System.currentTimeMillis() : selectedEffectiveFromUtc;
        if (effectiveFrom <= 0 || (!selectedDateIsToday && effectiveFrom <= System.currentTimeMillis())) {
            dateError.setText("Chọn hôm nay hoặc một ngày trong tương lai.");
            return;
        }
        String reason = reasonInput.getText().toString().trim();
        if (reason.isEmpty()) { reasonError.setText("Vui lòng nhập lý do điều chỉnh."); return; }
        try {
            JSONObject body = new JSONObject(); body.put("rateType", rateTypes.get(rateTypeSpinner.getSelectedItemPosition()));
            body.put("annualRateBasisPoints", (int) Math.round(percent * 100)); body.put("effectiveFrom", effectiveFrom); body.put("reason", reason);
            status.setText("Đang lưu chính sách…");
            api.post("/officer/rate-policies", body.toString()).addOnCompleteListener(task -> {
                if (!isAdded()) return;
                if (task.isSuccessful() && task.getResult() != null && task.getResult().statusCode >= 200 && task.getResult().statusCode < 300) {
                    status.setTextColor(ContextCompat.getColor(requireContext(), R.color.navy));
                    status.setText("Đã lưu chính sách lãi suất.");
                    new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(() -> {
                        if (isAdded()) Navigation.findNavController(requireView()).navigateUp();
                    }, 700);
                } else {
                    status.setText("Chưa thể lưu chính sách. Kiểm tra thông tin rồi thử lại.");
                    String code = "";
                    try { code = new JSONObject(task.getResult().body).optString("error"); } catch (Exception ignored) { }
                    if ("INVALID_RATE_TYPE".equals(code)) rateTypeError.setText("Chọn lãi suất tiết kiệm hoặc lãi vay.");
                    else if ("INVALID_TERMS".equals(code)) {
                        rateError.setText("Lãi suất vượt giới hạn ngân hàng cho phép.");
                        dateError.setText("Ngày áp dụng cần ở trong tương lai.");
                    }
                    else status.setText("Chưa thể lưu chính sách. Hãy kiểm tra kết nối rồi thử lại.");
                }
            });
        } catch (Exception e) { status.setText("Không thể gửi yêu cầu. Hãy kiểm tra lại thông tin."); }
    }

    private void showDatePicker() {
        Calendar minimumDate = Calendar.getInstance();
        minimumDate.set(Calendar.HOUR_OF_DAY, 0);
        minimumDate.set(Calendar.MINUTE, 0);
        minimumDate.set(Calendar.SECOND, 0);
        minimumDate.set(Calendar.MILLISECOND, 0);

        Calendar initialDate = Calendar.getInstance();
        if (selectedEffectiveFromUtc > 0) initialDate.setTimeInMillis(selectedEffectiveFromUtc);
        else initialDate.setTimeInMillis(minimumDate.getTimeInMillis());

        com.google.android.material.datepicker.CalendarConstraints constraints =
                new com.google.android.material.datepicker.CalendarConstraints.Builder()
                        .setValidator(com.google.android.material.datepicker.DateValidatorPointForward.from(minimumDate.getTimeInMillis()))
                        .build();
        com.google.android.material.datepicker.MaterialDatePicker<Long> picker =
                com.google.android.material.datepicker.MaterialDatePicker.Builder.datePicker()
                        .setTitleText("Chọn ngày áp dụng")
                        .setSelection(initialDate.getTimeInMillis())
                        .setCalendarConstraints(constraints)
                        .build();
        picker.addOnPositiveButtonClickListener(selection -> {
            if (selection == null) return;
            Calendar utc = Calendar.getInstance(java.util.TimeZone.getTimeZone("UTC"));
            utc.setTimeInMillis(selection);
            int year = utc.get(Calendar.YEAR);
            int month = utc.get(Calendar.MONTH);
            int day = utc.get(Calendar.DAY_OF_MONTH);
            Calendar selectedDate = Calendar.getInstance();
            selectedDate.clear();
            selectedDate.set(year, month, day, 0, 0, 0);
            Calendar today = Calendar.getInstance();
            selectedDateIsToday = year == today.get(Calendar.YEAR)
                    && month == today.get(Calendar.MONTH)
                    && day == today.get(Calendar.DAY_OF_MONTH);
            selectedEffectiveFromUtc = selectedDateIsToday ? System.currentTimeMillis() : selectedDate.getTimeInMillis();
            SimpleDateFormat display = new SimpleDateFormat("dd/MM/yyyy", Locale.getDefault());
            dateInput.setText(display.format(new Date(selectedDate.getTimeInMillis())));
            dateError.setText("");
        });
        picker.show(getParentFragmentManager(), "rate_effective_date");
    }

    private void clearErrors() { rateTypeError.setText(""); rateError.setText(""); dateError.setText(""); reasonError.setText(""); }
}
