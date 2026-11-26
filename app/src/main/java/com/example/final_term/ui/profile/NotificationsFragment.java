package com.example.final_term.ui.profile;

import android.content.Context;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;
import androidx.navigation.Navigation;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.example.final_term.R;
import com.example.final_term.core.AppContainer;
import com.example.final_term.core.FinalTermApplication;
import com.example.final_term.data.ReadModelResult;
import com.example.final_term.data.ReadModels;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

public class NotificationsFragment extends Fragment {
    private static final String FILTER_ALL = "ALL";
    private static final String FILTER_TRANSACTION = "TRANSACTION";
    private static final String FILTER_EKYC = "EKYC";
    private static final String FILTER_SECURITY = "SECURITY";

    private AppContainer container;
    private String currentFilter = FILTER_ALL;
    private final List<ReadModels.NotificationSummary> allItems = new ArrayList<>();
    private final NotificationAdapter adapter = new NotificationAdapter();
    private TextView statusText;
    private TextView chipAll;
    private TextView chipTransaction;
    private TextView chipEkyc;
    private TextView chipSecurity;
    private View markAllReadButton;
    private boolean isLoading = true;
    private String lastError;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup containerView,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_notifications, containerView, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        container = ((FinalTermApplication) requireActivity().getApplication()).getAppContainer();

        view.findViewById(R.id.back_button).setOnClickListener(v ->
                Navigation.findNavController(v).navigateUp());

        statusText = view.findViewById(R.id.status_text);
        chipAll = view.findViewById(R.id.chip_all);
        chipTransaction = view.findViewById(R.id.chip_transaction);
        chipEkyc = view.findViewById(R.id.chip_ekyc);
        chipSecurity = view.findViewById(R.id.chip_security);
        markAllReadButton = view.findViewById(R.id.mark_all_read_button);

        RecyclerView recyclerView = view.findViewById(R.id.notifications_recycler);
        recyclerView.setLayoutManager(new LinearLayoutManager(requireContext()));
        recyclerView.setAdapter(adapter);

        chipAll.setOnClickListener(v -> selectFilter(FILTER_ALL));
        chipTransaction.setOnClickListener(v -> selectFilter(FILTER_TRANSACTION));
        chipEkyc.setOnClickListener(v -> selectFilter(FILTER_EKYC));
        chipSecurity.setOnClickListener(v -> selectFilter(FILTER_SECURITY));

        markAllReadButton.setOnClickListener(v -> {
            if (!isAdded()) return;
            markAllReadButton.setEnabled(false);
            container.getBackendApiClient().post("/notifications/read-all", "{}")
                    .addOnCompleteListener(task -> {
                        if (isAdded()) {
                            markAllReadButton.setEnabled(true);
                        }
                    });
        });

        adapter.setOnItemClickListener(item -> {
            if (!item.read && isAdded()) {
                container.getBackendApiClient().post("/notifications/" + item.id + "/read", "{}");
            }
        });

        updateChipStyles();
        bindNotifications();
    }

    private void selectFilter(@NonNull String filter) {
        if (filter.equals(currentFilter)) return;
        currentFilter = filter;
        updateChipStyles();
        renderFilteredList();
    }

    private void updateChipStyles() {
        styleChip(chipAll, FILTER_ALL.equals(currentFilter));
        styleChip(chipTransaction, FILTER_TRANSACTION.equals(currentFilter));
        styleChip(chipEkyc, FILTER_EKYC.equals(currentFilter));
        styleChip(chipSecurity, FILTER_SECURITY.equals(currentFilter));
    }

    private void styleChip(TextView chip, boolean selected) {
        if (chip == null || !isAdded()) return;
        chip.setBackgroundResource(selected ? R.drawable.navy_button_pill : R.drawable.glass_pill_bg);
        chip.setTextColor(ContextCompat.getColor(requireContext(),
                selected ? R.color.white : R.color.slate_blue));
    }

    private void bindNotifications() {
        container.getAuthRepository().observeAuthState().observe(getViewLifecycleOwner(), state -> {
            if (state == null || state.getUser() == null) return;
            String uid = state.getUser().getUid();
            container.getReadRepository().observeNotifications(uid).observe(getViewLifecycleOwner(), result -> {
                if (result == null) return;
                isLoading = result.getStatus() == ReadModelResult.Status.LOADING;
                lastError = result.getStatus() == ReadModelResult.Status.ERROR ? result.getMessage() : null;
                allItems.clear();
                if (result.getData() != null) {
                    allItems.addAll(result.getData());
                    Collections.sort(allItems, (a, b) -> {
                        if (a.createdAt == null && b.createdAt == null) return 0;
                        if (a.createdAt == null) return 1;
                        if (b.createdAt == null) return -1;
                        return b.createdAt.compareTo(a.createdAt);
                    });
                }
                renderFilteredList();
            });
        });
    }

    private void renderFilteredList() {
        List<ReadModels.NotificationSummary> filtered = new ArrayList<>();
        int unreadCount = 0;
        for (ReadModels.NotificationSummary item : allItems) {
            if (!item.read) unreadCount++;
            if (FILTER_ALL.equals(currentFilter) || currentFilter.equals(item.type)) {
                filtered.add(item);
            }
        }
        adapter.submitList(filtered);

        if (markAllReadButton != null) {
            markAllReadButton.setVisibility(unreadCount > 0 ? View.VISIBLE : View.GONE);
        }

        if (filtered.isEmpty()) {
            statusText.setVisibility(View.VISIBLE);
            if (isLoading) {
                statusText.setText("Đang tải thông báo...");
            } else if (lastError != null && !lastError.isEmpty()) {
                statusText.setText(lastError);
            } else {
                statusText.setText(FILTER_ALL.equals(currentFilter)
                        ? "Chưa có thông báo nào."
                        : "Không có thông báo thuộc nhóm này.");
            }
        } else {
            statusText.setVisibility(View.GONE);
        }
    }

    private static final class NotificationAdapter extends RecyclerView.Adapter<NotificationAdapter.Holder> {
        interface OnItemClickListener {
            void onItemClick(ReadModels.NotificationSummary item);
        }

        private final List<ReadModels.NotificationSummary> items = new ArrayList<>();
        private final SimpleDateFormat dateFormat = new SimpleDateFormat("dd/MM · HH:mm", Locale.getDefault());
        private OnItemClickListener listener;

        void setOnItemClickListener(OnItemClickListener listener) {
            this.listener = listener;
        }

        void submitList(List<ReadModels.NotificationSummary> list) {
            items.clear();
            items.addAll(list);
            notifyDataSetChanged();
        }

        @NonNull
        @Override
        public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            Context context = parent.getContext();
            float density = context.getResources().getDisplayMetrics().density;
            int pad16 = Math.round(16 * density);
            int pad12 = Math.round(12 * density);
            int pad10 = Math.round(10 * density);
            int iconBoxSize = Math.round(40 * density);
            int iconPad = Math.round(10 * density);
            int dotSize = Math.round(8 * density);

            LinearLayout card = new LinearLayout(context);
            LinearLayout.LayoutParams cardParams = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            cardParams.bottomMargin = pad10;
            card.setLayoutParams(cardParams);
            card.setOrientation(LinearLayout.HORIZONTAL);
            card.setGravity(android.view.Gravity.TOP);
            card.setPadding(pad16, pad16, pad16, pad16);

            ImageView iconView = new ImageView(context);
            LinearLayout.LayoutParams iconParams = new LinearLayout.LayoutParams(iconBoxSize, iconBoxSize);
            iconParams.setMarginEnd(pad12);
            iconView.setLayoutParams(iconParams);
            iconView.setBackgroundResource(R.drawable.circle_action_button);
            iconView.setPadding(iconPad, iconPad, iconPad, iconPad);
            iconView.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
            card.addView(iconView);

            LinearLayout contentCol = new LinearLayout(context);
            contentCol.setLayoutParams(new LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            contentCol.setOrientation(LinearLayout.VERTICAL);

            LinearLayout topRow = new LinearLayout(context);
            topRow.setLayoutParams(new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            topRow.setOrientation(LinearLayout.HORIZONTAL);
            topRow.setGravity(android.view.Gravity.CENTER_VERTICAL);

            TextView title = new TextView(context);
            title.setLayoutParams(new LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            title.setTextSize(15f);
            title.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
            title.setTextColor(ContextCompat.getColor(context, R.color.navy));

            TextView time = new TextView(context);
            time.setTextSize(11f);
            time.setTextColor(ContextCompat.getColor(context, R.color.slate_blue));

            View unreadDot = new View(context);
            LinearLayout.LayoutParams dotParams = new LinearLayout.LayoutParams(dotSize, dotSize);
            dotParams.setMarginStart(Math.round(6 * density));
            unreadDot.setLayoutParams(dotParams);
            android.graphics.drawable.GradientDrawable dotDrawable = new android.graphics.drawable.GradientDrawable();
            dotDrawable.setShape(android.graphics.drawable.GradientDrawable.OVAL);
            dotDrawable.setColor(ContextCompat.getColor(context, R.color.navy));
            unreadDot.setBackground(dotDrawable);

            topRow.addView(title);
            topRow.addView(time);
            topRow.addView(unreadDot);

            TextView body = new TextView(context);
            body.setTextSize(13.5f);
            body.setTextColor(ContextCompat.getColor(context, R.color.slate_blue));
            body.setPadding(0, Math.round(4 * density), 0, 0);

            contentCol.addView(topRow);
            contentCol.addView(body);
            card.addView(contentCol);

            return new Holder(card, iconView, title, body, time, unreadDot);
        }

        @Override
        public void onBindViewHolder(@NonNull Holder holder, int position) {
            ReadModels.NotificationSummary item = items.get(position);
            Context context = holder.itemView.getContext();

            holder.itemView.setBackgroundResource(
                    item.read ? R.drawable.glass_panel_soft : R.drawable.glass_panel_card);
            holder.title.setText(item.title);
            holder.body.setText(item.body);
            holder.time.setText(item.createdAt == null
                    ? ""
                    : dateFormat.format(item.createdAt.toDate()));
            holder.unreadDot.setVisibility(item.read ? View.GONE : View.VISIBLE);

            int iconRes;
            int tintRes;
            switch (item.type) {
                case "TRANSACTION":
                    iconRes = R.drawable.ic_receipt;
                    tintRes = item.body.startsWith("+") ? R.color.success : R.color.navy;
                    break;
                case "EKYC":
                    iconRes = R.drawable.ic_shield;
                    String lower = (item.title + " " + item.body).toLowerCase(Locale.ROOT);
                    tintRes = (lower.contains("chụp lại") || lower.contains("thất bại") || lower.contains("resubmit"))
                            ? R.color.warning : R.color.success;
                    break;
                case "SECURITY":
                    iconRes = R.drawable.ic_lock;
                    tintRes = R.color.danger;
                    break;
                default:
                    iconRes = R.drawable.ic_nav_notifications;
                    tintRes = R.color.slate_blue;
                    break;
            }
            holder.icon.setImageResource(iconRes);
            holder.icon.setColorFilter(ContextCompat.getColor(context, tintRes));

            holder.itemView.setOnClickListener(v -> {
                if (listener != null) {
                    listener.onItemClick(item);
                }
            });
        }

        @Override
        public int getItemCount() {
            return items.size();
        }

        static final class Holder extends RecyclerView.ViewHolder {
            final ImageView icon;
            final TextView title;
            final TextView body;
            final TextView time;
            final View unreadDot;

            Holder(@NonNull View itemView, ImageView icon, TextView title,
                   TextView body, TextView time, View unreadDot) {
                super(itemView);
                this.icon = icon;
                this.title = title;
                this.body = body;
                this.time = time;
                this.unreadDot = unreadDot;
            }
        }
    }
}
