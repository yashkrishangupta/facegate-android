package com.facegate

import android.os.Bundle
import android.widget.Button
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.facegate.ui.admin.AdminDashboard
import com.facegate.ui.attendance.AttendanceFragment

@AndroidEntryPoint
class MainActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_main)

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(
                systemBars.left,
                systemBars.top,
                systemBars.right,
                systemBars.bottom
            )
            insets
        }

        val btnStudent = findViewById<Button>(R.id.btnStudent)
        val btnAdmin = findViewById<Button>(R.id.btnAdmin)

        btnStudent.setOnClickListener {
            supportFragmentManager.beginTransaction()
                .replace(android.R.id.content, com.facegate.ui.attendance.AttendanceFragment())
                .addToBackStack(null)
                .commit()
        }

        btnAdmin.setOnClickListener {
            supportFragmentManager.beginTransaction()
                .replace(android.R.id.content, com.facegate.ui.admin.AdminDashboard())
                .addToBackStack(null)
                .commit()
        }
    }
}
