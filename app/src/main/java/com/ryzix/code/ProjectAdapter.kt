package com.ryzix.code

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

class ProjectAdapter(
    private val list: List<Project>,
    private val onClick: (Project) -> Unit
) : RecyclerView.Adapter<ProjectAdapter.VH>() {

    inner class VH(view: View) : RecyclerView.ViewHolder(view) {
        val name: TextView = view.findViewById(R.id.tvName)
        val path: TextView = view.findViewById(R.id.tvPath)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        VH(LayoutInflater.from(parent.context).inflate(R.layout.item_project, parent, false))

    override fun onBindViewHolder(h: VH, pos: Int) {
        val p = list[pos]
        h.name.text = p.name
        h.path.text = p.uriString
        h.itemView.setOnClickListener { onClick(p) }
    }

    override fun getItemCount() = list.size
}
