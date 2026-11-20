package com.example.final_term.data;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class ReadModelResultTest {
    @Test
    public void successContainsFreshData() {
        ReadModelResult<List<String>> result = ReadModelResult.success(Arrays.asList("checking"));

        assertEquals(ReadModelResult.Status.SUCCESS, result.getStatus());
        assertEquals(1, result.getData().size());
        assertFalse(result.isReadOnly());
    }

    @Test
    public void emptyIsDistinctFromLoadingAndError() {
        ReadModelResult<List<String>> result = ReadModelResult.empty();

        assertEquals(ReadModelResult.Status.EMPTY, result.getStatus());
        assertTrue(result.getData().isEmpty());
        assertEquals("", result.getMessage());
    }

    @Test
    public void cachedDataIsExplicitlyReadOnly() {
        ReadModelResult<List<String>> result = ReadModelResult.cached(Arrays.asList("checking"), "Offline");

        assertEquals(ReadModelResult.Status.CACHED, result.getStatus());
        assertTrue(result.isReadOnly());
        assertEquals("Offline", result.getMessage());
    }

    @Test
    public void errorsDoNotExposeDataAsSuccess() {
        ReadModelResult<List<String>> result = ReadModelResult.error("Retry");

        assertEquals(ReadModelResult.Status.ERROR, result.getStatus());
        assertEquals(null, result.getData());
        assertEquals("Retry", result.getMessage());
    }
}
