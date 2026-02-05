package com.soundalarm

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.engine.*
import io.ktor.server.netty.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.coroutines.*

class AlarmServerService : Service() {

    companion object {
        const val ACTION_START_SERVER = "com.soundalarm.START_SERVER"
        const val ACTION_STOP_SERVER = "com.soundalarm.STOP_SERVER"
        const val ACTION_STOP_ALARM = "com.soundalarm.STOP_ALARM"
        const val ACTION_PLAY_ALARM = "com.soundalarm.PLAY_ALARM"
        
        const val ACTION_ALARM_STARTED = "com.soundalarm.ALARM_STARTED"
        const val ACTION_ALARM_STOPPED = "com.soundalarm.ALARM_STOPPED"
        const val ACTION_SERVER_STARTED = "com.soundalarm.SERVER_STARTED"
        const val ACTION_SERVER_STOPPED = "com.soundalarm.SERVER_STOPPED"
        
        private const val CHANNEL_ID = "alarm_server_channel"
        private const val NOTIFICATION_ID = 1
        private const val PORT = 8080
    }

    private var server: ApplicationEngine? = null
    private var mediaPlayer: MediaPlayer? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var isAlarmPlaying = false
    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        acquireWakeLock()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START_SERVER -> startServer()
            ACTION_STOP_SERVER -> stopServer()
            ACTION_STOP_ALARM -> stopAlarm()
            ACTION_PLAY_ALARM -> playAlarm()
        }
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        stopAlarm()
        server?.stop(1000, 2000)
        wakeLock?.release()
        serviceScope.cancel()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Alarm Server",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Serwer alarmu działa w tle"
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
            "SoundAlarm::ServerWakeLock"
        ).apply {
            acquire(10 * 60 * 60 * 1000L) // 10 godzin max
        }
    }

    private fun buildNotification(isPlaying: Boolean): Notification {
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

        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(if (isPlaying) "🔊 ALARM!" else "Serwer alarmu")
            .setContentText(if (isPlaying) "Dźwięk gra - naciśnij aby wyłączyć" else "Nasłuchuje na porcie $PORT")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)

        if (isPlaying) {
            builder.addAction(
                android.R.drawable.ic_media_pause,
                "Wyłącz alarm",
                stopAlarmPendingIntent
            )
        }

        return builder.build()
    }

    private fun updateNotification(isPlaying: Boolean) {
        val notification = buildNotification(isPlaying)
        val notificationManager = getSystemService(NotificationManager::class.java)
        notificationManager.notify(NOTIFICATION_ID, notification)
    }

    private fun startServer() {
        if (server != null) return

        startForeground(NOTIFICATION_ID, buildNotification(false))

        serviceScope.launch {
            try {
                server = embeddedServer(Netty, port = PORT) {
                    routing {
                        // Endpoint do włączania alarmu
                        get("/play") {
                            withContext(Dispatchers.Main) {
                                playAlarm()
                            }
                            call.respondText(
                                """{"status": "playing", "message": "Alarm włączony!"}""",
                                ContentType.Application.Json
                            )
                        }

                        // Endpoint do wyłączania alarmu
                        get("/stop") {
                            withContext(Dispatchers.Main) {
                                stopAlarm()
                            }
                            call.respondText(
                                """{"status": "stopped", "message": "Alarm wyłączony!"}""",
                                ContentType.Application.Json
                            )
                        }

                        // Endpoint statusu
                        get("/status") {
                            call.respondText(
                                """{"server": "running", "alarm": "${if (isAlarmPlaying) "playing" else "stopped"}"}""",
                                ContentType.Application.Json
                            )
                        }

                        // Domyślna strona
                        get("/") {
                            call.respondText(
                                """
                                <!DOCTYPE html>
                                <html>
                                <head>
                                    <meta name="viewport" content="width=device-width, initial-scale=1">
                                    <title>Sound Alarm</title>
                                    <style>
                                        body { font-family: Arial; text-align: center; padding: 20px; background: #1a1a2e; color: white; }
                                        button { padding: 20px 40px; margin: 10px; font-size: 18px; border-radius: 10px; border: none; cursor: pointer; }
                                        .play { background: #e94560; color: white; }
                                        .stop { background: #0f3460; color: white; }
                                        h1 { color: #e94560; }
                                    </style>
                                </head>
                                <body>
                                    <h1>🔔 Sound Alarm</h1>
                                    <p>Status: <span id="status">Sprawdzanie...</span></p>
                                    <br>
                                    <button class="play" onclick="fetch('/play').then(updateStatus)">▶️ PLAY</button>
                                    <button class="stop" onclick="fetch('/stop').then(updateStatus)">⏹️ STOP</button>
                                    <script>
                                        function updateStatus() {
                                            fetch('/status')
                                                .then(r => r.json())
                                                .then(d => document.getElementById('status').innerText = d.alarm === 'playing' ? '🔊 Gra!' : '🔇 Cisza');
                                        }
                                        updateStatus();
                                        setInterval(updateStatus, 2000);
                                    </script>
                                </body>
                                </html>
                                """.trimIndent(),
                                ContentType.Text.Html
                            )
                        }
                    }
                }.start(wait = false)

                // Powiadom UI że serwer wystartował
                sendBroadcast(Intent(ACTION_SERVER_STARTED))
                
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    private fun stopServer() {
        stopAlarm()
        server?.stop(1000, 2000)
        server = null
        sendBroadcast(Intent(ACTION_SERVER_STOPPED))
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun playAlarm() {
        if (isAlarmPlaying) return

        try {
            // Ustaw głośność na max
            val audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
            val maxVolume = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
            audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, maxVolume, 0)

            // Odtwórz dźwięk w pętli
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
            updateNotification(true)
            sendBroadcast(Intent(ACTION_ALARM_STARTED))
            
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun stopAlarm() {
        if (!isAlarmPlaying) return

        try {
            mediaPlayer?.apply {
                if (isPlaying) {
                    stop()
                }
                release()
            }
            mediaPlayer = null
            
            isAlarmPlaying = false
            updateNotification(false)
            sendBroadcast(Intent(ACTION_ALARM_STOPPED))
            
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}
