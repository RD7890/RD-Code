package com.ryzix.code

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.view.animation.AlphaAnimation
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

class ChatAdapter(
    private val context: Context,
    private val msgs: List<ChatMessage>
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    companion object {
        private const val TYPE_TEXT = 0
        private const val TYPE_DIFF = 1

        fun makeDiff(old: String, new: String): String {
            if (old.isEmpty()) return new.lines().take(120).joinToString("\n") { "+ $it" }
            val sb = StringBuilder()
            val o = old.lines(); val n = new.lines()
            for (i in 0 until minOf(maxOf(o.size, n.size), 300)) {
                val ol = o.getOrNull(i); val nl = n.getOrNull(i)
                when {
                    ol == null -> sb.append("+ $nl\n")
                    nl == null -> sb.append("- $ol\n")
                    ol != nl   -> sb.append("- $ol\n+ $nl\n")
                    else       -> sb.append("  $ol\n")
                }
            }
            val total = maxOf(o.size, n.size)
            if (total > 300) sb.append("  … (${total - 300} more lines)")
            return sb.toString().trimEnd()
        }
    }

    private fun dp(n: Int) = (n * context.resources.displayMetrics.density).toInt()

    inner class TextVH(val row: LinearLayout, val tv: TextView) : RecyclerView.ViewHolder(row)

    inner class DiffVH(
        root: View,
        val tvFilename: TextView,
        val tvDiff: TextView,
        val btnRow: LinearLayout,
        val btnAccept: TextView,
        val btnReject: TextView,
        val tvStatus: TextView
    ) : RecyclerView.ViewHolder(root)

    override fun getItemViewType(pos: Int) =
        if (msgs[pos].diffChange != null) TYPE_DIFF else TYPE_TEXT

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder =
        if (viewType == TYPE_TEXT) createTextVH() else createDiffVH()

    private fun createTextVH(): TextVH {
        val row = LinearLayout(context).apply {
            layoutParams = ViewGroup.LayoutParams(MATCH_PARENT, WRAP_CONTENT)
            setPadding(0, dp(8), 0, dp(8))
        }
        val tv = TextView(context).apply {
            textSize = 13f
            setPadding(dp(16), dp(14), dp(16), dp(14))
        }
        row.addView(tv)
        return TextVH(row, tv)
    }

    private fun createDiffVH(): DiffVH {
        val outer = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = ViewGroup.LayoutParams(MATCH_PARENT, WRAP_CONTENT)
            setPadding(dp(10), dp(6), dp(10), dp(6))
        }

        val card = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#0F0F12"))
                cornerRadius = dp(10).toFloat()
                setStroke(dp(1), Color.parseColor("#2A2A33"))
            }
        }

        val header = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(12), dp(9), dp(12), dp(9))
            setBackgroundColor(Color.parseColor("#141418"))
        }
        val tvFilename = TextView(context).apply {
            textSize = 11.5f
            typeface = Typeface.MONOSPACE
            setTextColor(Color.parseColor("#E04060"))
            layoutParams = LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f)
            maxLines = 1
        }
        val tvLabel = TextView(context).apply {
            text = "agent edit"
            textSize = 9.5f
            setTextColor(Color.parseColor("#555560"))
        }
        header.addView(tvFilename)
        header.addView(tvLabel)

        val sv = ScrollView(context).apply {
            layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, dp(170))
        }
        val tvDiff = TextView(context).apply {
            textSize = 10.5f
            typeface = Typeface.MONOSPACE
            setTextColor(Color.parseColor("#A0A0B0"))
            setBackgroundColor(Color.parseColor("#0A0A0E"))
            setPadding(dp(10), dp(8), dp(10), dp(8))
            layoutParams = ViewGroup.LayoutParams(MATCH_PARENT, WRAP_CONTENT)
        }
        sv.addView(tvDiff)

        val btnRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(10), dp(8), dp(10), dp(8))
            gravity = Gravity.END
        }
        fun btn(label: String, bgHex: String, borderHex: String, fgHex: String): TextView =
            TextView(context).apply {
                text = label; textSize = 12f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(Color.parseColor(fgHex))
                setPadding(dp(18), dp(8), dp(18), dp(8))
                gravity = Gravity.CENTER
                background = GradientDrawable().apply {
                    setColor(Color.parseColor(bgHex))
                    cornerRadius = dp(7).toFloat()
                    setStroke(dp(1), Color.parseColor(borderHex))
                }
            }
        val btnReject = btn("✕  Reject", "#1E0808", "#3A1212", "#D04040")
        val btnAccept = btn("✓  Accept", "#081E08", "#124012", "#40C040").apply {
            layoutParams = LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT)
                .also { it.leftMargin = dp(8) }
        }
        btnRow.addView(btnReject)
        btnRow.addView(btnAccept)

        val tvStatus = TextView(context).apply {
            textSize = 11f
            typeface = Typeface.MONOSPACE
            setPadding(dp(12), dp(10), dp(12), dp(10))
            visibility = View.GONE
        }

        card.addView(header)
        card.addView(sv)
        card.addView(btnRow)
        card.addView(tvStatus)
        outer.addView(card)
        return DiffVH(outer, tvFilename, tvDiff, btnRow, btnAccept, btnReject, tvStatus)
    }

    override fun onBindViewHolder(h: RecyclerView.ViewHolder, pos: Int) {
        val m = msgs[pos]
        if (h is TextVH) bindText(h, m) else if (h is DiffVH) bindDiff(h, m, pos)
    }

    private fun bindText(h: TextVH, m: ChatMessage) {
        h.tv.text = m.text
        val bg = GradientDrawable()
        bg.cornerRadius = dp(14).toFloat()
        val p = LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT)
        if (m.isUser) {
            bg.setColor(Color.parseColor("#2A2A2E"))
            p.gravity = Gravity.END; p.leftMargin = dp(60)
            h.tv.setTextColor(Color.parseColor("#EEEEF2"))
        } else {
            bg.setColor(Color.parseColor("#1A1A1D"))
            bg.setStroke(dp(1), Color.parseColor("#333336"))
            p.gravity = Gravity.START; p.rightMargin = dp(60)
            h.tv.setTextColor(Color.parseColor("#B0B0BA"))
        }
        h.tv.layoutParams = p; h.tv.background = bg
        AlphaAnimation(0f, 1f).also { it.duration = 200; h.itemView.startAnimation(it) }
    }

    private fun bindDiff(h: DiffVH, m: ChatMessage, pos: Int) {
        val change = m.diffChange ?: return
        h.tvFilename.text = "◈  ${change.filename}"
        h.tvDiff.text = makeDiff(change.oldContent, change.newContent)

        // Color +/- lines inline via SpannableString
        colorDiff(h.tvDiff)

        when (m.diffState) {
            ChatMessage.DiffState.PENDING -> {
                h.btnRow.visibility = View.VISIBLE
                h.tvStatus.visibility = View.GONE
                h.btnAccept.setOnClickListener {
                    if (m.diffState != ChatMessage.DiffState.PENDING) return@setOnClickListener
                    m.diffState = ChatMessage.DiffState.ACCEPTED
                    notifyItemChanged(pos)
                    m.diffCallback?.invoke(true)
                    m.diffCallback = null
                }
                h.btnReject.setOnClickListener {
                    if (m.diffState != ChatMessage.DiffState.PENDING) return@setOnClickListener
                    m.diffState = ChatMessage.DiffState.REJECTED
                    notifyItemChanged(pos)
                    m.diffCallback?.invoke(false)
                    m.diffCallback = null
                }
            }
            ChatMessage.DiffState.ACCEPTED -> {
                h.btnRow.visibility = View.GONE
                h.tvStatus.visibility = View.VISIBLE
                h.tvStatus.text = "✓  Applied: ${change.filename}"
                h.tvStatus.setTextColor(Color.parseColor("#40B040"))
            }
            ChatMessage.DiffState.REJECTED -> {
                h.btnRow.visibility = View.GONE
                h.tvStatus.visibility = View.VISIBLE
                h.tvStatus.text = "✗  Rejected: ${change.filename}"
                h.tvStatus.setTextColor(Color.parseColor("#606068"))
            }
            else -> {}
        }
        AlphaAnimation(0f, 1f).also { it.duration = 200; h.itemView.startAnimation(it) }
    }

    private fun colorDiff(tv: TextView) {
        val raw = tv.text.toString()
        val span = android.text.SpannableStringBuilder(raw)
        var i = 0
        for (line in raw.lines()) {
            val end = i + line.length
            val color = when {
                line.startsWith("+ ") -> Color.parseColor("#3A7A3A")
                line.startsWith("- ") -> Color.parseColor("#7A3A3A")
                else                  -> Color.TRANSPARENT
            }
            if (color != Color.TRANSPARENT) {
                span.setSpan(
                    android.text.style.BackgroundColorSpan(color),
                    i, minOf(end, raw.length),
                    android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                )
                val fgColor = if (line.startsWith("+ ")) Color.parseColor("#80D080")
                              else Color.parseColor("#D08080")
                span.setSpan(
                    android.text.style.ForegroundColorSpan(fgColor),
                    i, minOf(end, raw.length),
                    android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                )
            }
            i = end + 1
        }
        tv.text = span
    }

    override fun getItemCount() = msgs.size
}
