package com.example.final_term;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.Observer;
import androidx.lifecycle.ViewModelProvider;
import androidx.navigation.NavController;
import androidx.navigation.fragment.NavHostFragment;

import com.example.final_term.auth.AuthState;
import com.example.final_term.core.FinalTermApplication;
import com.example.final_term.core.SystemNotificationHelper;
import com.example.final_term.auth.UserRole;
import com.example.final_term.data.ReadModelResult;
import com.example.final_term.data.ReadModels;
import com.example.final_term.navigation.BottomNavigationBinder;
import com.example.final_term.navigation.RootViewModel;
import com.example.final_term.databinding.ActivityMainBinding;

import java.util.List;

public class MainActivity extends AppCompatActivity {
    private ActivityMainBinding binding;
    private NavController navController;
    private RootViewModel rootViewModel;
    private AuthState latestAuthState;
    private boolean askedNotificationPermission;
    private boolean pendingOpenNotifications;
    private String observedNotifUid;
    private LiveData<ReadModelResult<List<ReadModels.NotificationSummary>>> notificationsLiveData;
    private final Observer<ReadModelResult<List<ReadModels.NotificationSummary>>> notificationsObserver = result -> {
        if (result != null && result.getData() != null) {
            SystemNotificationHelper.showIfNew(this, result.getData());
        }
    };
    private final ActivityResultLauncher<String> notificationPermissionLauncher =
            registerForActivityResult(new ActivityResultContracts.RequestPermission(), granted -> {});

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        WindowCompat.setDecorFitsSystemWindows(getWindow(), false);
        binding = ActivityMainBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());
        ViewCompat.setOnApplyWindowInsetsListener(binding.main, (v, insets) -> {
            Insets systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars()
                    | WindowInsetsCompat.Type.displayCutout());
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
        SystemNotificationHelper.ensureChannels(this);
        pendingOpenNotifications = getIntent() != null
                && getIntent().getBooleanExtra(SystemNotificationHelper.EXTRA_OPEN_NOTIFICATIONS, false);
        application.getAppContainer().getAuthRepository().observeAuthState().observe(this, this::routeFor);

        binding.splashOverlay.postDelayed(() -> {
            if (binding == null) return;
            binding.splashOverlay.animate()
                    .alpha(0f)
                    .setDuration(260)
                    .withEndAction(() -> {
                        if (binding != null) binding.splashOverlay.setVisibility(android.view.View.GONE);
                    })
                    .start();
        }, 650);
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        if (intent != null && intent.getBooleanExtra(SystemNotificationHelper.EXTRA_OPEN_NOTIFICATIONS, false)) {
            pendingOpenNotifications = true;
            if (latestAuthState != null && latestAuthState.getUser() != null
                    && latestAuthState.getUser().getRole() == UserRole.CUSTOMER
                    && !latestAuthState.getUser().isOnboarding()
                    && navController != null
                    && (navController.getCurrentDestination() == null
                            || navController.getCurrentDestination().getId() != R.id.notifications)) {
                pendingOpenNotifications = false;
                navController.navigate(R.id.notifications);
            }
        }
    }

    private void routeFor(AuthState state) {
        syncNotificationObserver(state);
        boolean keepDestination = rootViewModel.keepsDestination(latestAuthState, state);
        latestAuthState = state;
        RootViewModel.RootRoute route = rootViewModel.routeFor(state);
        int current = navController.getCurrentDestination() == null
                ? R.id.login : navController.getCurrentDestination().getId();
        if (keepDestination && current != R.id.login) {
            syncBottomNavigation(current);
            return;
        }
        if (route == RootViewModel.RootRoute.CUSTOMER_HOME && current != R.id.customerHome) {
            navController.navigate(R.id.customerHome);
        } else if (route == RootViewModel.RootRoute.REGISTRATION && current != R.id.register) {
            navController.navigate(R.id.register);
        } else if (route == RootViewModel.RootRoute.ONBOARDING
                && current != R.id.profile && current != R.id.register) {
            navController.navigate(R.id.profile);
        } else if (route == RootViewModel.RootRoute.OFFICER_HOME && current != R.id.officerHome) {
            navController.navigate(R.id.officerHome);
        } else if (route == RootViewModel.RootRoute.LOGIN && current != R.id.login
                && current != R.id.resetPassword && current != R.id.register) {
            navController.navigate(R.id.login, null,
                    new androidx.navigation.NavOptions.Builder()
                            .setPopUpTo(navController.getGraph().getId(), true)
                            .setLaunchSingleTop(true)
                            .build());
        }
        int activeDestination = navController.getCurrentDestination() == null
                ? current : navController.getCurrentDestination().getId();
        if (pendingOpenNotifications && route == RootViewModel.RootRoute.CUSTOMER_HOME
                && activeDestination != R.id.notifications) {
            pendingOpenNotifications = false;
            navController.navigate(R.id.notifications);
            activeDestination = R.id.notifications;
        }
        syncBottomNavigation(activeDestination);
    }

    private void syncNotificationObserver(AuthState state) {
        String uid = state != null && state.getUser() != null && state.getUser().getRole() == UserRole.CUSTOMER
                ? state.getUser().getUid() : null;
        if (uid != null && !askedNotificationPermission
                && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                && ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                        != PackageManager.PERMISSION_GRANTED) {
            askedNotificationPermission = true;
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS);
        }
        if (uid == null ? observedNotifUid == null : uid.equals(observedNotifUid)) {
            return;
        }
        if (notificationsLiveData != null) {
            notificationsLiveData.removeObserver(notificationsObserver);
            notificationsLiveData = null;
        }
        observedNotifUid = uid;
        if (uid != null) {
            FinalTermApplication application = (FinalTermApplication) getApplication();
            notificationsLiveData = application.getAppContainer().getReadRepository().observeNotifications(uid);
            notificationsLiveData.observe(this, notificationsObserver);
        }
    }

    private void syncBottomNavigation(int destination) {
        if (binding == null || navController == null) return;
        boolean onboarding = latestAuthState != null && latestAuthState.getUser() != null
                && latestAuthState.getUser().isOnboarding();
        boolean show = destination != R.id.login && destination != R.id.resetPassword
                && destination != R.id.register
                && destination != R.id.branchLocator
                && destination != R.id.officerCustomerForm && destination != R.id.transactionFlow
                && destination != R.id.personalInfo
                && destination != R.id.notifications
                && !onboarding;
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
