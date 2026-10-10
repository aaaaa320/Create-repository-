package temizweb.android.ui

import android.Manifest
import android.content.pm.PackageManager
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.inputmethod.EditorInfo
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.widget.doAfterTextChanged
import temizweb.android.FilterPrefs
import temizweb.android.FilterService
import temizweb.android.R
import temizweb.android.RuleLists
import temizweb.android.databinding.ActivityMainBinding
import temizweb.android.databinding.ItemPausedSiteBinding
import temizweb.android.dns.UpstreamDns
import temizweb.android.util.DomainNormalizer

/**
 * Tek ekranlık arayüz: aç/kapat, liste anahtarları, izin listesi ve sayaçlar.
 *
 * Uzantının açılır penceresiyle aynı zihinsel modeli korur: durum en üstte,
 * liste bilgileri ve duraklatılan siteler altta. Tüm metinler `strings.xml`
 * içinde Türkçedir ve koyu temayı sistemden devralır.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var prefs: FilterPrefs

    private val handler = Handler(Looper.getMainLooper())
    private var statsRunning = false

    /** render() sırasında anahtar olayları tetiklenmesin diye koruma. */
    private var suppressSwitchEvents = true

    private val vpnPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == RESULT_OK) {
                FilterService.start(this)
            } else {
                showStatusError(getString(R.string.vpn_permission_denied))
            }
            render()
        }

    private val notificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { render() }

    private val statsTicker = object : Runnable {
        override fun run() {
            renderStats()
            handler.postDelayed(this, STATS_REFRESH_MS)
        }
    }

    private val upstreamApply = Runnable { applyUpstream() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        prefs = FilterPrefs(this)

        setUpToggle()
        setUpSwitches()
        setUpPausedSites()
        setUpActions()

        suppressSwitchEvents = false
        render()
    }

    override fun onResume() {
        super.onResume()
        render()
        if (!statsRunning) {
            statsRunning = true
            handler.post(statsTicker)
        }
    }

    override fun onPause() {
        super.onPause()
        statsRunning = false
        handler.removeCallbacks(statsTicker)
        handler.removeCallbacks(upstreamApply)
    }

    // ---------------------------------------------------------------- kurulum

    private fun setUpToggle() {
        binding.toggleButton.setOnClickListener {
            if (FilterService.isRunning) {
                FilterService.stop(this)
                render()
                return@setOnClickListener
            }

            requestNotificationPermissionIfNeeded()
            val consentIntent = VpnService.prepare(this)
            if (consentIntent == null) {
                FilterService.start(this)
                render()
            } else {
                vpnPermissionLauncher.launch(consentIntent)
            }
        }
    }

    private fun setUpSwitches() {
        binding.switchTracking.setOnCheckedChangeListener { _, checked ->
            if (suppressSwitchEvents) return@setOnCheckedChangeListener
            prefs.trackingEnabled = checked
            FilterService.restart(this)
            render()
        }

        binding.switchAutoStart.setOnCheckedChangeListener { _, checked ->
            if (suppressSwitchEvents) return@setOnCheckedChangeListener
            prefs.autoStart = checked
        }

        binding.switchIpv6.setOnCheckedChangeListener { _, checked ->
            if (suppressSwitchEvents) return@setOnCheckedChangeListener
            prefs.ipv6Enabled = checked
            FilterService.restart(this)
        }

        binding.upstreamInput.doAfterTextChanged {
            handler.removeCallbacks(upstreamApply)
            handler.postDelayed(upstreamApply, UPSTREAM_DEBOUNCE_MS)
        }
        binding.upstreamInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                applyUpstream()
                true
            } else {
                false
            }
        }
    }

    private fun setUpPausedSites() {
        binding.addButton.setOnClickListener { addPausedSite() }
        binding.pausedInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                addPausedSite()
                true
            } else {
                false
            }
        }
    }

    private fun setUpActions() {
        binding.clearAllButton.setOnClickListener {
            prefs.pausedSites = emptyList()
            FilterService.updatePausedSites(this, emptyList())
            render()
        }

        binding.resetButton.setOnClickListener {
            prefs.reset()
            FilterService.updatePausedSites(this, emptyList())
            FilterService.restart(this)
            render()
        }
    }

    // ---------------------------------------------------------------- eylemler

    private fun addPausedSite() {
        val raw = binding.pausedInput.text?.toString().orEmpty()
        val domain = DomainNormalizer.normalizeDomain(raw)
        if (domain == null) {
            binding.pausedLayout.error = getString(R.string.paused_invalid)
            return
        }

        binding.pausedLayout.error = null
        val next = DomainNormalizer.sanitizePausedSites(prefs.pausedSites + domain)
        prefs.pausedSites = next
        FilterService.updatePausedSites(this, next)
        binding.pausedInput.setText("")
        render()
    }

    /** Üst DNS girişini doğrular ve uygulayarak tüneli tazeler. */
    private fun applyUpstream() {
        val raw = binding.upstreamInput.text?.toString().orEmpty()
        val parts = raw.split(',', ';', ' ', '\n').map { it.trim() }.filter { it.isNotEmpty() }

        if (parts.isEmpty()) {
            binding.upstreamLayout.error = null
            if (prefs.upstreamServers.isNotEmpty()) {
                prefs.upstreamServers = emptyList()
                FilterService.restart(this)
            }
            return
        }

        if (parts.any { !UpstreamDns.isNumericAddress(it) }) {
            binding.upstreamLayout.error = getString(R.string.upstream_error)
            return
        }

        binding.upstreamLayout.error = null
        if (parts != prefs.upstreamServers) {
            prefs.upstreamServers = parts
            FilterService.restart(this)
        }
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val granted = ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
        if (granted != PackageManager.PERMISSION_GRANTED) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    // ---------------------------------------------------------------- çizim

    private fun render() {
        val running = FilterService.isRunning

        binding.statusTitle.setText(if (running) R.string.status_running else R.string.status_stopped)
        binding.statusSubtitle.setText(
            if (running) R.string.status_subtitle_running else R.string.status_subtitle_stopped
        )
        binding.toggleButton.setText(if (running) R.string.action_disable else R.string.action_enable)

        val error = FilterService.lastError
        if (!running && error != null) {
            showStatusError("${getString(R.string.error_title)}: $error")
        } else {
            binding.errorText.visibility = View.GONE
        }

        suppressSwitchEvents = true
        binding.switchTracking.isChecked = prefs.trackingEnabled
        binding.switchAutoStart.isChecked = prefs.autoStart
        binding.switchIpv6.isChecked = prefs.ipv6Enabled
        suppressSwitchEvents = false

        binding.countAds.text = RuleLists.adCount(this).toString()
        binding.countTracking.text = RuleLists.trackingCount(this).toString()

        val paused = prefs.pausedSites
        binding.countPaused.text = paused.size.toString()
        renderPausedList(paused)
        renderStats()
    }

    private fun renderPausedList(paused: List<String>) {
        val container = binding.pausedList
        container.removeAllViews()
        binding.pausedEmpty.visibility = if (paused.isEmpty()) View.VISIBLE else View.GONE

        for (domain in paused) {
            val item = ItemPausedSiteBinding.inflate(layoutInflater, container, false)
            item.pausedDomain.text = domain

            val note = RuleLists.noteFor(this, domain)
            if (note == null) {
                item.pausedNote.visibility = View.GONE
            } else {
                item.pausedNote.visibility = View.VISIBLE
                item.pausedNote.text = getString(
                    if (note.isTracking) R.string.paused_tracking_note else R.string.paused_blocked_note,
                    note.note
                )
            }

            item.removeButton.setOnClickListener {
                val next = prefs.pausedSites.filter { it != domain }
                prefs.pausedSites = next
                FilterService.updatePausedSites(this, next)
                render()
            }
            container.addView(item.root)
        }
    }

    private fun renderStats() {
        val stats = FilterService.currentStats?.snapshot()
        if (stats == null) {
            binding.statsIdle.visibility = View.VISIBLE
            binding.statsRows.visibility = View.GONE
            return
        }

        binding.statsIdle.visibility = View.GONE
        binding.statsRows.visibility = View.VISIBLE
        binding.statQueries.text = stats.queries.toString()
        binding.statBlockedAds.text = stats.blockedAds.toString()
        binding.statBlockedTracking.text = stats.blockedTracking.toString()
        binding.statForwarded.text = stats.forwarded.toString()
        binding.statCache.text = stats.cacheHits.toString()

        val seconds = stats.uptimeMillis / 1000
        binding.statUptime.text = getString(R.string.uptime_format, seconds / 60, seconds % 60)
    }

    private fun showStatusError(message: String) {
        binding.errorText.text = message
        binding.errorText.visibility = View.VISIBLE
    }

    companion object {
        private const val STATS_REFRESH_MS = 1_000L
        private const val UPSTREAM_DEBOUNCE_MS = 800L
    }
}
