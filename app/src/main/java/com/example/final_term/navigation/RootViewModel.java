package com.example.final_term.navigation;

import androidx.annotation.NonNull;
import androidx.lifecycle.ViewModel;

import com.example.final_term.auth.AuthState;
import com.example.final_term.auth.UserRole;

public final class RootViewModel extends ViewModel {
    public enum RootRoute { LOGIN, CUSTOMER_HOME, OFFICER_HOME, ONBOARDING }

    public boolean keepsDestination(AuthState previous, AuthState next) {
        RootRoute previousRoute = routeFor(previous);
        return previousRoute != RootRoute.LOGIN && previousRoute == routeFor(next)
                && previous.getUser().getUid().equals(next.getUser().getUid());
    }

    @NonNull
    public RootRoute routeFor(AuthState state) {
        if (state == null || state.getStatus() != AuthState.Status.SIGNED_IN || state.getUser() == null) {
            return RootRoute.LOGIN;
        }
        if (state.getUser().getRole() == UserRole.CUSTOMER && state.getUser().isActive()
                && state.getUser().isOnboarding()) {
            return RootRoute.ONBOARDING;
        }
        if (state.getUser().getRole() == UserRole.CUSTOMER && state.getUser().isActive()) {
            return RootRoute.CUSTOMER_HOME;
        }
        if (state.getUser().getRole() == UserRole.OFFICER && state.getUser().isActive()) {
            return RootRoute.OFFICER_HOME;
        }
        return RootRoute.LOGIN;
    }
}
