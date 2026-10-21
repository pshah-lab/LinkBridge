package com.linkbridge.android

import android.content.ContentResolver
import android.net.Uri
import android.provider.OpenableColumns

data class SelectedFileInfo(val name: String, val size: Long)

/** Display name and size of a content URI; size is -1 when the provider doesn't say. */
fun ContentResolver.fileInfo(uri: Uri): SelectedFileInfo {
    var name = "selected-file"
    var size = -1L

    query(uri, null, null, null, null)?.use {
        if (it.moveToFirst()) {
            val nameIndex = it.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            val sizeIndex = it.getColumnIndex(OpenableColumns.SIZE)
            if (nameIndex >= 0) name = it.getString(nameIndex)
            if (sizeIndex >= 0 && !it.isNull(sizeIndex)) size = it.getLong(sizeIndex)
        }
    }

    if (size < 0) {
        openAssetFileDescriptor(uri, "r")?.use { descriptor ->
            if (descriptor.length >= 0) size = descriptor.length
        }
    }

    return SelectedFileInfo(name = name, size = size)
}
