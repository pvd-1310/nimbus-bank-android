package com.example.final_term.core;

import android.Manifest;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Build;

import androidx.annotation.NonNull;
import androidx.core.app.ActivityCompat;
import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;
import androidx.core.content.ContextCompat;

import com.example.final_term.MainActivity;
import com.example.final_term.R;
import com.example.final_term.data.ReadModels;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Displays incoming Firestore notifications in the real Android system notification shade
 * across 4 themed channels while deduplicating historical items.
 */
public final class SystemNotificationHelper {

    public static final String EXTRA_OPEN_NOTIFICATIONS = "open_notifications";

    private static final String CHANNEL_TRANSACTION = "nimbus_transactions";
    private static final String CHANNEL_EKYC = "nimbus_ekyc";
    private static final String CHANNEL_SECURITY = "nimbus_security";
    private static final String CHANNEL_SYSTEM = "nimbus_system";

    private static final String PREFS_NAME = "nimbus_notif_prefs";
    private static final String KEY_BASELINE_MS = "baseline_ms";
    private static final String KEY_NOTIFIED_IDS = "notified_ids";
    private static final long STARTUP_GRACE_MS = 10_000L;
    private static final int MAX_TRACKED_IDS = 120;

    private SystemNotificationHelper() {
    }

    public static void ensureChannels(@NonNull Context context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return;
        }
        NotificationManager manager = context.getSystemService(NotificationManager.class);
        if (manager == null) {
            return;
        }
        manager.createNotificationChannel(new NotificationChannel(
                CHANNEL_TRANSACTION,
                "Biến động số dư",
                NotificationManager.IMPORTANCE_HIGH
        ));
        manager.createNotificationChannel(new NotificationChannel(
                CHANNEL_EKYC,
                "Xác thực eKYC",
                NotificationManager.IMPORTANCE_HIGH
        ));
        manager.createNotificationChannel(new NotificationChannel(
                CHANNEL_SECURITY,
                "Cảnh báo bảo mật",
                NotificationManager.IMPORTANCE_HIGH
        ));
        manager.createNotificationChannel(new NotificationChannel(
                CHANNEL_SYSTEM,
                "Thông báo hệ thống",
                NotificationManager.IMPORTANCE_DEFAULT
        ));
    }

    public static void showIfNew(@NonNull Context context, @NonNull List<ReadModels.NotificationSummary> items) {
        ensureChannels(context);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                && ActivityCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
            return;
        }

        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        long baselineMs = prefs.getLong(KEY_BASELINE_MS, 0L);
        if (baselineMs == 0L) {
            baselineMs = System.currentTimeMillis() - STARTUP_GRACE_MS;
            prefs.edit().putLong(KEY_BASELINE_MS, baselineMs).apply();
        }

        Set<String> notifiedIds = new HashSet<>(prefs.getStringSet(KEY_NOTIFIED_IDS, new HashSet<>()));
        boolean changed = false;
        long newestMs = baselineMs;

        NotificationManagerCompat manager = NotificationManagerCompat.from(context);
        for (ReadModels.NotificationSummary item : items) {
            if (item.read || notifiedIds.contains(item.id)) {
                continue;
            }
            long createdMs = item.createdAt != null ? item.createdAt.toDate().getTime() : System.currentTimeMillis();
            if (createdMs < baselineMs) {
                continue;
            }
            if (createdMs > newestMs) {
                newestMs = createdMs;
            }

            postNotification(context, manager, item);
            notifiedIds.add(item.id);
            changed = true;
        }

        if (changed) {
            if (notifiedIds.size() > MAX_TRACKED_IDS) {
                notifiedIds.clear();
            }
            prefs.edit()
                    .putLong(KEY_BASELINE_MS, newestMs)
                    .putStringSet(KEY_NOTIFIED_IDS, notifiedIds)
                    .apply();
        }
    }

    private static void postNotification(
            @NonNull Context context,
            @NonNull NotificationManagerCompat manager,
            @NonNull ReadModels.NotificationSummary item
    ) {
        Intent intent = new Intent(context, MainActivity.class);
        intent.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        intent.putExtra(EXTRA_OPEN_NOTIFICATIONS, true);

        PendingIntent pendingIntent = PendingIntent.getActivity(
                context,
                item.id.hashCode(),
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );

        String channelId = channelFor(item.type);
        int priority = "SYSTEM".equals(item.type)
                ? NotificationCompat.PRIORITY_DEFAULT
                : NotificationCompat.PRIORITY_HIGH;

        NotificationCompat.Builder builder = new NotificationCompat.Builder(context, channelId)
                .setSmallIcon(smallIconFor(item.type))
                .setColor(ContextCompat.getColor(context, R.color.navy))
                .setContentTitle(item.title)
                .setContentText(item.body)
                .setStyle(new NotificationCompat.BigTextStyle().bigText(item.body))
                .setPriority(priority)
                .setAutoCancel(true)
                .setContentIntent(pendingIntent);

        manager.notify(item.id.hashCode(), builder.build());
    }

    @NonNull
    private static String channelFor(@NonNull String type) {
        switch (type) {
            case "TRANSACTION":
                return CHANNEL_TRANSACTION;
            case "EKYC":
                return CHANNEL_EKYC;
            case "SECURITY":
                return CHANNEL_SECURITY;
            default:
                return CHANNEL_SYSTEM;
        }
    }

    private static int smallIconFor(@NonNull String type) {
        switch (type) {
            case "TRANSACTION":
                return R.drawable.ic_receipt;
            case "EKYC":
                return R.drawable.ic_shield;
            case "SECURITY":
                return R.drawable.ic_lock;
            default:
                return R.drawable.ic_nav_notifications;
        }
    }
}
