package com.example.captivelogin

import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.service.quicksettings.TileService

class LoginTileService : TileService() {

    companion object {
        const val ACTION_QUICK_LOGIN = "com.example.captivelogin.ACTION_QUICK_LOGIN"
    }

    override fun onClick() {
        super.onClick()

        // Start Background Login Service
        val serviceIntent = Intent(this, BackgroundLoginService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent)
        } else {
            startService(serviceIntent)
        }
    }

    override fun onStartListening() {
        super.onStartListening()
        // You could update tile state here if needed (Active/Inactive)
        val tile = qsTile
        tile.state = android.service.quicksettings.Tile.STATE_ACTIVE
        tile.updateTile()
    }
}
