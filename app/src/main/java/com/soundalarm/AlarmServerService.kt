package com.soundalarm

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.os.Build
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.util.Log
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import com.google.android.gms.location.*
import okhttp3.*
import org.json.JSONObject
import java.util.concurrent.TimeUnit

class AlarmServerService : Service() {

    companion object {
        const val ACTION_START_SERVER = "com.soundalarm.START_SERVER"
        const val ACTION_STOP_SERVER = "com.soundalarm.STOP_SERVER"
        const val ACTION_STOP_ALARM = "com.soundalarm.STOP_ALARM"

        const val ACTION_ALARM_STARTED = "com.soundalarm.ALARM_STARTED"
        const val ACTION_ALARM_STOPPED = "com.soundalarm.ALARM_STOPPED"
        const val ACTION_SERVER_STARTED = "com.soundalarm.SERVER_STARTED"
        const val ACTION_SERVER_STOPPED = "com.soundalarm.SERVER_STOPPED"
        const val ACTION_LOCATION_STARTED = "com.soundalarm.LOCATION_STARTED"
        const val ACTION_LOCATION_STOPPED = "com.soundalarm.LOCATION_STOPPED"

        private const val CHANNEL_ID = "alarm_server_channel"
        private const val NOTIFICATION_ID = 1
        private const val SERVER_URL = "wss://alarm-server-3aag.onrender.com"
        private const val TAG = "AlarmServerService"
    }

    private var webSocket: WebSocket? = null
    private var mediaPlayer: MediaPlayer? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var isAlarmPlaying = false
    private var isLocationEnabled = false

    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private lateinit var locationCallback: LocationCallback

    // NOWE: sterowanie reconnectem
    private var shouldReconnect = false
    private var reconnectDelayMs = 5_000L
    private val maxReconnectDelayMs = 60_000L

    // ZMIANA: klient z pingInterval + retry
    private val client = OkHttpClient.Builder()
        .readTimeout(0, TimeUnit.MILLISECONDS)          // dla WebSocket
        .pingInterval(15, TimeUnit.SECONDS)             // ping co 15s
        .retryOnConnectionFailure(true)
        .build()

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        acquireWakeLock()
        setupLocationClient()
    }

    private fun setupLocationClient() {
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)

        locationCallback = object : LocationCallback() {
            override fun onLocationResult(locationResult: LocationResult) {
                locationResult.lastLocation?.let { location ->
                    sendLocationToServer(location)
                }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START_SERVER -> connectToServer()
            ACTION_STOP_SERVER -> stopServer()
            ACTION_STOP_ALARM -> stopAlarm()
        }
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        shouldReconnect = false
        stopAlarm()
        stopLocationUpdates()
        webSocket?.close(1000, "Service destroyed")
        webSocket = null
        try {
            wakeLock?.takeIf { it.isHeld }?.release()
        } catch (_: Exception) {
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Alarm Server",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Połączenie z serwerem alarmu"
                setSound(null, null)
            }
            val notificationManager = getSystemService(NotificationManager::class.java)
            notificationManager.createNotificationChannel(channel)
        }
    }

    private fun acquireWakeLock() {
        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "SoundAlarm::WebSocketWakeLock"
        ).apply {
            acquire(10 * 60 * 60 * 1000L)
        }
    }

    private fun buildNotification(status: String): Notification {
        val mainIntent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, mainIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val stopAlarmIntent = Intent(this, AlarmServerService::class.java).apply {
            action = ACTION_STOP_ALARM
        }
        val stopAlarmPendingIntent = PendingIntent.getService(
            this, 1, stopAlarmIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val title = when {
            isAlarmPlaying -> "🔊 ALARM!"
            isLocationEnabled -> "📍 Sound Alarm (GPS włączony)"
            else -> "Sound Alarm"
        }

        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(status)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)

        if (isAlarmPlaying) {
            builder.addAction(
                android.R.drawable.ic_media_pause,
                "Wyłącz alarm",
                stopAlarmPendingIntent
            )
        }

        return builder.build()
    }

    private fun updateNotification(status: String) {
        val notification = buildNotification(status)
        val notificationManager = getSystemService(NotificationManager::class.java)
        notificationManager.notify(NOTIFICATION_ID, notification)
    }

    private fun connectToServer() {
        // jeśli już mamy żywe połączenie – nic nie rób
        if (webSocket != null) {
            Log.d(TAG, "connectToServer: WebSocket już istnieje")
            return
        }

        shouldReconnect = true
        startForeground(NOTIFICATION_ID, buildNotification("Łączenie z serwerem..."))

        val request = Request.Builder()
            .url(SERVER_URL)
            .build()

        webSocket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                Log.d(TAG, "Połączono z serwerem")
                reconnectDelayMs = 5_000L // reset backoff
                updateNotification("Połączono - czekam na sygnał")
                sendBroadcast(Intent(ACTION_SERVER_STARTED))
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                Log.d(TAG, "Otrzymano: $text")
                try {
                    val json = JSONObject(text)
                    when (json.optString("action")) {
                        "play" -> playAlarm()
                        "stop" -> stopAlarm()
                        "location_on" -> startLocationUpdates()
                        "location_off" -> stopLocationUpdates()
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Błąd parsowania", e)
                }
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                Log.d(TAG, "Zamykanie: $reason")
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                Log.d(TAG, "Zamknięto: $reason")
                this@AlarmServerService.webSocket = null
                scheduleReconnect()
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                Log.e(TAG, "Błąd WebSocket", t)
                this@AlarmServerService.webSocket = null
                updateNotification("Błąd połączenia - ponawiam...")
                scheduleReconnect()
            }
        })
    }

    // NOWE: reconnect z backoffem
    private fun scheduleReconnect() {
        if (!shouldReconnect) {
            Log.d(TAG, "scheduleReconnect: shouldReconnect = false, nie łączę ponownie")
            return
        }

        val delay = reconnectDelayMs
        reconnectDelayMs = (reconnectDelayMs * 2).coerceAtMost(maxReconnectDelayMs)

        Log.d(TAG, "Ponowna próba połączenia za ${delay} ms")

        val handler = android.os.Handler(Looper.getMainLooper())
        handler.postDelayed({
            if (shouldReconnect && webSocket == null) {
                connectToServer()
            }
        }, delay)
    }

    private fun stopServer() {
        shouldReconnect = false
        stopAlarm()
        stopLocationUpdates()
        webSocket?.close(1000, "User stopped")
        webSocket = null
        sendBroadcast(Intent(ACTION_SERVER_STOPPED))
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun playAlarm() {
        if (isAlarmPlaying) return

        try {
            val audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
            val maxVolume = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
            audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, maxVolume, 0)

            mediaPlayer = MediaPlayer().apply {
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .setUsage(AudioAttributes.USAGE_ALARM)
                        .build()
                )
                val afd = resources.openRawResourceFd(R.raw.alarm_sound)
                setDataSource(afd.fileDescriptor, afd.startOffset, afd.length)
                afd.close()
                isLooping = true
                prepare()
                start()
            }

            isAlarmPlaying = true
            updateNotification("🔊 ALARM GRA!")
            sendBroadcast(Intent(ACTION_ALARM_STARTED))
        } catch (e: Exception) {
            Log.e(TAG, "Błąd odtwarzania", e)
        }
    }

    private fun stopAlarm() {
        if (!isAlarmPlaying) return

        try {
            mediaPlayer?.apply {
                if (isPlaying) stop()
                release()
            }
            mediaPlayer = null
            isAlarmPlaying = false
            updateNotification("Połączono - czekam na sygnał")
            sendBroadcast(Intent(ACTION_ALARM_STOPPED))
        } catch (e: Exception) {
            Log.e(TAG, "Błąd zatrzymania", e)
        }
    }

    private fun startLocationUpdates() {
        if (isLocationEnabled) return

        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
            != PackageManager.PERMISSION_GRANTED
        ) {
            Log.e(TAG, "Brak uprawnień do lokalizacji")
            return
        }

        val locationRequest = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 5000)
            .setWaitForAccurateLocation(false)
            .setMinUpdateIntervalMillis(3000)
            .build()

        fusedLocationClient.requestLocationUpdates(
            locationRequest,
            locationCallback,
            Looper.getMainLooper()
        )

        isLocationEnabled = true
        updateNotification("Połączono - GPS włączony")
        sendBroadcast(Intent(ACTION_LOCATION_STARTED))
        Log.d(TAG, "Lokalizacja włączona")
    }

    private fun stopLocationUpdates() {
        if (!isLocationEnabled) return

        fusedLocationClient.removeLocationUpdates(locationCallback)
        isLocationEnabled = false
        updateNotification("Połączono - czekam na sygnał")
        sendBroadcast(Intent(ACTION_LOCATION_STOPPED))
        Log.d(TAG, "Lokalizacja wyłączona")
    }

    private fun sendLocationToServer(location: Location) {
        val json = JSONObject().apply {
            put("type", "location")
            put("lat", location.latitude)
            put("lng", location.longitude)
        }
        webSocket?.send(json.toString())
        Log.d(TAG, "Wysłano lokalizację: ${location.latitude}, ${location.longitude}")
    }
}
