package com.ctslab.kidmentor

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch

/**
 * "Hồ sơ các bé" — READ-ONLY list of the parent's children plus picking the ACTIVE child.
 * The parent adds/edits/deletes children on the Dashboard, not in the app: there is no
 * "thêm bé", edit, or delete action here. Tapping a row only SELECTS that child (it does
 * not modify any data); the active child's username is sent as device_id in the WS
 * handshake so the AI tutor + chat history follow that child ([ActiveChild]).
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
    }

    override fun onResume() {
        super.onResume()
        loadChildren() // refresh in case the Dashboard data changed
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
        row.findViewById<TextView>(R.id.tvChildActiveBadge).visibility = View.GONE

        // Tap the detail icon → open the READ-ONLY child detail.
        row.findViewById<ImageView>(R.id.btnViewChild).setOnClickListener {
            child.id?.let {
                startActivity(Intent(this, ChildInfoActivity::class.java).putExtra(ChildInfoActivity.EXTRA_CHILD_ID, it))
            }
        }
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
}
