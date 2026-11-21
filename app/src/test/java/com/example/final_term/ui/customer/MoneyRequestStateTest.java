package com.example.final_term.ui.customer;

import org.junit.Test;
import static org.junit.Assert.assertTrue;

public class MoneyRequestStateTest {
    @Test public void rejectsAmountsOutsideTheBackendIntegerRange() {
        assertTrue(Phase2Presentation.validRequestAmount("9007199254740991"));
        org.junit.Assert.assertFalse(Phase2Presentation.validRequestAmount("9007199254740992"));
    }
    @Test public void rejectsZeroDecimalsNegativeAndOverflow() {
        for (String value : new String[]{"", "0", "00", "0.00", "-1", "1.5", "abc", "9999999999999999999"})
            org.junit.Assert.assertFalse(value, Phase2Presentation.validRequestAmount(value));
        assertTrue(Phase2Presentation.validRequestAmount("10000"));
    }
    @Test public void unavailableAdapterMustNotClaimRequestWasSubmitted() {
        assertTrue(Phase2Presentation.requestStatusMessage("DEPOSIT").contains("chưa được gửi"));
    }
    @Test public void depositCopyStatesBalanceDoesNotChange() {
        assertTrue(Phase2Presentation.requestStatusMessage("DEPOSIT").contains("chưa thay đổi"));
    }

    @Test public void withdrawalCopyStatesBalanceDoesNotChange() {
        assertTrue(Phase2Presentation.requestStatusMessage("WITHDRAWAL").contains("chưa thay đổi"));
    }
}
