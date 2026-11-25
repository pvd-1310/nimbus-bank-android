package com.example.final_term.ui.customer;

import com.example.final_term.data.ReadModels;
import com.google.firebase.Timestamp;
import org.junit.Test;
import java.util.Arrays;
import java.util.List;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import com.example.final_term.data.ReadSummaryFormatter;

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

    @Test public void accountDateTypeAndStatusFiltersCombine() {
        ReadModels.TransactionSummary inRange = new ReadModels.TransactionSummary("deposit-1", "sav-1", "DEMO_DEPOSIT", "VND", 100, "DEMO_SETTLED", new Timestamp(new java.util.Date(2000)));
        ReadModels.TransactionSummary outside = new ReadModels.TransactionSummary("withdrawal-1", "sav-1", "DEMO_WITHDRAWAL", "VND", 50, "PENDING", new Timestamp(new java.util.Date(900)));
        assertEquals(1, TransactionHistoryFilter.apply(Arrays.asList(inRange, outside), "", "CREDIT", "DEMO_SETTLED", "sav-1", 1000L, 3000L).size());
        assertEquals(0, TransactionHistoryFilter.apply(Arrays.asList(inRange, outside), "", "ALL", "SETTLED", "sav-1", null, null).size());
    }

    @Test public void customerTransactionCopyHidesTestSettlementAndRawStatuses() {
        ReadModels.TransactionSummary settled = new ReadModels.TransactionSummary("deposit-1", "a", "DEMO_DEPOSIT", "VND", 100, "DEMO_SETTLED", (Timestamp) null);
        ReadModels.TransactionSummary failed = new ReadModels.TransactionSummary("withdrawal-1", "a", "DEMO_WITHDRAWAL", "VND", 50, "FAILED", (Timestamp) null);
        assertEquals("Nạp tiền · Thành công", ReadSummaryFormatter.transactionTitle(settled));
        assertEquals("Rút tiền · Thất bại", ReadSummaryFormatter.transactionTitle(failed));
        assertTrue(!ReadSummaryFormatter.transactionTitle(settled).contains("mô phỏng"));
    }
}
