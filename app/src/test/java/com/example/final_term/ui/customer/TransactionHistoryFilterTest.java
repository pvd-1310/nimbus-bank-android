package com.example.final_term.ui.customer;

import com.example.final_term.data.ReadModels;
import com.google.firebase.Timestamp;
import org.junit.Test;
import java.util.Arrays;
import java.util.List;
import static org.junit.Assert.assertEquals;

public class TransactionHistoryFilterTest {
    private final List<ReadModels.TransactionSummary> rows = Arrays.asList(
            new ReadModels.TransactionSummary("salary-1", "a", "CREDIT", "VND", 100, "SETTLED", (Timestamp) null),
            new ReadModels.TransactionSummary("food-1", "a", "DEBIT", "VND", 20, "PENDING", (Timestamp) null));

    @Test public void matchesQueryTypeAndStatusCaseInsensitively() {
        assertEquals(1, TransactionHistoryFilter.apply(rows, "salary", "ALL", "SETTLED").size());
        assertEquals(1, TransactionHistoryFilter.apply(rows, "", "DEBIT", "ALL").size());
    }

    @Test public void nullSourceIsEmpty() {
        assertEquals(0, TransactionHistoryFilter.apply(null, "", "ALL", "ALL").size());
    }
}
