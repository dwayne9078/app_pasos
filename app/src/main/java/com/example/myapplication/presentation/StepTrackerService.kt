package com.example.myapplication.presentation

import android.Manifest
import android.R
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlin.random.Random

// Constants for broadcasting updates from service to activity
const val ACTION_STEP_UPDATE = "com.example.myapplication.STEP_UPDATE"
const val ACTION_START_TRACKING = "com.example.myapplication.START_TRACKING"
const val ACTION_STOP_TRACKING = "com.example.myapplication.STOP_TRACKING"

const val EXTRA_CURRENT_STEPS = "current_steps"
const val EXTRA_STEPS_PER_SECOND = "steps_per_second"
const val EXTRA_IS_TRACKING = "is_tracking"
const val EXTRA_IS_SIMULATING = "is_simulating"

// Notification constants (also used by the service)
const val NOTIFICATION_CHANNEL_ID = "step_tracker_channel"
const val NOTIFICATION_ID = 101 // Unique ID for our notification

/**
 * StepTrackerService runs the sensor and simulation logic in the background as a Foreground Service.
 * It broadcasts updates to the MainActivity (UI) using LocalBroadcastManager.
 */
class StepTrackerService : Service(), SensorEventListener {

    private lateinit var sensorManager: SensorManager
    private var stepCounterSensor: Sensor? = null
    private var stepDetectorSensor: Sensor? = null

    // Internal state variables for the service
    private var initialStepCount = -1
    private var currentStepCount = 0
    private var stepsPerSecond = 0.0
    private var isTracking = false
    private var isSimulating = false

    // Store timestamps of detected steps to calculate steps per second
    private var stepHistory = mutableListOf<Long>()
    private val windowSizeMs = 1000L // 1-second window for calculating steps/sec

    // Simulation variables
    private var simulationJob: Job? = null
    private var simulatedSteps = 0
    private var simulationSpeed = 1.8 // Realistic base speed (steps per second)

    // Coroutine scope for service operations, ensuring jobs are cancelled when service stops
    private val serviceScope = CoroutineScope(Dispatchers.Default + Job())

    override fun onCreate() {
        super.onCreate()
        sensorManager = getSystemService(SENSOR_SERVICE) as SensorManager
        // Create the notification channel when the service is created
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action

        when (action) {
            ACTION_START_TRACKING -> {
                // Start the service in the foreground and begin tracking logic
                startForegroundService()
                startTrackingLogic()
            }
            ACTION_STOP_TRACKING -> {
                // Stop tracking logic and stop the service
                stopTrackingLogic()
                stopSelf() // Stop the service itself
            }
        }
        // START_STICKY ensures the system will try to re-create the service if it's killed,
        // but it won't redeliver the last intent. Good for long-running background tasks.
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? {
        return null // This is a started service, not a bound service
    }

    /**
     * Starts the service in the foreground, displaying a persistent notification.
     */
    private fun startForegroundService() {
        val notification = createNotification()
        startForeground(NOTIFICATION_ID, notification)
    }

    /**
     * Creates the persistent notification displayed when the service is in the foreground.
     */
    private fun createNotification(): Notification {
        // Intent to open MainActivity when the notification is tapped
        val notificationIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }
        val pendingIntent: PendingIntent = PendingIntent.getActivity(this, 0, notificationIntent, PendingIntent.FLAG_IMMUTABLE)

        return NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setContentTitle("Seguimiento de Pasos Activo")
            .setContentText("Contando pasos en segundo plano...")
            .setSmallIcon(R.drawable.ic_notification_overlay) // Using a generic Android icon for simplicity
            .setContentIntent(pendingIntent)
            .setPriority(NotificationCompat.PRIORITY_LOW) // Use LOW priority for less obtrusive ongoing notification
            .setOngoing(true) // Makes the notification non-dismissible
            .build()
    }

    /**
     * Creates a notification channel for Android 8.0 (Oreo) and above.
     * This is required for notifications to be displayed.
     */
    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val name = "Seguimiento de Pasos"
            val descriptionText = "Notificaciones para el seguimiento de pasos de la aplicación."
            // Use IMPORTANCE_LOW for ongoing foreground service notifications
            val importance = NotificationManager.IMPORTANCE_LOW
            val channel = NotificationChannel(NOTIFICATION_CHANNEL_ID, name, importance).apply {
                description = descriptionText
            }
            val notificationManager: NotificationManager =
                getSystemService(NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(channel)
        }
    }

    /**
     * Initializes sensors and starts tracking or simulation logic.
     */
    private fun startTrackingLogic() {
        // Only proceed if not already tracking
        if (!isTracking) {
            isTracking = true
            stepHistory.clear() // Clear history on new start
            initialStepCount = -1 // Reset initial step count for hardware sensor
            currentStepCount = 0
            stepsPerSecond = 0.0
            simulatedSteps = 0

            stepCounterSensor = sensorManager.getDefaultSensor(Sensor.TYPE_STEP_COUNTER)
            stepDetectorSensor = sensorManager.getDefaultSensor(Sensor.TYPE_STEP_DETECTOR)

            // If no hardware step sensors are available, fall back to simulation
            if (stepCounterSensor == null && stepDetectorSensor == null) {
                isSimulating = true
                startStepSimulation()
            } else {
                isSimulating = false
                // Register listeners for available step sensors
                // SENSOR_DELAY_NORMAL is more battery-friendly than FASTEST for step counting
                stepCounterSensor?.let {
                    sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_NORMAL)
                }
                stepDetectorSensor?.let {
                    sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_NORMAL)
                }
            }
            sendStepUpdateBroadcast() // Send initial state to UI
        }
    }

    /**
     * Stops step tracking by unregistering sensor listeners or canceling simulation.
     */
    private fun stopTrackingLogic() {
        if (isTracking) { // Only stop if currently tracking
            isTracking = false
            simulationJob?.cancel() // Cancel simulation job if running
            simulationJob = null
            sensorManager.unregisterListener(this) // Unregister all sensor listeners
            sendStepUpdateBroadcast() // Send final state to UI
        }
    }

    /**
     * Starts a coroutine to simulate step events.
     */
    private fun startStepSimulation() {
        simulationJob = serviceScope.launch { // Use the service's CoroutineScope
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
     * Simulates a single step event and updates the state.
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

        sendStepUpdateBroadcast() // Notify UI of update
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
            sendStepUpdateBroadcast() // Notify UI of update
        }
    }

    /**
     * Callback for sensor accuracy changes. Not used in this application.
     */
    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
        // No implementation needed for this app
    }

    /**
     * Sends the current step tracking data to the MainActivity (UI) via a local broadcast.
     */
    private fun sendStepUpdateBroadcast() {
        val intent = Intent(ACTION_STEP_UPDATE).apply {
            putExtra(EXTRA_CURRENT_STEPS, currentStepCount)
            putExtra(EXTRA_STEPS_PER_SECOND, stepsPerSecond)
            putExtra(EXTRA_IS_TRACKING, isTracking)
            putExtra(EXTRA_IS_SIMULATING, isSimulating)
        }
        LocalBroadcastManager.getInstance(this).sendBroadcast(intent)
    }

    /**
     * Cleans up resources when the service is destroyed.
     */
    override fun onDestroy() {
        super.onDestroy()
        serviceScope.cancel() // Cancel all coroutines launched in this scope
        // Unregister sensor listeners if the sensorManager was initialized
        if (::sensorManager.isInitialized) {
            sensorManager.unregisterListener(this)
        }
        // Stop the foreground service and remove its notification
        stopForeground(STOP_FOREGROUND_REMOVE)
    }
}
