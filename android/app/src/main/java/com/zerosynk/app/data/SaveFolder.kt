package com.zerosynk.app.data

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract

/**
 * Destination folder for files sent from the PC.
 *
 * Default = Download/ZeroSynk via MediaStore. A custom folder is any directory
 * picked with the system folder picker (SAF tree) — we hold a persistable
 * read+write grant on it. If the grant is ever revoked, [activeTree] returns
 * null and callers silently fall back to the default path so files are never lost.
 */
object SaveFolder {

    const val DEFAULT_LABEL = "Download/ZeroSynk"

    private const val GRANT =
        Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION

    /** Display name for the settings row (defaults to the MediaStore path). */
    fun label(context: Context): String =
        Prefs.get(context).saveTreeName ?: DEFAULT_LABEL

    /** True when the user picked a custom folder (grant may still be gone → check [activeTree]). */
    fun isCustom(context: Context): Boolean = Prefs.get(context).saveTreeUri != null

    /** Label for the settings row; flags a revoked grant instead of silently lying. */
    fun statusLabel(context: Context): String {
        val prefs = Prefs.get(context)
        if (prefs.saveTreeUri == null) return DEFAULT_LABEL
        val name = prefs.saveTreeName ?: DEFAULT_LABEL
        return if (activeTree(context) != null) name else "$name — доступ потерян"
    }

    /** The custom tree URI, but only while we still hold its permission. */
    fun activeTree(context: Context): Uri? {
        val raw = Prefs.get(context).saveTreeUri ?: return null
        val uri = Uri.parse(raw)
        val held = context.contentResolver.persistedUriPermissions.any {
            it.uri == uri && it.isReadPermission && it.isWritePermission
        }
        return uri.takeIf { held }
    }

    /** Stores the picked folder: takes the persistable grant, caches its display name. */
    fun adopt(context: Context, uri: Uri): String {
        val prefs = Prefs.get(context)
        val resolver = context.contentResolver
        // Drop the previous grant when replacing the folder.
        prefs.saveTreeUri?.let { old ->
            if (old != uri.toString()) {
                try {
                    resolver.releasePersistableUriPermission(Uri.parse(old), GRANT)
                } catch (_: Exception) {
                }
            }
        }
        try {
            resolver.takePersistableUriPermission(uri, GRANT)
        } catch (_: Exception) {
        }
        val name = displayName(context, uri) ?: DEFAULT_LABEL
        prefs.saveTreeUri = uri.toString()
        prefs.saveTreeName = name
        return name
    }

    /** Back to Download/ZeroSynk; releases the SAF grant. */
    fun reset(context: Context) {
        val prefs = Prefs.get(context)
        prefs.saveTreeUri?.let { raw ->
            try {
                context.contentResolver.releasePersistableUriPermission(Uri.parse(raw), GRANT)
            } catch (_: Exception) {
            }
        }
        prefs.saveTreeUri = null
        prefs.saveTreeName = null
    }

    private fun displayName(context: Context, uri: Uri): String? = try {
        val docId = DocumentsContract.getTreeDocumentId(uri)
        val docUri = DocumentsContract.buildDocumentUriUsingTree(uri, docId)
        context.contentResolver
            .query(docUri, arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null)
            ?.use { c ->
                if (c.moveToFirst()) c.getString(0) else null
            }
    } catch (_: Exception) {
        null
    }
}
