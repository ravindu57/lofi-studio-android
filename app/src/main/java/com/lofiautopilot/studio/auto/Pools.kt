package com.lofiautopilot.studio.auto

import android.net.Uri
import android.provider.DocumentsContract
import com.lofiautopilot.studio.core.SongPool

/** Helpers for the automation recipe: which pool a song belongs to and how files are ordered. */
object Pools {
    /** The file's document id (its path inside the storage), the same no matter which granted folder it was reached through. */
    fun docId(uri: String): String? = runCatching { DocumentsContract.getDocumentId(Uri.parse(uri)) }.getOrNull()

    private fun folderDocId(folderUri: String): String? =
        runCatching { DocumentsContract.getTreeDocumentId(Uri.parse(folderUri)).trimEnd('/') }.getOrNull()

    fun contains(pool: SongPool, trackUri: String): Boolean {
        val f = folderDocId(pool.folderUri) ?: return false
        val id = docId(trackUri) ?: return false
        return id.startsWith("$f/")
    }

    /** "intro 2" before "intro 10": numbers inside names compare as numbers. */
    fun naturalCompare(a: String, b: String): Int {
        val x = Regex("\\d+|\\D+").findAll(a.lowercase()).map { it.value }.toList()
        val y = Regex("\\d+|\\D+").findAll(b.lowercase()).map { it.value }.toList()
        for (i in 0 until minOf(x.size, y.size)) {
            val p = x[i]; val q = y[i]
            val c = if (p[0].isDigit() && q[0].isDigit()) (p.toBigInteger().compareTo(q.toBigInteger())).takeIf { it != 0 } ?: p.length.compareTo(q.length) else p.compareTo(q)
            if (c != 0) return c
        }
        return x.size.compareTo(y.size)
    }
}
