package com.facegate

import android.os.Bundle
import android.view.View
import androidx.activity.OnBackPressedCallback
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.navigation.NavController
import androidx.navigation.fragment.NavHostFragment
import dagger.hilt.android.AndroidEntryPoint

/**
 * MAIN ACTIVITY
 *
 * Layout has two layers:
 *   - roleSelector   : "Take Attendance" / "Admin Mode" buttons (visible on launch)
 *   - navHostFragment: a single FragmentContainerView (hidden until a mode is chosen)
 *
 * IMPORTANT: there is only ONE nav graph (nav_graph.xml). Both modes load it;
 * they differ only in which destination it starts on:
 *   - Take Attendance -> todayScheduleFragment (teacher picks today's session,
 *     then proceeds to attendanceFragment via action_schedule_to_attendance,
 *     which always supplies real sessionId/subject/batch/windowMinutes args)
 *   - Admin Mode       -> adminDashboard (timetable setup, changes log, reports, etc.)
 *
 * There used to be a second graph (student_nav_graph.xml) that opened
 * attendanceFragment directly with NO arguments at all, which crashed
 * navArgs(). That graph has been removed for good — AttendanceFragment must
 * always be reached through TodayScheduleFragment's nav action.
 */
@AndroidEntryPoint
class MainActivity : AppCompatActivity() {

    private var navController: NavController? = null

    // Back-press callback — active only while a nav graph is loaded
    private val navBackCallback = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() {
            val nav = navController ?: return
            if (!nav.popBackStack()) {
                // Nothing left to pop — go back to the role selector
                showRoleSelector()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_main)

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        onBackPressedDispatcher.addCallback(this, navBackCallback)

        findViewById<View>(R.id.btnAttendance).setOnClickListener {
            launchGraph(startDestinationId = R.id.todayScheduleFragment)
        }

        findViewById<View>(R.id.btnAdmin).setOnClickListener {
            launchGraph(startDestinationId = R.id.adminDashboard)
        }
    }

    // ── Graph loading ─────────────────────────────────────────────────────────

    /**
     * Loads nav_graph.xml and overrides its start destination before attaching
     * it to the NavController, so the same graph can serve both the teacher's
     * "jump straight to today's schedule" flow and the full admin flow.
     */
    private fun launchGraph(startDestinationId: Int) {
        val navHostFragment =
            supportFragmentManager.findFragmentById(R.id.nav_host_fragment) as NavHostFragment

        val inflater = navHostFragment.navController.navInflater
        val graph    = inflater.inflate(R.navigation.nav_graph)
        graph.setStartDestination(startDestinationId)
        navHostFragment.navController.setGraph(graph, null)

        navController = navHostFragment.navController

        // Show the nav host, hide the role selector
        findViewById<View>(R.id.roleSelector).animate()
            .alpha(0f)
            .setDuration(180)
            .withEndAction {
                findViewById<View>(R.id.roleSelector).visibility = View.GONE
            }.start()

        findViewById<View>(R.id.nav_host_fragment).apply {
            alpha     = 0f
            visibility = View.VISIBLE
            animate().alpha(1f).setDuration(220).start()
        }

        navBackCallback.isEnabled = true
    }

    // ── Return to selector ────────────────────────────────────────────────────

    private fun showRoleSelector() {
        navController = null
        navBackCallback.isEnabled = false

        // Clear the nav graph so the old back-stack doesn't linger
        val navHostFragment =
            supportFragmentManager.findFragmentById(R.id.nav_host_fragment) as? NavHostFragment
        navHostFragment?.navController?.let { nav ->
            // Pop everything off the back stack
            nav.popBackStack(nav.graph.startDestinationId, true)
        }

        findViewById<View>(R.id.nav_host_fragment).animate()
            .alpha(0f)
            .setDuration(150)
            .withEndAction {
                findViewById<View>(R.id.nav_host_fragment).visibility = View.GONE
            }.start()

        findViewById<View>(R.id.roleSelector).apply {
            alpha     = 0f
            visibility = View.VISIBLE
            animate().alpha(1f).setDuration(200).start()
        }
    }
}