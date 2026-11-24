package com.example.final_term.ui.customer;

import android.app.Dialog;
import android.os.Bundle;
import android.text.InputFilter;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.fragment.app.DialogFragment;
import androidx.navigation.NavController;
import androidx.navigation.fragment.NavHostFragment;

import com.example.final_term.R;
import com.example.final_term.core.FinalTermApplication;
import com.example.final_term.ui.utilities.UtilitiesHubDialogFragment;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import org.json.JSONException;
import org.json.JSONObject;

public final class ChatbotDialogFragment extends DialogFragment {
    @NonNull
    @Override
    public Dialog onCreateDialog(Bundle savedState) {
        var context = requireContext();
        var app = (FinalTermApplication) requireActivity().getApplication();
        var api = app.getAppContainer().getBackendApiClient();
        var content = new LinearLayout(context);
        content.setOrientation(LinearLayout.VERTICAL);
        int padding = (int) (20 * getResources().getDisplayMetrics().density);
        content.setPadding(padding, 0, padding, padding);
        var serviceStatus = new TextView(context);
        serviceStatus.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        content.addView(serviceStatus);
        var transcript = new TextView(context);
        transcript.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        transcript.setText("Hỏi về nạp điện thoại, hóa đơn, giao dịch tháng này, chuyển khoản hoặc chi nhánh. Chỉ nhập chủ đề, tránh nhập dữ liệu cá nhân.");
        var scroll = new ScrollView(context);
        scroll.addView(transcript);
        content.addView(scroll, new LinearLayout.LayoutParams(-1, padding * 9));
        var input = new EditText(context);
        input.setHint("Ví dụ: Giao dịch tháng này");
        input.setFilters(new InputFilter[]{new InputFilter.LengthFilter(300)});
        content.addView(input);
        var action = new Button(context);
        action.setVisibility(View.GONE);
        content.addView(action);

        var dialog = new MaterialAlertDialogBuilder(context)
                .setTitle("Trợ lý Nimbus")
                .setView(content)
                .setNegativeButton("Đóng", null)
                .setPositiveButton("Gửi", null)
                .create();
        dialog.setOnShowListener(ignored -> {
            Button send = dialog.getButton(android.content.DialogInterface.BUTTON_POSITIVE);
            Runnable checkService = () -> {
                send.setEnabled(false);
                input.setEnabled(false);
                serviceStatus.setText("Đang kiểm tra trợ lý…");
                api.get("/capabilities").addOnCompleteListener(task -> {
                    if (!dialog.isShowing() || !isAdded()) return;
                    try {
                        if (!task.isSuccessful()) throw new JSONException("Connection unavailable");
                        var response = task.getResult();
                        if (response.statusCode == 401 || response.statusCode == 403) {
                            serviceStatus.setText("Phiên đăng nhập đã hết hạn. Vui lòng đăng nhập lại.");
                            send.setText("Kiểm tra lại");
                            send.setEnabled(true);
                            return;
                        }
                        if (response.statusCode != 200) throw new JSONException("Unavailable");
                        String mode = new JSONObject(response.body).getString("chatbot");
                        if ("SETUP_REQUIRED".equals(mode) || "DEFERRED".equals(mode)) {
                            serviceStatus.setText("Chatbot chưa được cấu hình.");
                            send.setText("Kiểm tra lại");
                            send.setEnabled(true);
                            return;
                        }
                        if (!"ENABLED".equals(mode) && !"DEMO_ONLY".equals(mode))
                            throw new JSONException("Unknown chatbot mode");
                        serviceStatus.setText("DEMO_ONLY".equals(mode) ? "Trợ lý có thể hỗ trợ các câu hỏi hướng dẫn cơ bản." : "");
                        send.setText("Gửi");
                        input.setEnabled(true);
                    } catch (JSONException error) {
                        serviceStatus.setText("Không thể kết nối với trợ lý. Vui lòng thử lại.");
                        send.setText("Kiểm tra lại");
                    }
                    send.setEnabled(true);
                });
            };
            send.setOnClickListener(v -> {
                if (!input.isEnabled()) {
                    checkService.run();
                    return;
                }
                String message = input.getText().toString().trim();
                if (message.isEmpty()) return;
                send.setEnabled(false);
                action.setVisibility(View.GONE);
                transcript.append("\n\nBạn: " + message);
                CharSequence modeStatus = serviceStatus.getText();
                serviceStatus.setText("Đang chờ trả lời…");
                scroll.post(() -> scroll.fullScroll(View.FOCUS_DOWN));
                try {
                    api.post("/chatbot", new JSONObject().put("message", message).toString())
                            .addOnCompleteListener(task -> {
                                if (!dialog.isShowing() || !isAdded()) return;
                                send.setEnabled(true);
                                serviceStatus.setText(modeStatus);
                                String reply = "Chatbot tạm thời không phản hồi. Vui lòng thử lại.";
                                if (!task.isSuccessful()
                                        && task.getException() instanceof java.net.SocketTimeoutException) {
                                    reply = "Kết nối bị gián đoạn. Bạn có thể gửi lại câu hỏi.";
                                }
                                if (task.isSuccessful()) {
                                    try {
                                        var response = task.getResult();
                                        var body = new JSONObject(response.body);
                                        if (response.statusCode == 200) {
                                            reply = body.getString("text");
                                            bindAction(action, body.optString("action"));
                                            input.setText("");
                                        } else if ("CHATBOT_SETUP_REQUIRED".equals(body.optString("error"))) {
                                            reply = "Chatbot chưa được cấu hình.";
                                            input.setEnabled(false);
                                            send.setText("Kiểm tra lại");
                                        } else if ("CHATBOT_TIMEOUT".equals(body.optString("error"))) {
                                            reply = "Trợ lý phản hồi quá thời gian. Vui lòng thử lại.";
                                        } else if (response.statusCode == 429) {
                                            reply = "Bạn đã gửi nhiều câu hỏi. Vui lòng chờ rồi thử lại.";
                                        }
                                    } catch (JSONException ignoredResponse) {
                                        // Keep the retry message.
                                    }
                                }
                                transcript.append("\nNimbus: " + reply);
                                scroll.post(() -> scroll.fullScroll(View.FOCUS_DOWN));
                            });
                } catch (JSONException error) {
                    send.setEnabled(true);
                    serviceStatus.setText(modeStatus);
                    transcript.append("\nKhông gửi được câu hỏi.");
                }
            });
            checkService.run();
        });
        return dialog;
    }

    private void bindAction(Button button, String action) {
        final String label;
        switch (action) {
            case "TOPUP": label = "Mở nạp điện thoại"; break;
            case "BILLS": label = "Mở hóa đơn"; break;
            case "HISTORY": label = "Mở lịch sử giao dịch"; break;
            case "TRANSFER": label = "Mở chuyển khoản"; break;
            case "BRANCHES": label = "Mở chi nhánh"; break;
            case "DEPOSIT": label = "Mở yêu cầu nạp tiền"; break;
            default: return;
        }
        button.setText(label);
        button.setVisibility(View.VISIBLE);
        button.setOnClickListener(v -> {
            if ("TOPUP".equals(action) || "BILLS".equals(action)) {
                var utilities = new UtilitiesHubDialogFragment();
                Bundle args = new Bundle();
                args.putBoolean("openTopup", "TOPUP".equals(action));
                utilities.setArguments(args);
                utilities.show(getParentFragmentManager(), "UtilitiesHub");
            } else {
                NavController nav = NavHostFragment.findNavController(requireParentFragment());
                switch (action) {
                    case "HISTORY": {
                        Bundle args = new Bundle();
                        args.putBoolean("thisMonth", true);
                        nav.navigate(R.id.transactionHistory, args);
                        break;
                    }
                    case "TRANSFER": nav.navigate(R.id.transfer); break;
                    case "BRANCHES": nav.navigate(R.id.branchLocator); break;
                    case "DEPOSIT": {
                        Bundle args = new Bundle();
                        args.putString("operation", "DEPOSIT");
                        nav.navigate(R.id.moneyRequest, args);
                        break;
                    }
                }
            }
            dismiss();
        });
    }
}
