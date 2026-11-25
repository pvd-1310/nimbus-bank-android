package com.example.final_term.data;

import androidx.annotation.NonNull;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;

import com.google.firebase.Timestamp;
import com.google.firebase.firestore.DocumentSnapshot;
import com.google.firebase.firestore.FirebaseFirestore;
import com.google.firebase.firestore.ListenerRegistration;
import com.google.firebase.firestore.MetadataChanges;
import com.google.firebase.firestore.Query;
import com.google.firebase.firestore.QuerySnapshot;

import java.util.ArrayList;
import java.util.List;

public final class FirestoreReadRepository {
    private final FirebaseFirestore firestore;

    public FirestoreReadRepository(FirebaseFirestore firestore) {
        this.firestore = firestore;
    }

    public LiveData<ReadModelResult<List<ReadModels.AccountSummary>>> observeAccounts(String uid) {
        if (firestore == null) {
            return error("Chưa cấu hình Firebase.");
        }
        if (uid == null || uid.trim().isEmpty()) {
            return error("Không xác định được người dùng.");
        }
        return observeQuery(firestore.collection(FirestoreCollections.ACCOUNTS)
                        .whereEqualTo("ownerUid", uid),
                this::mapAccounts);
    }

    public LiveData<ReadModelResult<List<ReadModels.AccountSummary>>> observeAllAccountsForOfficer() {
        if (firestore == null) return error("Chưa cấu hình Firebase.");
        return observeQuery(firestore.collection(FirestoreCollections.ACCOUNTS), this::mapAccounts);
    }

    public LiveData<ReadModelResult<List<ReadModels.TransactionSummary>>> observeTransactions(String uid) {
        if (firestore == null) {
            return error("Chưa cấu hình Firebase.");
        }
        if (uid == null || uid.trim().isEmpty()) {
            return error("Không xác định được người dùng.");
        }
        return observeQuery(firestore.collection(FirestoreCollections.TRANSACTIONS)
                        .whereEqualTo("ownerUid", uid),
                this::mapTransactions);
    }

    public LiveData<ReadModelResult<List<ReadModels.TransactionSummary>>> observeAllTransactionsForOfficer() {
        if (firestore == null) return error("Chưa cấu hình Firebase.");
        return observeQuery(firestore.collection(FirestoreCollections.TRANSACTIONS), this::mapTransactions);
    }

    public LiveData<ReadModelResult<List<ReadModels.NotificationSummary>>> observeNotifications(String uid) {
        if (firestore == null) {
            return error("Chưa cấu hình Firebase.");
        }
        if (uid == null || uid.trim().isEmpty()) {
            return error("Không xác định được người dùng.");
        }
        return observeQuery(firestore.collection(FirestoreCollections.NOTIFICATIONS)
                        .whereEqualTo("ownerUid", uid),
                this::mapNotifications);
    }

    private <T> LiveData<ReadModelResult<List<T>>> observeQuery(
            Query query, SnapshotMapper<T> mapper) {
        return new MutableLiveData<ReadModelResult<List<T>>>(ReadModelResult.loading()) {
            private ListenerRegistration registration;

            @Override
            protected void onActive() {
                registration = query.addSnapshotListener(MetadataChanges.INCLUDE, (snapshot, error) -> {
                    if (error != null || snapshot == null) {
                        postValue(ReadModelResult.error("Không thể tải dữ liệu. Vui lòng thử lại."));
                        return;
                    }
                    List<T> mapped = mapper.map(snapshot);
                    if (snapshot.getMetadata().isFromCache()) {
                        postValue(ReadModelResult.cached(mapped,
                                "Dữ liệu đang ở chế độ đọc offline hoặc chưa đồng bộ mới nhất."));
                    } else {
                        postValue(ReadModelResult.success(mapped));
                    }
                });
            }

            @Override
            protected void onInactive() {
                if (registration != null) registration.remove();
                registration = null;
            }
        };
    }

    private <T> LiveData<ReadModelResult<List<T>>> error(String message) {
        MutableLiveData<ReadModelResult<List<T>>> result = new MutableLiveData<>();
        result.setValue(ReadModelResult.error(message));
        return result;
    }

    private List<ReadModels.AccountSummary> mapAccounts(QuerySnapshot snapshot) {
        List<ReadModels.AccountSummary> result = new ArrayList<>();
        for (DocumentSnapshot document : snapshot.getDocuments()) {
            result.add(new ReadModels.AccountSummary(document.getId(),
                    document.getString("type"), document.getString("currency"),
                    longValue(document, "balanceMinor"), document.getString("status"),
                    document.getString("ownerUid"), document.getLong("availableMinor"),
                    Boolean.TRUE.equals(document.getBoolean("demo"))));
        }
        return result;
    }

    private List<ReadModels.TransactionSummary> mapTransactions(QuerySnapshot snapshot) {
        List<ReadModels.TransactionSummary> result = new ArrayList<>();
        for (DocumentSnapshot document : snapshot.getDocuments()) {
            String accountId = document.getString("accountId");
            result.add(new ReadModels.TransactionSummary(document.getId(),
                    accountId == null ? document.getString("sourceAccountId") : accountId, document.getString("type"),
                    document.getString("currency"), longValue(document, "amountMinor"),
                    document.getString("status"),
                    readTimestamp(document.get("createdAt"), document.get("committedAt")),
                    document.getString("failureCode"), document.getLong("balanceAfterMinor"),
                    document.getString("destinationBankCode")));
        }
        result.sort(java.util.Comparator.comparing(
                (ReadModels.TransactionSummary item) -> item.createdAt,
                java.util.Comparator.nullsLast(java.util.Comparator.reverseOrder())));
        return result;
    }

    private List<ReadModels.NotificationSummary> mapNotifications(QuerySnapshot snapshot) {
        List<ReadModels.NotificationSummary> result = new ArrayList<>();
        for (DocumentSnapshot document : snapshot.getDocuments()) {
            Boolean read = document.getBoolean("read");
            result.add(new ReadModels.NotificationSummary(document.getId(),
                    document.getString("title"), document.getString("body"),
                    Boolean.TRUE.equals(read), readTimestamp(document.get("createdAt"), null)));
        }
        return result;
    }

    static Timestamp readTimestamp(Object createdAt, Object committedAt) {
        Timestamp timestamp = asTimestamp(createdAt);
        return timestamp != null ? timestamp : asTimestamp(committedAt);
    }

    private static Timestamp asTimestamp(Object value) {
        if (value instanceof Timestamp) return (Timestamp) value;
        // Backend writes epoch milliseconds; Firestore returns integer fields as Long.
        if (!(value instanceof Long)) return null;
        long millis = (Long) value;
        long seconds = Math.floorDiv(millis, 1_000L);
        if (seconds < -62_135_596_800L || seconds >= 253_402_300_800L) return null;
        return new Timestamp(seconds, (int) Math.floorMod(millis, 1_000L) * 1_000_000);
    }

    private static long longValue(DocumentSnapshot document, String field) {
        Long value = document.getLong(field);
        return value == null ? 0L : value;
    }

    private interface SnapshotMapper<T> {
        List<T> map(QuerySnapshot snapshot);
    }
}
