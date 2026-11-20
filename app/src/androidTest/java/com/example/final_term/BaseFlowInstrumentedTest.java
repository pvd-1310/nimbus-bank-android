package com.example.final_term;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.core.app.ActivityScenario;
import org.junit.Test;
import org.junit.runner.RunWith;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

@RunWith(AndroidJUnit4.class)
public class BaseFlowInstrumentedTest {
    @Test public void loginScreenRendersWithConfiguredOrRecoverableFirebaseState() {
        ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class);
        try {
            scenario.onActivity(activity -> {
                assertNotNull(activity.findViewById(com.example.final_term.R.id.nav_host_fragment));
                int destination = ((androidx.navigation.fragment.NavHostFragment) activity.getSupportFragmentManager()
                        .findFragmentById(com.example.final_term.R.id.nav_host_fragment))
                        .getNavController().getCurrentDestination().getId();
                assertTrue(destination == com.example.final_term.R.id.login
                        || destination == com.example.final_term.R.id.customerHome
                        || destination == com.example.final_term.R.id.officerHome);
            });
        } finally {
            scenario.close();
        }
    }
}
