package com.example.final_term.navigation;

import com.example.final_term.R;
import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class BottomNavigationStateTest {
    @Test public void accountRouteHasOneDefaultActiveItem() {
        assertEquals(R.id.nav_accounts, BottomNavigationState.defaultItemFor(R.id.accountList));
    }

    @Test public void homeAndProfileRoutesHaveDistinctActiveItems() {
        assertEquals(R.id.nav_home, BottomNavigationState.defaultItemFor(R.id.customerHome));
        assertEquals(R.id.nav_profile, BottomNavigationState.defaultItemFor(R.id.profile));
    }

    @Test public void officerCustomerManagementRoutesShareTheWalletTab() {
        assertEquals(R.id.nav_wallet, BottomNavigationState.defaultItemFor(R.id.officerCheckingAccounts));
        assertEquals(R.id.nav_wallet, BottomNavigationState.defaultItemFor(R.id.officerCustomers));
    }
}
