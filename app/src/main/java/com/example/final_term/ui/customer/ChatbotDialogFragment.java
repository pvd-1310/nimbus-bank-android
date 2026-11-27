package com.example.final_term.ui.customer;

import android.app.Dialog;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.text.InputFilter;
import android.view.Gravity;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.widget.Button;
import android.widget.EditText;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.fragment.app.DialogFragment;
import androidx.navigation.NavController;
import androidx.navigation.fragment.NavHostFragment;
import com.example.final_term.R;
import com.example.final_term.core.FinalTermApplication;
import com.example.final_term.ui.common.FullscreenInsets;
import com.google.android.material.button.MaterialButton;
import org.json.JSONException;
import org.json.JSONObject;
import java.text.DateFormat;
import java.util.Date;

public final class ChatbotDialogFragment extends DialogFragment {
    private LinearLayout messages;
    private ScrollView scroll;
    private EditText input;
    private TextView status;
    private MaterialButton send;
    private boolean ready;
    private boolean sending;
    private final java.util.List<JSONObject> chatHistory = new java.util.ArrayList<>();
    private static final int NAVY = 0xff2f3e6e, INK = 0xff1b2340, MUTED = 0xff56627f;

    @NonNull @Override public Dialog onCreateDialog(Bundle savedState) {
        Dialog dialog = new Dialog(requireContext(), R.style.Theme_Final_term);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        LinearLayout root = new LinearLayout(requireContext());
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{0xfff4f6fa, 0xffe4e9f2, 0xffb9c4dc, 0xff8d9cc0}));

        LinearLayout header = new LinearLayout(requireContext());
        header.setGravity(Gravity.CENTER_VERTICAL); header.setPadding(dp(16), dp(8), dp(16), dp(8));
        header.setBackgroundColor(0x66ffffff);
        MaterialButton back = iconButton(R.drawable.ic_arrow_back, "Quay lại", false);
        back.setOnClickListener(v -> dismiss());
        header.addView(back, new LinearLayout.LayoutParams(dp(48), dp(48)));
        TextView avatar = text("N", 19, Color.WHITE, true);
        avatar.setGravity(Gravity.CENTER); avatar.setBackground(shape(NAVY, 24));
        LinearLayout.LayoutParams avatarParams = new LinearLayout.LayoutParams(dp(40), dp(40)); avatarParams.leftMargin = dp(12);
        header.addView(avatar, avatarParams);
        LinearLayout identity = new LinearLayout(requireContext()); identity.setOrientation(LinearLayout.VERTICAL); identity.setPadding(dp(10), 0, 0, 0);
        identity.addView(text("Trợ lý AI Nimbus", 15, INK, true));
        status = text("Đang kiểm tra kết nối…", 11, MUTED, false); status.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE); identity.addView(status);
        header.addView(identity, new LinearLayout.LayoutParams(0, -2, 1)); root.addView(header);

        scroll = new ScrollView(requireContext()); scroll.setFillViewport(true); scroll.setClipToPadding(false); scroll.setPadding(dp(16), dp(12), dp(16), dp(12));
        messages = new LinearLayout(requireContext()); messages.setOrientation(LinearLayout.VERTICAL); messages.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        scroll.addView(messages); root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        TextView today = text("Hôm nay", 11, MUTED, false); today.setGravity(Gravity.CENTER); today.setPadding(dp(14), dp(5), dp(14), dp(5)); today.setBackground(shape(0xaaffffff, 20));
        LinearLayout.LayoutParams day = new LinearLayout.LayoutParams(-2, -2); day.gravity = Gravity.CENTER_HORIZONTAL; day.bottomMargin = dp(12); messages.addView(today, day);
        addMessage("Chào bạn! Tôi là trợ lý AI của Nimbus Bank. Tôi có thể kiểm tra số dư, chi tiêu tháng này, hóa đơn chưa thanh toán, tư vấn tiết kiệm/vay vốn hoặc mở nhanh các tính năng cho bạn.", false);

        LinearLayout footer = new LinearLayout(requireContext()); footer.setOrientation(LinearLayout.VERTICAL); footer.setPadding(dp(16), dp(4), dp(16), dp(12));
        HorizontalScrollView suggestions = new HorizontalScrollView(requireContext()); suggestions.setHorizontalScrollBarEnabled(false);
        LinearLayout chips = new LinearLayout(requireContext());
        for (String prompt : new String[]{"Chi tiêu tháng này?", "Tôi còn hóa đơn nào?", "Số dư tài khoản?", "Lãi suất tiết kiệm?", "Nạp điện thoại", "Đặt vé xem phim", "Tìm chi nhánh"}) {
            TextView chip = text(prompt, 12, INK, true); chip.setGravity(Gravity.CENTER); chip.setPadding(dp(14), 0, dp(14), 0); chip.setBackground(shape(0xccffffff, 24));
            LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(-2, dp(40)); cp.rightMargin = dp(8); chips.addView(chip, cp);
            chip.setOnClickListener(v -> {
                input.setText(prompt);
                input.setSelection(input.length());
                if (ready && !sending) sendMessage();
            });
        }
        suggestions.addView(chips); footer.addView(suggestions);
        LinearLayout composer = new LinearLayout(requireContext()); composer.setGravity(Gravity.CENTER_VERTICAL); composer.setPadding(dp(16), dp(4), dp(4), dp(4)); composer.setBackground(shape(0xeeffffff, 30));
        LinearLayout.LayoutParams composerParams = new LinearLayout.LayoutParams(-1, -2); composerParams.topMargin = dp(8); footer.addView(composer, composerParams);
        input = new EditText(requireContext()); input.setSingleLine(true); input.setTextSize(14); input.setTextColor(INK); input.setHintTextColor(MUTED); input.setHint("Hỏi Nimbus AI..."); input.setBackgroundColor(Color.TRANSPARENT); input.setFilters(new InputFilter[]{new InputFilter.LengthFilter(300)}); input.setImeOptions(EditorInfo.IME_ACTION_SEND); composer.addView(input, new LinearLayout.LayoutParams(0, dp(48), 1));
        send = iconButton(R.drawable.ic_chat_send, "Gửi tin nhắn", true); composer.addView(send, new LinearLayout.LayoutParams(dp(48), dp(48)));
        send.setOnClickListener(v -> { if (!ready) checkService(); else sendMessage(); });
        input.setOnEditorActionListener((v, id, event) -> { if (id == EditorInfo.IME_ACTION_SEND) { if (ready) sendMessage(); return true; } return false; });
        scroll.addOnLayoutChangeListener((v, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom) -> {
            if (bottom < oldBottom) scrollToBottom();
        });
        root.addView(footer); dialog.setContentView(root);
        dialog.setOnShowListener(ignored -> checkService());
        Window window = dialog.getWindow();
        if (window != null) {
            window.setLayout(-1, -1);
            window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE | WindowManager.LayoutParams.SOFT_INPUT_STATE_HIDDEN);
            FullscreenInsets.apply(window, root);
        }
        return dialog;
    }

    @Override public void onStart() { super.onStart(); Window w = requireDialog().getWindow(); if (w != null) w.setLayout(-1, -1); }
    private void checkService() {
        ready = false; input.setEnabled(false); send.setEnabled(false); status.setText("Đang kiểm tra kết nối…");
        api().get("/capabilities").addOnCompleteListener(task -> {
            if (!isAdded() || getDialog() == null || !getDialog().isShowing()) return;
            send.setEnabled(true);
            try {
                if (!task.isSuccessful()) throw new JSONException("connection");
                var response = task.getResult();
                if (response.statusCode == 401 || response.statusCode == 403) { status.setText("Phiên đăng nhập hết hạn. Nhấn nút gửi để thử lại."); return; }
                if (response.statusCode != 200) throw new JSONException("unavailable");
                String mode = new JSONObject(response.body).getString("chatbot");
                if ("SETUP_REQUIRED".equals(mode) || "DEFERRED".equals(mode)) { status.setText("Chưa cấu hình · Nhấn nút gửi để thử lại"); return; }
                if (!"ENABLED".equals(mode) && !"DEMO_ONLY".equals(mode)) throw new JSONException("mode");
                ready = true; input.setEnabled(true); status.setText("DEMO_ONLY".equals(mode) ? "Hướng dẫn cơ bản · Chế độ demo" : "Sẵn sàng hỗ trợ");
            } catch (JSONException e) { status.setText("Không kết nối được · Nhấn nút gửi để thử lại"); }
        });
    }
    private com.example.final_term.data.BackendApiClient api() { return ((FinalTermApplication) requireActivity().getApplication()).getAppContainer().getBackendApiClient(); }
    private void sendMessage() {
        String message = input.getText().toString().trim(); if (message.isEmpty() || sending) return;
        sending = true;
        send.setEnabled(false);
        send.setAlpha(0.5f);
        input.setText("");
        addMessage(message, true);
        View typingBubble = addTypingIndicator();
        String previousStatus = status.getText().toString();
        status.setText("Nimbus đang trả lời…");
        try {
            JSONObject payload = new JSONObject().put("message", message);
            if (!chatHistory.isEmpty()) {
                org.json.JSONArray historyArray = new org.json.JSONArray();
                int start = Math.max(0, chatHistory.size() - 6);
                for (int i = start; i < chatHistory.size(); i++) historyArray.put(chatHistory.get(i));
                payload.put("history", historyArray);
            }
            api().post("/chatbot", payload.toString()).addOnCompleteListener(task -> {
                if (!isAdded() || getDialog() == null || !getDialog().isShowing()) return;
                sending = false;
                send.setEnabled(true);
                send.setAlpha(1f);
                status.setText(previousStatus);
                messages.removeView(typingBubble);
                String reply = "Chatbot tạm thời không phản hồi. Vui lòng thử lại."; String action = "";
                if (!task.isSuccessful() && task.getException() instanceof java.net.SocketTimeoutException) reply = "Kết nối bị gián đoạn. Bạn có thể gửi lại câu hỏi.";
                if (task.isSuccessful()) try {
                    var response = task.getResult(); JSONObject body = new JSONObject(response.body);
                    if (response.statusCode == 200) {
                        reply = body.getString("text");
                        action = body.optString("action");
                        recordHistory("user", message);
                        recordHistory("assistant", reply);
                    }
                    else if ("CHATBOT_SETUP_REQUIRED".equals(body.optString("error"))) { reply = "Chatbot chưa được cấu hình."; ready = false; input.setEnabled(false); status.setText("Chưa cấu hình · Nhấn nút gửi để thử lại"); }
                    else if ("CHATBOT_TIMEOUT".equals(body.optString("error"))) reply = "Trợ lý phản hồi quá thời gian. Vui lòng thử lại.";
                    else if (response.statusCode == 429) reply = "Bạn đã gửi nhiều câu hỏi. Vui lòng chờ rồi thử lại.";
                } catch (JSONException ignored) { }
                LinearLayout bubble = addMessage(reply, false); addAction(bubble, action);
            });
        } catch (JSONException e) {
            sending = false;
            send.setEnabled(true);
            send.setAlpha(1f);
            messages.removeView(typingBubble);
            status.setText(previousStatus);
            addMessage("Không gửi được câu hỏi.", false);
        }
    }

    private View addTypingIndicator() {
        LinearLayout bubble = new LinearLayout(requireContext());
        bubble.setOrientation(LinearLayout.HORIZONTAL);
        bubble.setGravity(Gravity.CENTER_VERTICAL);
        bubble.setPadding(dp(14), dp(10), dp(14), dp(10));
        bubble.setBackground(shape(0xe6ffffff, 20));
        TextView dots = text("Nimbus đang suy nghĩ…", 13, MUTED, false);
        bubble.addView(dots);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-2, -2);
        params.gravity = Gravity.START;
        params.bottomMargin = dp(12);
        messages.addView(bubble, params);
        bubble.setAlpha(0f);
        bubble.animate().alpha(1f).setDuration(160).start();
        scrollToBottom();
        return bubble;
    }

    private void scrollToBottom() {
        scroll.post(() -> scroll.smoothScrollTo(0, Math.max(0, messages.getBottom())));
    }

    private void recordHistory(String role, String text) {
        try {
            String clipped = text.length() > 450 ? text.substring(0, 450) : text;
            chatHistory.add(new JSONObject().put("role", role).put("text", clipped));
            while (chatHistory.size() > 10) chatHistory.remove(0);
        } catch (JSONException ignored) { }
    }
    private LinearLayout addMessage(String content, boolean user) {
        LinearLayout bubble = new LinearLayout(requireContext()); bubble.setOrientation(LinearLayout.VERTICAL); bubble.setPadding(dp(14), dp(10), dp(14), dp(10)); bubble.setBackground(shape(user ? NAVY : 0xe6ffffff, 20));
        TextView body = text(content, 14, user ? Color.WHITE : INK, false); body.setLineSpacing(dp(2), 1); bubble.addView(body);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-2, -2); params.gravity = user ? Gravity.END : Gravity.START; params.bottomMargin = dp(12); params.leftMargin = user ? dp(48) : 0; params.rightMargin = user ? 0 : dp(32); messages.addView(bubble, params);
        bubble.setAlpha(0f); bubble.setTranslationY(dp(8)); bubble.animate().alpha(1f).translationY(0f).setDuration(180).start();
        TextView time = text(DateFormat.getTimeInstance(DateFormat.SHORT).format(new Date()), 10, MUTED, false); LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(-2, -2); tp.gravity = user ? Gravity.END : Gravity.START; tp.bottomMargin = dp(10); tp.topMargin = -dp(8); messages.addView(time, tp);
        scrollToBottom();
        return bubble;
    }
    private void addAction(LinearLayout bubble, String action) {
        String label = actionLabel(action);
        if (label == null) return;
        TextView button = text(label + "  →", 12, Color.WHITE, true);
        button.setBackground(shape(NAVY, 18));
        button.setPadding(dp(14), dp(8), dp(14), dp(8));
        LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(-2, -2);
        bp.topMargin = dp(10);
        bubble.addView(button, bp);
        button.setOnClickListener(v -> navigate(action));
    }

    private String actionLabel(String action) {
        if (action == null) return null;
        switch (action) {
            case "TOPUP": return "Mở nạp điện thoại";
            case "ELECTRICITY": return "Mở hóa đơn tiền điện";
            case "WATER": return "Mở hóa đơn tiền nước";
            case "BILLS": return "Mở tiện ích";
            case "HISTORY": return "Xem giao dịch tháng";
            case "TRANSFER": return "Mở chuyển khoản";
            case "DEPOSIT": return "Mở yêu cầu nạp tiền";
            case "WITHDRAWAL": return "Mở yêu cầu rút tiền";
            case "SAVINGS": return "Xem tài khoản tiết kiệm";
            case "LOAN": return "Xem khoản vay";
            case "MOVIE": return "Mở vé xem phim";
            case "HOTEL": return "Mở đặt khách sạn";
            case "FLIGHT": return "Mở đặt vé máy bay";
            case "BRANCHES": return "Tìm chi nhánh";
            default: return null;
        }
    }

    private void navigate(String action) {
        NavController nav = NavHostFragment.findNavController(requireParentFragment());
        switch (action) {
            case "TOPUP": nav.navigate(R.id.mobileTopup); break;
            case "ELECTRICITY": nav.navigate(R.id.electricityBill); break;
            case "WATER": nav.navigate(R.id.waterBill); break;
            case "BILLS": nav.navigate(R.id.utilitiesHub); break;
            case "HISTORY":
                Bundle history = new Bundle();
                history.putBoolean("thisMonth", true);
                nav.navigate(R.id.transactionHistory, history);
                break;
            case "TRANSFER": nav.navigate(R.id.transfer); break;
            case "DEPOSIT":
                Bundle deposit = new Bundle();
                deposit.putString("operation", "DEPOSIT");
                nav.navigate(R.id.moneyRequest, deposit);
                break;
            case "WITHDRAWAL":
                Bundle withdrawal = new Bundle();
                withdrawal.putString("operation", "WITHDRAWAL");
                nav.navigate(R.id.moneyRequest, withdrawal);
                break;
            case "SAVINGS":
            case "LOAN":
                nav.navigate(R.id.accountList);
                break;
            case "MOVIE": nav.navigate(R.id.movieTickets); break;
            case "HOTEL": nav.navigate(R.id.hotelBooking); break;
            case "FLIGHT": nav.navigate(R.id.flightBooking); break;
            case "BRANCHES": nav.navigate(R.id.branchLocator); break;
            default: return;
        }
        dismiss();
    }
    private MaterialButton iconButton(int icon, String description, boolean primary) { MaterialButton b = new MaterialButton(requireContext()); b.setIconResource(icon); b.setIconSize(dp(20)); b.setIconPadding(0); b.setIconGravity(MaterialButton.ICON_GRAVITY_TEXT_START); b.setGravity(Gravity.CENTER); b.setPadding(0,0,0,0); b.setMinWidth(0); b.setInsetTop(0); b.setInsetBottom(0); b.setBackground(shape(primary ? NAVY : 0xccffffff, 24)); b.setBackgroundTintList(null); b.setIconTint(android.content.res.ColorStateList.valueOf(primary ? Color.WHITE : INK)); b.setContentDescription(description); return b; }
    private TextView text(String value, int size, int color, boolean bold) { TextView t = new TextView(requireContext()); t.setText(value); t.setTextSize(size); t.setTextColor(color); if (bold) t.setTypeface(null, Typeface.BOLD); return t; }
    private GradientDrawable shape(int color, int radius) { GradientDrawable d = new GradientDrawable(); d.setColor(color); d.setCornerRadius(dp(radius)); return d; }
    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
}
