package com.example.final_term.core;

import com.example.final_term.BuildConfig;
import org.junit.Test;
import static org.junit.Assert.*;

public class BuildConfigurationTest {
    @Test public void applicationUsesRequiredPackage() {
        String expected = AppConfig.APPLICATION_ID + (BuildConfig.FIREBASE_DEMO ? ".localreview" : "");
        assertEquals(expected, BuildConfig.APPLICATION_ID);
    }
    @Test public void roleNamesAreStableForFirestoreProfiles() {
        assertEquals("CUSTOMER", AppConfig.CUSTOMER_ROLE);
        assertEquals("OFFICER", AppConfig.OFFICER_ROLE);
    }
}
