package com.example.final_term.navigation;

import android.os.Bundle;
import android.view.View;
import android.widget.ImageView;
import android.widget.TextView;
import android.content.res.ColorStateList;

import androidx.annotation.IdRes;
import androidx.annotation.NonNull;
import androidx.fragment.app.FragmentActivity;
import androidx.navigation.NavController;

import com.example.final_term.R;
import com.example.final_term.ui.quickactions.QuickActionsBottomSheet;

public final class BottomNavigationBinder {
    private BottomNavigationBinder() { }

    public static void bind(@NonNull View root, @NonNull NavController navController,
                            @IdRes int homeDestination, @IdRes int activeDestination) {
        View home = root.findViewById(R.id.nav_home);
        View wallet = root.findViewById(R.id.nav_wallet);
        View accounts = root.findViewById(R.id.nav_accounts);
        View profile = root.findViewById(R.id.nav_profile);
        View quickContainer = root.findViewById(R.id.nav_quick);
        View quick = root.findViewById(R.id.nav_quick_button);
        boolean officer = homeDestination == R.id.officerHome;

        if (quickContainer != null) {
            quickContainer.setVisibility(officer ? View.GONE : View.VISIBLE);
        }

        ImageView homeIcon = root.findViewById(R.id.nav_home_icon);
        ImageView walletIcon = root.findViewById(R.id.nav_wallet_icon);
        ImageView accountsIcon = root.findViewById(R.id.nav_accounts_icon);
        ImageView profileIcon = root.findViewById(R.id.nav_profile_icon);
        if (homeIcon != null) homeIcon.setImageResource(R.drawable.ic_nav_home);
        if (walletIcon != null) {
            walletIcon.setImageResource(officer ? R.drawable.ic_officer_customers : R.drawable.ic_nav_wallet);
        }
        if (accountsIcon != null) {
            accountsIcon.setImageResource(officer ? R.drawable.ic_shield : R.drawable.ic_nav_accounts);
        }
        if (profileIcon != null) profileIcon.setImageResource(R.drawable.ic_nav_person);

        ((TextView) root.findViewById(R.id.nav_home_label)).setText(officer ? "Tổng quan" : "Trang chủ");
        ((TextView) root.findViewById(R.id.nav_wallet_label)).setText(officer ? "Khách hàng" : "Ví");
        ((TextView) root.findViewById(R.id.nav_accounts_label)).setText(officer ? "eKYC" : "Thẻ");
        ((TextView) root.findViewById(R.id.nav_profile_label)).setText("Cá nhân");
        quick.setContentDescription("Thao tác nhanh");

        home.setBackgroundResource(0);
        wallet.setBackgroundResource(0);
        accounts.setBackgroundResource(0);
        profile.setBackgroundResource(0);

        int activeItem = BottomNavigationState.defaultItemFor(activeDestination);
        Bundle args = navController.getCurrentBackStackEntry() == null
                ? null : navController.getCurrentBackStackEntry().getArguments();
        if (activeDestination == R.id.accountList && navController.getCurrentBackStackEntry() != null) {
            if (args != null && args.containsKey("activeNavItem")) {
                activeItem = args.getInt("activeNavItem", activeItem);
            } else {
                Integer selectedItem = navController.getCurrentBackStackEntry()
                        .getSavedStateHandle().get("activeNavItem");
                if (selectedItem != null) activeItem = selectedItem;
            }
        } else if (args != null && args.containsKey("activeNavItem")) {
            activeItem = args.getInt("activeNavItem", activeItem);
        }
        if (activeDestination == homeDestination) activeItem = R.id.nav_home;

        home.setSelected(activeItem == R.id.nav_home);
        wallet.setSelected(activeItem == R.id.nav_wallet);
        accounts.setSelected(activeItem == R.id.nav_accounts);
        profile.setSelected(activeItem == R.id.nav_profile);

        tintItem(root, R.id.nav_home, activeItem == R.id.nav_home);
        tintItem(root, R.id.nav_wallet, activeItem == R.id.nav_wallet);
        tintItem(root, R.id.nav_accounts, activeItem == R.id.nav_accounts);
        tintItem(root, R.id.nav_profile, activeItem == R.id.nav_profile);

        home.setOnClickListener(v -> navigate(navController, homeDestination));
        wallet.setOnClickListener(v -> {
            if (officer) navigate(navController, R.id.officerCheckingAccounts);
            else selectTab(root, navController, homeDestination, R.id.nav_wallet);
        });

        accounts.setOnClickListener(v -> {
            if (officer) {
                Bundle officerArgs = new Bundle();
                officerArgs.putString("title", "Hồ sơ eKYC");
                officerArgs.putString("subtitle", "Theo dõi kết quả kiểm tra tự động và bằng chứng hồ sơ.");
                navController.navigate(R.id.officerEkyc, officerArgs);
            } else {
                selectTab(root, navController, homeDestination, R.id.nav_accounts);
            }
        });

        quick.setOnClickListener(v -> {
            if (officer) return;
            if (root.getContext() instanceof FragmentActivity) {
                FragmentActivity activity = (FragmentActivity) root.getContext();
                QuickActionsBottomSheet sheet = new QuickActionsBottomSheet();
                sheet.show(activity.getSupportFragmentManager(), "QuickActions");
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

    private static void tintItem(@NonNull View root, @IdRes int itemId, boolean selected) {
        View item = root.findViewById(itemId);
        if (item == null) return;
        int color = androidx.core.content.ContextCompat.getColor(root.getContext(),
                selected ? R.color.navy_deep : R.color.ink_muted);
        ImageView icon = item instanceof android.view.ViewGroup
                ? (ImageView) ((android.view.ViewGroup) item).getChildAt(0) : null;
        TextView label = item instanceof android.view.ViewGroup
                ? (TextView) ((android.view.ViewGroup) item).getChildAt(1) : null;
        if (icon != null) icon.setImageTintList(ColorStateList.valueOf(color));
        if (label != null) label.setTextColor(color);
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
            if (navController.getCurrentBackStackEntry() != null) {
                if (navController.getCurrentBackStackEntry().getArguments() != null) {
                    navController.getCurrentBackStackEntry().getArguments().putInt("activeNavItem", activeItem);
                }
                navController.getCurrentBackStackEntry().getSavedStateHandle()
                        .set("activeNavItem", activeItem);
            }
            bind(root, navController, homeDestination, R.id.accountList);
            return;
        }
        navigate(navController, R.id.accountList, activeItem);
    }
}
