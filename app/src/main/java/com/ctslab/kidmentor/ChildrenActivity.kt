package com.ctslab.kidmentor

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch

/**
 * "Hồ sơ các bé" — manage the parent's children: list, add, edit, delete, and pick the
 * ACTIVE child. The active child's username is sent as device_id in the WS handshake so
 * the AI tutor + chat history follow that child ([ActiveChild]).
 */
class ChildrenActivity : AppCompatActivity() {

    companion object {
        private val CURRICULUM_CODES = listOf("chan_troi_sang_tao", "canh_dieu", "ket_noi_tri_thuc")
    }

    private lateinit var container: LinearLayout
    private lateinit var empty: TextView
    private lateinit var progress: ProgressBar

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_children)

        TokenManager.init(this)
        ActiveChild.init(this)
        if (!TokenManager.isLoggedIn()) {
            Toast.makeText(this, getString(R.string.student_login_required), Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        container = findViewById(R.id.childrenContainer)
        empty = findViewById(R.id.tvChildrenEmpty)
        progress = findViewById(R.id.progressChildren)

        findViewById<ImageView>(R.id.btnBack).setOnClickListener { finish() }
        findViewById<Button>(R.id.btnAddChild).setOnClickListener {
            startActivity(Intent(this, ChildInfoActivity::class.java))
        }
    }

    override fun onResume() {
        super.onResume()
        loadChildren() // refresh after returning from add/edit
    }

    private fun loadChildren() {
        progress.visibility = View.VISIBLE
        lifecycleScope.launch {
            val children = ChildrenApiService.list()
            progress.visibility = View.GONE
            container.removeAllViews()
            if (children == null) {
                empty.text = getString(R.string.student_load_error)
                empty.visibility = View.VISIBLE
                return@launch
            }
            empty.visibility = if (children.isEmpty()) View.VISIBLE else View.GONE
            empty.text = getString(R.string.children_empty)
            children.forEach { addRow(it) }
        }
    }

    private fun addRow(child: ChildProfile) {
        val row = LayoutInflater.from(this).inflate(R.layout.item_child, container, false)
        row.findViewById<TextView>(R.id.tvChildName).text = child.fullName ?: getString(R.string.children_unnamed)
        row.findViewById<TextView>(R.id.tvChildMeta).text = metaFor(child)

        val isActive = child.id != null && child.id == ActiveChild.getId()
        row.findViewById<TextView>(R.id.tvChildActiveBadge).visibility = if (isActive) View.VISIBLE else View.GONE

        // Tap row → make this child the active one.
        row.setOnClickListener {
            if (child.id != null && child.username != null) {
                ActiveChild.set(child.id, child.username, child.fullName)
                loadChildren()
                Toast.makeText(this, getString(R.string.children_now_active, child.fullName ?: ""), Toast.LENGTH_SHORT).show()
            }
        }
        row.findViewById<ImageView>(R.id.btnEditChild).setOnClickListener {
            child.id?.let {
                startActivity(Intent(this, ChildInfoActivity::class.java).putExtra(ChildInfoActivity.EXTRA_CHILD_ID, it))
            }
        }
        row.findViewById<ImageView>(R.id.btnDeleteChild).setOnClickListener { confirmDelete(child) }
        container.addView(row)
    }

    private fun metaFor(child: ChildProfile): String {
        val parts = mutableListOf<String>()
        child.grade?.takeIf { it.isNotBlank() }?.let { parts.add(getString(R.string.student_grade_fmt, it)) }
        child.curriculum?.let { code ->
            val idx = CURRICULUM_CODES.indexOf(code)
            if (idx >= 0) parts.add(resources.getStringArray(R.array.student_curriculum_options)[idx])
        }
        return if (parts.isEmpty()) getString(R.string.children_no_class) else parts.joinToString(" · ")
    }

    private fun confirmDelete(child: ChildProfile) {
        val id = child.id ?: return
        AlertDialog.Builder(this)
            .setTitle(R.string.children_delete_title)
            .setMessage(getString(R.string.children_delete_message, child.fullName ?: ""))
            .setPositiveButton(R.string.children_delete) { _, _ ->
                lifecycleScope.launch {
                    val ok = ChildrenApiService.delete(id)
                    if (ok) {
                        if (ActiveChild.getId() == id) ActiveChild.clear()
                        loadChildren()
                    } else {
                        Toast.makeText(this@ChildrenActivity, getString(R.string.student_save_error), Toast.LENGTH_SHORT).show()
                    }
                }
            }
            .setNegativeButton(R.string.profile_close, null)
            .show()
    }
}
