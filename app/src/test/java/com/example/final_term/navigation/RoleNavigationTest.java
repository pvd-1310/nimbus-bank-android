package com.example.final_term.navigation;

import com.example.final_term.auth.AppUser;
import com.example.final_term.auth.AuthState;
import com.example.final_term.auth.UserRole;
import org.junit.Test;
import static org.junit.Assert.*;

public class RoleNavigationTest {
    private final RootViewModel viewModel = new RootViewModel();

    @Test public void signedOutGoesToLogin() { assertEquals(RootViewModel.RootRoute.LOGIN, viewModel.routeFor(AuthState.signedOut())); }
    @Test public void activeCustomerGoesOnlyToCustomerHome() {
        AppUser user = new AppUser("c", "customer.demo@example.com", UserRole.CUSTOMER, true);
        assertEquals(RootViewModel.RootRoute.CUSTOMER_HOME, viewModel.routeFor(AuthState.signedIn(user)));
    }
    @Test public void activeOfficerGoesOnlyToOfficerHome() {
        AppUser user = new AppUser("o", "officer.demo@example.com", UserRole.OFFICER, true);
        assertEquals(RootViewModel.RootRoute.OFFICER_HOME, viewModel.routeFor(AuthState.signedIn(user)));
    }
    @Test public void unknownOrInactiveRoleReturnsToLogin() {
        assertEquals(RootViewModel.RootRoute.LOGIN, viewModel.routeFor(AuthState.signedIn(new AppUser("x", "x", UserRole.UNKNOWN, true))));
        assertEquals(RootViewModel.RootRoute.LOGIN, viewModel.routeFor(AuthState.signedIn(new AppUser("x", "x", UserRole.CUSTOMER, false))));
    }
}
