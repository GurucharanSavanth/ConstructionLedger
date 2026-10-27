package com.constructioncost.nativeapp.platform.files

import android.content.Context
import android.os.StatFs
import android.os.storage.StorageManager
import java.io.File

internal fun availablePrivateBytes(context: Context, directory: File): Long {
    val storage = checkNotNull(context.getSystemService(StorageManager::class.java)) { "Private storage capacity is unavailable" }
    // Recovery staging and originals must fit without reclaiming any cached user input.
    return minOf(storage.getAllocatableBytes(storage.getUuidForPath(directory)), StatFs(directory.path).availableBytes)
}
