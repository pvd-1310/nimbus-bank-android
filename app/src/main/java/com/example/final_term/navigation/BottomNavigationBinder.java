package com.example.final_term.navigation;

import android.view.View;
import android.os.Bundle;
import android.widget.TextView;

import androidx.annotation.IdRes;
import androidx.annotation.NonNull;
import androidx.navigation.NavController;

import com.example.final_term.R;

public final class BottomNavigationBinder {
    private BottomNavigationBinder() { }

    public static void bind(@NonNull View root, @NonNull NavController navController,
                            @IdRes int homeDestination, @IdRes int activeDestination) {
        View home = root.findViewById(R.id.nav_home);
        View wallet = root.findViewById(R.id.nav_wallet);
        View accounts = root.findViewById(R.id.nav_accounts);
        View profile = root.findViewById(R.id.nav_profile);
        View quick = root.findViewById(R.id.nav_quick_button);
        boolean officer = homeDestination == R.id.officerHome;
        ((TextView) root.findViewById(R.id.nav_home_label)).setText(officer ? "Dashboard" : "Trang chủ");
        ((TextView) root.findViewById(R.id.nav_wallet_label)).setText(officer ? "Khách hàng" : "Ví");
        ((TextView) root.findViewById(R.id.nav_accounts_label)).setText(officer ? "Duyệt" : "Lịch sử");
        ((TextView) root.findViewById(R.id.nav_profile_label)).setText(officer ? "Nhân sự" : "Cá nhân");
        quick.setContentDescription(officer ? "Tác vụ vận hành" : "Chuyển tiền");
        home.setBackgroundResource(0);
        wallet.setBackgroundResource(0);
        accounts.setBackgroundResource(0);
        profile.setBackgroundResource(0);
        int activeItem = BottomNavigationState.defaultItemFor(activeDestination);
        Bundle args = navController.getCurrentBackStackEntry() == null
                ? null : navController.getCurrentBackStackEntry().getArguments();
        if (args != null && args.containsKey("activeNavItem")) {
            activeItem = args.getInt("activeNavItem", activeItem);
        }
        if (activeDestination == R.id.accountList && navController.getCurrentBackStackEntry() != null) {
            Integer selectedItem = navController.getCurrentBackStackEntry()
                    .getSavedStateHandle().get("activeNavItem");
            if (selectedItem != null) activeItem = selectedItem;
        }
        if (activeDestination == homeDestination) activeItem = R.id.nav_home;
        home.setSelected(activeItem == R.id.nav_home);
        wallet.setSelected(activeItem == R.id.nav_wallet);
        accounts.setSelected(activeItem == R.id.nav_accounts);
        profile.setSelected(activeItem == R.id.nav_profile);
        if (activeItem == R.id.nav_home) home.setBackgroundResource(R.drawable.nav_active_background);
        if (activeItem == R.id.nav_wallet) wallet.setBackgroundResource(R.drawable.nav_active_background);
        if (activeItem == R.id.nav_accounts) accounts.setBackgroundResource(R.drawable.nav_active_background);
        if (activeItem == R.id.nav_profile) profile.setBackgroundResource(R.drawable.nav_active_background);
        home.setOnClickListener(v -> navigate(navController, homeDestination));
        wallet.setOnClickListener(v -> selectTab(root, navController, homeDestination, R.id.nav_wallet));
        accounts.setOnClickListener(v -> {
            if (officer) {
                Bundle officerArgs = new Bundle();
                officerArgs.putString("title", "Duyệt hồ sơ eKYC");
                officerArgs.putString("subtitle", "Xem và xử lý các yêu cầu xác thực danh tính.");
                navController.navigate(R.id.officerEkyc, officerArgs);
            } else {
                navigate(navController, R.id.transactionHistory, R.id.nav_accounts);
            }
        });
        quick.setOnClickListener(v -> {
            if (officer) {
                Bundle officerArgs = new Bundle();
                officerArgs.putString("title", "Kiểm tra giao dịch");
                officerArgs.putString("subtitle", "Theo dõi lịch sử và audit log theo permission.");
                navController.navigate(R.id.officerAudit, officerArgs);
            } else {
                navigate(navController, R.id.transfer);
            }
        });
        profile.setOnClickListener(v -> navigate(navController, R.id.profile));
    }

    private static void navigate(NavController navController, @IdRes int destination) {
        if (navController.getCurrentDestination() != null
                && navController.getCurrentDestination().getId() != destination) {
            navController.navigate(destination);
        }
    }

    private static void navigate(NavController navController, @IdRes int destination, @IdRes int activeItem) {
        if (navController.getCurrentDestination() != null
                && navController.getCurrentDestination().getId() != destination) {
            Bundle args = new Bundle();
            args.putInt("activeNavItem", activeItem);
            navController.navigate(destination, args);
        }
    }

    private static void selectTab(@NonNull View root, @NonNull NavController navController,
                                  @IdRes int homeDestination, @IdRes int activeItem) {
        if (navController.getCurrentDestination() != null
                && navController.getCurrentDestination().getId() == R.id.accountList) {
            navController.getCurrentBackStackEntry().getSavedStateHandle()
                    .set("activeNavItem", activeItem);
            bind(root, navController, homeDestination, R.id.accountList);
            return;
        }
        navigate(navController, R.id.accountList, activeItem);
    }
}
