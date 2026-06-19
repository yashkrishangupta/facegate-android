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
 * MERGED MAIN ACTIVITY
 *
 * Replaces MainActivity + NavigationActivity + StudentNavigationActivity.
 *
 * Layout has two layers:
 *   - roleSelector  : the Student / Admin buttons (visible on launch)
 *   - navHostFragment: a single FragmentContainerView (hidden until a role is chosen)
 *
 * On role selection the selector fades out, the nav host loads the correct
 * graph (nav_graph for Admin, student_nav_graph for Student), and the
 * system back button is wired to pop the nav stack or return to the selector.
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

        findViewById<View>(R.id.btnStudent).setOnClickListener {
            launchGraph(R.navigation.student_nav_graph)
        }

        findViewById<View>(R.id.btnAdmin).setOnClickListener {
            launchGraph(R.navigation.nav_graph)
        }
    }

    // ── Graph loading ─────────────────────────────────────────────────────────

    private fun launchGraph(graphResId: Int) {
        val navHostFragment =
            supportFragmentManager.findFragmentById(R.id.nav_host_fragment) as NavHostFragment

        // Inflate the chosen graph and set it as the current graph
        val inflater = navHostFragment.navController.navInflater
        val graph    = inflater.inflate(graphResId)
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