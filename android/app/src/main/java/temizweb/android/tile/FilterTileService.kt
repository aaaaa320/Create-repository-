package temizweb.android.tile

import android.app.PendingIntent
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import temizweb.android.FilterService
import temizweb.android.R

/**
 * Hızlı ayarlar karosu: bildirim gölgesinden tek dokunuşla aç/kapat.
 *
 * Karoya dokunmak kullanıcı etkileşimi sayıldığı için Android 12+ arka plan
 * servis kısıtlarına takılmaz; VPN izni henüz verilmemişse sistem istemi
 * etkinlik üzerinden açılır.
 */
class FilterTileService : TileService() {

    override fun onStartListening() {
        super.onStartListening()
        updateTile()
    }

    override fun onClick() {
        if (FilterService.isRunning) {
            FilterService.stop(this)
            updateTile()
            return
        }

        val consentIntent = VpnService.prepare(this)
        if (consentIntent != null) {
            openConsent(consentIntent)
            return
        }

        FilterService.start(this)
        updateTile()
    }

    private fun openConsent(intent: Intent) {
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            val pending = PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE)
            startActivityAndCollapse(pending)
        } else {
            @Suppress("DEPRECATION")
            startActivityAndCollapse(intent)
        }
    }

    private fun updateTile() {
        val tile = qsTile ?: return
        val active = FilterService.isRunning

        tile.state = if (active) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            tile.subtitle = getString(if (active) R.string.status_running else R.string.status_stopped)
        }
        tile.updateTile()
    }
}
