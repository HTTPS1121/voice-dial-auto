package com.example.voicedialauto.playreg

import android.content.ContentProvider
import android.content.ContentValues
import android.content.res.AssetFileDescriptor
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import java.io.File

/**
 * Exported, read-only ContentProvider that serves THIS app's own `base.apk`
 * at `content://<applicationId>.apk/base.apk`. Used by the adb fallback command so the
 * Package Installer can open the APK without copying it first. The APK is already
 * world-readable under `/data/app/...`, so exposing it read-only is not a secret leak.
 */
class SelfApkProvider : ContentProvider() {

    override fun onCreate(): Boolean = true

    override fun getType(uri: Uri): String = "application/vnd.android.package-archive"

    override fun openAssetFile(uri: Uri, mode: String): AssetFileDescriptor {
        require(mode == "r") { "read-only" }
        require(uri.lastPathSegment == "base.apk") { "unknown path" }
        val apk = File(requireNotNull(context).applicationInfo.sourceDir)
        require(apk.isFile) { "base.apk missing" }
        return AssetFileDescriptor(
            ParcelFileDescriptor.open(apk, ParcelFileDescriptor.MODE_READ_ONLY),
            0,
            apk.length(),
        )
    }

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor =
        openAssetFile(uri, mode).parcelFileDescriptor

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor {
        val apk = File(requireNotNull(context).applicationInfo.sourceDir)
        val cols = projection ?: arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)
        val row = cols.map { col ->
            when (col) {
                OpenableColumns.DISPLAY_NAME -> "base.apk"
                OpenableColumns.SIZE -> apk.length()
                else -> null
            }
        }.toTypedArray()
        return MatrixCursor(cols).apply { addRow(row) }
    }

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0
}
