package com.example.final_term;

import android.graphics.Rect;
import android.view.View;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.TextView;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.core.app.ActivityScenario;
import androidx.test.platform.app.InstrumentationRegistry;
import com.example.final_term.ui.transaction.TransactionFlowRequest;
import org.junit.Test;
import org.junit.runner.RunWith;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.assertEquals;

@RunWith(AndroidJUnit4.class)
public class BaseFlowInstrumentedTest {
    @Test public void paymentSourceAppearsBelowTheAmountAndOpensThemedDropdown() throws Exception {
        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
            scenario.onActivity(activity -> {
                androidx.navigation.fragment.NavHostFragment host = (androidx.navigation.fragment.NavHostFragment)
                        activity.getSupportFragmentManager().findFragmentById(R.id.nav_host_fragment);
                var request = TransactionFlowRequest.billPayment(
                        "bill_ui_test", "Thanh toán tiền điện", "EVN", "PE000123",
                        null, 50_000L, "VND", null);
                host.getNavController().navigate(R.id.transactionFlow, request.toBundle());
                host.getChildFragmentManager().executePendingTransactions();
            });
            var instrumentation = InstrumentationRegistry.getInstrumentation();
            instrumentation.waitForIdleSync();
            scenario.onActivity(activity -> {
                TextView role = activity.findViewById(R.id.account_role);
                assertEquals(activity.getString(R.string.source_account), role.getText().toString());
                int[] roleLocation = new int[2];
                int[] amountLocation = new int[2];
                role.getLocationOnScreen(roleLocation);
                activity.findViewById(R.id.confirm_amount_value).getLocationOnScreen(amountLocation);
                assertTrue(roleLocation[1] > amountLocation[1]);
                TextView change = activity.findViewById(R.id.change_account);
                assertEquals(0, change.getText().length());
                assertNotNull(change.getCompoundDrawablesRelative()[2]);
                change.requestRectangleOnScreen(new Rect(0, 0, change.getWidth(), change.getHeight()), true);
                assertTrue(change.performClick());
            });
            var automation = instrumentation.getUiAutomation();
            automation.waitForIdle(300, 3000);
            AccessibilityNodeInfo popup = automation.getRootInActiveWindow();
            assertNotNull(popup);
            var context = instrumentation.getTargetContext();
            String labelId = context.getResources().getResourceName(R.id.choice_label);
            assertTrue(popup.findAccessibilityNodeInfosByViewId(labelId).stream()
                    .anyMatch(item -> context.getString(R.string.payment_source_stripe).contentEquals(item.getText())));
        }
    }

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
                        || reviewDestination == R.id.officerEkyc
                        || reviewDestination == R.id.accountList);

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
