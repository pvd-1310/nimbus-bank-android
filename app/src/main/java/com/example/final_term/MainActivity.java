package com.example.final_term;

import android.os.Bundle;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.lifecycle.ViewModelProvider;
import androidx.navigation.NavController;
import androidx.navigation.fragment.NavHostFragment;

import com.example.final_term.auth.AuthState;
import com.example.final_term.core.FinalTermApplication;
import com.example.final_term.navigation.RootViewModel;
import com.example.final_term.databinding.ActivityMainBinding;

public class MainActivity extends AppCompatActivity {
    private ActivityMainBinding binding;
    private NavController navController;
    private RootViewModel rootViewModel;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        WindowCompat.setDecorFitsSystemWindows(getWindow(), false);
        binding = ActivityMainBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());
        ViewCompat.setOnApplyWindowInsetsListener(binding.main, (v, insets) -> {
            Insets systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom);
            return insets;
        });

        NavHostFragment navHost = (NavHostFragment) getSupportFragmentManager()
                .findFragmentById(R.id.nav_host_fragment);
        if (navHost == null) throw new IllegalStateException("Navigation host is missing");
        navController = navHost.getNavController();
        rootViewModel = new ViewModelProvider(this).get(RootViewModel.class);

        FinalTermApplication application = (FinalTermApplication) getApplication();
        application.getAppContainer().getAuthRepository().observeAuthState().observe(this, this::routeFor);
    }

    private void routeFor(AuthState state) {
        RootViewModel.RootRoute route = rootViewModel.routeFor(state);
        int current = navController.getCurrentDestination() == null
                ? R.id.login : navController.getCurrentDestination().getId();
        if (route == RootViewModel.RootRoute.CUSTOMER_HOME && current != R.id.customerHome) {
            navController.navigate(R.id.customerHome);
        } else if (route == RootViewModel.RootRoute.OFFICER_HOME && current != R.id.officerHome) {
            navController.navigate(R.id.officerHome);
        } else if (route == RootViewModel.RootRoute.LOGIN && current != R.id.login) {
            navController.navigate(R.id.login, null,
                    new androidx.navigation.NavOptions.Builder()
                            .setPopUpTo(current, true)
                            .setLaunchSingleTop(true)
                            .build());
        }
    }

    public void signOutAndReturnToLogin() {
        ((FinalTermApplication) getApplication()).getAppContainer().getAuthRepository().signOut();
    }
}
