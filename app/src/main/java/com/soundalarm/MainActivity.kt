package com.soundalarm

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity() {

    private lateinit var statusText: TextView
    private lateinit var infoText: TextView
    private lateinit var startButton: Button
    private lateinit var stopSoundButton: Button
    private lateinit var stopServerButton: Button

    private var isServerRunning = false
    private var isAlarmPlaying = false

    private val alarmStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                AlarmServerService.ACTION_ALARM_STARTED -> {
                    isAlarmPlaying = true
                    updateUI()
                }
                AlarmServerService.ACTION_ALARM_STOPPED -> {
                    isAlarmPlaying = false
                    updateUI()
                }
                AlarmServerService.ACTION_SERVER_STARTED -> {
                    isServerRunning = true
                    updateUI()
                }
                AlarmServerService.ACTION_SERVER_STOPPED -> {
                    isServerRunning = false
                    isAlarmPlaying = false
                    updateUI()
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        statusText = findViewById(R.id.statusText)
        infoText = findViewById(R.id.ipText)
        startButton = findViewById(R.id.startButton)
        stopSoundButton = findViewById(R.id.stopSoundButton)
        stopServerButton = findViewById(R.id.stopServerButton)

        checkPermissions()

        startButton.setOnClickListener {
            startAlarmServer()
        }

        stopSoundButton.setOnClickListener {
            stopAlarmSound()
        }

        stopServerButton.setOnClickListener {
            stopAlarmServer()
        }

        val filter = IntentFilter().apply {
            addAction(AlarmServerService.ACTION_ALARM_STARTED)
            addAction(AlarmServerService.ACTION_ALARM_STOPPED)
            addAction(AlarmServerService.ACTION_SERVER_STARTED)
            addAction(AlarmServerService.ACTION_SERVER_STOPPED)
        }
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(alarmStateReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(alarmStateReceiver, filter)
        }

        updateUI()
    }

    override fun onDestroy() {
        super.onDestroy()
        unregisterReceiver(alarmStateReceiver)
    }

    private fun checkPermissions() {
        val permissions = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
                permissions.add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
        if (permissions.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, permissions.toTypedArray(), 1)
        }
    }

    private fun startAlarmServer() {
        val intent = Intent(this, AlarmServerService::class.java).apply {
            action = AlarmServerService.ACTION_START_SERVER
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
    }

    private fun stopAlarmSound() {
        val intent = Intent(this, AlarmServerService::class.java).apply {
            action = AlarmServerService.ACTION_STOP_ALARM
        }
        startService(intent)
    }

    private fun stopAlarmServer() {
        val intent = Intent(this, AlarmServerService::class.java).apply {
            action = AlarmServerService.ACTION_STOP_SERVER
        }
        startService(intent)
    }

    private fun updateUI() {
        if (isServerRunning) {
            statusText.text = if (isAlarmPlaying) "🔊 ALARM GRAJĄCY!" else "✅ Połączono z serwerem"
            
            infoText.text = """
                🌐 Serwer: alarm-server-3aag.onrender.com
                
                Włącz alarm z dowolnego urządzenia:
                https://alarm-server-3aag.onrender.com
                
                Lub przez API:
                • /play - włącz alarm
                • /stop - wyłącz alarm
            """.trimIndent()
            
            startButton.isEnabled = false
            stopSoundButton.isEnabled = isAlarmPlaying
            stopServerButton.isEnabled = true
            stopSoundButton.text = if (isAlarmPlaying) "🔇 WYŁĄCZ DŹWIĘK" else "Dźwięk wyłączony"
        } else {
            statusText.text = "⏸️ Rozłączono"
            infoText.text = "Naciśnij START aby połączyć z serwerem"
            startButton.isEnabled = true
            stopSoundButton.isEnabled = false
            stopServerButton.isEnabled = false
        }
    }
}
