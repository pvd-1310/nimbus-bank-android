package com.example.final_term.navigation;

import android.view.View;
import android.widget.Toast;

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
        if (activeDestination == homeDestination) home.setBackgroundResource(R.drawable.nav_active_background);
        if (activeDestination == R.id.accountList) {
            wallet.setBackgroundResource(R.drawable.nav_active_background);
            accounts.setBackgroundResource(R.drawable.nav_active_background);
        }
        home.setOnClickListener(v -> navigate(navController, homeDestination));
        wallet.setOnClickListener(v -> navigate(navController, R.id.accountList));
        accounts.setOnClickListener(v -> navigate(navController, R.id.accountList));
        quick.setOnClickListener(v -> Toast.makeText(v.getContext(),
                "Thao tác chuyển tiền và thanh toán sẽ mở sau Phase 3.", Toast.LENGTH_SHORT).show());
        profile.setOnClickListener(v -> Toast.makeText(v.getContext(),
                "Hồ sơ và cài đặt sẽ mở ở Phase 2.", Toast.LENGTH_SHORT).show());
    }

    private static void navigate(NavController navController, @IdRes int destination) {
        if (navController.getCurrentDestination() != null
                && navController.getCurrentDestination().getId() != destination) {
            navController.navigate(destination);
        }
    }
}
