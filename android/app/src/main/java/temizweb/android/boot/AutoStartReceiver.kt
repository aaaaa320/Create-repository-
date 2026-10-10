package temizweb.android.boot

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import temizweb.android.FilterPrefs
import temizweb.android.FilterService

/**
 * "Açılışta başlat" tercihi açıksa cihaz açıldığında filtreyi kurar.
 *
 * VPN izni kalıcı olduğu için kullanıcı etkileşimi gerekmez; bazı üretici
 * yazılımları arka plandan servis başlatmayı kısıtladığı için hata sessizce
 * günlüğe yazılır ve kullanıcı filtreyi elle açabilir.
 */
class AutoStartReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        if (action != Intent.ACTION_BOOT_COMPLETED &&
            action != Intent.ACTION_LOCKED_BOOT_COMPLETED
        ) {
            return
        }

        val prefs = FilterPrefs(context)
        if (!prefs.autoStart || !prefs.enabled) return

        try {
            FilterService.start(context)
        } catch (error: RuntimeException) {
            Log.w(TAG, "Filtre açılışta başlatılamadı", error)
        }
    }

    companion object {
        private const val TAG = "TemizWebBoot"
    }
}
