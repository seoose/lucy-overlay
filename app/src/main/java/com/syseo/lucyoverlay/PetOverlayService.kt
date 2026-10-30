package com.syseo.lucyoverlay

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.content.res.Configuration
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.util.Log
import android.view.WindowManager
import androidx.core.app.NotificationCompat
import kotlin.random.Random

class PetOverlayService : Service() {
    private val handler = Handler(Looper.getMainLooper())
    private val pets = mutableListOf<FloatingPetWindow>()
    private var lastTick = 0L
    private var screenOn = true
    private var tickerRunning = false

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                Intent.ACTION_SCREEN_OFF -> {
                    screenOn = false
                    stopTicker()
                }
                Intent.ACTION_SCREEN_ON, Intent.ACTION_USER_PRESENT -> {
                    screenOn = true
                    startTickerIfNeeded()
                }
            }
        }
    }

    private val ticker = object : Runnable {
        override fun run() {
            if (!screenOn || !tickerRunning) {
                tickerRunning = false
                return
            }
            val now = System.currentTimeMillis()
            val dt = if (lastTick == 0L) 16L else (now - lastTick).coerceIn(8L, 40L)
            lastTick = now

            pets.toList().forEach { pet ->
                runCatching { pet.tick(dt) }
            }

            if (tickerRunning) {
                handler.postDelayed(this, 16L)
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        running = true
        val pm = getSystemService(PowerManager::class.java)
        screenOn = pm.isInteractive
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_USER_PRESENT)
        }
        registerReceiver(screenReceiver, filter)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                handler.post {
                    stopTicker()
                    clearPets()
                    activePetIds = emptyList()
                    running = false
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                }
                return START_NOT_STICKY
            }
            ACTION_UPDATE, ACTION_START, null -> {
                if (intent?.hasExtra(EXTRA_EDGE_ONLY) == true) {
                    edgeOnly = intent.getBooleanExtra(EXTRA_EDGE_ONLY, edgeOnly)
                }
                val ids = intent?.getStringArrayListExtra(EXTRA_PET_IDS)
                    ?: ArrayList(activePetIds.ifEmpty { listOf("chani") })
                startAsForeground()
                running = true

                val newIds = ids.distinct().filter { id -> PetCatalog.byId(id) != null }
                    .ifEmpty { listOf("chani") }
                val samePets = pets.isNotEmpty() && newIds.toSet() == activePetIds.toSet()

                if (intent?.action == ACTION_UPDATE && samePets) {
                    pets.toList().forEach { it.setEdgeOnly(edgeOnly) }
                } else {
                    stopTicker()
                    spawnPets(newIds)
                }
                startTickerIfNeeded()
            }
        }
        return START_STICKY
    }

    private fun spawnPets(ids: List<String>) {
        clearPets()
        activePetIds = ids

        val wm = getSystemService(WINDOW_SERVICE) as WindowManager
        val metrics = resources.displayMetrics
        val screenW = metrics.widthPixels.coerceAtLeast(1)
        val screenH = metrics.heightPixels.coerceAtLeast(1)

        ids.forEachIndexed { index, id ->
            try {
                val sheet = SpriteSheet.load(this, id)
                val (x, y) = spawnPoint(index, screenW, screenH)
                pets += FloatingPetWindow(this, wm, id, sheet, x, y, edgeOnly)
            } catch (t: Throwable) {
                Log.e(TAG, "Failed to spawn pet=$id", t)
            }
        }
        Log.i(TAG, "Spawned ${pets.size}/${ids.size} pets edgeOnly=$edgeOnly")
    }

    private fun spawnPoint(index: Int, screenW: Int, screenH: Int): Pair<Int, Int> {
        return if (edgeOnly) {
            when (index % 4) {
                1 -> rand(40, (screenW * 0.8f).toInt()) to 12
                2 -> 12 to rand((screenH * 0.25f).toInt(), (screenH * 0.75f).toInt())
                3 -> (screenW - 140).coerceAtLeast(0) to
                    rand((screenH * 0.25f).toInt(), (screenH * 0.75f).toInt())
                else -> rand(40, (screenW * 0.8f).toInt()) to (screenH - 180).coerceAtLeast(0)
            }
        } else {
            val yMin = (screenH * 0.2f).toInt()
            val yMax = (screenH * 0.7f).toInt()
            rand((screenW * 0.1f).toInt(), (screenW * 0.7f).toInt()) to
                rand(yMin, yMax)
        }
    }

    private fun rand(min: Int, max: Int): Int {
        val hi = max.coerceAtLeast(1)
        val lo = min.coerceIn(0, (hi - 1).coerceAtLeast(0))
        return if (lo >= hi) lo else Random.nextInt(lo, hi)
    }

    private fun clearPets() {
        val snapshot = pets.toList()
        pets.clear()
        snapshot.forEach { pet ->
            runCatching { pet.destroy() }
        }
    }

    private fun startTickerIfNeeded() {
        if (!screenOn || pets.isEmpty()) return
        if (tickerRunning) return
        tickerRunning = true
        lastTick = 0L
        handler.post(ticker)
    }

    private fun stopTicker() {
        tickerRunning = false
        handler.removeCallbacks(ticker)
    }

    private fun startAsForeground() {
        val channelId = "lucy_pets"
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(
                channelId,
                getString(R.string.notification_channel),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                setShowBadge(false)
                enableVibration(false)
                setSound(null, null)
            }
        )

        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val stop = PendingIntent.getService(
            this,
            1,
            Intent(this, PetOverlayService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification: Notification = NotificationCompat.Builder(this, channelId)
            .setContentTitle(getString(R.string.notification_title))
            .setContentText(getString(R.string.notification_text))
            .setSmallIcon(R.drawable.ic_stat_pet)
            .setContentIntent(open)
            .addAction(0, getString(R.string.stop_pets), stop)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        val metrics = resources.displayMetrics
        pets.toList().forEach {
            it.updateScreenSize(metrics.widthPixels, metrics.heightPixels)
        }
    }

    override fun onDestroy() {
        stopTicker()
        runCatching { unregisterReceiver(screenReceiver) }
        clearPets()
        activePetIds = emptyList()
        running = false
        super.onDestroy()
    }

    companion object {
        private const val TAG = "PetOverlayService"
        const val ACTION_STOP = "com.syseo.lucyoverlay.STOP"
        const val ACTION_START = "com.syseo.lucyoverlay.START"
        const val ACTION_UPDATE = "com.syseo.lucyoverlay.UPDATE"
        const val EXTRA_PET_IDS = "pet_ids"
        const val EXTRA_EDGE_ONLY = "edge_only"
        private const val NOTIFICATION_ID = 912

        @Volatile
        var running: Boolean = false
            private set

        @Volatile
        var activePetIds: List<String> = emptyList()
            private set

        @Volatile
        var edgeOnly: Boolean = false
            private set
    }
}
