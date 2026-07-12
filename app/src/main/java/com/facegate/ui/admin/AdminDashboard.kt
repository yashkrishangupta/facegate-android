package com.facegate.ui.admin

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.fragment.findNavController
import android.widget.Toast
import com.facegate.R
import com.facegate.databinding.FragmentAdminDashboardBinding
import com.facegate.sync.AttendanceSyncWorker
import com.facegate.sync.DeviceIdManager
import com.facegate.sync.SyncRepository
import com.facegate.storage.TemplateRepository
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject

@AndroidEntryPoint
class AdminDashboard : Fragment() {

    private var _binding: FragmentAdminDashboardBinding? = null
    private val binding get() = _binding!!

    private val viewModel: AdminDashboardViewModel by viewModels()

    @Inject
    lateinit var deviceIdManager: DeviceIdManager

    @Inject
    lateinit var syncRepository: SyncRepository

    @Inject
    lateinit var templateRepository: TemplateRepository

    private val clockHandler = Handler(Looper.getMainLooper())
    private val clockRunnable = object : Runnable {
        override fun run() {
            if (_binding != null) updateClock()
            clockHandler.postDelayed(this, 1000)
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        _binding = FragmentAdminDashboardBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // Block everything until the device is paired with the backend — no
        // stats, no student data, no sync can happen without a valid deviceId
        // + Device Token. popUpTo/popUpToInclusive on this action (nav_graph.xml)
        // removes this dashboard from the back stack so Back can't bypass pairing.
        if (!deviceIdManager.isPaired()) {
            findNavController().navigate(R.id.action_dashboard_to_pairing)
            return
        }

        setupClickListeners()
        updateDate()
        clockHandler.post(clockRunnable)
        refreshSyncStatus()

        // ── Lifecycle-aware collection ────────────────────────────────────────
        // repeatOnLifecycle cancels the inner block when the view goes to STOPPED
        // (back-stack, screen-off) and restarts it on STARTED — so we never
        // accumulate stale collectors across navigations, and we always get a
        // fresh emission on resume.
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.stats.collect { stats -> renderStats(stats) }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // Force a data reload every time the screen becomes visible so deletions,
        // new sessions, resolved conflicts, etc. are reflected immediately.
        viewModel.loadStats()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        clockHandler.removeCallbacks(clockRunnable)
        _binding = null
    }

    // ── Clock & Date ──────────────────────────────────────────────────────────

    private fun updateClock() {
        binding.tvClock.text = SimpleDateFormat("hh:mm a", Locale.getDefault()).format(Date())
    }

    private fun updateDate() {
        binding.tvDate.text =
            SimpleDateFormat("EEEE, d MMMM yyyy", Locale.getDefault()).format(Date())
    }

    // ── Stats rendering ───────────────────────────────────────────────────────

    private fun renderStats(stats: DashboardStats) {

        // ── Card 1: Total Students ─────────────────────────────────────────
        binding.tvTotalStudents.text = stats.totalStudents.toString()
        binding.progressTotalStudents.progress = if (stats.totalStudents > 0) 100 else 0
        binding.tvTileStudentsSub.text = when (stats.totalStudents) {
            0    -> "No students yet"
            1    -> "1 enrolled"
            else -> "${stats.totalStudents} enrolled"
        }

        // ── Card 2: Periods Conducted ──────────────────────────────────────
        binding.tvPresentToday.text = stats.periodsConducted.toString()
        binding.progressPresent.progress =
            if (stats.totalPeriodsToday > 0)
                ((stats.periodsConducted.toFloat() / stats.totalPeriodsToday) * 100).toInt()
            else if (stats.periodsConducted > 0) 100 else 0
        binding.tvPresentSubtitle.text =
            if (stats.totalPeriodsToday > 0)
                "of ${stats.totalPeriodsToday} scheduled today"
            else
                "No timetable set for today"

        // ── Card 3: Periods Remaining ──────────────────────────────────────
        binding.tvAbsentToday.text = stats.periodsRemaining.toString()
        binding.progressAbsent.progress =
            if (stats.totalPeriodsToday > 0)
                ((stats.periodsRemaining.toFloat() / stats.totalPeriodsToday) * 100).toInt()
            else 0
        binding.tvAbsentSubtitle.text = when {
            stats.totalPeriodsToday == 0  -> "No timetable for today"
            stats.periodsRemaining == 0   -> "All periods done ✓"
            else                           -> "${stats.attendancePctToday}% avg attendance"
        }

        // ── Card 4: Pending Conflicts ──────────────────────────────────────
        binding.tvHolidaysLeft.text = stats.pendingConflicts.toString()
        binding.progressConflicts.progress = (stats.pendingConflicts * 10).coerceAtMost(100)
        binding.tvConflictsSubtitle.text = when (stats.pendingConflicts) {
            0    -> "No unresolved matches"
            1    -> "1 match needs review"
            else -> "${stats.pendingConflicts} matches need review"
        }

        // ── Quick-action tile subtitles ────────────────────────────────────
        binding.tvTileStudentsSubTile.text = when (stats.totalStudents) {
            0    -> "No students yet"
            1    -> "1 enrolled"
            else -> "${stats.totalStudents} enrolled"
        }
        binding.tvTileManualSub.text = when (stats.uniquePresentToday) {
            0    -> "None marked yet today"
            1    -> "1 student marked today"
            else -> "${stats.uniquePresentToday} students today"
        }
        binding.tvTileReportsSub.text =
            if (stats.periodsConducted > 0)
                "${stats.attendancePctToday}% avg • ${stats.periodsConducted} period(s)"
            else
                "No sessions today"
        binding.tvTileHolidaysSub.text = when (stats.upcomingHolidays) {
            0    -> "None scheduled"
            1    -> "1 upcoming"
            else -> "${stats.upcomingHolidays} upcoming"
        }
        binding.tvTileTimetableSub.text = when (stats.timetablePeriods) {
            0    -> "Not set up"
            1    -> "1 period set"
            else -> "${stats.timetablePeriods} periods set"
        }
        binding.tvTileChangesLogSub.text = when (stats.changesLogged) {
            0    -> "No changes yet"
            1    -> "1 change logged"
            else -> "${stats.changesLogged} changes logged"
        }

        // ── Conflict banner ────────────────────────────────────────────────
        binding.conflictBanner.visibility =
            if (stats.pendingConflicts > 0) View.VISIBLE else View.GONE
        if (stats.pendingConflicts > 0) {
            binding.tvConflictTitle.text = when (stats.pendingConflicts) {
                1    -> "1 Open Conflict"
                else -> "${stats.pendingConflicts} Open Conflicts"
            }
        }
    }

    // ── Navigation ────────────────────────────────────────────────────────────

    private fun setupClickListeners() {
        binding.tileStudents.setOnClickListener {
            findNavController().navigate(R.id.action_dashboard_to_students)
        }
        binding.tileManual.setOnClickListener {
            findNavController().navigate(R.id.action_dashboard_to_manual)
        }
        binding.tileHolidays.setOnClickListener {
            findNavController().navigate(R.id.action_dashboard_to_holidays)
        }
        binding.tileReports.setOnClickListener {
            findNavController().navigate(R.id.action_dashboard_to_reports)
        }
        binding.btnResolve.setOnClickListener {
            findNavController().navigate(R.id.action_dashboard_to_conflicts)
        }
        binding.navHome.setOnClickListener { /* already here */ }
        binding.navStudents.setOnClickListener {
            findNavController().navigate(R.id.action_dashboard_to_students)
        }
        binding.navReports.setOnClickListener {
            findNavController().navigate(R.id.action_dashboard_to_reports)
        }
        binding.navConflicts.setOnClickListener {
            findNavController().navigate(R.id.action_dashboard_to_conflicts)
        }
        binding.navExit.setOnClickListener {
            requireActivity().onBackPressedDispatcher.onBackPressed()
        }
        binding.tileTimetable.setOnClickListener {
            findNavController().navigate(R.id.action_dashboard_to_timetableSetup)
        }
        binding.tileChangesLog.setOnClickListener {
            findNavController().navigate(R.id.action_dashboard_to_changesLog)
        }
        binding.tileStartAttendance.setOnClickListener {
            findNavController().navigate(R.id.action_dashboard_to_schedule)
        }

        // Manual sync trigger — item 7 (getSyncStatus) is surfaced here too,
        // rather than as a permanent auto-refreshing widget, since sync is
        // designed to fail silently in the background (see AttendanceSyncWorker)
        // and this is the one place an admin can actually check on it on demand.
        binding.btnSyncNow.setOnClickListener {
            val roomId = deviceIdManager.getRoomId() ?: ""
            AttendanceSyncWorker.Scheduler.runOnce(requireContext(), roomId)
            Toast.makeText(requireContext(), "Sync started…", Toast.LENGTH_SHORT).show()
            // Give the one-off worker a moment before re-checking status — this
            // is a best-effort UX nicety, not a guarantee the sync has finished.
            binding.tvSyncStatus.postDelayed({ refreshSyncStatus() }, 4000)
        }

        // Re-pairing entry point (item 5) — e.g. moving this device to a
        // different room, or recovering from a revoked/expired Device Token.
        binding.btnDeviceSettings.setOnClickListener {
            findNavController().navigate(R.id.action_dashboard_to_pairing)
        }
    }

    /**
     * GET /api/v1/sync/status, plus per-category detail from SyncStateEntity —
     * that endpoint only ever covered heartbeat/pull/attendance-up; it has no
     * concept of enrollment/embedding/conflict/change-log pushes, all of
     * which are best-effort steps in AttendanceSyncWorker with their own
     * outcomes recorded locally. A failure here just leaves the status line
     * as-is, consistent with sync being designed to fail silently elsewhere.
     */
    private fun refreshSyncStatus() {
        viewLifecycleOwner.lifecycleScope.launch {
            val result = syncRepository.getSyncStatus()
            if (_binding == null) return@launch // view may be gone by the time this returns

            val pendingUploads = templateRepository.getUnsyncedAttendance().size
            val pendingConflicts = templateRepository.getUnsyncedConflicts().size
            val pendingEnrollments = templateRepository.getStudentsWithUnsyncedEmbedding().size
            val pendingChangeLog = templateRepository.getUnpushedOverrides().size
            val localStates = templateRepository.getSyncStates()
            val failedCategories = localStates.filter { it.status == "FAILED" }.map { it.category }

            result.onSuccess { response ->
                val status = response.data
                val headline = if (status == null) "Sync status unavailable"
                    else "${status.syncStatus} • last sync ${status.lastSync ?: "never"}"

                binding.tvSyncStatus.text = buildString {
                    append(headline)
                    append(" • $pendingUploads attendance")
                    if (pendingEnrollments > 0) append(", $pendingEnrollments enrollments")
                    if (pendingConflicts > 0) append(", $pendingConflicts conflicts")
                    if (pendingChangeLog > 0) append(", $pendingChangeLog change-log entries")
                    append(" pending")
                    if (failedCategories.isNotEmpty()) append(" • failing: ${failedCategories.joinToString()}")
                }
            }.onFailure {
                binding.tvSyncStatus.text = buildString {
                    append("Sync status unavailable")
                    append(" • $pendingUploads attendance pending locally")
                    if (failedCategories.isNotEmpty()) append(" • failing: ${failedCategories.joinToString()}")
                }
            }
        }
    }
}