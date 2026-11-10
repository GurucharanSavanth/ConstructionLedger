package com.constructioncost.nativeapp.ui

import org.junit.Assert.*
import org.junit.Test

class AppUpdatesTest {
    @Test fun sequentialReleaseVersionsSupportLegacyUpgradeAndNumericOrdering() {
        assertTrue(newerRelease("f.001", "1.0.0"))
        assertTrue(newerRelease("f.010", "f.009"))
        assertTrue(newerRelease("f.1000", "f.999"))
        assertTrue(newerRelease("vf.002", "f.001"))
        assertFalse(newerRelease("f.001", "f.001"))
        assertFalse(newerRelease("f.009", "f.010"))
        assertFalse(newerRelease("9.0.0", "f.001"))
        assertFalse(newerRelease("f.000", "1.0.0"))
        assertFalse(newerRelease("f.2", "f.001"))
        assertFalse(newerRelease("f.002-beta", "f.001"))
        assertFalse(newerRelease("f.999999999999999999999", "f.001"))
    }
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
