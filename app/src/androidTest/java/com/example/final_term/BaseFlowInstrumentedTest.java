package com.example.final_term;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.core.app.ActivityScenario;
import org.junit.Test;
import org.junit.runner.RunWith;
import static androidx.test.espresso.Espresso.onView;
import static androidx.test.espresso.matcher.ViewMatchers.withText;
import static androidx.test.espresso.assertion.ViewAssertions.matches;

@RunWith(AndroidJUnit4.class)
public class BaseFlowInstrumentedTest {
    @Test public void loginShowsFirebaseSetupStateWithoutConfiguration() {
        ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class);
        try {
            onView(withText("Đăng nhập")).check(matches(androidx.test.espresso.matcher.ViewMatchers.isDisplayed()));
            onView(withText("Cần cấu hình Firebase")).check(matches(androidx.test.espresso.matcher.ViewMatchers.isDisplayed()));
        } finally {
            scenario.close();
        }
    }
}
