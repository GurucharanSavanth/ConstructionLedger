package com.constructioncost.nativeapp.data.backup

import org.junit.Assert.assertEquals
import org.junit.Test

class LegacyImportLimitTest {
    @Test fun legacyUnframedItemsReserveHeapForAuthenticationAndMapping() {
        assertEquals(6L * 1024 * 1024, legacyDecryptLimit(192L * 1024 * 1024, true))
        assertEquals(12L * 1024 * 1024, legacyDecryptLimit(192L * 1024 * 1024, false))
        assertEquals(16L * 1024 * 1024, legacyDecryptLimit(1024L * 1024 * 1024, true))
        assertEquals(32L * 1024 * 1024, legacyDecryptLimit(1024L * 1024 * 1024, false))
    }
}
