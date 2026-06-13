package com.ryzix.code

import android.content.Context
import androidx.documentfile.provider.DocumentFile
import fi.iki.elonen.NanoHTTPD

/**
 * LocalServer — serves the open project folder over HTTP on localhost so HTML
 * files can be previewed in an in-app WebView (or any browser on the device).
 *
 * URL path   → DocumentFile resolution (SAF tree walk)
 * GET /           → index.html
 * GET /foo.html   → <root>/foo.html
 * GET /js/app.js  → <root>/js/app.js
 */
class LocalServer(
    private val rootDoc: DocumentFile,
    private val context: Context
) : NanoHTTPD(PORT) {

    companion object {
        const val PORT = 8787
    }

    override fun serve(session: IHTTPSession): Response {
        var path = session.uri.removePrefix("/")
        if (path.isEmpty()) path = "index.html"

        val parts = path.split("/").filter { it.isNotEmpty() }
        val file  = resolveFile(rootDoc, parts)

        if (file == null || !file.isFile) {
            return newFixedLengthResponse(
                Response.Status.NOT_FOUND, MIME_HTML,
                "<html><body style='background:#0f0f11;color:#e52a3f;font-family:monospace'>" +
                "<h2>404 — /${parts.joinToString("/")} not found</h2>" +
                "<p>Make sure you have an <b>index.html</b> in your project root.</p></body></html>"
            )
        }

        val mime = mimeForName(file.name ?: "")
        return try {
            val stream = context.contentResolver.openInputStream(file.uri)!!
            newFixedLengthResponse(Response.Status.OK, mime, stream, file.length())
        } catch (e: Exception) {
            newFixedLengthResponse(Response.Status.INTERNAL_ERROR, "text/plain", "Error: ${e.message}")
        }
    }

    private fun resolveFile(dir: DocumentFile, parts: List<String>): DocumentFile? {
        if (parts.isEmpty()) return null
        val name  = parts[0]
        val child = dir.listFiles().find { it.name == name } ?: return null
        return when {
            parts.size == 1 -> if (child.isFile) child else null
            child.isDirectory -> resolveFile(child, parts.drop(1))
            else -> null
        }
    }

    private fun mimeForName(name: String): String {
        val ext = name.substringAfterLast('.', "").lowercase()
        return when (ext) {
            "html", "htm" -> "text/html; charset=utf-8"
            "css"         -> "text/css; charset=utf-8"
            "js", "mjs"   -> "text/javascript; charset=utf-8"
            "ts"          -> "text/typescript; charset=utf-8"
            "json"        -> "application/json; charset=utf-8"
            "xml"         -> "text/xml; charset=utf-8"
            "svg"         -> "image/svg+xml"
            "png"         -> "image/png"
            "jpg", "jpeg" -> "image/jpeg"
            "gif"         -> "image/gif"
            "webp"        -> "image/webp"
            "ico"         -> "image/x-icon"
            "woff"        -> "font/woff"
            "woff2"       -> "font/woff2"
            "ttf"         -> "font/ttf"
            "otf"         -> "font/otf"
            "mp4"         -> "video/mp4"
            "mp3"         -> "audio/mpeg"
            "ogg"         -> "audio/ogg"
            "wav"         -> "audio/wav"
            "pdf"         -> "application/pdf"
            "txt", "md"   -> "text/plain; charset=utf-8"
            else          -> "application/octet-stream"
        }
    }
}
