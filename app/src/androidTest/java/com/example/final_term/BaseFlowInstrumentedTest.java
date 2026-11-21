package com.example.final_term;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.core.app.ActivityScenario;
import org.junit.Test;
import org.junit.runner.RunWith;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.assertEquals;

@RunWith(AndroidJUnit4.class)
public class BaseFlowInstrumentedTest {
    @Test public void missingAccountNeverRendersAnInventedZeroBalance() {
        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
            scenario.onActivity(activity -> {
                androidx.navigation.fragment.NavHostFragment host = (androidx.navigation.fragment.NavHostFragment)
                        activity.getSupportFragmentManager().findFragmentById(R.id.nav_host_fragment);
                for (int destination : new int[]{R.id.accountDetail, R.id.savingsDetail, R.id.mortgageDetail}) {
                    host.getNavController().navigate(destination);
                    host.getChildFragmentManager().executePendingTransactions();
                    assertEquals("—", ((android.widget.TextView) activity.findViewById(R.id.balance_text)).getText().toString());
                    assertTrue(((android.widget.TextView) activity.findViewById(R.id.status_text))
                            .getText().toString().contains("Thiếu mã tài khoản"));
                }
            });
        }
    }
    @Test public void logoutClearsAllAuthenticatedHistory() {
        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
            scenario.onActivity(activity -> {
                androidx.navigation.NavController controller = ((androidx.navigation.fragment.NavHostFragment)
                        activity.getSupportFragmentManager().findFragmentById(R.id.nav_host_fragment)).getNavController();
                controller.navigate(R.id.accountList);
                controller.navigate(R.id.transactionHistory);
                controller.navigate(R.id.profile);
                ((androidx.navigation.fragment.NavHostFragment) activity.getSupportFragmentManager()
                        .findFragmentById(R.id.nav_host_fragment)).getChildFragmentManager().executePendingTransactions();
                android.view.View logout = activity.findViewById(R.id.logout_button);
                assertNotNull(logout);
                assertEquals(android.view.View.VISIBLE, logout.getVisibility());
                logout.performClick();
            });
            androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().waitForIdleSync();
            scenario.onActivity(activity -> {
                androidx.navigation.NavController controller = ((androidx.navigation.fragment.NavHostFragment)
                        activity.getSupportFragmentManager().findFragmentById(R.id.nav_host_fragment)).getNavController();
                assertEquals(R.id.login, controller.getCurrentDestination().getId());
                org.junit.Assert.assertNull(controller.getPreviousBackStackEntry());
                assertEquals(android.view.View.GONE, activity.findViewById(R.id.main_bottom_navigation).getVisibility());
            });
            scenario.recreate();
            scenario.onActivity(activity -> {
                androidx.navigation.NavController controller = ((androidx.navigation.fragment.NavHostFragment)
                        activity.getSupportFragmentManager().findFragmentById(R.id.nav_host_fragment)).getNavController();
                assertEquals(R.id.login, controller.getCurrentDestination().getId());
                org.junit.Assert.assertNull(controller.getPreviousBackStackEntry());
            });
        }
    }
    @Test public void roleNavigationKeepsWalletAndReviewRoutesDistinct() {
        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
            scenario.onActivity(activity -> {
                androidx.navigation.NavController controller = ((androidx.navigation.fragment.NavHostFragment)
                        activity.getSupportFragmentManager().findFragmentById(R.id.nav_host_fragment))
                        .getNavController();
                android.os.Bundle args = new android.os.Bundle();
                args.putInt("activeNavItem", R.id.nav_wallet);
                controller.navigate(R.id.accountList, args);
                android.view.View bar = activity.findViewById(R.id.main_bottom_navigation);
                bar.findViewById(R.id.nav_wallet).performClick();
                assertEquals(R.id.accountList, controller.getCurrentDestination().getId());
                assertTrue(bar.findViewById(R.id.nav_wallet).isSelected());

                bar.findViewById(R.id.nav_accounts).performClick();
                int reviewDestination = controller.getCurrentDestination().getId();
                assertTrue(reviewDestination == R.id.transactionHistory
                        || reviewDestination == R.id.officerEkyc);

                bar.findViewById(R.id.nav_wallet).performClick();
                assertEquals(R.id.accountList, controller.getCurrentDestination().getId());
                assertTrue(bar.findViewById(R.id.nav_wallet).isSelected());
            });
        }
    }
    @Test public void loginScreenRendersWithConfiguredOrRecoverableFirebaseState() {
        ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class);
        try {
            scenario.onActivity(activity -> {
                assertNotNull(activity.findViewById(com.example.final_term.R.id.nav_host_fragment));
                int destination = ((androidx.navigation.fragment.NavHostFragment) activity.getSupportFragmentManager()
                        .findFragmentById(com.example.final_term.R.id.nav_host_fragment))
                        .getNavController().getCurrentDestination().getId();
                assertTrue(destination == com.example.final_term.R.id.login
                        || destination == com.example.final_term.R.id.customerHome
                        || destination == com.example.final_term.R.id.officerHome);
                assertNotNull(((androidx.navigation.fragment.NavHostFragment) activity.getSupportFragmentManager()
                        .findFragmentById(com.example.final_term.R.id.nav_host_fragment))
                        .getNavController().getGraph().findNode(com.example.final_term.R.id.profile));
            });
        } finally {
            scenario.close();
        }
    }
}
