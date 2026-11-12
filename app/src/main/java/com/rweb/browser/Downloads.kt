package com.rweb.browser

import android.app.DownloadManager
import android.content.Context
import android.content.Intent

class DownloadItem(
    val id: Long,
    val title: String,
    val status: Int,
    val total: Long,
    val soFar: Long,
    val time: Long
) {
    val running: Boolean
        get() = status == DownloadManager.STATUS_RUNNING ||
            status == DownloadManager.STATUS_PENDING ||
            status == DownloadManager.STATUS_PAUSED
    val done: Boolean
        get() = status == DownloadManager.STATUS_SUCCESSFUL
    val percent: Int
        get() = if (total > 0) ((soFar * 100) / total).toInt().coerceIn(0, 100) else 0
}

// Reads and manages downloads started by this app through Android's DownloadManager.
object Downloads {

    // Private tabs only see private downloads, and normal tabs only see normal ones.
    fun query(ctx: Context, privateIds: Set<String>, wantPrivate: Boolean): List<DownloadItem> {
        val out = mutableListOf<DownloadItem>()
        val dm = ctx.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        val c = dm.query(DownloadManager.Query()) ?: return out
        try {
            val iId = c.getColumnIndexOrThrow(DownloadManager.COLUMN_ID)
            val iTitle = c.getColumnIndexOrThrow(DownloadManager.COLUMN_TITLE)
            val iStatus = c.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS)
            val iTotal = c.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES)
            val iSoFar = c.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR)
            val iTime = c.getColumnIndexOrThrow(DownloadManager.COLUMN_LAST_MODIFIED_TIMESTAMP)
            while (c.moveToNext()) {
                val id = c.getLong(iId)
                if (privateIds.contains(id.toString()) != wantPrivate) continue
                out.add(
                    DownloadItem(
                        id,
                        c.getString(iTitle) ?: "Download",
                        c.getInt(iStatus),
                        c.getLong(iTotal),
                        c.getLong(iSoFar),
                        c.getLong(iTime)
                    )
                )
            }
        } finally {
            c.close()
        }
        return out.sortedByDescending { it.time }
    }

    fun size(bytes: Long): String {
        if (bytes < 1024) return bytes.toString() + " B"
        if (bytes < 1024 * 1024) return String.format("%.0f KB", bytes / 1024.0)
        if (bytes < 1024L * 1024 * 1024) return String.format("%.1f MB", bytes / 1048576.0)
        return String.format("%.2f GB", bytes / 1073741824.0)
    }

    fun describe(d: DownloadItem): String = when (d.status) {
        DownloadManager.STATUS_SUCCESSFUL -> "Completed \u00B7 " + size(d.total)
        DownloadManager.STATUS_FAILED -> "Failed"
        DownloadManager.STATUS_PAUSED -> "Paused"
        DownloadManager.STATUS_PENDING -> "Waiting to start"
        else -> if (d.total > 0) {
            "Downloading " + d.percent + "% \u00B7 " + size(d.soFar) + " of " + size(d.total)
        } else {
            "Downloading " + size(d.soFar)
        }
    }

    fun open(ctx: Context, id: Long): Boolean {
        return try {
            val dm = ctx.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
            val uri = dm.getUriForDownloadedFile(id) ?: return false
            val mime = dm.getMimeTypeForDownloadedFile(id) ?: "*/*"
            val i = Intent(Intent.ACTION_VIEW)
            i.setDataAndType(uri, mime)
            i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            ctx.startActivity(i)
            true
        } catch (e: Exception) {
            false
        }
    }

    // Cancels a running download or deletes a finished one, including its file.
    fun remove(ctx: Context, id: Long) {
        try {
            val dm = ctx.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
            dm.remove(id)
        } catch (e: Exception) {
        }
    }
}
