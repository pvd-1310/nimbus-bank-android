package com.example.final_term.ui.customer;

import com.example.final_term.data.ReadModels;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public final class TransactionHistoryFilter {
    private TransactionHistoryFilter() { }

    public static List<ReadModels.TransactionSummary> apply(List<ReadModels.TransactionSummary> source,
                                                             String query, String type, String status) {
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
            boolean statusMatch = status == null || status.isEmpty() || "ALL".equals(status) || status.equalsIgnoreCase(item.status);
            if (text && typeMatch && statusMatch) result.add(item);
        }
        return result;
    }
}
