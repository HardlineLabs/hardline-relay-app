package com.hardlinelabs.relay

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri

/**
 * Read-only labels and activation evidence for ATAK. Never exports keys, QR data or a write API.
 */
class ProfileProvider : ContentProvider() {
    override fun onCreate() = true

    override fun getType(uri: Uri) = "vnd.android.cursor.dir/vnd.hardline.profile"

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor {
        require(uri.path == "/profiles")
        val cursor =
            MatrixCursor(
                arrayOf(
                    "id",
                    "name",
                    "locked",
                    "activation",
                    "node",
                    "index",
                    "slot",
                    "fingerprint",
                    "switching",
                )
            )
        val store = ProfileStore(requireNotNull(context))
        val snapshot = store.snapshot()
        val active = snapshot.active
        cursor.extras = android.os.Bundle().apply { putBoolean("switching", snapshot.switching) }
        snapshot.profiles.forEach { pkg ->
            val a = active?.takeIf { it.optString("id") == pkg.id }
            cursor.addRow(
                arrayOf<Any>(
                    pkg.id,
                    pkg.name,
                    if (pkg.locked) 1 else 0,
                    a?.optString("activation") ?: "",
                    a?.optInt("node") ?: 0,
                    a?.optInt("index") ?: -1,
                    a?.optInt("slot") ?: 0,
                    a?.optString("fingerprint") ?: "",
                    if (snapshot.switching) 1 else 0,
                )
            )
        }
        return cursor
    }

    override fun insert(uri: Uri, values: ContentValues?): Uri =
        throw UnsupportedOperationException()

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int =
        throw UnsupportedOperationException()

    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<out String>?,
    ): Int = throw UnsupportedOperationException()
}
