package temizweb.android

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import temizweb.android.dns.DnsCache
import temizweb.android.dns.DnsTunnel
import temizweb.android.dns.DomainFilter
import temizweb.android.dns.IpPacket
import temizweb.android.dns.TunnelSettings
import temizweb.android.dns.UpstreamDns
import temizweb.android.ui.MainActivity
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ExecutorService
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/**
 * TemizWeb'in Android çekirdeği: yerel DNS filtresi olarak çalışan VPN servisi.
 *
 * Tarayıcı uzantısı istekleri `declarativeNetRequest` ile engeller; Android'de
 * bunun karşılığı, cihazın DNS sorgularını yerel bir tünel üzerinden geçirip
 * aynı alan adı listesiyle değerlendirmektir. Engellenen alan adlarına
 * NXDOMAIN döner, diğerleri ağın kendi DNS sunucusuna iletilir.
 *
 * Servis hiçbir veriyi kaydetmez veya dışarı göndermez: sorgu adları yalnızca
 * bellekte karşılaştırılır, sayaçlar toplam sayı tutar.
 */
class FilterService : VpnService() {

    private val prefs: FilterPrefs by lazy { FilterPrefs(this) }

    private var descriptor: ParcelFileDescriptor? = null
    private var filter: DomainFilter? = null
    private var tunnel: DnsTunnel? = null
    private var tunnelThread: Thread? = null
    private var workers: ExecutorService? = null
    private var upstreamServers: UpstreamServers? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
        ensureNotificationChannel()
        upstreamServers = UpstreamServers(this, prefs)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP || !prefs.enabled) {
            shutdown()
            stopSelf()
            return START_NOT_STICKY
        }

        startForegroundCompat()

        try {
            startTunnel()
            lastError = null
        } catch (error: Exception) {
            lastError = error.message ?: error.javaClass.simpleName
            Log.e(TAG, "Tünel kurulamadı", error)
            shutdown()
            stopSelf()
            return START_NOT_STICKY
        }

        // Sistem servisi öldürürse (ör. bellek baskısı) yeniden başlat.
        return START_STICKY
    }

    override fun onRevoke() {
        // Kullanıcı VPN'i sistem ayarlarından kapattı.
        shutdown()
        stopSelf()
    }

    override fun onDestroy() {
        shutdown()
        instance = null
        super.onDestroy()
    }

    // ---------------------------------------------------------------- tünel

    /** Tercihi değişen tüneli baştan kurar (liste açma/kapama, izin listesi). */
    private fun startTunnel() {
        teardownTunnel()

        val pausedSites = prefs.pausedSites
        val activeFilter: DomainFilter = RuleLists.loadFilter(this, pausedSites).also { filter = it }
        val stats = FilterStats()
        currentStats = stats

        val servers = upstreamServers ?: UpstreamServers(this, prefs).also { upstreamServers = it }
        val upstream = UpstreamDns(
            protector = UpstreamDns.SocketProtector { socket -> protect(socket) },
            serversProvider = { servers.servers() }
        )

        val tunnelDescriptor = establishDescriptor()
        descriptor = tunnelDescriptor

        val executor = createWorkers()
        workers = executor

        val dnsTunnel = DnsTunnel(
            input = FileInputStream(tunnelDescriptor.fileDescriptor),
            output = FileOutputStream(tunnelDescriptor.fileDescriptor),
            filter = activeFilter,
            cache = DnsCache(),
            upstream = upstream,
            stats = stats,
            executor = executor,
            // Ana anahtar uzantıdaki `enabled` ile aynıdır; Android'de karşılığı
            // tünelin çalışıyor olmasıdır, bu yüzden reklam listesi hep açıktır.
            settings = { TunnelSettings(adsEnabled = prefs.enabled, trackingEnabled = prefs.trackingEnabled) },
            mtu = MTU,
            blocking = true
        )
        tunnel = dnsTunnel

        val thread = Thread(dnsTunnel, TUNNEL_THREAD_NAME).apply { isDaemon = true }
        tunnelThread = thread
        thread.start()

        isRunning = true
        updateNotification()
    }

    /**
     * Tünel arayüzünü kurar.
     *
     * Yalnızca DNS sunucusunun adresi tünele yönlendirilir; tüm trafiği tünele
     * almak (0.0.0.0/0) bu uygulamada bilerek yapılmaz, çünkü o durumda her IP
     * paketini yeniden göndermek gerekirdi. Farklı ROM'larda rota doğrulaması
     * değişebildiği için iki yerleşim sırayla denenir.
     */
    private fun establishDescriptor(): ParcelFileDescriptor {
        var lastError: Exception? = null

        for (plan in TUNNEL_PLANS) {
            try {
                val builder = Builder()
                    .setSession(getString(R.string.app_name))
                    .setMtu(MTU)

                builder.addAddress(IpPacket.TUNNEL_IPV4_ADDRESS, 32)
                builder.addRoute(plan.ipv4Route, plan.ipv4Prefix)
                builder.addDnsServer(IpPacket.TUNNEL_IPV4_ADDRESS)

                if (prefs.ipv6Enabled) {
                    builder.addAddress(IpPacket.TUNNEL_IPV6_ADDRESS, 128)
                    builder.addRoute(plan.ipv6Route, plan.ipv6Prefix)
                    builder.addDnsServer(IpPacket.TUNNEL_IPV6_ADDRESS)
                }

                disallowOwnPackage(builder)

                return builder.establish() ?: throw IOException("VPN arayüzü kurulamadı")
            } catch (error: Exception) {
                lastError = error
                Log.w(TAG, "Tünel yerleşimi denenemedi: ${plan.ipv4Route}/${plan.ipv4Prefix}", error)
            }
        }

        throw IOException("VPN tüneli kurulamadı", lastError)
    }

    /**
     * Kendi paketlerimizin tünele dönmesini engeller (API 29+).
     * `protect()` her sürümde kullanıldığı için bu yalnızca ek bir güvencedir.
     */
    private fun disallowOwnPackage(builder: Builder) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return
        try {
            builder.addDisallowedApplication(packageName)
        } catch (error: Exception) {
            Log.w(TAG, "Kendi paketi hariç tutulamadı", error)
        }
    }

    private fun createWorkers(): ExecutorService = ThreadPoolExecutor(
        WORKER_THREADS,
        WORKER_THREADS,
        WORKER_KEEP_ALIVE_SECONDS,
        TimeUnit.SECONDS,
        ArrayBlockingQueue(WORKER_QUEUE_SIZE),
        { runnable -> Thread(runnable, WORKER_THREAD_NAME).apply { isDaemon = true } },
        ThreadPoolExecutor.AbortPolicy()
    )

    /**
     * İzin listesini çalışan tünele anında uygular (yeniden kurma gerekmez).
     * Tünel kapalıysa `false` döner; tercihler yine de depoya yazılmıştır.
     */
    fun applyPausedSites(sites: List<String>): Boolean {
        val activeFilter = filter ?: return false
        activeFilter.setPausedSites(sites)
        return true
    }

    private fun teardownTunnel() {
        tunnel?.stop()
        tunnelThread?.let { thread ->
            try {
                thread.join(TEARDOWN_JOIN_MILLIS)
            } catch (interrupted: InterruptedException) {
                Thread.currentThread().interrupt()
            }
        }
        tunnelThread = null
        tunnel = null
        filter = null

        workers?.shutdownNow()
        workers = null

        try {
            descriptor?.close()
        } catch (error: IOException) {
            Log.w(TAG, "Tünel kapatılamadı", error)
        }
        descriptor = null
    }

    private fun shutdown() {
        teardownTunnel()
        isRunning = false
        currentStats = null
    }

    // ---------------------------------------------------------------- bildirim

    private fun ensureNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return

        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.notification_channel_name),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = getString(R.string.notification_channel_description)
            setShowBadge(false)
        }
        manager.createNotificationChannel(channel)
    }

    private fun buildNotification(): Notification {
        val ruleCount = RuleLists.adCount(this) +
            if (prefs.trackingEnabled) RuleLists.trackingCount(this) else 0

        val openIntent = PendingIntent.getActivity(
            this,
            REQUEST_OPEN_APP,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )
        val stopActionIntent = PendingIntent.getService(
            this,
            REQUEST_STOP,
            stopIntent(this),
            PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(getString(R.string.notification_title))
            .setContentText(getString(R.string.notification_text, ruleCount))
            .setContentIntent(openIntent)
            .setColor(ContextCompat.getColor(this, R.color.accent))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .addAction(0, getString(R.string.action_stop), stopActionIntent)
            .build()
    }

    private fun startForegroundCompat() {
        ensureNotificationChannel()
        val notification = buildNotification()
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

    private fun updateNotification() {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
        try {
            manager.notify(NOTIFICATION_ID, buildNotification())
        } catch (error: RuntimeException) {
            Log.w(TAG, "Bildirim güncellenemedi", error)
        }
    }

    companion object {
        private const val TAG = "TemizWeb"
        private const val CHANNEL_ID = "temizweb-filter"
        private const val NOTIFICATION_ID = 1
        private const val REQUEST_OPEN_APP = 10
        private const val REQUEST_STOP = 11

        const val ACTION_START = "temizweb.android.action.START"
        const val ACTION_STOP = "temizweb.android.action.STOP"

        private const val MTU = 1_500
        private const val WORKER_THREADS = 4
        private const val WORKER_QUEUE_SIZE = 256
        private const val WORKER_KEEP_ALIVE_SECONDS = 30L
        private const val TEARDOWN_JOIN_MILLIS = 1_000L
        private const val TUNNEL_THREAD_NAME = "temizweb-dns-tunnel"
        private const val WORKER_THREAD_NAME = "temizweb-dns-worker"

        /**
         * Tünel yerleşimleri: DNS sunucusunun adresi her durumda tünele yönlendirilir,
         * genişlik ROM doğrulamasına takılırsa diye iki seçenek denenir.
         */
        private val TUNNEL_PLANS = listOf(
            TunnelPlan(IpPacket.TUNNEL_IPV4_ADDRESS, 32, IpPacket.TUNNEL_IPV6_ADDRESS, 128),
            TunnelPlan("10.211.211.0", 24, "fd00:ad00::", 64)
        )

        /** Filtre şu anda çalışıyor mu? (arayüz ve hızlı ayar karosu bunu okur) */
        @Volatile
        var isRunning: Boolean = false
            private set

        /** Çalışan tünelin sayaçları; tünel kapalıysa `null`. */
        @Volatile
        var currentStats: FilterStats? = null
            private set

        /** Tünel kurulurken oluşan son hata; arayüzde gösterilir. */
        @Volatile
        var lastError: String? = null
            private set

        @Volatile
        private var instance: FilterService? = null

        fun startIntent(context: Context): Intent =
            Intent(context, FilterService::class.java).setAction(ACTION_START)

        fun stopIntent(context: Context): Intent =
            Intent(context, FilterService::class.java).setAction(ACTION_STOP)

        /** Filtreyi başlatır (VPN izni daha önce verilmiş olmalıdır). */
        fun start(context: Context) {
            val intent = startIntent(context)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                ContextCompat.startForegroundService(context, intent)
            } else {
                context.startService(intent)
            }
        }

        /** Filtreyi durdurur. */
        fun stop(context: Context) {
            context.stopService(Intent(context, FilterService::class.java))
        }

        /** Çalışan tüneli güncel tercihlerle yeniden kurar. */
        fun restart(context: Context) {
            if (isRunning) start(context) else stop(context)
        }

        /** İzin listesini çalışan tünele iletir; tünel kapalıysa `false` döner. */
        fun updatePausedSites(context: Context, sites: List<String>): Boolean =
            instance?.applyPausedSites(sites) ?: false

        private class TunnelPlan(
            val ipv4Route: String,
            val ipv4Prefix: Int,
            val ipv6Route: String,
            val ipv6Prefix: Int
        )
    }
}
