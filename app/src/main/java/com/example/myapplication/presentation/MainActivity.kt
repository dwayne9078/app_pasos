package com.example.myapplication.presentation

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import com.google.firebase.FirebaseApp
import kotlinx.coroutines.delay

/**
 * MainActivity is now primarily responsible for managing the UI and interacting with the StepTrackerService.
 */
class MainActivity : ComponentActivity() {

    // Mutable state variables for the UI, updated by the service's broadcasts
    private var currentStepCount by mutableStateOf(0)
    private var stepsPerSecond by mutableStateOf(0.0)
    private var isTracking by mutableStateOf(false)
    private var isSimulating by mutableStateOf(false)

    // Broadcast receiver to get updates from StepTrackerService
    private val stepUpdateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            intent?.let {
                // Update UI state variables with data from the broadcast
                currentStepCount = it.getIntExtra(EXTRA_CURRENT_STEPS, 0)
                stepsPerSecond = it.getDoubleExtra(EXTRA_STEPS_PER_SECOND, 0.0)
                isTracking = it.getBooleanExtra(EXTRA_IS_TRACKING, false)
                isSimulating = it.getBooleanExtra(EXTRA_IS_SIMULATING, false)
            }
        }
    }

    // Launcher for requesting ACTIVITY_RECOGNITION permission
    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            // Permission granted, start the step tracking service
            startStepTrackerService()
        } else {
            // Permission denied, inform the user and still attempt to start service (for simulation fallback)
            Toast.makeText(this, "Permiso de actividad física requerido para el conteo de pasos.", Toast.LENGTH_LONG).show()
            // Still try to start the service; it will fall back to simulation if no sensors
            startStepTrackerService()
        }
    }

    // Launcher for requesting POST_NOTIFICATIONS permission (for Android 13+)
    private val requestNotificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted: Boolean ->
        if (!isGranted) {
            Toast.makeText(this, "Permiso de notificaciones requerido para mostrar alertas.", Toast.LENGTH_LONG).show()
        }
        // Proceed to start the service regardless, as the service will check again internally
        startStepTrackerService()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Initialize Firebase. This is crucial for FCM to work.
        FirebaseApp.initializeApp(this)

        // Register the broadcast receiver to get updates from the service
        LocalBroadcastManager.getInstance(this).registerReceiver(
            stepUpdateReceiver, IntentFilter(ACTION_STEP_UPDATE)
        )

        // Check and request necessary permissions
        checkPermissions()

        setContent {
            // Provide the Wear OS Material Theme for the application
            StepsPerSecondTheme {
                // Main screen composable, now receiving states from MainActivity's mutableStateOf
                StepsScreen(
                    currentSteps = currentStepCount,
                    stepsPerSecond = stepsPerSecond,
                    isTracking = isTracking,
                    isSimulating = isSimulating,
                )
            }
        }
    }

    /**
     * Checks and requests necessary Android permissions (ACTIVITY_RECOGNITION and POST_NOTIFICATIONS).
     * After permissions are handled, it initiates the StepTrackerService.
     */
    private fun checkPermissions() {
        when {
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.ACTIVITY_RECOGNITION
            ) == PackageManager.PERMISSION_GRANTED -> {
                // ACTIVITY_RECOGNITION permission already granted
                // Now check for notification permission for Android 13+
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    if (ContextCompat.checkSelfPermission(
                            this,
                            Manifest.permission.POST_NOTIFICATIONS
                        ) != PackageManager.PERMISSION_GRANTED
                    ) {
                        requestNotificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                    } else {
                        // Both permissions granted, start service
                        startStepTrackerService()
                    }
                } else {
                    // Pre-Android 13, no POST_NOTIFICATIONS needed, start service
                    startStepTrackerService()
                }
            }
            else -> {
                // Request ACTIVITY_RECOGNITION permission
                requestPermissionLauncher.launch(Manifest.permission.ACTIVITY_RECOGNITION)
            }
        }
    }

    /**
     * Starts the StepTrackerService as a foreground service.
     */
    private fun startStepTrackerService() {
        val serviceIntent = Intent(this, StepTrackerService::class.java).apply {
            action = ACTION_START_TRACKING
        }
        // Use startForegroundService for starting foreground service (required for Android O+)
        ContextCompat.startForegroundService(this, serviceIntent)
    }

    /**
     * Stops the StepTrackerService. This is called when the MainActivity is destroyed.
     */
    private fun stopStepTrackerService() {
        val serviceIntent = Intent(this, StepTrackerService::class.java).apply {
            action = ACTION_STOP_TRACKING
        }
        stopService(serviceIntent)
    }

    override fun onDestroy() {
        super.onDestroy()
        // Unregister the broadcast receiver to prevent memory leaks
        LocalBroadcastManager.getInstance(this).unregisterReceiver(stepUpdateReceiver)
        // Stop the service when the activity is finally destroyed
        stopStepTrackerService()
    }
}

/**
 * Defines the Wear OS Material Theme for the application.
 */
@Composable
fun StepsPerSecondTheme(content: @Composable () -> Unit) {
    // Define the color scheme for Wear OS Material Design
    val colors = androidx.wear.compose.material.Colors(
        primary = Color(0xFF1976D2), // A shade of blue for primary elements
        primaryVariant = Color(0xFF0D47A1), // Darker shade for variants
        secondary = Color(0xFF64B5F6), // Lighter blue for secondary elements
        secondaryVariant = Color(0xFF2196F3), // Another secondary shade
        background = Color.Black, // Black background for Wear OS screens
        surface = Color.Black, // Black surface
        error = Color(0xFFD32F2F), // Red for error states
        onPrimary = Color.White, // White text on primary background
        onSecondary = Color.Black, // Black text on secondary background
        onBackground = Color.White, // White text on background
        onSurface = Color.White, // White text on surface
        onError = Color.White // White text on error background
    )

    androidx.wear.compose.material.MaterialTheme(
        colors = colors,
        // Typography and shapes can be customized here if needed
        content = content
    )
}

/**
 * The main screen composable that handles navigation between MainScreen and DetailsScreen.
 */
@OptIn(ExperimentalFoundationApi::class) // Required for HorizontalPager
@Composable
fun StepsScreen(
    currentSteps: Int,
    stepsPerSecond: Double,
    isTracking: Boolean,
    isSimulating: Boolean,
) {
    // State for the elapsed time in seconds
    var timeElapsed by remember { mutableStateOf(0) }

    // LaunchedEffect to manage the timer based on the tracking state
    LaunchedEffect(isTracking) {
        if (isTracking) {
            // Start the timer when tracking is active
            while (isTracking) {
                delay(1000) // Wait for 1 second
                timeElapsed++
            }
        } else {
            // Reset time elapsed when tracking stops
            timeElapsed = 0
        }
    }

    // State for managing the horizontal pager
    val pagerState = rememberPagerState(initialPage = 0) { 2 } // Two pages: Main and Details

    // HorizontalPager to allow swiping between screens
    HorizontalPager(
        state = pagerState,
        modifier = Modifier.fillMaxSize()
    ) { page ->
        when (page) {
            0 -> MainScreen(
                stepsPerSecond = stepsPerSecond,
                isTracking = isTracking,
                isSimulating = isSimulating
            )
            1 -> DetailsScreen(
                currentSteps = currentSteps,
                timeElapsed = timeElapsed,
                stepsPerSecond = stepsPerSecond
            )
        }
    }
}

/**
 * Displays the main step per second count and tracking status.
 */
@Composable
fun MainScreen(
    stepsPerSecond: Double,
    isTracking: Boolean,
    isSimulating: Boolean
) {
    // Get the current context to check BuildConfig.DEBUG
    val context = LocalContext.current
    val isDebug = context.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE != 0

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black), // Black background for the screen
        contentAlignment = Alignment.Center // Center content in the box
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            // Minimalist status indicator
            if (isTracking) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center
                ) {
                    Box(
                        modifier = Modifier
                            .size(6.dp)
                            .background(Color.Green, CircleShape) // Green dot for active status
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    androidx.wear.compose.material.Text(
                        text = "ACTIVO",
                        fontSize = 8.sp,
                        color = Color.Green,
                        fontWeight = FontWeight.Bold
                    )
                }

                // Show "(Simulación)" text only in debug builds if simulating
                if (isSimulating && isDebug) {
                    androidx.wear.compose.material.Text(
                        text = "(Simulación)",
                        fontSize = 6.sp,
                        color = Color.Yellow
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Main data display - very large and prominent
            Box(
                modifier = Modifier
                    .size(160.dp) // Large circular background
                    .background(
                        Color(0xFF1976D2).copy(alpha = 0.2f), // Semi-transparent blue circle
                        CircleShape
                    ),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    androidx.wear.compose.material.Text(
                        text = String.format("%.1f", stepsPerSecond), // Display steps/sec with one decimal
                        fontSize = 48.sp, // Very large font size
                        fontWeight = FontWeight.Bold,
                        color = Color.White
                    )
                    androidx.wear.compose.material.Text(
                        text = "pasos/seg",
                        fontSize = 14.sp,
                        color = Color.Gray
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Page indicator/instruction
            androidx.wear.compose.material.Text(
                text = "← Desliza para más info →",
                fontSize = 8.sp,
                color = Color.Gray,
                textAlign = TextAlign.Center
            )
        }
    }
}

/**
 * Displays detailed statistics, including total steps, time elapsed, and average steps per second.
 */
@Composable
fun DetailsScreen(
    currentSteps: Int,
    timeElapsed: Int,
    stepsPerSecond: Double
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
            modifier = Modifier.padding(20.dp) // Padding around the column content
        ) {
            androidx.wear.compose.material.Text(
                text = "ESTADÍSTICAS",
                fontSize = 12.sp,
                color = Color.Gray,
                fontWeight = FontWeight.Bold
            )

            Spacer(modifier = Modifier.height(20.dp))

            // Metrics with better spacing
            MetricRow(
                label = "Total",
                value = "$currentSteps",
                unit = "pasos",
                color = Color.White
            )

            Spacer(modifier = Modifier.height(12.dp))

            MetricRow(
                label = "Tiempo",
                value = formatTime(timeElapsed), // Using the new formatTime function
                unit = "",
                color = Color.Cyan
            )

            Spacer(modifier = Modifier.height(12.dp))

            // Only show average if time has elapsed to avoid division by zero
            if (timeElapsed > 0) {
                MetricRow(
                    label = "Promedio",
                    value = String.format("%.1f", currentSteps.toDouble() / timeElapsed),
                    unit = "p/s",
                    color = Color.Yellow
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            androidx.wear.compose.material.Text(
                text = "← Desliza para regresar",
                fontSize = 8.sp,
                color = Color.Gray
            )
        }
    }
}

/**
 * A reusable composable for displaying a metric row with a label, value, and unit.
 */
@Composable
fun MetricRow(
    label: String,
    value: String,
    unit: String,
    color: Color
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        androidx.wear.compose.material.Text(
            text = label.uppercase(), // Label in uppercase
            fontSize = 10.sp,
            color = Color.Gray,
            fontWeight = FontWeight.Medium
        )
        Row(
            verticalAlignment = Alignment.CenterVertically // Align text baselines
        ) {
            androidx.wear.compose.material.Text(
                text = value,
                fontSize = 24.sp, // Larger value text
                color = color,
                fontWeight = FontWeight.Bold
            )
            if (unit.isNotEmpty()) { // Only show unit if it's not empty
                Spacer(modifier = Modifier.width(4.dp))
                androidx.wear.compose.material.Text(
                    text = unit,
                    fontSize = 12.sp,
                    color = Color.Gray
                )
            }
        }
    }
}

/**
 * Formats time in seconds into a human-readable H:MM:SS string.
 */
fun formatTime(totalSeconds: Int): String {
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val seconds = totalSeconds % 60
    return String.format("%02d:%02d:%02d", hours, minutes, seconds)
}
