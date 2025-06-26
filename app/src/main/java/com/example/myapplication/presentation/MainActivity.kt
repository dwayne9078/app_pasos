package com.example.myapplication.presentation

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.NotificationCompat // Import for NotificationCompat
import androidx.core.app.NotificationManagerCompat // Import for NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.random.Random
import com.google.firebase.FirebaseApp // Import FirebaseApp

// Import Wear OS specific Compose Material components
import androidx.wear.compose.material.Button
import androidx.wear.compose.material.ButtonDefaults
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.Text
import androidx.wear.compose.material.Colors
import androidx.wear.compose.material.CompactButton // Often useful for smaller buttons on Wear OS

import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.ui.platform.LocalContext // To access BuildConfig.DEBUG
import androidx.annotation.OptIn // Needed for @OptIn annotation
import androidx.compose.foundation.ExperimentalFoundationApi // Needed for HorizontalPager

// Define constants for the notification channel ID and notification ID
const val NOTIFICATION_CHANNEL_ID = "step_tracker_channel"
const val NOTIFICATION_ID = 101 // Unique ID for our notification

class MainActivity : ComponentActivity(), SensorEventListener {

    private lateinit var sensorManager: SensorManager
    private var stepCounterSensor: Sensor? = null
    private var stepDetectorSensor: Sensor? = null

    private var initialStepCount = -1
    // Use mutableStateOf for properties that need to trigger UI recomposition
    private var currentStepCount by mutableStateOf(0)
    private var stepsPerSecond by mutableStateOf(0.0)
    private var isTracking by mutableStateOf(false)
    private var isSimulating by mutableStateOf(false)

    // Store timestamps of detected steps to calculate steps per second
    private var stepHistory = mutableListOf<Long>()
    private val windowSizeMs = 1000L // 1-second window for calculating steps/sec

    // Simulation variables
    private var simulationJob: kotlinx.coroutines.Job? = null
    private var simulatedSteps = 0
    private var simulationSpeed = 1.8 // Realistic base speed (steps per second)

    // Launcher for requesting ACTIVITY_RECOGNITION permission
    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            // Permission granted, initialize sensors
            initializeSensors()
        } else {
            // Permission denied, show a toast message
            Toast.makeText(this, "Permiso de actividad física requerido para el conteo de pasos.", Toast.LENGTH_LONG).show()
            // If permission is denied, ensure simulation starts if no sensors are available.
            isSimulating = true
            startTracking()
        }
    }

    // Launcher for requesting POST_NOTIFICATIONS permission (for Android 13+)
    private val requestNotificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted: Boolean ->
        if (!isGranted) {
            Toast.makeText(this, "Permiso de notificaciones requerido para mostrar alertas.", Toast.LENGTH_LONG).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Initialize Firebase. This is crucial for FCM to work.
        FirebaseApp.initializeApp(this)

        // Create notification channel (important for Android O and above)
        createNotificationChannel()

        // Check and request permissions upon creation
        checkPermissions()

        setContent {
            // Provide the Wear OS Material Theme for the application
            StepsPerSecondTheme {
                // Main screen composable
                StepsScreen(
                    currentSteps = currentStepCount,
                    stepsPerSecond = stepsPerSecond,
                    isTracking = isTracking,
                    isSimulating = isSimulating,
                    //onToggleTracking = { toggleTracking() }, // Pass lambda for UI interaction
                    //onResetCounter = { resetCounter() }     // Pass lambda for UI interaction
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // Automatically start tracking when the app becomes visible
        // Only start if not already tracking (idempotent call)
        startTracking()
    }

    override fun onPause() {
        super.onPause()
        // Pause tracking when the app is not visible to save battery
        // This ensures the sensor listener is unregistered
        stopTracking()
    }

    /**
     * Checks if the ACTIVITY_RECOGNITION permission is granted.
     * If not, it requests the permission.
     * Also checks and requests POST_NOTIFICATIONS permission for Android 13+.
     */
    private fun checkPermissions() {
        when {
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.ACTIVITY_RECOGNITION
            ) == PackageManager.PERMISSION_GRANTED -> {
                // Permission already granted, proceed with sensor initialization
                initializeSensors()
            }
            else -> {
                // Request the permission
                requestPermissionLauncher.launch(Manifest.permission.ACTIVITY_RECOGNITION)
            }
        }

        // Check and request POST_NOTIFICATIONS permission for Android 13+ (API 33)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(
                    this,
                    Manifest.permission.POST_NOTIFICATIONS
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                requestNotificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }

    /**
     * Initializes the SensorManager and attempts to get step counter and detector sensors.
     * If no step sensors are found, it enables simulation mode.
     */
    private fun initializeSensors() {
        sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager

        stepCounterSensor = sensorManager.getDefaultSensor(Sensor.TYPE_STEP_COUNTER)
        stepDetectorSensor = sensorManager.getDefaultSensor(Sensor.TYPE_STEP_DETECTOR)

        // If no hardware step sensors are available, fall back to simulation
        if (stepCounterSensor == null && stepDetectorSensor == null) {
            Toast.makeText(this, "No se encontraron sensores de pasos, usando simulación.", Toast.LENGTH_LONG).show()
            isSimulating = true
        } else {
            isSimulating = false
        }

        // Start tracking after sensors are initialized (if not already tracking).
        // This handles cases where permission is granted late or app resumes.
        startTracking()
    }

    /**
     * Toggles the tracking state (start/stop).
     * This function is currently not called from the UI as per previous user request.
     */
    private fun toggleTracking() {
        if (isTracking) {
            stopTracking()
        } else {
            startTracking()
        }
    }

    /**
     * Starts step tracking. This method is idempotent.
     * It either starts sensor listening or step simulation.
     */
    private fun startTracking() {
        // Only proceed if not already tracking
        if (!isTracking) {
            isTracking = true
            stepHistory.clear() // Clear history on new start

            if (isSimulating) {
                startStepSimulation()
            } else {
                // Register listeners for available step sensors
                // SENSOR_DELAY_NORMAL is more battery-friendly than FASTEST for step counting
                stepCounterSensor?.let {
                    sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_NORMAL)
                }
                stepDetectorSensor?.let {
                    sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_NORMAL)
                }
            }
        }
    }

    /**
     * Stops step tracking by unregistering sensor listeners or canceling simulation.
     */
    private fun stopTracking() {
        if (isTracking) { // Only stop if currently tracking
            isTracking = false
            simulationJob?.cancel() // Cancel simulation job if running
            simulationJob = null
            // Check if sensorManager is initialized before unregistering listener
            if (::sensorManager.isInitialized) {
                sensorManager.unregisterListener(this) // Unregister all sensor listeners
            }
        }
    }

    /**
     * Resets all step-related counters and history.
     * This function is currently not called from the UI as per previous user request.
     */
    private fun resetCounter() {
        stopTracking() // Stop tracking before resetting
        currentStepCount = 0
        stepsPerSecond = 0.0
        stepHistory.clear()
        initialStepCount = -1 // Reset initial step counter value
        simulatedSteps = 0
        startTracking() // Restart tracking after reset
    }

    /**
     * Starts a coroutine to simulate step events.
     */
    private fun startStepSimulation() {
        simulationJob = lifecycleScope.launch {
            while (isTracking) {
                // Simulate realistic walking patterns with variations
                val activityVariation = when (Random.nextInt(100)) {
                    in 0..60 -> Random.nextDouble(-0.2, 0.2) // Normal walk (60%)
                    in 61..80 -> Random.nextDouble(0.3, 0.8) // Fast walk (20%)
                    in 81..90 -> Random.nextDouble(-0.4, -0.1) // Slow walk (10%)
                    else -> Random.nextDouble(1.0, 2.0) // Occasional run (10%)
                }

                val currentSpeed = (simulationSpeed + activityVariation).coerceAtLeast(0.3) // Ensure speed is at least 0.3 steps/sec

                // Calculate interval between steps with small natural variations
                val baseInterval = (1000.0 / currentSpeed).toLong()
                val intervalVariation = Random.nextLong(-50, 50) // ±50ms variation
                val intervalMs = (baseInterval + intervalVariation).coerceAtLeast(200) // Ensure interval is at least 200ms

                delay(intervalMs) // Wait for the calculated interval

                if (isTracking) {
                    simulateStep() // Simulate a step if still tracking
                }
            }
        }
    }

    /**
     * Simulates a single step event.
     */
    private fun simulateStep() {
        simulatedSteps++
        currentStepCount = simulatedSteps // Update current step count

        val currentTime = System.currentTimeMillis()
        stepHistory.add(currentTime) // Add current step timestamp to history

        // Remove steps older than the defined window size (1 second)
        stepHistory.removeAll { it < currentTime - windowSizeMs }

        // Calculate steps per second based on the number of steps in the window
        stepsPerSecond = stepHistory.size.toDouble()
    }

    /**
     * Changes the base simulation speed, clamped within a realistic range.
     * This function is not currently used in the provided UI.
     */
    private fun changeSimulationSpeed(newSpeed: Double) {
        simulationSpeed = newSpeed.coerceIn(0.5, 4.0) // Clamp speed between 0.5 and 4.0 steps/sec
    }

    /**
     * Callback for sensor events.
     */
    override fun onSensorChanged(event: SensorEvent?) {
        event?.let { sensorEvent ->
            when (sensorEvent.sensor.type) {
                Sensor.TYPE_STEP_COUNTER -> {
                    // TYPE_STEP_COUNTER gives the total number of steps since the last reboot
                    // or sensor activation. We need to calculate steps since app started.
                    if (initialStepCount == -1) {
                        initialStepCount = sensorEvent.values[0].toInt()
                    }
                    currentStepCount = sensorEvent.values[0].toInt() - initialStepCount
                }
                Sensor.TYPE_STEP_DETECTOR -> {
                    // TYPE_STEP_DETECTOR fires for each step taken.
                    val currentTime = System.currentTimeMillis()
                    stepHistory.add(currentTime) // Add current step timestamp

                    // Remove steps older than the 1-second window
                    stepHistory.removeAll { it < currentTime - windowSizeMs }

                    // Calculate steps per second
                    stepsPerSecond = stepHistory.size.toDouble()
                }
            }
        }
    }

    /**
     * Callback for sensor accuracy changes. Not used in this application.
     */
    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
        // No implementation needed for this app
    }

    /**
     * Cleans up resources when the activity is destroyed.
     */
    override fun onDestroy() {
        super.onDestroy()
        simulationJob?.cancel() // Cancel any running simulation
        // Check if sensorManager is initialized before unregistering listener
        if (::sensorManager.isInitialized) {
            sensorManager.unregisterListener(this) // Unregister sensor listeners
        }
    }

    /**
     * Creates a notification channel for Android 8.0 (Oreo) and above.
     * This is required for notifications to be displayed.
     */
    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val name = "Seguimiento de Pasos"
            val descriptionText = "Notificaciones para el seguimiento de pasos de la aplicación."
            val importance = NotificationManager.IMPORTANCE_DEFAULT
            val channel = NotificationChannel(NOTIFICATION_CHANNEL_ID, name, importance).apply {
                description = descriptionText
            }
            val notificationManager: NotificationManager =
                getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(channel)
        }
    }

    /**
     * Shows a sample notification. In a real app, this would be triggered by FCM.
     * For demonstration, you could call this, for example, when currentSteps reaches a milestone.
     */
    fun showSampleNotification(title: String, message: String) {
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }
        val pendingIntent: PendingIntent = PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE)

        val builder = NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_notification_overlay) // Using a generic Android icon for simplicity
            .setContentTitle(title)
            .setContentText(message)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setContentIntent(pendingIntent) // Set the intent to launch when the user taps the notification
            .setAutoCancel(true) // Automatically removes the notification when the user taps it

        with(NotificationManagerCompat.from(this)) {
            // For Android 13+ (API 33), POST_NOTIFICATIONS permission is required
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                if (ContextCompat.checkSelfPermission(
                        this@MainActivity, // Use this@MainActivity for context
                        Manifest.permission.POST_NOTIFICATIONS
                    ) != PackageManager.PERMISSION_GRANTED
                ) {
                    // Permission not granted, do not show notification
                    // The permission request is handled in checkPermissions()
                    return
                }
            }
            notify(NOTIFICATION_ID, builder.build())
        }
    }
}

/**
 * Defines the Wear OS Material Theme for the application.
 */
@Composable
fun StepsPerSecondTheme(content: @Composable () -> Unit) {
    // Define the color scheme for Wear OS Material Design
    val colors = Colors(
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

    MaterialTheme(
        colors = colors,
        // Typography and shapes can be customized here if needed
        content = content
    )
}

/**
 * The main screen composable that handles navigation between MainScreen and DetailsScreen.
 */
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
                    Text(
                        text = "ACTIVO",
                        fontSize = 8.sp,
                        color = Color.Green,
                        fontWeight = FontWeight.Bold
                    )
                }

                // Show "(Simulación)" text only in debug builds if simulating
                if (isSimulating && isDebug) {
                    Text(
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
                    Text(
                        text = String.format("%.1f", stepsPerSecond), // Display steps/sec with one decimal
                        fontSize = 48.sp, // Very large font size
                        fontWeight = FontWeight.Bold,
                        color = Color.White
                    )
                    Text(
                        text = "pasos/seg",
                        fontSize = 14.sp,
                        color = Color.Gray
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Page indicator/instruction
            Text(
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
 * Removed control buttons as per request.
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
            Text(
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

            // Removed the control buttons (Toggle Tracking and Reset Counter)
            // as per the user's request.

            Text(
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
        Text(
            text = label.uppercase(), // Label in uppercase
            fontSize = 10.sp,
            color = Color.Gray,
            fontWeight = FontWeight.Medium
        )
        Row(
            verticalAlignment = Alignment.CenterVertically // Align text baselines
        ) {
            Text(
                text = value,
                fontSize = 24.sp, // Larger value text
                color = color,
                fontWeight = FontWeight.Bold
            )
            if (unit.isNotEmpty()) { // Only show unit if it's not empty
                Spacer(modifier = Modifier.width(4.dp))
                Text(
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
