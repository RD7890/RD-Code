package com.ryzix.code

import android.content.Context
import android.content.SharedPreferences

class PrefsHelper(context: Context) {
    private val sp = context.getSharedPreferences("ryzix_prefs", Context.MODE_PRIVATE)

    fun getProjects(): List<Project> {
        val raw = sp.getString("projects", "") ?: return emptyList()
        if (raw.isEmpty()) return emptyList()
        return raw.split("||").mapNotNull {
            val p = it.split("|")
            if (p.size == 3) try { Project(p[0], p[1], p[2].toLong()) } catch (e: Exception) { null } else null
        }
    }

    fun saveProject(p: Project) {
        val list = getProjects().toMutableList()
        list.removeAll { it.uriString == p.uriString }
        list.add(0, p)
        sp.edit().putString("projects", list.take(20).joinToString("||") {
            "${it.name.replace("|","")}|${it.uriString}|${it.lastOpened}"
        }).apply()
    }
}
