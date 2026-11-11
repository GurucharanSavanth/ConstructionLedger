package com.constructioncost.nativeapp.ui

import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

internal class UpdateHttpException(val status: Int) : IOException("Update server returned HTTP $status")

internal fun updateFailureMessage(error: Exception): String = when (error) {
    is UnknownHostException -> "Cannot reach GitHub. Check your internet connection and try again."
    is SocketTimeoutException -> "The update check timed out. Try again on a stable connection."
    is UpdateHttpException -> when (error.status) {
        404 -> "No stable release is available yet. Try again later."
        403, 429 -> "GitHub is limiting requests. Wait a few minutes before trying again."
        else -> "GitHub could not complete the request (HTTP ${error.status}). Try again later."
    }
    else -> "Could not verify the update: ${error.message ?: "Unknown error"}. Try again later."
}
