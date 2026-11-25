package com.example.final_term.navigation;

import androidx.annotation.IdRes;

import com.example.final_term.R;

public final class BottomNavigationState {
    private BottomNavigationState() { }

    public static @IdRes int defaultItemFor(@IdRes int destination) {
        if (destination == R.id.customerHome || destination == R.id.officerHome) return R.id.nav_home;
        if (destination == R.id.officerCheckingAccounts || destination == R.id.officerCustomers) return R.id.nav_wallet;
        if (destination == R.id.profile) return R.id.nav_profile;
        return R.id.nav_accounts;
    }
}
