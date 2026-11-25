package com.example.final_term.ui.customer;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class Phase2PresentationTest {
    @Test public void accountTypesUseCustomerFacingLabels() {
        assertEquals("Tài khoản thanh toán", Phase2Presentation.accountTitle("CHECKING"));
        assertEquals("Tài khoản tiết kiệm", Phase2Presentation.accountTitle("SAVINGS"));
        assertEquals("Khoản vay thế chấp", Phase2Presentation.accountTitle("MORTGAGE"));
    }

    @Test public void unknownAccountTypeHasSafeFallback() {
        assertEquals("Tài khoản", Phase2Presentation.accountTitle("OTHER"));
    }

    @Test public void moneyRequestCopyNeverClaimsBalanceMutation() {
        assertEquals("Chưa kết nối API yêu cầu nạp/rút. Yêu cầu chưa được gửi; số dư chưa thay đổi.",
                Phase2Presentation.requestStatusMessage("DEPOSIT"));
    }

    @Test public void withdrawalUsesRequestFormWhileDepositUsesCardFlow() {
        org.junit.Assert.assertTrue(Phase2Presentation.usesRequestForm("WITHDRAWAL"));
        org.junit.Assert.assertFalse(Phase2Presentation.usesRequestForm("DEPOSIT"));
    }

    @Test public void cashAndCardFlowsSkipRecipientLookup() {
        org.junit.Assert.assertFalse(Phase2Presentation.requiresRecipientLookup("DEPOSIT", false));
        org.junit.Assert.assertFalse(Phase2Presentation.requiresRecipientLookup("WITHDRAWAL", false));
        org.junit.Assert.assertTrue(Phase2Presentation.requiresRecipientLookup(null, false));
        org.junit.Assert.assertFalse(Phase2Presentation.requiresRecipientLookup(null, true));
    }
}
