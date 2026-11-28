package com.example.final_term.navigation;

import com.example.final_term.auth.AppUser;
import com.example.final_term.auth.AuthState;
import com.example.final_term.auth.UserRole;
import org.junit.Test;
import static org.junit.Assert.*;

public class RoleNavigationTest {
    private final RootViewModel viewModel = new RootViewModel();

    @Test public void pendingRegistrationResumesRegistration() {
        AuthState pending = AuthState.registrationPending();
        assertEquals(RootViewModel.RootRoute.REGISTRATION, viewModel.routeFor(pending));
        org.junit.Assert.assertFalse(viewModel.keepsDestination(pending, pending));
    }

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

    @Test public void signedInProfileKeepsFullNameForGreeting() {
        AppUser user = new AppUser("c", "customer.demo@example.com", "PHAN VAN DUONG", UserRole.CUSTOMER, true);
        assertEquals("PHAN VAN DUONG", user.getFullName());
    }
    @Test public void profileRefreshKeepsTheCurrentScreenButSessionChangesDoNot() {
        AuthState customer = AuthState.signedIn(new AppUser("c", "", "Before", UserRole.CUSTOMER, true));
        AuthState updated = AuthState.signedIn(new AppUser("c", "", "After", UserRole.CUSTOMER, true));
        AuthState officer = AuthState.signedIn(new AppUser("o", "", UserRole.OFFICER, true));
        assertTrue(viewModel.keepsDestination(customer, updated));
        assertFalse(viewModel.keepsDestination(customer, officer));
        assertFalse(viewModel.keepsDestination(customer, AuthState.signedOut()));
    }
}
