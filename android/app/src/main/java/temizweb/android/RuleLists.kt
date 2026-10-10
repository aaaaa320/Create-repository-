package temizweb.android

import android.content.Context
import org.json.JSONArray
import org.json.JSONException
import temizweb.android.dns.DomainFilter
import java.io.IOException

/**
 * `android/app/src/main/assets` içindeki alan adı listelerini yükler.
 *
 * Bu dosyalar elle düzenlenmez: `node scripts/build-android-assets.mjs`
 * betiği tarafından `rules/domains.json` kaynağından üretilir. Uzantı ve
 * Android uygulaması böylece her zaman aynı listeleri kullanır.
 */
object RuleLists {

    const val ADS_ASSET = "ads-domains.txt"
    const val TRACKING_ASSET = "tracking-domains.txt"
    const val NOTES_ASSET = "domain-notes.json"

    /** Bir alan adının neden listede olduğunu açıklayan kayıt. */
    data class Note(val domain: String, val list: String, val note: String) {
        val isTracking: Boolean get() = list == "tracking"
    }

    @Volatile
    private var cached: Lists? = null

    private class Lists(
        val ads: List<String>,
        val tracking: List<String>,
        val notes: Map<String, Note>
    )

    /** Listeleri varlıklardan okur ve filtre kurar. */
    fun loadFilter(context: Context, pausedSites: Iterable<String>): DomainFilter {
        val lists = lists(context)
        return DomainFilter.create(lists.ads, lists.tracking, pausedSites)
    }

    /** Reklam listesindeki alan adı sayısı. */
    fun adCount(context: Context): Int = lists(context).ads.size

    /** İzleme/ölçüm listesindeki alan adı sayısı. */
    fun trackingCount(context: Context): Int = lists(context).tracking.size

    /** Bir alan adının listedeki açıklaması; listede yoksa `null`. */
    fun noteFor(context: Context, domain: String?): Note? {
        val normalized = temizweb.android.util.DomainNormalizer.normalizeDomain(domain) ?: return null
        return lists(context).notes[normalized]
    }

    /** Listelerin güncellenmesi gerekirse (ör. sürüm yükseltmesi) önbelleği boşaltır. */
    fun invalidate() {
        cached = null
    }

    private fun lists(context: Context): Lists {
        cached?.let { return it }
        synchronized(this) {
            cached?.let { return it }
            val loaded = Lists(
                ads = readLines(context.applicationContext, ADS_ASSET),
                tracking = readLines(context.applicationContext, TRACKING_ASSET),
                notes = readNotes(context.applicationContext)
            )
            cached = loaded
            return loaded
        }
    }

    private fun readLines(context: Context, asset: String): List<String> = try {
        context.assets.open(asset).bufferedReader().use { reader -> reader.readLines() }
    } catch (error: IOException) {
        emptyList()
    }

    private fun readNotes(context: Context): Map<String, Note> {
        val text = try {
            context.assets.open(NOTES_ASSET).bufferedReader().use { reader -> reader.readText() }
        } catch (error: IOException) {
            return emptyMap()
        }

        return try {
            val array = JSONArray(text)
            val notes = HashMap<String, Note>(array.length())
            for (index in 0 until array.length()) {
                val entry = array.optJSONObject(index) ?: continue
                val domain = entry.optString("domain").lowercase()
                if (domain.isEmpty()) continue
                notes[domain] = Note(domain, entry.optString("list"), entry.optString("note"))
            }
            notes
        } catch (error: JSONException) {
            emptyMap()
        }
    }
}
