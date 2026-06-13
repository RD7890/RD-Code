package com.ryzix.code

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.Collections
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.atomic.AtomicBoolean

class AIClient(private val context: Context) {

    private val pool = Executors.newSingleThreadExecutor()
    private var currentFuture: Future<*>? = null
    private val isCancelled = AtomicBoolean(false)

    private val agentDir: File
        get() {
            val base = context.getExternalFilesDir(null) ?: context.filesDir
            return File(base, "agent").also { it.mkdirs() }
        }

    data class PendingChange(
        val filename:   String,
        val oldContent: String,
        val newContent: String,
        val targetUri:  Uri?
    )

    var onPendingChange: ((PendingChange, (Boolean) -> Unit) -> Unit)? = null

    var projectUri: Uri? = null

    // Thread-safe conversation history
    private val history: MutableList<Pair<String, String>> =
        Collections.synchronizedList(mutableListOf())

    fun clearHistory() = history.clear()
    fun getHistorySize() = history.size

    // Cancel any in-flight request
    fun cancelRequest() {
        isCancelled.set(true)
        currentFuture?.cancel(true)
    }

    fun send(
        message:     String,
        projectUri:  Uri?,
        openFileUri: Uri?                    = null,
        openTabs:    List<Pair<String, Uri>> = emptyList(),
        onStatus:    ((String) -> Unit)?     = null,
        callback:    (Boolean, String) -> Unit
    ) {
        this.projectUri = projectUri
        isCancelled.set(false)

        val sp      = context.getSharedPreferences("ryzix_prefs", Context.MODE_PRIVATE)
        val baseUrl = sp.getString("agent_url",   "") ?: ""
        val apiKey  = sp.getString("agent_key",   "") ?: ""
        val model   = sp.getString("agent_model", "") ?: ""

        if (baseUrl.isEmpty() || apiKey.isEmpty() || model.isEmpty()) {
            callback(false, "Not configured. Tap the settings icon.")
            return
        }

        val systemPrompt = buildSystemPrompt(projectUri, openFileUri, openTabs)
        val isGemini     = baseUrl.contains("googleapis.com", ignoreCase = true)

        val historySnapshot = synchronized(history) { history.toList() }

        currentFuture = pool.submit {
            if (isCancelled.get()) { callback(false, "Request cancelled."); return@submit }
            sendWithRetry(
                baseUrl, apiKey, model, systemPrompt, message,
                isGemini, historySnapshot, attempt = 1, maxRetries = 5,
                onStatus = onStatus, callback = { ok, resp ->
                    if (ok && !isCancelled.get()) {
                        synchronized(history) {
                            history.add("user" to message)
                            history.add("assistant" to resp)
                            while (history.size > 20) history.removeAt(0)
                        }
                    }
                    callback(ok, resp)
                }
            )
        }
    }

    private fun sendWithRetry(
        baseUrl: String, apiKey: String, model: String,
        system: String, message: String, isGemini: Boolean,
        history: List<Pair<String, String>>,
        attempt: Int, maxRetries: Int,
        onStatus: ((String) -> Unit)?,
        callback: (Boolean, String) -> Unit
    ) {
        if (isCancelled.get()) { callback(false, "Request cancelled."); return }

        val (success, code, body) =
            if (isGemini) callGemini(baseUrl, apiKey, model, system, message, history)
            else          callOpenAI(baseUrl, apiKey, model, system, message, history)

        if (!success && (code == 429 || code == 503) && attempt <= maxRetries) {
            val wait = (1 shl attempt).coerceAtMost(32)
            onStatus?.invoke("Rate limited (attempt $attempt/$maxRetries). Retrying in ${wait}s...")
            Thread.sleep(wait * 1000L)
            sendWithRetry(baseUrl, apiKey, model, system, message, isGemini, history,
                attempt + 1, maxRetries, onStatus, callback)
            return
        }

        if (!success && (code == 429 || code == 503)) {
            callback(false, "Rate limited after $maxRetries retries. Try a different model.")
            return
        }

        if (!success) { callback(false, body); return }

        val afterNotes   = handleAgentNoteCreation(body)
        val (cleanResponse, changes) = parseFileWrites(afterNotes)

        if (changes.isNotEmpty()) {
            applyChangesWithConfirmation(changes, cleanResponse, callback)
        } else {
            callback(true, cleanResponse)
        }
    }

    // ── Parse FILE_WRITE blocks ───────────────────────────────────────────

    private fun parseFileWrites(response: String): Pair<String, List<PendingChange>> {
        val changes = mutableListOf<PendingChange>()
        var result  = response
        val prefix  = "FILE_WRITE:"
        val end     = "FILE_END"

        var search = result
        while (search.contains(prefix)) {
            val startIdx   = search.indexOf(prefix)
            val lineEnd    = search.indexOf('\n', startIdx).takeIf { it >= 0 } ?: break
            val filename   = search.substring(startIdx + prefix.length, lineEnd).trim()
            val cStart     = lineEnd + 1
            val endIdx     = search.indexOf(end, cStart)

            // If no FILE_END found, stop — don't consume the rest of the response
            if (endIdx < 0) break

            val newContent = search.substring(cStart, endIdx).trim()

            if (filename.isNotEmpty() && newContent.isNotEmpty()) {
                val existingUri = findFileUri(filename)
                val oldContent  = existingUri?.let { readUri(it) } ?: ""
                changes.add(PendingChange(filename, oldContent, newContent, existingUri))

                val block = search.substring(startIdx, endIdx + end.length)
                result = result.replace(block, "[File change ready: $filename]")
            }
            search = search.substring(endIdx + end.length)
        }

        return result to changes
    }

    // ── Confirm each change ───────────────────────────────────────────────

    private fun applyChangesWithConfirmation(
        changes: List<PendingChange>,
        baseMessage: String,
        callback: (Boolean, String) -> Unit
    ) {
        var summary = baseMessage + "\n\n"
        var idx     = 0

        fun processNext() {
            if (idx >= changes.size) {
                callback(true, summary.trim())
                return
            }
            val change  = changes[idx++]
            val handler = onPendingChange
            if (handler == null) {
                val ok = writeChange(change)
                summary += if (ok) "Written: ${change.filename}\n"
                           else    "Write failed: ${change.filename}\n"
                processNext()
            } else {
                handler(change) { accepted ->
                    if (accepted) {
                        val ok = writeChange(change)
                        summary += if (ok) "Applied: ${change.filename}\n"
                                   else    "Write failed (check permissions): ${change.filename}\n"
                    } else {
                        summary += "Rejected: ${change.filename}\n"
                    }
                    processNext()
                }
            }
        }
        processNext()
    }

    // Returns true on success, false on failure
    private fun writeChange(change: PendingChange): Boolean {
        return try {
            if (change.targetUri != null) {
                context.contentResolver.openOutputStream(change.targetUri, "wt")
                    ?.bufferedWriter()?.use { it.write(change.newContent) }
                true
            } else {
                val folder = projectUri?.let { DocumentFile.fromTreeUri(context, it) }
                    ?: return false
                // Support subdirectory paths like src/main/Foo.kt
                val parts   = change.filename.split("/", "\\").filter { it.isNotEmpty() }
                val dir     = if (parts.size > 1) ensureDirectory(folder, parts.dropLast(1))
                              else folder
                val safeName = parts.last().replace(Regex("[*?\"<>|]"), "_")
                val doc = dir?.createFile("text/plain", safeName)
                if (doc != null) {
                    context.contentResolver.openOutputStream(doc.uri, "wt")
                        ?.bufferedWriter()?.use { it.write(change.newContent) }
                    true
                } else false
            }
        } catch (_: Exception) { false }
    }

    private fun ensureDirectory(root: DocumentFile, parts: List<String>): DocumentFile? {
        var cur = root
        for (part in parts) {
            val safe = part.replace(Regex("[*?\"<>|]"), "_")
            cur = cur.listFiles().find { it.isDirectory && it.name == safe }
                ?: cur.createDirectory(safe)
                ?: return null
        }
        return cur
    }

    // ── System prompt ─────────────────────────────────────────────────────

    private fun buildSystemPrompt(
        projectUri: Uri?,
        openFileUri: Uri?,
        openTabs: List<Pair<String, Uri>>
    ): String {
        val sb = StringBuilder()
        sb.append(
            "You are RD Agent — an autonomous coding agent inside Ryzix Code IDE.\n" +
            "You operate like a senior engineer: you plan, execute, and deliver complete solutions independently.\n\n" +
            "═══ AUTONOMOUS OPERATION RULES ═══\n" +
            "1. Complete tasks end-to-end without asking permission at each step.\n" +
            "2. When given a task, think through it, then execute ALL required file changes in one response.\n" +
            "3. Proactively fix bugs you notice while working — don't leave broken code.\n" +
            "4. Provide a clear summary at the end of every response listing what you changed and why.\n" +
            "5. If a task is ambiguous, make a reasonable decision and proceed — explain your choice in the summary.\n\n" +
            "═══ FILE OPERATIONS ═══\n" +
            "To CREATE or EDIT a file in the user's project:\n" +
            "  FILE_WRITE:path/to/filename.ext\n" +
            "  <COMPLETE file content — never truncate>\n" +
            "  FILE_END\n\n" +
            "Rules for FILE_WRITE:\n" +
            "- Always output COMPLETE file content. Never use '...' or omit sections.\n" +
            "- You can write multiple files in a single response — include all needed FILE_WRITE blocks.\n" +
            "- The user will see a diff and can ACCEPT or REJECT each file change.\n" +
            "- For files in subdirectories use the path: src/main/java/com/example/Foo.kt\n" +
            "- Every FILE_WRITE MUST end with FILE_END on its own line.\n\n" +
            "To save personal notes or task plans (NOT project files):\n" +
            "  FILE_CREATE:notename.md\n" +
            "  <content>\n" +
            "  FILE_END\n\n" +
            "═══ CODING STANDARDS ═══\n" +
            "- Write clean, idiomatic code matching the project's existing style.\n" +
            "- This is an Android/Kotlin project — follow Android best practices.\n" +
            "- Never use AppCompatActivity — all activities extend android.app.Activity.\n" +
            "- File I/O uses SAF (DocumentFile + ContentResolver), never java.io.File for project files.\n" +
            "- Reference actual code from the project context below.\n\n"
        )

        val notes = agentDir.listFiles()?.filter { it.isFile } ?: emptyList()
        if (notes.isNotEmpty()) {
            sb.append("═══ YOUR NOTES ═══\n")
            notes.take(5).forEach {
                sb.append("[ ${it.name} ]\n${it.readText().take(500)}\n\n")
            }
        }

        if (projectUri != null) {
            sb.append("═══ PROJECT FILE TREE ═══\n")
            try {
                DocumentFile.fromTreeUri(context, projectUri)
                    ?.let { buildTree(it, sb, 0, intArrayOf(200)) }
            } catch (_: Exception) {}
            sb.append("\n")
        }

        if (openFileUri != null) {
            try {
                val text = readUri(openFileUri) ?: ""
                val name = DocumentFile.fromSingleUri(context, openFileUri)?.name ?: "file"
                sb.append("═══ ACTIVE FILE: $name ═══\n$text\n\n")
            } catch (_: Exception) {}
        }

        val otherTabs = openTabs.filter { it.second != openFileUri }.take(4)
        if (otherTabs.isNotEmpty()) {
            sb.append("═══ OTHER OPEN TABS ═══\n")
            for ((tabName, tabUri) in otherTabs) {
                try {
                    val text = readUri(tabUri) ?: continue
                    if (text.length > 6000) {
                        sb.append("[ $tabName ] (${text.length} chars — first 6000 shown)\n")
                        sb.append(text.take(6000)).append("\n...\n\n")
                    } else {
                        sb.append("[ $tabName ]\n$text\n\n")
                    }
                } catch (_: Exception) {}
            }
        }

        return sb.toString()
    }

    private fun buildTree(dir: DocumentFile, sb: StringBuilder, depth: Int, limit: IntArray) {
        if (limit[0] <= 0) return
        val pad = "  ".repeat(depth)
        dir.listFiles().sortedWith(compareBy({ !it.isDirectory }, { it.name })).forEach { f ->
            if (limit[0] <= 0) return
            sb.append("$pad${if (f.isDirectory) "[D]" else "[F]"} ${f.name}\n")
            limit[0]--
            if (f.isDirectory) buildTree(f, sb, depth + 1, limit)
        }
    }

    // ── FILE_CREATE — agent notes ─────────────────────────────────────────

    fun handleAgentNoteCreation(response: String): String {
        var result = response
        val prefix = "FILE_CREATE:"
        val end    = "FILE_END"
        while (result.contains(prefix)) {
            val s    = result.indexOf(prefix)
            val le   = result.indexOf('\n', s).takeIf { it >= 0 } ?: break
            val name = result.substring(s + prefix.length, le).trim()
            val cs   = le + 1
            val ei   = result.indexOf(end, cs)
            // If no FILE_END, stop — don't consume subsequent blocks
            if (ei < 0) break
            val cont = result.substring(cs, ei).trim()
            if (name.isNotEmpty() && cont.isNotEmpty()) {
                val safe = name.replace(Regex("[/\\\\:*?\"<>|]"), "_")
                try { File(agentDir, safe).writeText(cont) } catch (_: Exception) {}
                val block = result.substring(s, ei + end.length)
                result = result.replace(block, "[Note saved: $safe]")
            } else break
        }
        return result
    }

    // ── Helpers ───────────────────────────────────────────────────────────

    private fun findFileUri(filename: String): Uri? {
        val root = projectUri?.let { DocumentFile.fromTreeUri(context, it) } ?: return null
        return findInTree(root, filename)?.uri
    }

    private fun findInTree(dir: DocumentFile, name: String): DocumentFile? {
        for (f in dir.listFiles()) {
            if (!f.isDirectory && f.name == name) return f
            if (f.isDirectory) findInTree(f, name)?.let { return it }
        }
        return null
    }

    private fun readUri(uri: Uri): String? = try {
        context.contentResolver.openInputStream(uri)?.bufferedReader()?.readText()
    } catch (_: Exception) { null }

    fun getAgentFiles(): List<File> =
        agentDir.listFiles()?.filter { it.isFile }?.sortedByDescending { it.lastModified() } ?: emptyList()

    fun readAgentFile(file: File): String = try { file.readText() } catch (_: Exception) { "" }

    // ── HTTP — OpenAI-compatible ──────────────────────────────────────────

    private fun callOpenAI(
        baseUrl: String, apiKey: String, model: String,
        system: String, message: String,
        history: List<Pair<String, String>>
    ): Triple<Boolean, Int, String> = try {
        val conn = (URL(baseUrl.trimEnd('/') + "/chat/completions").openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("Authorization", "Bearer $apiKey")
            setRequestProperty("HTTP-Referer", "https://ryzix.code")
            setRequestProperty("X-Title", "Ryzix Code Agent")
            doOutput = true; connectTimeout = 30_000; readTimeout = 180_000
            outputStream.write(buildOpenAIJson(model, system, message, history).toByteArray(Charsets.UTF_8))
            outputStream.flush(); outputStream.close()
        }
        val code = conn.responseCode
        val body = (if (code < 400) conn.inputStream else conn.errorStream)
            .bufferedReader(Charsets.UTF_8).readText()
        conn.disconnect()
        if (code in 200..299) {
            val t = extractOpenAIText(body)
            if (t != null) Triple(true, code, t) else Triple(false, code, "Empty response from AI.")
        } else Triple(false, code, "Error $code: " + body.replace(Regex("<[^>]*>"), "").trim().take(400))
    } catch (e: Exception) { Triple(false, -1, "Network error: ${e.message}") }

    // ── HTTP — Google Gemini ──────────────────────────────────────────────
    // FIX: Normalize base URL to avoid double /v1beta/ path when user already
    // includes the API version in their base URL.

    private fun callGemini(
        baseUrl: String, apiKey: String, model: String,
        system: String, message: String,
        history: List<Pair<String, String>>
    ): Triple<Boolean, Int, String> = try {
        // Strip any trailing API version path so we always append our own
        val normalizedBase = baseUrl.trimEnd('/')
            .removeSuffix("/v1beta")
            .removeSuffix("/v1")
            .trimEnd('/')
        val url = "$normalizedBase/v1beta/models/$model:generateContent?key=$apiKey"

        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            setRequestProperty("Content-Type", "application/json")
            doOutput = true; connectTimeout = 30_000; readTimeout = 180_000
            outputStream.write(buildGeminiJson(system, message, history).toByteArray(Charsets.UTF_8))
            outputStream.flush(); outputStream.close()
        }
        val code = conn.responseCode
        val body = (if (code < 400) conn.inputStream else conn.errorStream)
            .bufferedReader(Charsets.UTF_8).readText()
        conn.disconnect()
        if (code in 200..299) {
            val t = extractGeminiText(body)
            if (t != null) Triple(true, code, t) else Triple(false, code, "Empty response from Gemini.")
        } else Triple(false, code, "Error $code: " + body.replace(Regex("<[^>]*>"), "").trim().take(400))
    } catch (e: Exception) { Triple(false, -1, "Network error: ${e.message}") }

    // ── JSON builders ─────────────────────────────────────────────────────

    private fun esc(s: String): String {
        val sb = StringBuilder()
        for (ch in s) when (ch) {
            '\\'  -> sb.append("\\\\")
            '"'   -> sb.append("\\\"")
            '\n'  -> sb.append("\\n")
            '\r'  -> sb.append("\\r")
            '\t'  -> sb.append("\\t")
            '\b'  -> sb.append("\\b")
            else  -> if (ch.code < 0x20) sb.append("\\u${ch.code.toString(16).padStart(4, '0')}") else sb.append(ch)
        }
        return sb.toString()
    }

    private fun buildOpenAIJson(
        model: String, system: String, msg: String,
        history: List<Pair<String, String>>
    ): String {
        val sb = StringBuilder()
        sb.append("""{"model":"${esc(model)}","messages":[""")
        sb.append("""{"role":"system","content":"${esc(system)}"}""")
        for ((role, content) in history) {
            val r = if (role == "user") "user" else "assistant"
            sb.append(""",{"role":"$r","content":"${esc(content)}"}""")
        }
        sb.append(""",{"role":"user","content":"${esc(msg)}"}""")
        sb.append("""],"max_tokens":8192}""")
        return sb.toString()
    }

    private fun buildGeminiJson(
        system: String, msg: String,
        history: List<Pair<String, String>>
    ): String {
        val sb = StringBuilder()
        sb.append("""{"system_instruction":{"parts":[{"text":"${esc(system)}"}]},"contents":[""")
        var first = true
        for ((role, content) in history) {
            val r = if (role == "user") "user" else "model"
            if (!first) sb.append(",")
            sb.append("""{"role":"$r","parts":[{"text":"${esc(content)}"}]}""")
            first = false
        }
        if (!first) sb.append(",")
        sb.append("""{"role":"user","parts":[{"text":"${esc(msg)}"}]}""")
        sb.append("""],"generationConfig":{"maxOutputTokens":8192}}""")
        return sb.toString()
    }

    // ── Response extractors ───────────────────────────────────────────────

    // FIX: Search for content after "assistant" role marker to avoid
    // matching "content" fields in other parts of the response JSON.
    private fun extractOpenAIText(json: String): String? {
        val assistantMarker = "\"role\":\"assistant\""
        val searchFrom = json.indexOf(assistantMarker).let { if (it >= 0) it else 0 }
        val key = "\"content\":\""
        val s   = json.indexOf(key, searchFrom).takeIf { it >= 0 } ?: return null
        return extractStr(json, s + key.length)
    }

    private fun extractGeminiText(json: String): String? {
        val key = "\"text\":\""
        val s   = json.indexOf(key).takeIf { it >= 0 } ?: return null
        return extractStr(json, s + key.length)
    }

    // FIX: Properly handles all JSON escape sequences including \uXXXX unicode.
    private fun extractStr(json: String, from: Int): String? {
        val sb = StringBuilder()
        var i  = from
        while (i < json.length) {
            val c = json[i]
            if (c == '\\' && i + 1 < json.length) {
                val next = json[i + 1]
                if (next == 'u' && i + 5 < json.length) {
                    try {
                        val codePoint = json.substring(i + 2, i + 6).toInt(16)
                        sb.append(codePoint.toChar())
                        i += 6
                    } catch (_: NumberFormatException) {
                        sb.append('u'); i += 2
                    }
                } else {
                    when (next) {
                        '"'  -> sb.append('"')
                        'n'  -> sb.append('\n')
                        'r'  -> sb.append('\r')
                        't'  -> sb.append('\t')
                        '\\' -> sb.append('\\')
                        'b'  -> sb.append('\b')
                        '/'  -> sb.append('/')
                        else -> sb.append(next)
                    }
                    i += 2
                }
            } else if (c == '"') break
            else { sb.append(c); i++ }
        }
        return if (sb.isEmpty()) null else sb.toString()
    }
}
