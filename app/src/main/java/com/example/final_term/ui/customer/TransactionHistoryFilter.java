package com.example.final_term.ui.customer;

import com.example.final_term.data.ReadModels;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public final class TransactionHistoryFilter {
    private TransactionHistoryFilter() { }

    public static List<ReadModels.TransactionSummary> apply(List<ReadModels.TransactionSummary> source,
                                                             String query, String type, String status) {
        return apply(source, query, type, status, "", null, null);
    }
    public static List<ReadModels.TransactionSummary> apply(List<ReadModels.TransactionSummary> source,
                                                             String query, String type, String status,
                                                             String accountId, Long from, Long to) {
        List<ReadModels.TransactionSummary> result = new ArrayList<>();
        if (source == null) return result;
        String q = query == null ? "" : query.trim().toLowerCase(Locale.ROOT);
        for (ReadModels.TransactionSummary item : source) {
            boolean text = q.isEmpty() || item.type.toLowerCase(Locale.ROOT).contains(q)
                    || item.status.toLowerCase(Locale.ROOT).contains(q)
                    || item.id.toLowerCase(Locale.ROOT).contains(q)
                    || item.accountId.toLowerCase(Locale.ROOT).contains(q);
            boolean typeMatch = type == null || type.isEmpty() || "ALL".equals(type) || type.equalsIgnoreCase(item.type)
                    || ("DEBIT".equals(type) && item.isOutgoing()) || ("CREDIT".equals(type) && item.isIncoming());
            boolean statusMatch = status == null || status.isEmpty() || "ALL".equals(status)
                    || ("COMPLETED".equals(status) && ("SETTLED".equals(item.status) || "DEMO_SETTLED".equals(item.status)))
                    || ("PROCESSING".equals(status) && "PENDING".equals(item.status))
                    || ("FAILED".equals(status) && "FAILED".equals(item.status))
                    || status.equalsIgnoreCase(item.status);
            boolean accountMatch = accountId == null || accountId.isEmpty() || accountId.equalsIgnoreCase(item.accountId);
            long timestamp = item.createdAt == null ? Long.MIN_VALUE : item.createdAt.toDate().getTime();
            boolean dateMatch = (from == null || timestamp >= from) && (to == null || timestamp <= to);
            if (text && typeMatch && statusMatch && accountMatch && dateMatch) result.add(item);
        }
        return result;
    }
}
