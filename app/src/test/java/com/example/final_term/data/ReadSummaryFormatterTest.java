package com.example.final_term.data;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class ReadSummaryFormatterTest {
    @Test public void formatsVndBalanceWithVietnameseSeparators() {
        assertEquals("25.000.000 ₫", ReadSummaryFormatter.money(25000000L));
    }

    @Test public void formatsSignedTransactionAmount() {
        assertEquals("+25.000.000 ₫", ReadSummaryFormatter.signedMoney(25000000L));
        assertEquals("−10.000 ₫", ReadSummaryFormatter.signedMoney(-10000L));
    }

    @Test public void includesDestinationBankInExternalTransferDetails() {
        ReadModels.TransactionSummary transaction = new ReadModels.TransactionSummary(
                "tx", "checking", "DEMO_EXTERNAL_TRANSFER", "VND", 500000,
                "DEMO_SETTLED", null, null, 1000000L, "BIDV");
        assertTrue(ReadSummaryFormatter.transactionDetail(transaction).contains("Ngân hàng nhận: BIDV"));
    }
}
