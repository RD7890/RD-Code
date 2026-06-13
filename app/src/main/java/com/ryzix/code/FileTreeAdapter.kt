package com.ryzix.code

import android.app.AlertDialog
import android.app.Dialog
import android.content.Context
import android.graphics.Color
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.WindowManager
import android.widget.*
import androidx.documentfile.provider.DocumentFile
import androidx.recyclerview.widget.RecyclerView
import java.util.concurrent.Executors

class FileTreeAdapter(
    private val context: Context,
    rootUri: Uri,
    private val onFile: (DocumentFile) -> Unit
) : RecyclerView.Adapter<FileTreeAdapter.VH>() {

    private data class Node(
        val name:     String,
        val doc:      DocumentFile,
        val isDir:    Boolean,
        var expanded: Boolean           = false,
        val depth:    Int               = 0,
        val children: MutableList<Node> = mutableListOf(),
        var loading:  Boolean           = false
    )

    private val nodes    = mutableListOf<Node>()
    private val rootDoc  = DocumentFile.fromTreeUri(context, rootUri)
    private val executor = Executors.newSingleThreadExecutor()
    private val handler  = Handler(Looper.getMainLooper())
    private val Int.dp   get() = (this * context.resources.displayMetrics.density).toInt()

    init {
        // FIX: Load root children asynchronously to avoid blocking the main thread.
        rootDoc?.let { r ->
            val rn = Node(r.name ?: "PROJECT", r, true, expanded = false, depth = 0)
            nodes.add(rn)
            notifyItemInserted(0)
            loadAsync(rn, insertAt = 1, onDone = {
                rn.expanded = true
                notifyItemChanged(0)
            })
        }
    }

    // ── ViewHolder ────────────────────────────────────────────────────────

    class VH(
        view:        View,
        val indent:  View,
        val arrow:   ImageView,
        val icon:    ImageView,
        val label:   TextView,
        val spinner: ProgressBar
    ) : RecyclerView.ViewHolder(view)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val row = LinearLayout(context).apply {
            layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 46.dp)
            orientation = LinearLayout.HORIZONTAL
            gravity     = Gravity.CENTER_VERTICAL
            setPadding(0, 0, 12.dp, 0)
            isClickable = true; isFocusable = true
            background  = android.util.TypedValue().let { tv ->
                context.theme.resolveAttribute(android.R.attr.selectableItemBackground, tv, true)
                if (tv.resourceId != 0) context.getDrawable(tv.resourceId) else null
            }
        }
        val indent  = View(context).also { row.addView(it) }
        val arrow   = ImageView(context).apply {
            layoutParams = LinearLayout.LayoutParams(18.dp, 18.dp)
            scaleType    = ImageView.ScaleType.CENTER_INSIDE
        }.also { row.addView(it) }
        val icon    = ImageView(context).apply {
            layoutParams = LinearLayout.LayoutParams(22.dp, 22.dp).also { it.leftMargin = 4.dp }
            scaleType    = ImageView.ScaleType.FIT_CENTER
        }.also { row.addView(it) }
        val label   = TextView(context).apply {
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                .also { it.leftMargin = 7.dp }
            textSize = 13f; maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
        }.also { row.addView(it) }
        val spinner = ProgressBar(context, null, android.R.attr.progressBarStyleSmall).apply {
            layoutParams = LinearLayout.LayoutParams(16.dp, 16.dp).also { it.rightMargin = 4.dp }
            visibility   = View.GONE
            indeterminateTintList = android.content.res.ColorStateList.valueOf(
                Color.parseColor("#E52A3F")
            )
        }.also { row.addView(it) }
        return VH(row, indent, arrow, icon, label, spinner)
    }

    override fun onBindViewHolder(h: VH, pos: Int) {
        val n = nodes[pos]
        (h.indent.layoutParams as LinearLayout.LayoutParams).width = n.depth.coerceAtMost(8) * 12.dp
        h.indent.requestLayout()
        h.label.text = n.name
        h.label.setTextColor(if (n.isDir) Color.parseColor("#D4D4DC") else Color.parseColor("#A0A0AA"))
        h.spinner.visibility = if (n.loading) View.VISIBLE else View.GONE

        if (n.isDir) {
            h.arrow.visibility = View.VISIBLE
            h.arrow.setImageResource(if (n.expanded) R.drawable.ic_chevron_down else R.drawable.ic_chevron_right)
            h.icon.setImageResource(if (n.expanded) R.drawable.ic_folder_open else R.drawable.ic_folder_closed)
        } else {
            h.arrow.visibility = View.INVISIBLE
            h.icon.setImageResource(fileIconRes(n.name))
        }

        h.itemView.setOnClickListener {
            val p = h.adapterPosition
            if (p < 0 || p >= nodes.size) return@setOnClickListener
            val node = nodes[p]
            if (node.isDir) {
                if (node.expanded) collapseNode(p, node) else expandNode(p, node)
            } else {
                onFile(node.doc)
            }
        }
        h.itemView.setOnLongClickListener {
            val p = h.adapterPosition
            if (p >= 0 && p < nodes.size) showMenu(nodes[p])
            true
        }
    }

    override fun getItemCount() = nodes.size

    // ── Async expand ──────────────────────────────────────────────────────

    private fun expandNode(pos: Int, n: Node) {
        if (n.loading || n.expanded) return
        n.loading = true; notifyItemChanged(pos)
        loadAsync(n, pos + 1, onDone = {
            n.loading = false; n.expanded = true
            notifyItemChanged(pos)
        })
    }

    // FIX: All SAF I/O (listFiles) is done on the executor thread, never main.
    private fun loadAsync(n: Node, insertAt: Int, onDone: () -> Unit) {
        executor.execute {
            val children = loadChildrenSync(n)
            handler.post {
                if (children.isNotEmpty()) {
                    nodes.addAll(insertAt, children)
                    notifyItemRangeInserted(insertAt, children.size)
                }
                onDone()
            }
        }
    }

    private fun loadChildrenSync(n: Node): List<Node> {
        n.children.clear()
        return try {
            val list = n.doc.listFiles()
                .sortedWith(compareBy({ !it.isDirectory }, { it.name?.lowercase() }))
                .map { child ->
                    Node(
                        name  = child.name ?: "?",
                        doc   = child,
                        isDir = child.isDirectory,
                        depth = n.depth + 1
                    )
                }
            n.children.addAll(list); list
        } catch (_: Exception) { emptyList() }
    }

    // ── Collapse ──────────────────────────────────────────────────────────

    private fun collapseNode(pos: Int, n: Node) {
        n.expanded = false
        val removed = removeDescendants(n)
        notifyItemChanged(pos)
        if (removed > 0) notifyItemRangeRemoved(pos + 1, removed)
    }

    private fun removeDescendants(n: Node): Int {
        var count = 0
        for (ch in n.children.toList()) {
            if (ch.isDir && ch.expanded) count += removeDescendants(ch)
            val i = nodes.indexOf(ch)
            if (i >= 0) { nodes.removeAt(i); count++ }
        }
        n.children.clear(); return count
    }

    // ── Refresh (async) ───────────────────────────────────────────────────

    // FIX: Refresh is now async — SAF I/O happens off the main thread.
    fun refresh() {
        val oldSize = nodes.size
        nodes.clear()
        if (oldSize > 0) notifyItemRangeRemoved(0, oldSize)
        rootDoc?.let { r ->
            val rn = Node(r.name ?: "PROJECT", r, true, expanded = false, depth = 0)
            nodes.add(rn)
            notifyItemInserted(0)
            loadAsync(rn, insertAt = 1, onDone = {
                rn.expanded = true
                notifyItemChanged(0)
            })
        }
    }

    // ── File icons ────────────────────────────────────────────────────────

    private fun fileIconRes(name: String) = when (name.substringAfterLast('.', "").lowercase()) {
        "kt", "kts"        -> R.drawable.ic_file_kt
        "java"             -> R.drawable.ic_file_java
        "xml"              -> R.drawable.ic_file_xml
        "json"             -> R.drawable.ic_file_json
        "gradle"           -> R.drawable.ic_file_gradle
        "md"               -> R.drawable.ic_file_md
        "txt"              -> R.drawable.ic_file_txt
        "html", "htm"      -> R.drawable.ic_file_html
        "css"              -> R.drawable.ic_file_css
        "js", "mjs", "jsx" -> R.drawable.ic_file_js
        "ts", "tsx"        -> R.drawable.ic_file_ts
        "py"               -> R.drawable.ic_file_py
        else               -> R.drawable.ic_file_default
    }

    // ── Context menu ─────────────────────────────────────────────────────

    private fun showMenu(n: Node) {
        val d = Dialog(context); d.requestWindowFeature(Window.FEATURE_NO_TITLE)
        val lay = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#1E1E21"))
            setPadding(32.dp, 24.dp, 32.dp, 24.dp)
        }
        lay.addView(TextView(context).apply {
            text = n.name; setTextColor(Color.parseColor("#606068"))
            textSize = 11f; setPadding(0, 0, 0, 12.dp)
        })
        val doc = n.doc
        if (n.isDir) {
            menuItem(lay, "New File",   "#EEEEF2", d) { prompt("File name", "Untitled.kt") { nm -> doc.createFile("text/plain", nm); refresh() } }
            menuItem(lay, "New Folder", "#EEEEF2", d) { prompt("Folder name", "NewFolder") { nm -> doc.createDirectory(nm); refresh() } }
        }
        menuItem(lay, "Rename", "#EEEEF2", d) { prompt("Rename", n.name) { nm -> doc.renameTo(nm); refresh() } }
        menuItem(lay, "Delete", "#E52A3F", d) {
            AlertDialog.Builder(context)
                .setTitle("Delete ${n.name}?").setMessage("This cannot be undone.")
                .setPositiveButton("Delete") { _, _ -> doc.delete(); refresh() }
                .setNegativeButton("Cancel", null).show()
        }
        d.setContentView(lay)
        d.window?.apply {
            setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.WRAP_CONTENT)
            setGravity(Gravity.BOTTOM)
            setBackgroundDrawable(android.graphics.drawable.ColorDrawable(Color.TRANSPARENT))
        }
        d.show()
    }

    private fun menuItem(lay: LinearLayout, text: String, color: String, d: Dialog, action: () -> Unit) {
        lay.addView(TextView(context).apply {
            this.text = text; setTextColor(Color.parseColor(color))
            textSize = 15f; setPadding(0, 18.dp, 0, 18.dp)
            setOnClickListener { d.dismiss(); action() }
        })
    }

    private fun prompt(title: String, default: String, onDone: (String) -> Unit) {
        val et = EditText(context).apply { setText(default); selectAll() }
        AlertDialog.Builder(context).setTitle(title).setView(et)
            .setPositiveButton("OK") { _, _ -> val v = et.text.toString().trim(); if (v.isNotEmpty()) onDone(v) }
            .setNegativeButton("Cancel", null).show()
    }
}
