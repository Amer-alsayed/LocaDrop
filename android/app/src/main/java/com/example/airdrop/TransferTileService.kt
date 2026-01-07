package com.example.airdrop

import android.content.Intent
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.os.Build

class TransferTileService : TileService() {

    override fun onStartListening() {
        super.onStartListening()
        // Initialize NetworkManager if not already done
        NetworkManager.initialize(applicationContext)
        updateTileState()
    }

    override fun onClick() {
        super.onClick()
        // Initialize NetworkManager if not already done
        NetworkManager.initialize(applicationContext)
        
        val tile = qsTile
        val isRunning = NetworkManager.isDiscoverable.value
        
        if (isRunning) {
            // Stop Service
            val intent = Intent(this, TransferService::class.java)
            stopService(intent)
            NetworkManager.stop() 
            tile.state = Tile.STATE_INACTIVE
        } else {
            // Start Service
            val intent = Intent(this, TransferService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(intent)
            } else {
                startService(intent)
            }
            // NetworkManager.start() is called in Service.onCreate
            tile.state = Tile.STATE_ACTIVE
        }
        tile.updateTile()
    }

    private fun updateTileState() {
        val tile = qsTile ?: return
        val isRunning = NetworkManager.isDiscoverable.value
        if (isRunning) {
            tile.state = Tile.STATE_ACTIVE
        } else {
            tile.state = Tile.STATE_INACTIVE
        }
        tile.updateTile()
    }
}

