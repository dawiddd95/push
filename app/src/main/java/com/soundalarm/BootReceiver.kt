package com.soundalarm

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build

class BootReceiver : BroadcastReceiver() {
    
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            // Sprawdź czy użytkownik włączył auto-start (możesz dodać SharedPreferences)
            val serviceIntent = Intent(context, AlarmServerService::class.java).apply {
                action = AlarmServerService.ACTION_START_SERVER
            }
            
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(serviceIntent)
            } else {
                context.startService(serviceIntent)
            }
        }
    }
}
