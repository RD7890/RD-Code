package com.ryzix.code

import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.webkit.*
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import android.app.Activity
import android.app.AlertDialog
import androidx.documentfile.provider.DocumentFile
import java.util.concurrent.Executors

class StudioActivity : Activity() {

    private lateinit var webView:   WebView
    private lateinit var tvStatus:  TextView
    private lateinit var tvTitle:   TextView
    private lateinit var btnInject: LinearLayout
    private lateinit var btnApply:  LinearLayout
    private lateinit var btnBack:   TextView

    private var folderUri:    Uri? = null
    private var openFileUri:  Uri? = null
    private var openFileName: String = ""

    private val executor = Executors.newSingleThreadExecutor()
    private val handler  = Handler(Looper.getMainLooper())
    private var builtContext = ""

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_studio)

        folderUri    = intent.getStringExtra("FOLDER_URI")
            ?.takeIf { it.isNotEmpty() }?.let { Uri.parse(it) }
        openFileUri  = intent.getStringExtra("OPEN_FILE_URI")
            ?.takeIf { it.isNotEmpty() }?.let { Uri.parse(it) }
        openFileName = intent.getStringExtra("OPEN_FILE_NAME") ?: "No file open"

        webView   = findViewById(R.id.studioWebView)
        tvStatus  = findViewById(R.id.tvStudioStatus)
        tvTitle   = findViewById(R.id.tvStudioTitle)
        btnInject = findViewById(R.id.btnStudioInject)
        btnApply  = findViewById(R.id.btnStudioApply)
        btnBack   = findViewById(R.id.btnStudioBack)

        tvTitle.text = "AI Studio  ·  $openFileName"

        btnBack.setOnClickListener {
            if (webView.canGoBack()) webView.goBack() else finish()
        }
        btnInject.setOnClickListener { doInject() }
        btnApply.setOnClickListener  { doApply()  }

        setupWebView()
    }

    override fun onBackPressed() {
        if (webView.canGoBack()) webView.goBack() else super.onBackPressed()
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView() {
        webView.settings.apply {
            javaScriptEnabled                     = true
            domStorageEnabled                     = true
            databaseEnabled                       = true
            allowContentAccess                    = true
            javaScriptCanOpenWindowsAutomatically = true
            setSupportMultipleWindows(true)
            loadWithOverviewMode = true
            useWideViewPort      = true
            userAgentString = "Mozilla/5.0 (Linux; Android 11; Tablet) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
        }

        webView.addJavascriptInterface(JsBridge(), "RyzixBridge")

        webView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                handler.post {
                    val host = try { Uri.parse(url).host ?: "AI Studio" } catch (_: Exception) { "AI Studio" }
                    tvTitle.text = "$host  ·  $openFileName"
                }
            }
            override fun shouldOverrideUrlLoading(
                view: WebView?, request: WebResourceRequest?
            ): Boolean = false
        }

        webView.webChromeClient = object : WebChromeClient() {
            override fun onConsoleMessage(m: ConsoleMessage?): Boolean = true
        }

        webView.loadUrl("https://aistudio.google.com/prompts/new_chat")
    }

    private fun doInject() {
        setStatus("Reading project files...")
        btnInject.isEnabled = false

        executor.execute {
            builtContext = buildContextString()
            handler.post {
                btnInject.isEnabled = true
                setStatus("Context ready (${builtContext.length} chars). Injecting into AI Studio...")
                runInjectJs()
            }
        }
    }

    private fun runInjectJs() {
        val escaped = builtContext
            .replace("\\", "\\\\")
            .replace("`",  "\\`")
            .replace("$",  "\\$")

        val instructions = escaped +
            "\n\nINSTRUCTIONS:\n" +
            "You have full access to the project files above.\n" +
            "When writing/modifying a file, wrap it EXACTLY like this:\n" +
            "RYZIX_WRITE:filename.kt\n<full file content>\nRYZIX_END\n" +
            "Always output the COMPLETE file, never partial.\n"

        val js = """
(function() {
  var ctx = `$instructions`;

  function tryFill(el) {
    if (!el) return false;
    if (el.tagName === 'TEXTAREA') {
      var setter = Object.getOwnPropertyDescriptor(
        window.HTMLTextAreaElement.prototype, 'value').set;
      setter.call(el, ctx);
      el.dispatchEvent(new Event('input',  {bubbles:true}));
      el.dispatchEvent(new Event('change', {bubbles:true}));
      return true;
    }
    if (el.isContentEditable) {
      el.focus();
      el.innerText = '';
      document.execCommand('insertText', false, ctx);
      el.dispatchEvent(new Event('input',  {bubbles:true}));
      return true;
    }
    return false;
  }

  var selectors = [
    'ms-system-instruction textarea',
    'ms-system-instruction [contenteditable]',
    '.system-instruction textarea',
    '.system-instruction [contenteditable]',
    'textarea[placeholder*="system"]',
    'textarea[placeholder*="System"]',
    'rich-textarea [contenteditable="true"]',
    '.ql-editor',
    'div[contenteditable="true"][data-placeholder]',
    'div[contenteditable="true"]',
    'textarea:not([readonly]):not([disabled])'
  ];

  var filled = false;
  for (var i = 0; i < selectors.length; i++) {
    var el = document.querySelector(selectors[i]);
    if (el && (el.offsetParent !== null || el.tagName === 'TEXTAREA')) {
      if (tryFill(el)) {
        filled = true;
        RyzixBridge.onStatus("Injected into input (" + selectors[i] + ")! Send it to start.");
        break;
      }
    }
  }

  if (!filled) {
    RyzixBridge.copyToClipboard(ctx);
    RyzixBridge.onStatus("COPIED to clipboard. Tap the chat input in AI Studio and paste (long press > Paste).");
  }
})();
        """.trimIndent()

        webView.evaluateJavascript(js, null)
    }

    private fun doApply() {
        setStatus("Scanning AI Studio response for RYZIX_WRITE blocks...")

        val js = """
(function() {
  var text = '';
  var selectors = [
    'ms-chat-turn[role="model"] .response-content',
    'ms-chat-turn[role="model"]',
    'model-response',
    '.model-turn',
    '.response-container',
    'ms-zero-shot-chat-turn',
    'pre', 'code'
  ];
  var found = false;
  for (var i = 0; i < selectors.length; i++) {
    var els = document.querySelectorAll(selectors[i]);
    if (els.length > 0) {
      for (var j = 0; j < els.length; j++) {
        text += els[j].innerText + '\n';
      }
      if (text.includes('RYZIX_WRITE:')) { found = true; break; }
    }
  }
  if (!found) {
    text = document.body.innerText;
  }
  RyzixBridge.receivePageText(text);
})();
        """.trimIndent()

        webView.evaluateJavascript(js, null)
    }

    private fun parseAndApplyFiles(pageText: String) {
        val marker    = "RYZIX_WRITE:"
        val endMarker = "RYZIX_END"
        val changes   = mutableListOf<Pair<String, String>>()
        var search    = pageText

        while (search.contains(marker)) {
            val start    = search.indexOf(marker)
            val lineEnd  = search.indexOf('\n', start).takeIf { it >= 0 } ?: break
            val filename = search.substring(start + marker.length, lineEnd).trim()
            val contentStart = lineEnd + 1
            val endIdx   = search.indexOf(endMarker, contentStart)
            val content  = if (endIdx >= 0)
                search.substring(contentStart, endIdx).trim()
            else
                search.substring(contentStart).trim()

            if (filename.isNotEmpty() && content.isNotEmpty())
                changes.add(filename to content)

            search = if (endIdx >= 0) search.substring(endIdx + endMarker.length) else ""
        }

        if (changes.isEmpty()) {
            handler.post {
                setStatus("No RYZIX_WRITE blocks found yet. Ask AI to write a file, then tap APPLY.")
            }
            return
        }

        handler.post {
            val names = changes.joinToString("\n") { "  • ${it.first}" }
            AlertDialog.Builder(this)
                .setTitle("Apply ${changes.size} file change(s)?")
                .setMessage("These files will be written to your project:\n\n$names")
                .setPositiveButton("Apply All") { _, _ ->
                    executor.execute { writeChanges(changes) }
                }
                .setNegativeButton("Cancel", null)
                .show()
        }
    }

    private fun writeChanges(changes: List<Pair<String, String>>) {
        if (folderUri == null) {
            handler.post { setStatus("No project folder open.") }
            return
        }
        var written = 0; var failed = 0
        val folder = DocumentFile.fromTreeUri(this, folderUri!!)

        for ((filename, content) in changes) {
            try {
                val existing = folder?.let { findFile(it, filename) }
                val target   = existing ?: folder?.createFile("text/plain", filename)
                if (target != null) {
                    contentResolver.openOutputStream(target.uri, "wt")
                        ?.bufferedWriter()?.use { it.write(content) }
                    written++
                } else failed++
            } catch (e: Exception) { failed++ }
        }

        val msg = if (failed == 0) "Applied $written file(s) successfully!"
                  else "Applied $written, failed $failed."
        handler.post {
            setStatus(msg)
            Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
        }
    }

    private fun findFile(dir: DocumentFile, name: String): DocumentFile? {
        for (f in dir.listFiles()) {
            if (!f.isDirectory && f.name == name) return f
            if (f.isDirectory) findFile(f, name)?.let { return it }
        }
        return null
    }

    private fun buildContextString(): String {
        val sb = StringBuilder()
        sb.append("=== RYZIX CODE PROJECT ===\n\n")

        if (folderUri != null) {
            sb.append("FILE TREE:\n")
            try {
                // FIX: Tree cap aligned with AIClient (200 instead of 120)
                DocumentFile.fromTreeUri(this, folderUri!!)
                    ?.let { buildTree(it, sb, 0, intArrayOf(200)) }
            } catch (_: Exception) {}
            sb.append("\n")
        }

        if (openFileUri != null) {
            try {
                val text = contentResolver.openInputStream(openFileUri!!)
                    ?.bufferedReader()?.readText() ?: ""
                sb.append("CURRENTLY OPEN: $openFileName\n")
                sb.append("```\n$text\n```\n\n")
            } catch (_: Exception) {}
        }

        if (folderUri != null) {
            sb.append("OTHER SOURCE FILES:\n\n")
            try {
                DocumentFile.fromTreeUri(this, folderUri!!)
                    ?.let { collectFiles(it, sb, intArrayOf(9000), openFileName) }
            } catch (_: Exception) {}
        }

        return sb.toString()
    }

    private val treeExts = setOf("kt","java","xml","json","gradle","md","properties","kts","pro","txt")

    private fun buildTree(dir: DocumentFile, sb: StringBuilder, depth: Int, limit: IntArray) {
        if (limit[0] <= 0) return
        val pad = "  ".repeat(depth)
        dir.listFiles()
            .sortedWith(compareBy({ !it.isDirectory }, { it.name }))
            .forEach { f ->
                if (limit[0] <= 0) return
                sb.append("$pad${if (f.isDirectory) "[D]" else "[F]"} ${f.name}\n")
                limit[0]--
                if (f.isDirectory) buildTree(f, sb, depth + 1, limit)
            }
    }

    private fun collectFiles(
        dir: DocumentFile, sb: StringBuilder,
        budget: IntArray, skipName: String
    ) {
        if (budget[0] <= 0) return
        dir.listFiles()
            .sortedWith(compareBy({ !it.isDirectory }, { it.name }))
            .forEach { f ->
                if (budget[0] <= 0) return
                if (f.isDirectory) { collectFiles(f, sb, budget, skipName); return@forEach }
                val name = f.name ?: return@forEach
                if (name == skipName) return@forEach
                val ext = name.substringAfterLast('.', "").lowercase()
                if (ext !in treeExts) return@forEach
                try {
                    val text    = contentResolver.openInputStream(f.uri)
                        ?.bufferedReader()?.readText() ?: return@forEach
                    val snippet = text.take(minOf(budget[0], 3500))
                    sb.append("--- $name ---\n```\n$snippet\n```\n\n")
                    budget[0] -= snippet.length
                } catch (_: Exception) {}
            }
    }

    inner class JsBridge {
        @JavascriptInterface
        fun onStatus(msg: String) = handler.post { setStatus(msg) }

        @JavascriptInterface
        fun receivePageText(text: String) = executor.execute { parseAndApplyFiles(text) }

        @JavascriptInterface
        fun onError(msg: String) = handler.post { setStatus("Error: $msg") }

        @JavascriptInterface
        fun copyToClipboard(text: String) {
            handler.post {
                val cm = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
                cm.setPrimaryClip(ClipData.newPlainText("ryzix_context", text))
            }
        }
    }

    private fun setStatus(msg: String) {
        tvStatus.text = msg
    }
}
