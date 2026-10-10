package temizweb.android

import android.content.Context
import android.content.SharedPreferences
import temizweb.android.util.DomainNormalizer

/**
 * Kullanıcı tercihleri.
 *
 * Anahtar adları tarayıcı uzantısındaki `chrome.storage.local` anahtarlarıyla
 * aynıdır (`enabled`, `trackingEnabled`, `pausedSites`); böylece iki sürüm de
 * aynı zihinsel modeli korur. Android'e özgü üç tercih eklenmiştir:
 * açılışta başlatma, IPv6 desteği ve üst DNS sunucuları.
 */
class FilterPrefs(context: Context) {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)

    /** Genel filtre anahtarı: VPN tüneli çalışıyor mu, çalışmalı mı? */
    var enabled: Boolean
        get() = prefs.getBoolean(KEY_ENABLED, DEFAULT_ENABLED)
        set(value) {
            prefs.edit().putBoolean(KEY_ENABLED, value).apply()
        }

    /** İzleme/ölçüm listesi (uzantıda olduğu gibi varsayılan kapalı). */
    var trackingEnabled: Boolean
        get() = prefs.getBoolean(KEY_TRACKING_ENABLED, DEFAULT_TRACKING_ENABLED)
        set(value) {
            prefs.edit().putBoolean(KEY_TRACKING_ENABLED, value).apply()
        }

    /**
     * Duraklatılan siteler (izin listesi). Okunurken uzantıdaki
     * `sanitizePausedSites` ile aynı kurallarla temizlenir: geçersizler atılır,
     * yinelenenler birleşir, liste sıralanır ve [DomainNormalizer.MAX_PAUSED_SITES]
     * ile sınırlanır.
     */
    var pausedSites: List<String>
        get() = DomainNormalizer.sanitizePausedSites(
            (prefs.getString(KEY_PAUSED_SITES, "") ?: "").split('\n')
        )
        set(value) {
            val cleaned = DomainNormalizer.sanitizePausedSites(value).joinToString("\n")
            prefs.edit().putString(KEY_PAUSED_SITES, cleaned).apply()
        }

    /** Cihaz açıldığında filtreyi kendiliğinden başlat. */
    var autoStart: Boolean
        get() = prefs.getBoolean(KEY_AUTO_START, DEFAULT_AUTO_START)
        set(value) {
            prefs.edit().putBoolean(KEY_AUTO_START, value).apply()
        }

    /** IPv6 DNS sorgularını da tünelden geçir (varsayılan kapalı; README'ye bakın). */
    var ipv6Enabled: Boolean
        get() = prefs.getBoolean(KEY_IPV6_ENABLED, DEFAULT_IPV6_ENABLED)
        set(value) {
            prefs.edit().putBoolean(KEY_IPV6_ENABLED, value).apply()
        }

    /**
     * Elle yazılmış üst DNS sunucuları (IP olarak). Boş bırakılırsa cihazın
     * bağlı olduğu ağın kendi DNS sunucuları kullanılır.
     */
    var upstreamServers: List<String>
        get() = (prefs.getString(KEY_UPSTREAM_SERVERS, "") ?: "")
            .split(',')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
        set(value) {
            prefs.edit().putString(KEY_UPSTREAM_SERVERS, value.joinToString(",")).apply()
        }

    /** Tüm tercihleri ilk kurulum durumuna döndürür. */
    fun reset() {
        prefs.edit().clear().apply()
    }

    companion object {
        const val FILE_NAME = "temizweb"

        const val KEY_ENABLED = "enabled"
        const val KEY_TRACKING_ENABLED = "trackingEnabled"
        const val KEY_PAUSED_SITES = "pausedSites"
        const val KEY_AUTO_START = "autoStart"
        const val KEY_IPV6_ENABLED = "ipv6Enabled"
        const val KEY_UPSTREAM_SERVERS = "upstreamServers"

        const val DEFAULT_ENABLED = true
        const val DEFAULT_TRACKING_ENABLED = false
        const val DEFAULT_AUTO_START = false
        const val DEFAULT_IPV6_ENABLED = false
    }
}
