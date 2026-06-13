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

        // Run all parsers in sequence — notes first, then file edits, then full writes
        val afterNotes = handleAgentNoteCreation(body)
        val (afterEdits, editChanges)   = parseFileEdits(afterNotes)
        val (cleanResponse, writeChanges) = parseFileWrites(afterEdits)
        val allChanges = editChanges + writeChanges

        if (allChanges.isNotEmpty()) {
            applyChangesWithConfirmation(allChanges, cleanResponse, callback)
        } else {
            callback(true, cleanResponse)
        }
    }

    // ── Parse FILE_EDIT blocks (Aider-style SEARCH/REPLACE diffs) ───────────
    // Format:
    //   FILE_EDIT:path/to/file.kt
    //   <<<<<<< SEARCH
    //   exact existing text to replace
    //   =======
    //   replacement text
    //   >>>>>>> REPLACE
    //   FILE_END
    //
    // Multiple SEARCH/REPLACE pairs are supported inside one FILE_EDIT block.
    // Empty SEARCH = insert replacement at top of file (create or prepend).

    private fun parseFileEdits(response: String): Pair<String, List<PendingChange>> {
        val changes = mutableListOf<PendingChange>()
        var result  = response
        val PREFIX  = "FILE_EDIT:"
        val END     = "FILE_END"
        val S_MARK  = "<<<<<<< SEARCH"
        val DIV     = "======="
        val R_MARK  = ">>>>>>> REPLACE"

        var pos = 0
        while (true) {
            val blockStart = result.indexOf(PREFIX, pos).takeIf { it >= 0 } ?: break
            val nameEnd    = result.indexOf('\n', blockStart).takeIf { it >= 0 } ?: break
            val filename   = result.substring(blockStart + PREFIX.length, nameEnd).trim()
            val bodyStart  = nameEnd + 1
            // FILE_END is required for edits (content is structured, not freeform)
            val blockEnd   = result.indexOf(END, bodyStart).takeIf { it >= 0 } ?: break
            val body       = result.substring(bodyStart, blockEnd)

            val existingUri = findFileUri(filename)
            val oldContent  = existingUri?.let { readUri(it) } ?: ""
            var newContent  = oldContent

            // Walk all SEARCH/REPLACE pairs inside this block
            var bPos = 0
            var patchApplied = false
            while (true) {
                val si = body.indexOf(S_MARK, bPos).takeIf { it >= 0 } ?: break
                val afterS = body.indexOf('\n', si).takeIf { it >= 0 }?.plus(1) ?: break
                val di = body.indexOf(DIV, afterS).takeIf { it >= 0 } ?: break
                val ri = body.indexOf(R_MARK, di).takeIf { it >= 0 } ?: break
                val searchText  = body.substring(afterS, di).trimEnd('\n')
                val replStart   = body.indexOf('\n', di).takeIf { it >= 0 }?.plus(1) ?: (di + DIV.length)
                val replaceText = body.substring(replStart, ri).trimEnd('\n')
                newContent = if (searchText.isEmpty()) {
                    replaceText + if (newContent.isNotEmpty()) "\n$newContent" else ""
                } else {
                    newContent.replace(searchText, replaceText)
                }
                patchApplied = true
                bPos = ri + R_MARK.length
            }

            // ALWAYS strip the raw protocol block — never let it leak as visible text.
            val fullBlock = result.substring(blockStart, blockEnd + END.length)
            if (filename.isNotEmpty() && patchApplied && newContent != oldContent) {
                changes.add(PendingChange(filename, oldContent, newContent, existingUri))
                result = result.replace(fullBlock, "")
                pos = blockStart
            } else {
                val reason = when {
                    !patchApplied           -> "search text not found"
                    newContent == oldContent -> "no changes"
                    else                    -> "empty filename"
                }
                result = result.replace(fullBlock, "\n[⚠ Edit skipped ($reason): $filename]\n")
                pos = blockStart
            }
        }
        return result to changes
    }

    // ── Parse FILE_WRITE blocks (full file create / overwrite) ────────────
    // Hardened:
    //   • Strips markdown code fences (```lang ... ```) the AI wraps content in
    //   • Tolerates a missing FILE_END on the very last block (uses end-of-string)

    private fun parseFileWrites(response: String): Pair<String, List<PendingChange>> {
        val changes = mutableListOf<PendingChange>()
        var result  = response
        val prefix  = "FILE_WRITE:"
        val end     = "FILE_END"

        var pos = 0
        while (true) {
            val blockStart = result.indexOf(prefix, pos).takeIf { it >= 0 } ?: break
            val nameEnd    = result.indexOf('\n', blockStart).takeIf { it >= 0 } ?: break
            val filename   = result.substring(blockStart + prefix.length, nameEnd).trim()
            val cStart     = nameEnd + 1

            // Accept FILE_END or fall back to end-of-string for the last block
            val endIdx = result.indexOf(end, cStart)
                .takeIf { it >= 0 } ?: result.length

            var newContent = result.substring(cStart, endIdx).trim()

            // Strip markdown code fences the AI often wraps content in:
            //   ```kotlin\n...\n```  or  ```\n...\n```
            newContent = newContent
                .replace(Regex("^```[a-zA-Z0-9]*\\n"), "")
                .replace(Regex("\\n```\\s*$"), "")
                .trim()

            if (filename.isNotEmpty() && newContent.isNotEmpty()) {
                val existingUri = findFileUri(filename)
                val oldContent  = existingUri?.let { readUri(it) } ?: ""
                changes.add(PendingChange(filename, oldContent, newContent, existingUri))
                val blockEndPos = if (endIdx < result.length) endIdx + end.length else result.length
                val block = result.substring(blockStart, blockEndPos)
                result = result.replace(block, "[File queued: $filename]")
                pos = blockStart
            } else {
                pos = if (endIdx < result.length) endIdx + end.length else result.length
            }
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
                val doc = dir?.createFile(mimeForFilename(safeName), safeName)
                if (doc != null) {
                    context.contentResolver.openOutputStream(doc.uri, "wt")
                        ?.bufferedWriter()?.use { it.write(change.newContent) }
                    true
                } else false
            }
        } catch (_: Exception) { false }
    }

    private fun mimeForFilename(name: String): String {
        val ext = name.substringAfterLast('.', "").lowercase()
        return when (ext) {
            "html", "htm"   -> "text/html"
            "css"           -> "text/css"
            "js", "mjs"     -> "text/javascript"
            "json"          -> "application/json"
            "xml"           -> "text/xml"
            "svg"           -> "image/svg+xml"
            "md"            -> "text/markdown"
            "ts", "tsx"     -> "text/x-typescript"
            "jsx"           -> "text/x-jsx"
            else            -> "application/octet-stream"
        }
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
            "You operate like a senior engineer: plan, execute, and deliver complete solutions independently.\n\n" +
            "═══ AUTONOMOUS OPERATION RULES ═══\n" +
            "1. Complete tasks end-to-end. Do NOT ask permission between steps.\n" +
            "2. Execute ALL required file changes in ONE response.\n" +
            "3. Proactively fix bugs you spot — don't leave broken code behind.\n" +
            "4. End every response with a short summary: what changed and why.\n" +
            "5. If a task is ambiguous, make a decision and proceed — explain it in the summary.\n\n" +
            "═══ FILE OPERATIONS ═══\n\n" +
            "▶ EDITING an existing file — use FILE_EDIT (REQUIRED for any existing file):\n" +
            "  FILE_EDIT:path/to/file.kt\n" +
            "  <<<<<<< SEARCH\n" +
            "  exact existing lines to replace — copy character-for-character from the file\n" +
            "  =======\n" +
            "  new replacement lines\n" +
            "  >>>>>>> REPLACE\n" +
            "  FILE_END\n\n" +
            "  RULES FOR FILE_EDIT (failure to follow = edit silently skipped):\n" +
            "  • SEARCH text must be an EXACT copy from the file: same whitespace, same indentation.\n" +
            "  • Include 3-5 lines of context so the SEARCH block is unique in the file.\n" +
            "  • Stack multiple SEARCH/REPLACE pairs inside one FILE_EDIT block.\n" +
            "  • Empty SEARCH = prepend the REPLACE text to the top of the file.\n" +
            "  • DO NOT rewrite the entire file via FILE_EDIT — only change what needs changing.\n\n" +
            "▶ CREATING a new file (NEW files only) — use FILE_WRITE:\n" +
            "  FILE_WRITE:path/to/newfile.ext\n" +
            "  <complete file content>\n" +
            "  FILE_END\n\n" +
            "  ⛔ FILE_WRITE on an existing file OVERWRITES it completely — use only for NEW files.\n" +
            "  ⛔ NEVER use FILE_WRITE to edit existing files. Use FILE_EDIT instead.\n\n" +
            "▶ SAVING agent notes (NOT project files):\n" +
            "  FILE_CREATE:notename.md\n" +
            "  <content>\n" +
            "  FILE_END\n\n" +
            "⚠ CRITICAL RULES:\n" +
            "  - NEVER wrap FILE_WRITE or FILE_EDIT blocks inside markdown code fences (``` or ~~~).\n" +
            "  - Every block MUST end with FILE_END on its own line.\n" +
            "  - Never truncate file content with '...' or '// rest unchanged' — always complete.\n" +
            "  - If a FILE_EDIT SEARCH fails (text not found), you will see [⚠ Edit skipped]. Fix by\n" +
            "    re-reading the file and using exact text from the current file content shown below.\n\n" +
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

    // Find the start of a JSON string value for a given key,
    // tolerating optional whitespace between ':' and '"' (pretty-printed JSON).
    private fun findStringStart(json: String, key: String, from: Int = 0): Int {
        var pos = json.indexOf(key, from).takeIf { it >= 0 } ?: return -1
        pos += key.length
        // Skip optional whitespace (spaces, tabs, newlines) before the opening quote
        while (pos < json.length && json[pos] in " \t\r\n") pos++
        return if (pos < json.length && json[pos] == '"') pos + 1 else -1
    }

    // FIX: Search for content after "assistant" role marker to avoid
    // matching "content" fields in other parts of the response JSON.
    // Also handles pretty-printed JSON where key: "value" has a space after ':'.
    private fun extractOpenAIText(json: String): String? {
        val assistantMarker = "\"role\":"
        // find "role": "assistant" tolerating spaces
        var search = 0
        var assistantPos = 0
        while (true) {
            val p = json.indexOf(assistantMarker, search).takeIf { it >= 0 } ?: break
            var q = p + assistantMarker.length
            while (q < json.length && json[q] in " \t\r\n") q++
            if (q < json.length && json[q] == '"') {
                q++
                if (json.startsWith("assistant", q)) { assistantPos = p; break }
            }
            search = p + 1
        }
        val s = findStringStart(json, "\"content\":", assistantPos)
        return if (s >= 0) extractStr(json, s) else null
    }

    private fun extractGeminiText(json: String): String? {
        val s = findStringStart(json, "\"text\":")
        return if (s >= 0) extractStr(json, s) else null
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
