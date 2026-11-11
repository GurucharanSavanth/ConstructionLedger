package com.constructioncost.nativeapp.ui

import org.junit.Assert.*
import org.junit.Test
import java.net.SocketTimeoutException
import java.net.UnknownHostException

class UpdateErrorsTest {
    @Test fun failuresExplainRecoveryWithoutClaimingUpToDate() {
        assertEquals("Cannot reach GitHub. Check your internet connection and try again.", updateFailureMessage(UnknownHostException()))
        assertEquals("The update check timed out. Try again on a stable connection.", updateFailureMessage(SocketTimeoutException()))
        assertEquals("No stable release is available yet. Try again later.", updateFailureMessage(UpdateHttpException(404)))
        for (status in listOf(403, 429)) assertTrue(updateFailureMessage(UpdateHttpException(status)).contains("Wait a few minutes"))
        assertTrue(updateFailureMessage(UpdateHttpException(503)).contains("HTTP 503"))
        assertTrue(updateFailureMessage(IllegalStateException("Update checksum mismatch")).contains("Update checksum mismatch"))
    }
}
