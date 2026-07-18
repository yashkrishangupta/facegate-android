package com.facegate

import android.os.Bundle
import android.view.View
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import androidx.navigation.NavController
import androidx.navigation.fragment.NavHostFragment
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.facegate.security.AuthGate
import com.facegate.security.AuthPromptDialog
import com.facegate.sync.AttendanceSyncWorker
import com.facegate.sync.DeviceIdManager
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

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

    @Inject
    lateinit var authGate: AuthGate

    @Inject
    lateinit var deviceIdManager: DeviceIdManager

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

        // Admin Mode is gated — only an account synced down as ADMIN/
        // SUPER_ADMIN for this device can enter (see AuthGate, and the
        // Android auth task this implements). Faculty accounts are never
        // valid here; they only unlock their own period, from the "Take
        // Attendance" side (TodayScheduleFragment's Start button).
        findViewById<View>(R.id.btnAdmin).setOnClickListener {
            AuthPromptDialog.show(
                context = this,
                lifecycleScope = lifecycleScope,
                title = "Admin Mode",
                message = "Enter your admin password to continue.",
                onVerify = { password -> authGate.verifyAdminLogin(password) },
                onSuccess = { launchGraph(startDestinationId = R.id.adminDashboard) },
            )
        }

        setupSyncButton()

        // Must be reachable with zero local data: an unpaired device has
        // never synced anything (no room, no admin/faculty accounts), and
        // Admin Mode is now login-gated against synced accounts (AuthGate).
        // So pairing can't be reached *through* Admin Mode the way it used
        // to be — it has to come before the role selector even shows.
        // Pairing itself needs no password, just the 6-digit code an admin
        // generates on the website.
        //
        // Root cause of a crash this used to trigger: activity_main.xml's
        // nav_host_fragment used to declare app:navGraph statically, which
        // made NavHostFragment auto-inflate the graph and silently start
        // showing its XML-declared start destination (adminDashboard) the
        // moment the layout was created — racing against this very call,
        // which sets the graph a second time to pairingFragment. Depending
        // on timing, the auto-shown AdminDashboard's own onViewCreated
        // (which has its own isPaired() check, see AdminDashboard.kt) could
        // run *after* this had already reset the graph, and then try to
        // navigate(action_dashboard_to_pairing) from a destination that no
        // longer had that action — crash. Fixed by removing app:navGraph
        // from the XML; setGraph() is now the *only* thing that ever loads
        // this NavHostFragment's graph. savedInstanceState == null here
        // just additionally avoids redundant graph resets (and losing
        // in-progress state, e.g. a half-typed pairing code) on a plain
        // rotation/config change.
        if (savedInstanceState == null && !deviceIdManager.isPaired()) {
            launchGraph(startDestinationId = R.id.pairingFragment)
        }
    }

    /**
     * Called by PairingFragment once pairing (and its post-pair sync) has
     * finished — always returns to the role selector rather than
     * navigating deeper into the graph, so a "just paired" device can't
     * skip Admin Mode's login gate. The person still has to tap Admin Mode
     * and enter a password, same as any other time.
     */
    fun onPairingComplete() {
        showRoleSelector()
    }

    // ── Manual sync ───────────────────────────────────────────────────────────

    /**
     * "Sync Now" on the role-selector screen — fires the same
     * AttendanceSyncWorker the hourly periodic job uses (see
     * AttendanceSyncWorker.Scheduler.runOnce), just on demand. Observes the
     * WorkInfo for that one request so the button can show a spinner while
     * it runs and a real success/failure result — not just fire-and-forget.
     */
    private fun setupSyncButton() {
        val card     = findViewById<View>(R.id.btnSyncNow)
        val icon     = findViewById<ImageView>(R.id.imgSyncIcon)
        val progress = findViewById<ProgressBar>(R.id.progressSync)
        val status   = findViewById<TextView>(R.id.tvSyncStatus)

        card.setOnClickListener {
            if (!deviceIdManager.isPaired()) {
                status.text = "Device isn't paired yet"
                return@setOnClickListener
            }
            val roomId = deviceIdManager.getRoomId()
            if (roomId.isNullOrBlank()) {
                status.text = "No room assigned to this device"
                return@setOnClickListener
            }

            card.isEnabled = false
            icon.visibility = View.GONE
            progress.visibility = View.VISIBLE
            status.text = "Syncing…"

            val requestId = AttendanceSyncWorker.Scheduler.runOnce(this, roomId)
            WorkManager.getInstance(this)
                .getWorkInfoByIdLiveData(requestId)
                .observe(this) { info ->
                    if (info == null) return@observe
                    val finished = when (info.state) {
                        WorkInfo.State.SUCCEEDED -> {
                            status.text = "Synced just now"
                            true
                        }
                        WorkInfo.State.FAILED -> {
                            status.text = "Sync failed — check your connection"
                            true
                        }
                        WorkInfo.State.CANCELLED -> {
                            status.text = "Sync cancelled"
                            true
                        }
                        else -> false // ENQUEUED / RUNNING / BLOCKED — keep the spinner
                    }
                    if (finished) {
                        card.isEnabled = true
                        icon.visibility = View.VISIBLE
                        progress.visibility = View.GONE
                    }
                }
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