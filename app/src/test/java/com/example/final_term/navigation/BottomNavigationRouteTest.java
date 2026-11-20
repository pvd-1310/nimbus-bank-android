package com.example.final_term.navigation;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class BottomNavigationRouteTest {
    @Test public void homeTabReturnsHome() {
        assertEquals(BottomNavigationRoute.HOME, BottomNavigationRoute.forTab(BottomNavigationRoute.Tab.HOME));
    }

    @Test public void walletAndAccountsUsePhaseOneAccountReadScreen() {
        assertEquals(BottomNavigationRoute.ACCOUNTS, BottomNavigationRoute.forTab(BottomNavigationRoute.Tab.WALLET));
        assertEquals(BottomNavigationRoute.ACCOUNTS, BottomNavigationRoute.forTab(BottomNavigationRoute.Tab.ACCOUNTS));
    }

    @Test public void profileHasNoMutationRouteYet() {
        assertEquals(BottomNavigationRoute.PROFILE, BottomNavigationRoute.forTab(BottomNavigationRoute.Tab.PROFILE));
    }
}
