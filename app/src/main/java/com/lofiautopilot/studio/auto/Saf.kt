package com.lofiautopilot.studio.auto

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract

data class SafFile(val uri: Uri, val name: String, val mime: String, val modified: Long, val size: Long)

/** Fast recursive listing of a folder the user granted through the system file picker. */
object Saf {
    fun walk(ctx: Context, tree: Uri, depth: Int = 6): List<SafFile> {
        val out = ArrayList<SafFile>()
        val rootId = runCatching { DocumentsContract.getTreeDocumentId(tree) }.getOrNull() ?: return out
        fun rec(docId: String, d: Int) {
            val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, docId)
            val cols = arrayOf(
                DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_MIME_TYPE, DocumentsContract.Document.COLUMN_LAST_MODIFIED,
                DocumentsContract.Document.COLUMN_SIZE,
            )
            runCatching {
                ctx.contentResolver.query(children, cols, null, null, null)?.use { c ->
                    while (c.moveToNext()) {
                        val id = c.getString(0)
                        val name = c.getString(1) ?: continue
                        val mime = c.getString(2) ?: ""
                        if (mime == DocumentsContract.Document.MIME_TYPE_DIR) {
                            if (d > 0) rec(id, d - 1)
                        } else {
                            out.add(SafFile(DocumentsContract.buildDocumentUriUsingTree(tree, id), name, mime, c.getLong(3), c.getLong(4)))
                        }
                    }
                }
            }
        }
        rec(rootId, depth)
        return out
    }

    fun persist(ctx: Context, uri: Uri) {
        runCatching {
            ctx.contentResolver.takePersistableUriPermission(uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }

    fun folderName(uri: Uri): String {
        val id = runCatching { DocumentsContract.getTreeDocumentId(uri) }.getOrNull() ?: return uri.toString()
        return id.substringAfter(':').ifBlank { id }
    }
}
