package com.example.final_term.data;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public final class ExternalBankCatalogTest {
    @Test public void exposesReadableNamesForSupportedBankCodes() {
        assertEquals("Vietcombank", ExternalBankCatalog.nameForCode("VCB"));
        assertEquals("BIDV", ExternalBankCatalog.nameForCode("BIDV"));
        assertEquals("VietinBank", ExternalBankCatalog.nameForCode("ICB"));
        assertEquals("Techcombank", ExternalBankCatalog.nameForCode("TCB"));
        assertEquals("MB", ExternalBankCatalog.nameForCode("MB"));
    }

    @Test public void unknownCodesDoNotExposeTechnicalIdentifiers() {
        assertEquals("Ngân hàng khác", ExternalBankCatalog.nameForCode("UNKNOWN"));
    }

    @Test public void requiresTheCustomerToChooseADestinationBank() {
        assertEquals("Chọn ngân hàng nhận", ExternalBankCatalog.nameForCode(""));
        assertEquals(0, ExternalBankCatalog.positionForCode(""));
    }
}
