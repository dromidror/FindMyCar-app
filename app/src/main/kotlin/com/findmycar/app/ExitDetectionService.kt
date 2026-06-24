package com.findmycar.app

import android.app.Service
import android.content.Context
import android.content.Intent
import android.hardware.SensorManager
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import androidx.core.content.ContextCompat
import com.findmycar.app.providers.*
import com.findmycar.shared.ExitDetectionEngine
import com.google.android.gms.location.LocationServices

/**
 * Android foreground service — thin shell that delegates all logic to ExitDetectionEngine.
 *
 * Responsibilities:
 * - Create platform providers
 * - Start foreground with notification
 * - Timer for periodic engine.tick()
 * - WakeLock management
 * - Model download trigger
 */
class ExitDetectionService : Service() {

    private lateinit var engine: ExitDetectionEngine
    private lateinit var notificationProvider: AndroidNotificationProvider
    private val handler = Handler(Looper.getMainLooper())
    private var wakeLock: PowerManager.WakeLock? = null

    companion object {
        private const val NOTIFICATION_ID = 2001
        private const val TICK_INTERVAL_MS = 5000L

        const val ACTION_TOGGLE_LOGGING = "com.findmycar.app.TOGGLE_LOGGING"
        const val ACTION_USER_CAR_EXIT = "com.findmycar.app.USER_CAR_EXIT"

        fun start(context: Context) {
            // Don't start the foreground service without location permission — it will crash
            if (ContextCompat.checkSelfPermission(context, android.Manifest.permission.ACCESS_FINE_LOCATION)
                != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                return
            }
            ContextCompat.startForegroundService(context, Intent(context, ExitDetectionService::class.java))
        }
    }

    override fun onCreate() {
        super.onCreate()

        // Create providers
        val sensorManager = getSystemService(SensorManager::class.java)
        val fusedClient = LocationServices.getFusedLocationProviderClient(this)
        val prefs = getSharedPreferences("exit_detection_state", MODE_PRIVATE)

        notificationProvider = AndroidNotificationProvider(this, NOTIFICATION_ID)

        engine = ExitDetectionEngine(
            location = AndroidLocationProvider(fusedClient),
            steps = AndroidStepProvider(sensorManager),
            barometer = AndroidBarometerProvider(sensorManager),
            imu = AndroidImuProvider(sensorManager),
            compass = AndroidCompassProvider(sensorManager),
            bluetooth = AndroidBluetoothProvider(this),
            persistence = AndroidPersistenceProvider(prefs),
            notification = notificationProvider
        )

        // Start foreground
        startForeground(NOTIFICATION_ID, notificationProvider.buildNotification("Initializing..."))

        // WakeLock
        val pm = getSystemService(PowerManager::class.java)
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "FindMyCar::ExitDetection")
        wakeLock?.acquire()

        // Start engine
        engine.start()

        // Download models in background
        Thread { ModelDownloader.ensureModelsDownloaded(this) }.start()

        // Configure car Bluetooth
        val btMac = getSharedPreferences("car_bluetooth", MODE_PRIVATE).getString("car_bt_mac", null)
        if (!btMac.isNullOrBlank()) {
            (engine as? ExitDetectionEngine)?.let { /* BT already started via engine.start() */ }
        }

        // Start periodic tick
        handler.postDelayed(tickRunnable, TICK_INTERVAL_MS)
    }

    private val tickRunnable = object : Runnable {
        override fun run() {
            engine.tick()
            handler.postDelayed(this, TICK_INTERVAL_MS)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacksAndMessages(null)
        engine.stop()
        wakeLock?.release()
    }
}
