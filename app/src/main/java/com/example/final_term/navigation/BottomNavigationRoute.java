package com.example.final_term.navigation;

public enum BottomNavigationRoute {
    HOME, ACCOUNTS, PROFILE;

    public enum Tab { HOME, WALLET, ACCOUNTS, PROFILE }

    public static BottomNavigationRoute forTab(Tab tab) {
        if (tab == Tab.WALLET || tab == Tab.ACCOUNTS) return ACCOUNTS;
        if (tab == Tab.PROFILE) return PROFILE;
        return HOME;
    }
}
