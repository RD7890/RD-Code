package com.ryzix.code

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.TextView
import android.widget.Toast
import android.view.View
import android.app.Activity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView

// FIX: Removed the unnecessary MANAGE_EXTERNAL_STORAGE gate before the SAF picker.
// ACTION_OPEN_DOCUMENT_TREE (SAF) works independently of that permission.
class MainActivity : Activity() {

    private lateinit var prefs:   PrefsHelper
    private lateinit var adapter: ProjectAdapter
    private val projects = mutableListOf<Project>()

    private val PICK_FOLDER_RC = 42

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        prefs = PrefsHelper(this)

        adapter = ProjectAdapter(projects) { p ->
            val uri = Uri.parse(p.uriString)
            try {
                contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                )
                openProject(uri)
            } catch (e: Exception) {
                Toast.makeText(this, "Cannot access folder — please open it again.", Toast.LENGTH_LONG).show()
            }
        }

        val recycler = findViewById<RecyclerView>(R.id.projectsRecycler)
        recycler.layoutManager = LinearLayoutManager(this)
        recycler.adapter = adapter

        // SAF folder picker — no storage permission needed for this
        findViewById<View>(R.id.btnOpenFolder).setOnClickListener {
            startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT_TREE), PICK_FOLDER_RC)
        }
        findViewById<View>(R.id.btnManager).setOnClickListener {
            Toast.makeText(this, "Coming soon", Toast.LENGTH_SHORT).show()
        }
        findViewById<View>(R.id.btnClone).setOnClickListener {
            Toast.makeText(this, "Coming soon", Toast.LENGTH_SHORT).show()
        }

        if (android.os.Build.VERSION.SDK_INT >= 33) {
            requestPermissions(arrayOf("android.permission.POST_NOTIFICATIONS"), 99)
        }

        val root = findViewById<View>(R.id.mainRoot)
        root.alpha = 0f
        root.animate().alpha(1f).setDuration(500).start()
    }

    override fun onResume() {
        super.onResume()
        projects.clear()
        projects.addAll(prefs.getProjects())
        adapter.notifyDataSetChanged()
        val empty = findViewById<TextView>(R.id.emptyStateText)
        empty.visibility = if (projects.isEmpty()) View.VISIBLE else View.GONE
    }

    private fun handleFolder(uri: Uri) {
        try {
            contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
        } catch (e: Exception) { e.printStackTrace() }
        val seg  = uri.lastPathSegment ?: ""
        val name = seg.substringAfterLast('/').substringAfterLast(':')
        val finalName = if (name.isEmpty()) "Project" else name
        prefs.saveProject(Project(finalName, uri.toString(), System.currentTimeMillis()))
        openProject(uri)
    }

    private fun openProject(uri: Uri) {
        startActivity(Intent(this, EditorActivity::class.java).apply {
            putExtra("FOLDER_URI", uri.toString())
        })
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: android.content.Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == PICK_FOLDER_RC && resultCode == RESULT_OK) {
            data?.data?.let { handleFolder(it) }
        }
    }
}
