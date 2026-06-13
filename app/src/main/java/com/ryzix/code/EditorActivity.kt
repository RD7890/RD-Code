package com.ryzix.code

import android.app.Activity
import android.app.AlertDialog
import android.app.Dialog
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.view.Window
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.webkit.WebView
import android.widget.*
import androidx.core.view.GravityCompat
import androidx.documentfile.provider.DocumentFile
import androidx.drawerlayout.widget.DrawerLayout
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import io.github.rosemoe.sora.event.ContentChangeEvent
import io.github.rosemoe.sora.event.SelectionChangeEvent
import io.github.rosemoe.sora.lang.EmptyLanguage
import io.github.rosemoe.sora.widget.CodeEditor
import io.github.rosemoe.sora.widget.schemes.EditorColorScheme
import java.util.concurrent.Executors

// ─── Per-tab state ─────────────────────────────────────────────────────────────
data class OpenTab(
    val uri:       Uri,
    val name:      String,
    var content:   String  = "",
    var cursorPos: Int     = 0,
    var modified:  Boolean = false
)

class EditorActivity : Activity() {

    // ─── Views ────────────────────────────────────────────────────────────
    private lateinit var drawer:        DrawerLayout
    private lateinit var editor:        CodeEditor
    private lateinit var tvLineCol:     TextView
    private lateinit var tabsContainer: LinearLayout
    private lateinit var tabsScroll:    HorizontalScrollView
    private lateinit var rvTree:        RecyclerView
    private lateinit var rvChat:        RecyclerView
    private lateinit var etInput:       EditText
    private lateinit var panelExplorer: View
    private lateinit var panelSearch:   View
    private lateinit var iconExplorer:  ImageView
    private lateinit var iconSearch:    ImageView

    // ─── State ────────────────────────────────────────────────────────────
    private var folderUri: Uri? = null
    private val tabs      = mutableListOf<OpenTab>()
    private var activeIdx = -1
    private var skipUndo  = false

    // ─── Chat ─────────────────────────────────────────────────────────────
    private val msgs = mutableListOf<ChatMessage>()
    private lateinit var chatAdapter: ChatAdapter
    private lateinit var ai: AIClient

    // ─── File tree ────────────────────────────────────────────────────────
    private lateinit var treeAdapter: FileTreeAdapter

    // ─── Search ───────────────────────────────────────────────────────────
    private val searchExecutor = Executors.newSingleThreadExecutor()
    private val searchHandler  = Handler(Looper.getMainLooper())

    // ─── HTTP preview server ──────────────────────────────────────────────
    private var localServer: LocalServer? = null

    // ─── Lifecycle ────────────────────────────────────────────────────────
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_editor)

        val uriStr = intent.getStringExtra("FOLDER_URI")
        if (uriStr == null) { finish(); return }
        folderUri = Uri.parse(uriStr)
        ai = AIClient(this)

        // Inline diff cards in the chat (no blocking dialog)
        ai.onPendingChange = { change, cb ->
            runOnUiThread {
                val diffMsg = ChatMessage(
                    text         = "",
                    isUser       = false,
                    diffChange   = change,
                    diffState    = ChatMessage.DiffState.PENDING,
                    diffCallback = { accepted ->
                        cb(accepted)
                        if (accepted) {
                            if (::treeAdapter.isInitialized) treeAdapter.refresh()
                            val openName = if (activeIdx >= 0) tabs[activeIdx].name else ""
                            if (openName == change.filename && activeIdx >= 0) {
                                try {
                                    val txt = contentResolver
                                        .openInputStream(tabs[activeIdx].uri)
                                        ?.bufferedReader()?.readText()
                                    if (txt != null) {
                                        skipUndo = true; editor.setText(txt); skipUndo = false
                                        tabs[activeIdx].content  = txt
                                        tabs[activeIdx].modified = false
                                        updateTabLabel(activeIdx)
                                    }
                                } catch (_: Exception) {}
                            }
                        }
                    }
                )
                msgs.add(diffMsg)
                chatAdapter.notifyItemInserted(msgs.size - 1)
                rvChat.scrollToPosition(msgs.size - 1)
            }
        }

        drawer        = findViewById(R.id.drawerLayout)
        editor        = findViewById(R.id.editor)
        tvLineCol     = findViewById(R.id.tvLineCol)
        tabsContainer = findViewById(R.id.tabsContainer)
        tabsScroll    = findViewById(R.id.tabsScroll)
        rvTree        = findViewById(R.id.panelExplorer)
        rvChat        = findViewById(R.id.recyclerChat)
        etInput       = findViewById(R.id.etChatInput)
        panelExplorer = findViewById(R.id.panelExplorer)
        panelSearch   = findViewById(R.id.panelSearch)
        iconExplorer  = findViewById(R.id.iconExplorer)
        iconSearch    = findViewById(R.id.iconSearch)

        setupEditor()
        setupEditorEvents()
        setupTree()
        setupChat()
        setupToolbar()
        setupLeftSidebarSwitch()
        setupSearchPanel()

        findViewById<View>(R.id.btnMenu).setOnClickListener  { drawer.openDrawer(GravityCompat.START) }
        findViewById<View>(R.id.btnAgent).setOnClickListener { drawer.openDrawer(GravityCompat.END) }
    }

    override fun onPause() {
        super.onPause()
        autoSaveAll()
    }

    override fun onDestroy() {
        super.onDestroy()
        localServer?.stop()
        editor.release()
    }

    private fun autoSaveAll() {
        saveCurrentTabState()
        for (tab in tabs) {
            if (!tab.modified) continue
            try {
                contentResolver.openOutputStream(tab.uri, "wt")?.bufferedWriter()?.use {
                    it.write(tab.content)
                }
                tab.modified = false
                val idx = tabs.indexOf(tab)
                if (idx >= 0) updateTabLabel(idx)
            } catch (_: Exception) {}
        }
    }

    // ─── Sora editor setup ────────────────────────────────────────────────
    private fun setupEditor() {
        try { editor.typefaceText = Typeface.MONOSPACE } catch (_: Exception) {}
        editor.setTextSize(13f)
        try { editor.wordwrap = false } catch (_: Exception) {}

        try {
            val scheme = EditorColorScheme()
            scheme.setColor(EditorColorScheme.WHOLE_BACKGROUND,        Color.parseColor("#0F0F11"))
            scheme.setColor(EditorColorScheme.LINE_NUMBER_BACKGROUND,   Color.parseColor("#0A0A0C"))
            scheme.setColor(EditorColorScheme.LINE_NUMBER,              Color.parseColor("#3A3A44"))
            scheme.setColor(EditorColorScheme.CURRENT_LINE,             Color.parseColor("#151518"))
            scheme.setColor(EditorColorScheme.TEXT_NORMAL,              Color.parseColor("#D4D4D4"))
            scheme.setColor(EditorColorScheme.KEYWORD,                  Color.parseColor("#569CD6"))
            scheme.setColor(EditorColorScheme.STRING,                   Color.parseColor("#CE9178"))
            scheme.setColor(EditorColorScheme.COMMENT,                  Color.parseColor("#6A9955"))
            scheme.setColor(EditorColorScheme.OPERATOR,                 Color.parseColor("#D4D4D4"))
            scheme.setColor(EditorColorScheme.SELECTED_TEXT_BACKGROUND, Color.parseColor("#264F78"))
            scheme.setColor(EditorColorScheme.SELECTION_INSERT,         Color.parseColor("#E52A3F"))
            scheme.setColor(EditorColorScheme.SELECTION_HANDLE,         Color.parseColor("#E52A3F"))
            editor.colorScheme = scheme
        } catch (_: Exception) {}

        editor.setEditorLanguage(EmptyLanguage())
    }

    // ─── Editor event subscriptions ───────────────────────────────────────
    private fun setupEditorEvents() {
        editor.subscribeEvent(ContentChangeEvent::class.java) { _, _ ->
            if (!skipUndo && activeIdx >= 0) {
                tabs[activeIdx].modified = true
                updateTabLabel(activeIdx)
            }
            updateLineCol()
        }
        editor.subscribeEvent(SelectionChangeEvent::class.java) { _, _ ->
            updateLineCol()
        }
    }

    private fun updateLineCol() {
        try {
            tvLineCol.text = "${editor.cursor.leftLine + 1}:${editor.cursor.leftColumn + 1}"
        } catch (_: Exception) {}
    }

    // ─── Cursor helpers ───────────────────────────────────────────────────

    private fun charOffsetToLineCol(text: String, offset: Int): Pair<Int, Int> {
        val safe   = offset.coerceIn(0, text.length)
        val before = text.substring(0, safe)
        val line   = before.count { it == '\n' }
        val col    = safe - (before.lastIndexOf('\n') + 1)
        return line to col
    }

    private fun editorCursorPos(): Int {
        return try {
            val txt   = editor.text.toString()
            val line  = editor.cursor.leftLine
            val col   = editor.cursor.leftColumn
            val lines = txt.split('\n')
            var pos   = 0
            for (i in 0 until minOf(line, lines.size)) pos += lines[i].length + 1
            (pos + col).coerceAtMost(txt.length)
        } catch (_: Exception) { 0 }
    }

    private fun editorSetSel(charPos: Int) {
        try {
            val (l, c) = charOffsetToLineCol(editor.text.toString(), charPos)
            editor.setSelection(l, c)
        } catch (_: Exception) {}
    }

    private fun editorSetSel(startChar: Int, endChar: Int) {
        try {
            val txt      = editor.text.toString()
            val (sl, sc) = charOffsetToLineCol(txt, startChar)
            val (el, ec) = charOffsetToLineCol(txt, endChar)
            editor.setSelectionRegion(sl, sc, el, ec)
        } catch (_: Exception) {}
    }

    // ─── File tree ────────────────────────────────────────────────────────
    private fun setupTree() {
        treeAdapter = FileTreeAdapter(this, folderUri!!) { doc -> openTab(doc) }
        rvTree.layoutManager = LinearLayoutManager(this)
        rvTree.adapter = treeAdapter
    }

    // ─── Multi-tab system ─────────────────────────────────────────────────
    private fun openTab(doc: DocumentFile) {
        val existing = tabs.indexOfFirst { it.uri == doc.uri }
        if (existing >= 0) {
            switchTab(existing)
            drawer.closeDrawer(GravityCompat.START)
            return
        }
        saveCurrentTabState()
        val content = try {
            contentResolver.openInputStream(doc.uri)?.bufferedReader()?.readText() ?: ""
        } catch (_: Exception) { "" }
        tabs.add(OpenTab(uri = doc.uri, name = doc.name ?: "?", content = content))
        switchTab(tabs.size - 1)
        drawer.closeDrawer(GravityCompat.START)
    }

    private fun saveCurrentTabState() {
        if (activeIdx < 0 || activeIdx >= tabs.size) return
        val tab = tabs[activeIdx]
        tab.content   = editor.text.toString()
        tab.cursorPos = editorCursorPos()
    }

    private fun switchTab(idx: Int) {
        if (idx < 0 || idx >= tabs.size) return
        saveCurrentTabState()
        activeIdx = idx
        val tab = tabs[idx]
        skipUndo = true
        editor.setText(tab.content)
        try {
            val (l, c) = charOffsetToLineCol(tab.content, tab.cursorPos)
            editor.setSelection(l, c)
        } catch (_: Exception) {}
        skipUndo = false
        renderTabs()
        updateLineCol()
    }

    private fun closeTab(idx: Int) {
        if (idx < 0 || idx >= tabs.size) return
        val tab = tabs[idx]
        val doClose = {
            tabs.removeAt(idx)
            when {
                tabs.isEmpty()         -> {
                    activeIdx = -1
                    skipUndo = true; editor.setText(""); skipUndo = false
                    renderTabs()
                }
                activeIdx >= tabs.size -> switchTab(tabs.size - 1)
                idx < activeIdx        -> { activeIdx--; switchTab(activeIdx) }
                else                   -> switchTab(activeIdx.coerceAtMost(tabs.size - 1))
            }
        }
        if (tab.modified) {
            AlertDialog.Builder(this)
                .setTitle("Unsaved changes")
                .setMessage("Close '${tab.name}' without saving?")
                .setPositiveButton("Discard") { _, _ -> doClose() }
                .setNegativeButton("Cancel", null).show()
        } else {
            doClose()
        }
    }

    private fun renderTabs() {
        tabsContainer.removeAllViews()
        for ((i, tab) in tabs.withIndex()) tabsContainer.addView(makeTabChip(tab, i, i == activeIdx))
        tabsScroll.post {
            if (activeIdx >= 0 && activeIdx < tabsContainer.childCount)
                tabsScroll.smoothScrollTo(tabsContainer.getChildAt(activeIdx).left, 0)
        }
    }

    private fun updateTabLabel(idx: Int) {
        if (idx < 0 || idx >= tabsContainer.childCount) return
        val chip  = tabsContainer.getChildAt(idx) as? LinearLayout ?: return
        val label = chip.getChildAt(0) as? TextView ?: return
        val tab   = tabs[idx]
        label.text = if (tab.modified) "${tab.name} ●" else tab.name
    }

    private fun makeTabChip(tab: OpenTab, idx: Int, active: Boolean): LinearLayout {
        val chip = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity     = Gravity.CENTER_VERTICAL
            setPadding(dp(12), 0, dp(4), 0)
            setBackgroundColor(if (active) Color.parseColor("#1A1A1D") else Color.parseColor("#101012"))
            layoutParams = LinearLayout.LayoutParams(WRAP_CONTENT, MATCH_PARENT)
        }
        chip.addView(TextView(this).apply {
            text     = if (tab.modified) "${tab.name} ●" else tab.name
            textSize = 12f; maxLines = 1
            setTextColor(if (active) Color.parseColor("#EEEEF2") else Color.parseColor("#666672"))
        })
        chip.addView(TextView(this).apply {
            text      = "×"; textSize = 15f
            setPadding(dp(6), dp(2), dp(8), dp(2))
            setTextColor(if (active) Color.parseColor("#888892") else Color.parseColor("#444450"))
            setOnClickListener { closeTab(idx) }
        })
        chip.setOnClickListener { switchTab(idx) }
        return chip
    }

    // ─── Symbol toolbar ───────────────────────────────────────────────────
    private fun setupToolbar() {
        findViewById<View>(R.id.btnUndo).setOnClickListener { editor.undo() }
        findViewById<View>(R.id.btnRedo).setOnClickListener { editor.redo() }
        findViewById<View>(R.id.btnFind).setOnClickListener { showFindReplace() }
        findViewById<View>(R.id.btnSave).setOnClickListener { saveActiveTab() }
        findViewById<View>(R.id.btnEsc).setOnClickListener  { hideKeyboard() }
        findViewById<View>(R.id.btnTab).setOnClickListener  { insertText("    ") }
        findViewById<View>(R.id.btnRun).setOnClickListener  { showPreview() }

        val symbolRow = findViewById<LinearLayout>(R.id.symbolKeysRow)
        val symbols = listOf(
            "{", "}", "(", ")", "[", "]",
            ";", ":", "\"", "'", "/", "\\",
            "#", "@", "=", "!", "<", ">",
            "+", "-", "*", "&", "|", "~",
            "$", "^", "%", ",", ".", "?"
        )
        for (sym in symbols) {
            symbolRow.addView(TextView(this).apply {
                text      = sym; textSize = 14f
                typeface  = Typeface.MONOSPACE; gravity = Gravity.CENTER
                setTextColor(Color.parseColor("#C8C8D0"))
                setBackgroundColor(Color.parseColor("#1A1A1D"))
                layoutParams = LinearLayout.LayoutParams(dp(36), MATCH_PARENT)
                    .also { it.leftMargin = dp(1) }
                setOnClickListener { insertText(sym) }
            })
        }
        listOf("←" to false, "→" to true).forEach { (label, isRight) ->
            symbolRow.addView(TextView(this).apply {
                text     = label; textSize = 14f; gravity = Gravity.CENTER
                setTextColor(Color.parseColor("#D4D4DC"))
                setBackgroundColor(Color.parseColor("#252528"))
                layoutParams = LinearLayout.LayoutParams(dp(40), MATCH_PARENT)
                    .also { it.leftMargin = dp(2) }
                setOnClickListener {
                    try {
                        val pos = editorCursorPos()
                        val len = editor.text.length
                        val next = if (isRight) (pos + 1).coerceAtMost(len)
                                   else         (pos - 1).coerceAtLeast(0)
                        editorSetSel(next)
                    } catch (_: Exception) {}
                }
            })
        }
    }

    private fun insertText(s: String) {
        try { editor.commitText(s, true) } catch (_: Exception) {}
    }

    private fun saveActiveTab() {
        val idx = activeIdx
        if (idx < 0 || idx >= tabs.size) { toast("No file open"); return }
        val tab = tabs[idx]
        try {
            val txt = editor.text.toString()
            contentResolver.openOutputStream(tab.uri, "wt")?.bufferedWriter()?.use { it.write(txt) }
            tab.modified = false
            tab.content  = txt
            updateTabLabel(idx)
            toast("Saved")
        } catch (_: Exception) { toast("Save failed") }
    }

    private fun hideKeyboard() {
        (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager)
            .hideSoftInputFromWindow(editor.windowToken, 0)
    }

    // ─── Find & Replace ───────────────────────────────────────────────────
    private fun showFindReplace() {
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(16), dp(20), dp(8))
            setBackgroundColor(Color.parseColor("#1A1A1D"))
        }
        fun et(hint: String) = EditText(this).apply {
            this.hint = hint
            setTextColor(Color.WHITE)
            setHintTextColor(Color.parseColor("#444450"))
            setBackgroundColor(Color.parseColor("#242428"))
            setPadding(dp(10), dp(8), dp(10), dp(8))
            layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT)
                .also { it.bottomMargin = dp(8) }
        }
        val etQuery   = et("Find in file...")
        val etReplace = et("Replace with...")
        layout.addView(etQuery)
        layout.addView(etReplace)

        AlertDialog.Builder(this)
            .setTitle("Find & Replace")
            .setView(layout)
            .setPositiveButton("Find Next") { _, _ ->
                val q = etQuery.text.toString()
                if (q.isEmpty()) return@setPositiveButton
                val txt  = editor.text.toString()
                val from = editorCursorPos().let { if (it < txt.length) it else 0 }
                val idx  = txt.indexOf(q, from).let { if (it < 0) txt.indexOf(q, 0) else it }
                if (idx >= 0) { editorSetSel(idx, idx + q.length); editor.requestFocus() }
                else toast("Not found")
            }
            .setNeutralButton("Replace All") { _, _ ->
                val q = etQuery.text.toString()
                val r = etReplace.text.toString()
                if (q.isEmpty()) return@setNeutralButton
                editor.setText(editor.text.toString().replace(q, r))
                toast("Replaced")
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // ─── Left panel switcher ──────────────────────────────────────────────
    private fun setupLeftSidebarSwitch() {
        iconExplorer.setOnClickListener { showPanel(explorer = true) }
        iconSearch.setOnClickListener   { showPanel(explorer = false) }
        showPanel(explorer = true)
    }

    private fun showPanel(explorer: Boolean) {
        panelExplorer.visibility = if (explorer) View.VISIBLE else View.GONE
        panelSearch.visibility   = if (explorer) View.GONE    else View.VISIBLE
        iconExplorer.alpha       = if (explorer) 1f           else 0.4f
        iconSearch.alpha         = if (explorer) 0.4f         else 1f
    }

    // ─── Search in Files ──────────────────────────────────────────────────
    private fun setupSearchPanel() {
        val etQuery   = findViewById<EditText>(R.id.etSearchQuery)
        val btnGo     = findViewById<View>(R.id.btnSearchGo)
        val rvResults = findViewById<RecyclerView>(R.id.rvSearchResults)
        val tvStatus  = findViewById<TextView>(R.id.tvSearchStatus)
        val cbCase    = findViewById<TextView>(R.id.cbCaseSensitive)
        val cbWord    = findViewById<TextView>(R.id.cbWholeWord)

        var caseSensitive = false
        var wholeWord     = false

        fun chip(tv: TextView, on: Boolean) {
            tv.setTextColor(if (on) Color.WHITE else Color.parseColor("#666672"))
            tv.setBackgroundColor(if (on) Color.parseColor("#2A2A6E") else Color.parseColor("#1A1A1D"))
        }
        chip(cbCase, caseSensitive); chip(cbWord, wholeWord)
        cbCase.setOnClickListener { caseSensitive = !caseSensitive; chip(cbCase, caseSensitive) }
        cbWord.setOnClickListener { wholeWord     = !wholeWord;     chip(cbWord, wholeWord) }

        val adapter = SearchResultAdapter { item ->
            val doc = DocumentFile.fromSingleUri(this, item.fileUri)
            if (doc != null) {
                openTab(doc)
                searchHandler.postDelayed({ jumpToLine(item.lineNumber, item.query) }, 150)
            }
            drawer.closeDrawer(GravityCompat.START)
        }
        rvResults.layoutManager = LinearLayoutManager(this)
        rvResults.adapter = adapter

        fun doSearch() {
            val q    = etQuery.text.toString().trim()
            if (q.isEmpty()) return
            val fUri = folderUri ?: return
            tvStatus.text = "Searching..."; tvStatus.visibility = View.VISIBLE
            adapter.setItems(emptyList())
            searchExecutor.execute {
                val results = mutableListOf<SearchResultItem>()
                try {
                    walkAndSearch(DocumentFile.fromTreeUri(this, fUri)!!, q, caseSensitive, wholeWord, results)
                } catch (_: Exception) {}
                searchHandler.post {
                    adapter.setItems(results)
                    tvStatus.text = if (results.isEmpty()) "No results"
                    else "${results.size} match(es) in ${results.map { it.fileName }.distinct().size} file(s)"
                }
            }
        }
        btnGo.setOnClickListener { doSearch() }
        etQuery.setOnEditorActionListener { _, _, _ -> doSearch(); true }
    }

    private fun walkAndSearch(
        dir: DocumentFile, q: String, cs: Boolean, whole: Boolean,
        out: MutableList<SearchResultItem>
    ) {
        val textExts = setOf(
            "kt","java","xml","json","gradle","md","txt","properties",
            "kts","html","css","js","py","cpp","h","c","ts","go","rb",
            "jsx","tsx","vue","php","swift","rs","dart","sh","yaml","toml"
        )
        for (f in dir.listFiles()) {
            if (f.isDirectory) { walkAndSearch(f, q, cs, whole, out); continue }
            val name = f.name ?: continue
            if (name.substringAfterLast('.', "").lowercase() !in textExts) continue
            if (out.size >= 500) return
            try {
                val lines = contentResolver.openInputStream(f.uri)
                    ?.bufferedReader()?.readLines() ?: continue
                for ((i, line) in lines.withIndex()) {
                    val hay    = if (cs) line else line.lowercase()
                    val needle = if (cs) q    else q.lowercase()
                    val hit    = if (whole) Regex("\\b${Regex.escape(needle)}\\b").containsMatchIn(hay)
                                 else hay.contains(needle)
                    if (hit) out.add(SearchResultItem(f.uri, name, i + 1, line.trim().take(80), q))
                    if (out.size >= 500) return
                }
            } catch (_: Exception) {}
        }
    }

    private fun jumpToLine(lineNumber: Int, query: String) {
        try {
            val line = (lineNumber - 1).coerceAtLeast(0)
            val col  = if (query.isNotEmpty()) {
                editor.text.toString().lines().getOrNull(line)
                    ?.lowercase()?.indexOf(query.lowercase())?.coerceAtLeast(0) ?: 0
            } else 0
            editor.setSelection(line, col)
            editor.requestFocus()
        } catch (_: Exception) {}
    }

    // ─── HTTP preview server ──────────────────────────────────────────────
    private fun showPreview() {
        val folder = folderUri?.let { DocumentFile.fromTreeUri(this, it) }
        if (folder == null) { toast("Open a project folder first"); return }

        if (localServer == null || !localServer!!.isAlive) {
            localServer?.stop()
            localServer = LocalServer(folder, this)
            try {
                localServer!!.start()
            } catch (e: Exception) {
                toast("Server error: ${e.message}")
                return
            }
        }

        val fileName = if (activeIdx >= 0) tabs[activeIdx].name else ""
        val url = if (fileName.endsWith(".html", ignoreCase = true))
            "http://localhost:${LocalServer.PORT}/$fileName"
        else
            "http://localhost:${LocalServer.PORT}/index.html"

        showWebViewSheet(url)
    }

    private fun showWebViewSheet(url: String) {
        val dialog = Dialog(this)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#0F0F11"))
        }

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(Color.parseColor("#141416"))
            setPadding(dp(12), 0, dp(4), 0)
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, dp(44))
        }
        header.addView(TextView(this).apply {
            text     = url.replace("http://localhost:${LocalServer.PORT}", "")
            textSize = 11f
            setTextColor(Color.parseColor("#808088"))
            typeface = Typeface.MONOSPACE
            layoutParams = LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f)
        })
        val refreshBtn = TextView(this).apply {
            text = "↻"; textSize = 20f; gravity = Gravity.CENTER
            setTextColor(Color.parseColor("#909098"))
            setPadding(dp(10), dp(10), dp(10), dp(10))
        }
        val closeBtn = TextView(this).apply {
            text = "✕"; textSize = 16f; gravity = Gravity.CENTER
            setTextColor(Color.parseColor("#909098"))
            setPadding(dp(10), dp(10), dp(14), dp(10))
            setOnClickListener { dialog.dismiss() }
        }
        header.addView(refreshBtn)
        header.addView(closeBtn)

        val webView = WebView(this).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.allowFileAccess   = true
            layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f)
        }
        refreshBtn.setOnClickListener { webView.reload() }

        root.addView(header)
        root.addView(webView)

        dialog.setContentView(root)
        dialog.window?.apply {
            setLayout(MATCH_PARENT, MATCH_PARENT)
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        }
        dialog.show()
        webView.loadUrl(url)
    }

    // ─── Agent chat ───────────────────────────────────────────────────────
    private fun setupChat() {
        chatAdapter = ChatAdapter(this, msgs)
        val llm = LinearLayoutManager(this).apply { stackFromEnd = true }
        rvChat.layoutManager = llm
        rvChat.adapter = chatAdapter

        msgs.add(ChatMessage(
            "Hi! I'm RD Agent — your autonomous coding assistant.\n\n" +
            "I have full access to your project. I can:\n" +
            "• Plan and execute multi-step coding tasks\n" +
            "• Write/fix multiple files in one response\n" +
            "• Proactively fix bugs I notice\n\n" +
            "Tap ▶ in the toolbar to preview HTML files.", false
        ))
        chatAdapter.notifyDataSetChanged()

        val btnSend = findViewById<View>(R.id.btnChatSend)
        btnSend.setOnClickListener { sendChatMessage() }
        btnSend.setOnLongClickListener {
            AlertDialog.Builder(this)
                .setTitle("Clear conversation?")
                .setMessage("This clears the chat history (${ai.getHistorySize() / 2} exchanges). Agent notes are kept.")
                .setPositiveButton("Clear") { _, _ ->
                    ai.clearHistory()
                    msgs.clear()
                    msgs.add(ChatMessage("Conversation cleared. Starting fresh.", false))
                    chatAdapter.notifyDataSetChanged()
                    rvChat.scrollToPosition(0)
                    toast("Chat cleared")
                }
                .setNegativeButton("Cancel", null).show()
            true
        }

        findViewById<View>(R.id.btnAgentSettings).setOnClickListener { showSettings() }
        findViewById<View>(R.id.btnOpenStudio).setOnClickListener {
            val openUri  = if (activeIdx >= 0) tabs[activeIdx].uri  else null
            val openName = if (activeIdx >= 0) tabs[activeIdx].name else ""
            startActivity(Intent(this, StudioActivity::class.java).apply {
                putExtra("FOLDER_URI",     folderUri?.toString() ?: "")
                putExtra("OPEN_FILE_URI",  openUri?.toString() ?: "")
                putExtra("OPEN_FILE_NAME", openName)
            })
        }
    }

    private fun sendChatMessage() {
        val msg = etInput.text.toString().trim()
        if (msg.isEmpty()) return

        msgs.add(ChatMessage(msg, true))
        chatAdapter.notifyItemInserted(msgs.size - 1)
        rvChat.scrollToPosition(msgs.size - 1)
        etInput.setText("")

        val typingIdx = msgs.size
        msgs.add(ChatMessage("Thinking...", false))
        chatAdapter.notifyItemInserted(typingIdx)
        rvChat.scrollToPosition(typingIdx)

        val openUri      = if (activeIdx >= 0) tabs[activeIdx].uri else null
        val openTabsList = tabs.map { it.name to it.uri }

        ai.send(
            message     = msg,
            projectUri  = folderUri,
            openFileUri = openUri,
            openTabs    = openTabsList,
            onStatus    = { status ->
                runOnUiThread {
                    if (typingIdx < msgs.size) {
                        msgs[typingIdx] = ChatMessage(status, false)
                        chatAdapter.notifyItemChanged(typingIdx)
                    }
                }
            }
        ) { _, resp ->
            runOnUiThread {
                if (typingIdx < msgs.size) {
                    msgs.removeAt(typingIdx)
                    chatAdapter.notifyItemRemoved(typingIdx)
                }
                msgs.add(ChatMessage(resp, false))
                chatAdapter.notifyItemInserted(msgs.size - 1)
                rvChat.scrollToPosition(msgs.size - 1)
            }
        }
    }

    // ─── Settings ─────────────────────────────────────────────────────────
    private fun showSettings() {
        val sp     = getSharedPreferences("ryzix_prefs", MODE_PRIVATE)
        val dialog = Dialog(this)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(20), dp(24), dp(20))
            setBackgroundColor(Color.parseColor("#1A1A1D"))
        }
        val itemLp = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT)
            .apply { bottomMargin = dp(10) }

        fun label(t: String) = TextView(this).apply {
            text = t; setTextColor(Color.parseColor("#909098"))
            textSize = 11f; layoutParams = itemLp
        }
        fun field(hint: String, key: String, def: String, pwd: Boolean = false) =
            EditText(this).apply {
                this.hint = hint
                setText(sp.getString(key, def) ?: "")
                setTextColor(Color.WHITE); setHintTextColor(Color.parseColor("#444450"))
                setBackgroundColor(Color.parseColor("#242428"))
                setPadding(dp(10), dp(8), dp(10), dp(8))
                if (pwd) inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
                layoutParams = itemLp
            }

        root.addView(TextView(this).apply {
            text = "Agent Settings"; setTextColor(Color.WHITE)
            textSize = 16f; setTypeface(null, Typeface.BOLD); layoutParams = itemLp
        })
        root.addView(label("Base URL"))
        val etUrl = field("https://openrouter.ai/api/v1", "agent_url", "https://openrouter.ai/api/v1")
        root.addView(etUrl)
        root.addView(label("API Key"))
        val etKey = field("sk-or-...", "agent_key", "", pwd = true)
        root.addView(etKey)
        root.addView(label("Model"))
        val etMod = field("qwen/qwen3-coder:free", "agent_model", "qwen/qwen3-coder:free")
        root.addView(etMod)

        root.addView(Button(this).apply {
            text = "SAVE"
            setBackgroundColor(Color.parseColor("#E52A3F")); setTextColor(Color.WHITE)
            layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT)
                .apply { topMargin = dp(8); bottomMargin = dp(16) }
            setOnClickListener {
                sp.edit()
                    .putString("agent_url",   etUrl.text.toString())
                    .putString("agent_key",   etKey.text.toString())
                    .putString("agent_model", etMod.text.toString())
                    .apply()
                dialog.dismiss(); toast("Saved")
            }
        })

        val historyCount = ai.getHistorySize() / 2
        if (historyCount > 0) {
            root.addView(TextView(this).apply {
                text = "Conversation: $historyCount exchange(s) in memory"
                setTextColor(Color.parseColor("#606068")); textSize = 11f; layoutParams = itemLp
            })
        }

        val agentFiles = ai.getAgentFiles()
        if (agentFiles.isNotEmpty()) {
            root.addView(label("Agent Notes"))
            agentFiles.forEach { f ->
                root.addView(TextView(this).apply {
                    text = f.name; setTextColor(Color.parseColor("#EEEEF2"))
                    textSize = 13f; setPadding(0, dp(12), 0, dp(12))
                    setOnClickListener { dialog.dismiss(); showAgentFile(f.name, ai.readAgentFile(f)) }
                })
            }
        }

        val sv = ScrollView(this).apply { addView(root) }
        dialog.setContentView(sv)
        dialog.window?.setLayout(MATCH_PARENT, (resources.displayMetrics.heightPixels * 0.75f).toInt())
        dialog.window?.setGravity(Gravity.BOTTOM)
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        dialog.show()
    }

    private fun showAgentFile(name: String, content: String) {
        val dialog = Dialog(this); dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setBackgroundColor(Color.parseColor("#161618"))
        }
        val hdr = LinearLayout(this).apply {
            setBackgroundColor(Color.parseColor("#1A1A1D"))
            setPadding(dp(16), dp(14), dp(16), dp(14)); gravity = Gravity.CENTER_VERTICAL
        }
        hdr.addView(TextView(this).apply {
            text = name; setTextColor(Color.WHITE); textSize = 13f
            layoutParams = LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f)
        })
        hdr.addView(TextView(this).apply {
            text = "  ×  "; setTextColor(Color.parseColor("#909098"))
            textSize = 16f; setOnClickListener { dialog.dismiss() }
        })
        root.addView(hdr)
        val sv = ScrollView(this)
        sv.addView(EditText(this).apply {
            setText(content); typeface = Typeface.MONOSPACE; textSize = 12f
            setTextColor(Color.parseColor("#EEEEF2")); setBackgroundColor(Color.parseColor("#161618"))
            setPadding(dp(16), dp(16), dp(16), dp(16))
            isFocusable = false; isFocusableInTouchMode = false
        })
        root.addView(sv)
        dialog.setContentView(root)
        dialog.window?.setLayout(MATCH_PARENT, (resources.displayMetrics.heightPixels * 0.85f).toInt())
        dialog.window?.setGravity(Gravity.BOTTOM)
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        dialog.show()
    }

    // ─── Back press ───────────────────────────────────────────────────────
    override fun onBackPressed() {
        when {
            drawer.isDrawerOpen(GravityCompat.END)   -> drawer.closeDrawer(GravityCompat.END)
            drawer.isDrawerOpen(GravityCompat.START) -> drawer.closeDrawer(GravityCompat.START)
            else -> super.onBackPressed()
        }
    }

    // ─── Util ─────────────────────────────────────────────────────────────
    private fun dp(n: Int) = (n * resources.displayMetrics.density).toInt()
    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
}

// ─── Search result model ──────────────────────────────────────────────────────
data class SearchResultItem(
    val fileUri:    Uri,
    val fileName:   String,
    val lineNumber: Int,
    val lineText:   String,
    val query:      String
)

// ─── Search results adapter ───────────────────────────────────────────────────
class SearchResultAdapter(
    private val onClick: (SearchResultItem) -> Unit
) : RecyclerView.Adapter<SearchResultAdapter.VH>() {

    private val items = mutableListOf<SearchResultItem>()

    fun setItems(list: List<SearchResultItem>) {
        items.clear(); items.addAll(list); notifyDataSetChanged()
    }

    inner class VH(v: View, val tvFile: TextView, val tvLine: TextView) : RecyclerView.ViewHolder(v)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val ctx = parent.context
        fun Int.dp() = (this * ctx.resources.displayMetrics.density).toInt()
        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(10.dp(), 8.dp(), 10.dp(), 8.dp())
            layoutParams = ViewGroup.LayoutParams(MATCH_PARENT, WRAP_CONTENT)
        }
        val tvFile = TextView(ctx).apply {
            textSize = 11f; setTextColor(Color.parseColor("#E52A3F"))
            typeface = Typeface.MONOSPACE; maxLines = 1
        }
        val tvLine = TextView(ctx).apply {
            textSize = 12f; setTextColor(Color.parseColor("#909098"))
            typeface = Typeface.MONOSPACE; maxLines = 2
            layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT)
                .also { it.topMargin = 2.dp() }
        }
        row.addView(tvFile); row.addView(tvLine)
        return VH(row, tvFile, tvLine)
    }

    override fun onBindViewHolder(h: VH, pos: Int) {
        val item = items[pos]
        h.tvFile.text = "${item.fileName}:${item.lineNumber}"
        h.tvLine.text = item.lineText
        h.itemView.setOnClickListener { onClick(item) }
    }

    override fun getItemCount() = items.size
}
