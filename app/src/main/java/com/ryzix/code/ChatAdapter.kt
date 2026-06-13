package com.ryzix.code

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.animation.AlphaAnimation
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

class ChatAdapter(
    private val context: Context,
    private val msgs: List<ChatMessage>
) : RecyclerView.Adapter<ChatAdapter.VH>() {

    inner class VH(val row: LinearLayout, val tv: TextView) : RecyclerView.ViewHolder(row)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val row = LinearLayout(context)
        row.layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        row.setPadding(0, 10, 0, 10)
        val tv = TextView(context)
        tv.textSize = 13f
        tv.setPadding(26, 18, 26, 18)
        row.addView(tv)
        return VH(row, tv)
    }

    override fun onBindViewHolder(h: VH, pos: Int) {
        val m = msgs[pos]
        h.tv.text = m.text
        val bg = GradientDrawable()
        bg.cornerRadius = 18f
        val p = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        if (m.isUser) {
            bg.setColor(Color.parseColor("#2A2A2E"))
            p.gravity  = Gravity.END
            p.leftMargin = 80
            h.tv.setTextColor(Color.parseColor("#EEEEF2"))
        } else {
            bg.setColor(Color.parseColor("#1A1A1D"))
            bg.setStroke(1, Color.parseColor("#333336"))
            p.gravity = Gravity.START
            p.rightMargin = 80
            h.tv.setTextColor(Color.parseColor("#B0B0BA"))
        }
        h.tv.layoutParams = p
        h.tv.background   = bg
        val anim = AlphaAnimation(0f, 1f)
        anim.duration = 250
        h.itemView.startAnimation(anim)
    }

    override fun getItemCount(): Int = msgs.size
}
