package com.example.autoclicker

import android.os.Bundle
import android.view.Gravity
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

class ChainTargetsActivity : AppCompatActivity() {

    private lateinit var container: LinearLayout
    private lateinit var countText: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_chain_targets)

        container = findViewById(R.id.targetsListContainer)
        countText = findViewById(R.id.chainCountText)

        findViewById<Button>(R.id.backBtn).setOnClickListener {
            finish()
        }

        refreshList()
    }

    override fun onResume() {
        super.onResume()
        refreshList()
    }

    private fun refreshList() {
        container.removeAllViews()
        val targets = TargetStorage.listTargets(this)
        countText.text = "عدد الأهداف: ${targets.size}"

        if (targets.isEmpty()) {
            val emptyText = TextView(this).apply {
                text = "ما فيه أهداف محفوظة بعد"
                setPadding(0, 40, 0, 0)
            }
            container.addView(emptyText)
            return
        }

        targets.forEachIndexed { index, file ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(0, 12, 0, 12)
                gravity = Gravity.CENTER_VERTICAL
            }

            val thumb = ImageView(this).apply {
                layoutParams = LinearLayout.LayoutParams(150, 150)
                val bmp = TargetStorage.loadBitmap(file)
                if (bmp != null) setImageBitmap(bmp)
                setBackgroundColor(0xFF333333.toInt())
            }

            val label = TextView(this).apply {
                text = "  خطوة ${index + 1}"
                textSize = 16f
                layoutParams = LinearLayout.LayoutParams(
                    0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f
                )
            }

            val deleteBtn = Button(this).apply {
                text = "حذف"
                setOnClickListener {
                    TargetStorage.deleteTarget(file)
                    refreshList()
                }
            }

            row.addView(thumb)
            row.addView(label)
            row.addView(deleteBtn)
            container.addView(row)
        }
    }
}
