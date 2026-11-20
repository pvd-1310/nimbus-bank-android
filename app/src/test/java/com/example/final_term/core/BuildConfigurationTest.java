package com.example.final_term.core;

import com.example.final_term.BuildConfig;
import org.junit.Test;
import static org.junit.Assert.*;

public class BuildConfigurationTest {
    @Test public void applicationUsesRequiredPackage() { assertEquals(AppConfig.APPLICATION_ID, BuildConfig.APPLICATION_ID); }
    @Test public void roleNamesAreStableForFirestoreProfiles() {
        assertEquals("CUSTOMER", AppConfig.CUSTOMER_ROLE);
        assertEquals("OFFICER", AppConfig.OFFICER_ROLE);
    }
}
