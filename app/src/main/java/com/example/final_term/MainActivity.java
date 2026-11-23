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
import com.example.final_term.auth.UserRole;
import com.example.final_term.navigation.BottomNavigationBinder;
import com.example.final_term.navigation.RootViewModel;
import com.example.final_term.databinding.ActivityMainBinding;

public class MainActivity extends AppCompatActivity {
    private ActivityMainBinding binding;
    private NavController navController;
    private RootViewModel rootViewModel;
    private AuthState latestAuthState;

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
        binding.mainBottomNavigation.getRoot().setVisibility(android.view.View.GONE);
        binding.mainBottomNavigation.getRoot().bringToFront();
        navController.addOnDestinationChangedListener((controller, destination, arguments) ->
                syncBottomNavigation(destination.getId()));

        FinalTermApplication application = (FinalTermApplication) getApplication();
        application.getAppContainer().getAuthRepository().observeAuthState().observe(this, this::routeFor);
    }

    private void routeFor(AuthState state) {
        latestAuthState = state;
        RootViewModel.RootRoute route = rootViewModel.routeFor(state);
        int current = navController.getCurrentDestination() == null
                ? R.id.login : navController.getCurrentDestination().getId();
        if (route == RootViewModel.RootRoute.CUSTOMER_HOME && current != R.id.customerHome) {
            navController.navigate(R.id.customerHome);
        } else if (route == RootViewModel.RootRoute.ONBOARDING && current != R.id.profile) {
            navController.navigate(R.id.profile);
        } else if (route == RootViewModel.RootRoute.OFFICER_HOME && current != R.id.officerHome) {
            navController.navigate(R.id.officerHome);
        } else if (route == RootViewModel.RootRoute.LOGIN && current != R.id.login) {
            navController.navigate(R.id.login, null,
                    new androidx.navigation.NavOptions.Builder()
                            .setPopUpTo(navController.getGraph().getId(), true)
                            .setLaunchSingleTop(true)
                            .build());
        }
        int activeDestination = navController.getCurrentDestination() == null
                ? current : navController.getCurrentDestination().getId();
        syncBottomNavigation(activeDestination);
    }

    private void syncBottomNavigation(int destination) {
        if (binding == null || navController == null) return;
        boolean onboarding = latestAuthState != null && latestAuthState.getUser() != null
                && latestAuthState.getUser().isOnboarding();
        boolean show = destination != R.id.login && destination != R.id.branchLocator && !onboarding;
        android.view.View bottomNavigation = binding.mainBottomNavigation.getRoot();
        bottomNavigation.setVisibility(show ? android.view.View.VISIBLE : android.view.View.GONE);
        bottomNavigation.bringToFront();
        if (!show) return;
        int home = latestAuthState != null && latestAuthState.getUser() != null
                && latestAuthState.getUser().getRole() == UserRole.OFFICER
                ? R.id.officerHome : R.id.customerHome;
        BottomNavigationBinder.bind(binding.mainBottomNavigation.getRoot(), navController, home, destination);
    }

    public void signOutAndReturnToLogin() {
        ((FinalTermApplication) getApplication()).getAppContainer().getAuthRepository().signOut();
    }
}
