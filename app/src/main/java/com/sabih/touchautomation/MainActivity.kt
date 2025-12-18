package com.sabih.touchautomation

import android.accessibilityservice.AccessibilityService
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.text.TextUtils
import android.view.LayoutInflater
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.isVisible
import android.view.accessibility.AccessibilityManager
import android.accessibilityservice.AccessibilityServiceInfo
import android.net.Uri
import android.view.accessibility.AccessibilityNodeInfo
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.sabih.touchautomation.accessibility.MyAccessibilityService
import com.sabih.touchautomation.ui.theme.FiverrAutoRefresherTheme
import java.util.ArrayDeque

class MainActivity : ComponentActivity() {
    private val handler = Handler(Looper.getMainLooper())
    private var isAutomationRunning = false
    private var pendingRefreshRunnable: Runnable? = null
    private var autoRefreshInfoView: TextView? = null
    private var intervalInput: EditText? = null
    private var autoRefreshIntervalMs: Long = DEFAULT_INTERVAL_MINUTES * 60_000L
    private var startButtonRef: Button? = null
    private var stopButtonRef: Button? = null
    private var batteryButtonRef: Button? = null
    private var floatingTimerButtonRef: Button? = null
    private var homeNavButtonRef: Button? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var nextRefreshView: TextView? = null
    private var nextRefreshAtMs: Long? = null
    private var wantsFloatingTimer = false
    private var forceNavigateHome = false
    private var pendingEnableFloatingTimer = false

    private val batteryOptLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        updateBatteryButtonVisibility()
    }

    private val overlayPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        handleOverlayPermissionResult()
    }

    // Repeating job: opens Fiverr, waits briefly, swipes down to refresh, then waits 5–6 minutes.
    private val automationRunnable = object : Runnable {
        override fun run() {
            if (!isAutomationRunning) return

            val service = MyAccessibilityService.instance
            if (service == null || !MyAccessibilityService.isServiceRunning()) {
                stopAutomation()
                Toast.makeText(
                    this@MainActivity,
                    "Accessibility service stopped. Enable it to continue.",
                    Toast.LENGTH_SHORT
                ).show()
                return
            }

            val isForeground = KNOWN_FIVERR_PACKAGES.any { pkg ->
                service.isPackageOnTop(pkg)
            }

            if (!isForeground) {
                val opened = openFiverrApp(showToast = false)
                if (!opened) {
                    stopAutomation()
                    Toast.makeText(
                        this@MainActivity,
                        "Fiverr app not found. Install it and try again.",
                        Toast.LENGTH_SHORT
                    ).show()
                    return
                }
            }

            val runRefresh: () -> Unit = {
                if (isAutomationRunning && MyAccessibilityService.isServiceRunning()) {
                    val coords = refreshCoordinates()
                    MyAccessibilityService.instance?.dragTopToBottom(
                        coords.x,
                        coords.startY,
                        coords.endY
                    )
                    nextRefreshAtMs = System.currentTimeMillis() + autoRefreshIntervalMs
                    startCountdownIfNeeded()
                    refreshFloatingTimer()
                }
                pendingRefreshRunnable = null
            }

            // Schedule a single refresh after the app has had time to appear (and optionally return home).
            val refreshRunnable = Runnable {
                if (!isAutomationRunning || !MyAccessibilityService.isServiceRunning()) {
                    pendingRefreshRunnable = null
                    return@Runnable
                }
                val serviceInstance = MyAccessibilityService.instance
                if (forceNavigateHome && serviceInstance != null) {
                    navigateToFiverrHome(serviceInstance) { runRefresh() }
                } else {
                    runRefresh()
                }
            }

            pendingRefreshRunnable = refreshRunnable
            nextRefreshAtMs = System.currentTimeMillis() + APP_LAUNCH_WAIT_MS
            startCountdownIfNeeded()
            refreshFloatingTimer()
            handler.postDelayed(refreshRunnable, APP_LAUNCH_WAIT_MS)

            // Queue the next cycle using the user-selected interval.
            handler.postDelayed(this, autoRefreshIntervalMs)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        wantsFloatingTimer = loadFloatingTimerPreference()
        forceNavigateHome = loadNavigateHomePreference()
        enableEdgeToEdge()
        setContent {
            FiverrAutoRefresherTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    AndroidView(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(innerPadding),
                        factory = { context ->
                            LayoutInflater.from(context).inflate(R.layout.activity_main, null).apply {
                                val startButton = findViewById<Button>(R.id.buttonStartAutomation)
                                val stopButton = findViewById<Button>(R.id.buttonStopAutomation)
                                val tapButton = findViewById<Button>(R.id.buttonTapTest)
                                val openFiverrButton = findViewById<Button>(R.id.buttonOpenFiverr)
                                val batteryButton = findViewById<Button>(R.id.buttonBatterySettings)
                                val accessibilityButton = findViewById<Button>(R.id.buttonAccessibilitySettings)
                                val floatingTimerButton = findViewById<Button>(R.id.buttonFloatingTimer)
                                val homeNavigationButton = findViewById<Button>(R.id.buttonHomeNavigation)
                                autoRefreshInfoView = findViewById(R.id.textAutoRefreshInfo)
                                intervalInput = findViewById(R.id.editIntervalMinutes)
                                nextRefreshView = findViewById(R.id.textNextRefresh)
                                startButtonRef = startButton
                                stopButtonRef = stopButton
                                batteryButtonRef = batteryButton
                                floatingTimerButtonRef = floatingTimerButton
                                homeNavButtonRef = homeNavigationButton
                                updateAutoRefreshStatus(isAutomationRunning)
                                updateButtonStates()
                                updateBatteryButtonVisibility()
                                updateNextRefreshDisplay()
                                updateFloatingTimerButton()
                                updateHomeNavButton()

                                startButton.setOnClickListener { startAutomationWithChecks(context) }

                                stopButton.setOnClickListener {
                                    stopAutomation()
                                    Toast.makeText(
                                        context,
                                        "Automation stopped.",
                                        Toast.LENGTH_SHORT
                                    ).show()
                                }

                                tapButton.setOnClickListener {
                                    val service = MyAccessibilityService.instance
                                    if (service == null || !MyAccessibilityService.isServiceRunning()) {
                                        showAccessibilityHelpDialog()
                                        return@setOnClickListener
                                    }

                                    val isForeground = KNOWN_FIVERR_PACKAGES.any { pkg ->
                                        service.isPackageOnTop(pkg)
                                    }

                                    if (!isForeground) {
                                        val opened = openFiverrApp()
                                        if (!opened) {
                                            return@setOnClickListener
                                        }
                                    }

                                    val serviceInstance = MyAccessibilityService.instance
                                    val runTapTest: () -> Unit = {
                                        val coords = refreshCoordinates()
                                        MyAccessibilityService.instance?.dragTopToBottom(
                                            coords.x,
                                            coords.startY,
                                            coords.endY
                                        )
                                    }

                                    handler.postDelayed({
                                        if (forceNavigateHome && serviceInstance != null) {
                                            navigateToFiverrHome(serviceInstance) { runTapTest() }
                                        } else {
                                            runTapTest()
                                        }
                                    }, APP_LAUNCH_WAIT_MS)
                                }

                                openFiverrButton.setOnClickListener {
                                    openFiverrApp()
                                }

                                batteryButton.setOnClickListener {
                                    openBatteryOptimizationSettings()
                                }

                                accessibilityButton.setOnClickListener {
                                    showAccessibilityHelpDialog()
                                }

                                floatingTimerButton.setOnClickListener {
                                    toggleFloatingTimer()
                                }

                                homeNavigationButton.setOnClickListener {
                                    toggleHomeNavigation()
                                }

                                promptBatteryOptimizationIfNeeded()
                            }
                        }
                    )
                }
            }
        }
    }

    private fun startAutomationWithChecks(context: Context) {
        updateIntervalFromInput()

        if (!isAccessibilityReady()) {
            showAccessibilityHelpDialog()
            return
        }

        startAutomation()
    }

    private fun startAutomation() {
        if (!MyAccessibilityService.isServiceRunning()) {
            Toast.makeText(this, "Accessibility Service not running.", Toast.LENGTH_SHORT).show()
            return
        }

        if (isAutomationRunning) {
            Toast.makeText(this, "Automation already running.", Toast.LENGTH_SHORT).show()
            return
        }

        isAutomationRunning = true
        handler.post(automationRunnable)
        updateAutoRefreshStatus(true)
        updateButtonStates()
        keepScreenOn(true)
        acquireWakeLock()
        startCountdownIfNeeded()
        refreshFloatingTimer()
    }

    private fun stopAutomation() {
        isAutomationRunning = false
        handler.removeCallbacks(automationRunnable)
        pendingRefreshRunnable?.let { handler.removeCallbacks(it) }
        pendingRefreshRunnable = null
        updateAutoRefreshStatus(false)
        updateButtonStates()
        keepScreenOn(false)
        releaseWakeLock()
        nextRefreshAtMs = null
        stopCountdown()
        refreshFloatingTimer()
    }

    private fun isAccessibilityReady(): Boolean {
        // If the service has already connected, we're good.
        if (MyAccessibilityService.isServiceRunning()) return true

        // Check enabled services from AccessibilityManager (more reliable than string parsing alone).
        val am = getSystemService(Context.ACCESSIBILITY_SERVICE) as? AccessibilityManager
        val enabledList = am?.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
        val expectedId = ComponentName(this, MyAccessibilityService::class.java).flattenToString()
        if (enabledList != null) {
            enabledList.firstOrNull { it.id.equals(expectedId, ignoreCase = true) }?.let {
                return true
            }
        }

        // Fallback: string-based check.
        val expectedComponent = ComponentName(this, MyAccessibilityService::class.java)
        val enabledServices = Settings.Secure.getString(
            contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false

        val splitter = TextUtils.SimpleStringSplitter(':').apply {
            setString(enabledServices)
        }

        while (splitter.hasNext()) {
            if (splitter.next().equals(expectedComponent.flattenToString(), ignoreCase = true)) {
                return true
            }
        }
        return false
    }

    private fun openFiverrApp(showToast: Boolean = true): Boolean {
        val launchIntent = resolveFiverrLaunchIntent()
        return if (launchIntent != null) {
            launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(launchIntent)
            true
        } else {
            if (showToast) {
                Toast.makeText(this, "Fiverr app not installed.", Toast.LENGTH_SHORT).show()
            }
            false
        }
    }

    private fun openAccessibilitySettings() {
        val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        startActivity(intent)
    }

    private fun showAccessibilityHelpDialog() {
        val message = """
            Android 13+ blocks Accessibility for sideloaded apps until you allow restricted settings.

            1) Open App info for Fiverr Auto Refresher and tap the three dots.
            2) Enable \"Allow restricted settings\".
            3) Return to Accessibility settings and switch on \"Fiverr Auto Refresher\".
        """.trimIndent()

        MaterialAlertDialogBuilder(this)
            .setTitle("Enable Accessibility")
            .setMessage(message)
            .setPositiveButton("Open Accessibility Settings") { _, _ ->
                openAccessibilitySettings()
            }
            .setNeutralButton("Open App Info") { _, _ ->
                openAppInfo()
            }
            .setNegativeButton("Close", null)
            .show()
    }

    private fun openAppInfo() {
        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = Uri.parse("package:$packageName")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        try {
            startActivity(intent)
        } catch (e: Exception) {
            Toast.makeText(
                this,
                "Unable to open App info on this device.",
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    private fun openBatteryOptimizationSettings() {
        val pm = getSystemService(Context.POWER_SERVICE) as? PowerManager
        val isIgnored = pm?.isIgnoringBatteryOptimizations(packageName) == true
        if (isIgnored) {
            try {
                val intent = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                startActivity(intent)
            } catch (e: Exception) {
                Toast.makeText(
                    this,
                    "Unable to open battery settings on this device.",
                    Toast.LENGTH_SHORT
                ).show()
            }
        } else {
            requestIgnoreBatteryOptimizations()
        }
    }

    private fun promptBatteryOptimizationIfNeeded() {
        if (isIgnoringBatteryOptimizations()) {
            updateBatteryButtonVisibility()
        } else {
            requestIgnoreBatteryOptimizations()
        }
    }

    private fun requestIgnoreBatteryOptimizations() {
        val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
            data = Uri.parse("package:$packageName")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        try {
            batteryOptLauncher.launch(intent)
        } catch (e: Exception) {
            Toast.makeText(
                this,
                "Unable to request battery optimization change on this device.",
                Toast.LENGTH_SHORT
            ).show()
            updateBatteryButtonVisibility()
        }
    }

    private fun resolveFiverrLaunchIntent(): Intent? {
        // First try known package names.
        KNOWN_FIVERR_PACKAGES.forEach { pkg ->
            packageManager.getLaunchIntentForPackage(pkg)?.let { return it }
        }

        // Fallback: scan launcher activities and pick the first package containing "fiverr".
        val mainIntent = Intent(Intent.ACTION_MAIN).apply {
            addCategory(Intent.CATEGORY_LAUNCHER)
        }
        val matches = packageManager.queryIntentActivities(mainIntent, 0)
        val match = matches.firstOrNull { it.activityInfo.packageName.contains("fiverr", ignoreCase = true) }
        if (match != null) {
            return Intent(Intent.ACTION_MAIN).apply {
                addCategory(Intent.CATEGORY_LAUNCHER)
                component = ComponentName(match.activityInfo.packageName, match.activityInfo.name)
            }
        }
        return null
    }

    private fun refreshCoordinates(): RefreshCoordinates {
        val metrics = resources.displayMetrics
        val x = metrics.widthPixels / 2f
        val startY = metrics.heightPixels * 0.2f
        val endY = metrics.heightPixels * 0.8f
        return RefreshCoordinates(x, startY, endY)
    }

    override fun onDestroy() {
        super.onDestroy()
        stopAutomation()
        autoRefreshInfoView = null
        intervalInput = null
        startButtonRef = null
        stopButtonRef = null
        batteryButtonRef = null
        floatingTimerButtonRef = null
        homeNavButtonRef = null
        nextRefreshView = null
        releaseWakeLock()
    }

    private fun updateAutoRefreshStatus(running: Boolean) {
        autoRefreshInfoView?.text = APP_TITLE
    }

    private fun updateButtonStates() {
        val running = isAutomationRunning
        startButtonRef?.isEnabled = !running
        stopButtonRef?.isEnabled = running
        startButtonRef?.alpha = if (running) 0.5f else 1f
        stopButtonRef?.alpha = if (running) 1f else 0.5f
    }

    private fun updateBatteryButtonVisibility() {
        val visible = !isIgnoringBatteryOptimizations()
        batteryButtonRef?.isVisible = visible
    }

    private fun updateFloatingTimerButton() {
        floatingTimerButtonRef?.text = if (wantsFloatingTimer) {
            "Disable Floating Timer"
        } else {
            "Enable Floating Timer"
        }
    }

    private fun updateHomeNavButton() {
        homeNavButtonRef?.text = if (forceNavigateHome) {
            "Go to Fiverr Home before refresh: ON"
        } else {
            "Go to Fiverr Home before refresh: OFF"
        }
    }

    private fun toggleFloatingTimer() {
        if (wantsFloatingTimer) {
            wantsFloatingTimer = false
            saveFloatingTimerPreference(false)
            FloatingTimerService.updateTimer(this, false, null)
            updateFloatingTimerButton()
            Toast.makeText(this, "Floating timer hidden.", Toast.LENGTH_SHORT).show()
            return
        }

        if (Settings.canDrawOverlays(this)) {
            wantsFloatingTimer = true
            saveFloatingTimerPreference(true)
            updateFloatingTimerButton()
            refreshFloatingTimer()
            Toast.makeText(
                this,
                "Floating timer enabled. It appears while automation is running.",
                Toast.LENGTH_SHORT
            ).show()
        } else {
            pendingEnableFloatingTimer = true
            openOverlayPermissionSettings()
            Toast.makeText(
                this,
                "Allow drawing over other apps to show the floating timer.",
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    private fun handleOverlayPermissionResult() {
        val allowed = Settings.canDrawOverlays(this)
        if (pendingEnableFloatingTimer) {
            if (allowed) {
                wantsFloatingTimer = true
                saveFloatingTimerPreference(true)
                refreshFloatingTimer()
                Toast.makeText(
                    this,
                    "Floating timer enabled. It appears while automation is running.",
                    Toast.LENGTH_SHORT
                ).show()
            } else {
                Toast.makeText(
                    this,
                    "Overlay permission denied. Floating timer cannot be shown.",
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
        pendingEnableFloatingTimer = false
        updateFloatingTimerButton()
    }

    private fun toggleHomeNavigation() {
        forceNavigateHome = !forceNavigateHome
        saveNavigateHomePreference(forceNavigateHome)
        updateHomeNavButton()
        Toast.makeText(
            this,
            if (forceNavigateHome) {
                "Will try to go to Fiverr home before each refresh."
            } else {
                "Will refresh wherever Fiverr is currently open."
            },
            Toast.LENGTH_SHORT
        ).show()
    }

    private fun navigateToFiverrHome(service: MyAccessibilityService, onComplete: () -> Unit) {
        val isFiverrForeground: () -> Boolean = {
            KNOWN_FIVERR_PACKAGES.any { pkg -> service.isPackageOnTop(pkg) }
        }

        val tapHomeAndFinish: () -> Unit = {
            val homeNode = service.rootInActiveWindow.findHomeNode()
            if (homeNode != null) {
                homeNode.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            } else {
                tryTapBottomHome(service)
            }
            handler.postDelayed({
                if (isAutomationRunning) onComplete()
            }, HOME_NAVIGATION_SETTLE_MS)
        }

        // If Fiverr isn't on top, bring it to foreground first, then tap Home.
        if (!isFiverrForeground()) {
            openFiverrApp(showToast = false)
            handler.postDelayed({
                if (!isAutomationRunning) return@postDelayed
                tapHomeAndFinish()
            }, APP_LAUNCH_WAIT_MS)
            return
        }

        tapHomeAndFinish()
    }

    private fun openOverlayPermissionSettings() {
        val intent = Intent(
            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
            Uri.parse("package:$packageName")
        )
        try {
            overlayPermissionLauncher.launch(intent)
        } catch (e: Exception) {
            Toast.makeText(
                this,
                "Unable to open overlay settings on this device.",
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    private fun refreshFloatingTimer() {
        if (!Settings.canDrawOverlays(this)) {
            FloatingTimerService.updateTimer(this, false, null)
            return
        }

        if (wantsFloatingTimer) {
            FloatingTimerService.updateTimer(this, isAutomationRunning, nextRefreshAtMs)
        } else {
            FloatingTimerService.updateTimer(this, false, null)
        }
    }

    private fun loadFloatingTimerPreference(): Boolean {
        val prefs = getSharedPreferences(FLOATING_PREFS, Context.MODE_PRIVATE)
        return prefs.getBoolean(KEY_FLOATING_ENABLED, false)
    }

    private fun saveFloatingTimerPreference(enabled: Boolean) {
        val prefs = getSharedPreferences(FLOATING_PREFS, Context.MODE_PRIVATE)
        prefs.edit().putBoolean(KEY_FLOATING_ENABLED, enabled).apply()
    }

    private fun loadNavigateHomePreference(): Boolean {
        val prefs = getSharedPreferences(FLOATING_PREFS, Context.MODE_PRIVATE)
        return prefs.getBoolean(KEY_NAVIGATE_HOME, false)
    }

    private fun saveNavigateHomePreference(enabled: Boolean) {
        val prefs = getSharedPreferences(FLOATING_PREFS, Context.MODE_PRIVATE)
        prefs.edit().putBoolean(KEY_NAVIGATE_HOME, enabled).apply()
    }

    private fun isIgnoringBatteryOptimizations(): Boolean {
        val pm = getSystemService(Context.POWER_SERVICE) as? PowerManager
        return pm?.isIgnoringBatteryOptimizations(packageName) == true
    }

    private fun keepScreenOn(enable: Boolean) {
        if (enable) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    private fun acquireWakeLock() {
        val pm = getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return
        if (wakeLock?.isHeld == true) return
        wakeLock = pm.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP,
            "FiverrAutoRefresher:AutomationWakeLock"
        ).apply {
            setReferenceCounted(false)
            try {
                acquire()
            } catch (_: SecurityException) {
                // WAKE_LOCK permission might be denied; ignore silently.
            }
        }
    }

    private fun releaseWakeLock() {
        try {
            wakeLock?.takeIf { it.isHeld }?.release()
        } catch (_: Exception) {
            // Ignore errors on release
        }
        wakeLock = null
    }

    private fun updateIntervalFromInput() {
        val minutes = intervalInput?.text?.toString()?.toIntOrNull()
            ?.coerceIn(MIN_INTERVAL_MINUTES, MAX_INTERVAL_MINUTES)
            ?: DEFAULT_INTERVAL_MINUTES
        autoRefreshIntervalMs = minutes * 60_000L
        updateAutoRefreshStatus(isAutomationRunning)
    }

    private fun startCountdownIfNeeded() {
        if (!isAutomationRunning) return
        handler.removeCallbacks(countdownRunnable)
        handler.post(countdownRunnable)
    }

    private fun stopCountdown() {
        handler.removeCallbacks(countdownRunnable)
        updateNextRefreshDisplay()
    }

    private fun updateNextRefreshDisplay() {
        val nextText = nextRefreshAtMs?.let { target ->
            val remainingMs = target - System.currentTimeMillis()
            if (remainingMs <= 0) {
                "Next refresh: refreshing..."
            } else {
                val minutes = remainingMs / 60_000
                val seconds = (remainingMs / 1_000) % 60
                String.format("Next refresh: %d:%02d", minutes, seconds)
            }
        } ?: "Next refresh: --"
        nextRefreshView?.text = nextText
    }

    private val countdownRunnable = object : Runnable {
        override fun run() {
            if (!isAutomationRunning) {
                updateNextRefreshDisplay()
                return
            }
            updateNextRefreshDisplay()
            handler.postDelayed(this, 1_000L)
        }
    }

    private fun tryTapBottomHome(service: MyAccessibilityService): Boolean {
        val metrics = service.resources.displayMetrics
        val x = metrics.widthPixels * HOME_TAB_X_FRACTION
        val y = metrics.heightPixels * HOME_TAB_Y_FRACTION
        service.tap(x, y)
        return true
    }

    companion object {
        private const val AUTOMATION_TAP_X = 500f
        private const val AUTOMATION_TAP_Y = 1200f
        private const val DEFAULT_INTERVAL_MINUTES = 5
        private const val MIN_INTERVAL_MINUTES = 1
        private const val MAX_INTERVAL_MINUTES = 60
        private const val APP_LAUNCH_WAIT_MS = 2_500L
        private const val HOME_NAVIGATION_SETTLE_MS = 500L
        private const val HOME_TAB_X_FRACTION = 0.12f // Bottom-left nav item
        private const val HOME_TAB_Y_FRACTION = 0.92f // Near bottom nav bar
        private const val APP_TITLE = "Fiverr Auto Refresher"
        private const val FLOATING_PREFS = "floating_timer_prefs"
        private const val KEY_FLOATING_ENABLED = "floating_timer_enabled"
        private const val KEY_NAVIGATE_HOME = "navigate_home_first"
        private val KNOWN_FIVERR_PACKAGES = listOf(
            "com.fiverr.fiverr",
            "com.fiverr.fiverrapp"
        )
    }
}

private data class RefreshCoordinates(
    val x: Float,
    val startY: Float,
    val endY: Float
)

private fun AccessibilityNodeInfo?.findHomeNode(): AccessibilityNodeInfo? {
    this ?: return null
    val queue: ArrayDeque<AccessibilityNodeInfo> = ArrayDeque()
    queue.add(this)
    while (queue.isNotEmpty()) {
        val node = queue.removeFirst()
        val text = node.text?.toString()?.lowercase()
        val desc = node.contentDescription?.toString()?.lowercase()
        val looksLikeHome = (text?.contains("home") == true) || (desc?.contains("home") == true)
        if (looksLikeHome && node.isClickable && node.isEnabled) {
            return node
        }
        for (i in 0 until node.childCount) {
            node.getChild(i)?.let { queue.add(it) }
        }
    }
    return null
}
