package com.constructioncost.nativeapp.ui

import org.junit.Assert.*
import org.junit.Test

class AppUpdatesTest {
    @Test fun stableVersionsCompareNumericallyAndRejectAmbiguousTags() {
        assertTrue(newerRelease("v1.10.0", "1.9.9"))
        assertTrue(newerRelease("2.0.0", "1.99.99"))
        assertFalse(newerRelease("v1.0.0", "1.0.0"))
        assertFalse(newerRelease("v0.9.9", "1.0.0"))
        assertFalse(newerRelease("v2.0.0-beta", "1.0.0"))
        assertFalse(newerRelease("latest", "1.0.0"))
        assertFalse(newerRelease("999999999999999999999.0.0", "1.0.0"))
    }
}
