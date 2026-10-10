package temizweb.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Android varlıklarının (`android/app/src/main/assets`) kural kaynağı
 * `rules/domains.json` ile eşleştiğini doğrular.
 *
 * Bu test, `node scripts/build-android-assets.mjs --check` betiğinin CI'daki
 * karşılığıdır: listeler hiçbir zaman elle kopyalanmaz.
 */
class AndroidAssetsTest {

    private val entryPattern =
        Regex("\"domain\":\\s*\"([^\"]+)\"\\s*,\\s*\"list\":\\s*\"(ads|tracking)\"")

    @Test
    fun `alan adi varliklari kural kaynagiyla eslesir`() {
        val root = repoRoot()
        val source = File(root, "rules/domains.json").readText()

        val expected = mutableMapOf("ads" to mutableListOf<String>(), "tracking" to mutableListOf<String>())
        for (match in entryPattern.findAll(source)) {
            expected.getValue(match.groupValues[2]).add(match.groupValues[1])
        }

        for ((list, file) in listOf("ads" to "ads-domains.txt", "tracking" to "tracking-domains.txt")) {
            val lines = File(root, "android/app/src/main/assets/$file")
                .readLines()
                .filter { it.isNotBlank() }

            assertTrue("$list listesi boş olmamalı", lines.isNotEmpty())
            assertEquals(expected.getValue(list).sorted(), lines.sorted())
        }
    }

    @Test
    fun `notlar dosyasi tum alan adlarini kapsar`() {
        val root = repoRoot()
        val source = File(root, "rules/domains.json").readText()
        val sourceCount = entryPattern.findAll(source).count()

        val notes = File(root, "android/app/src/main/assets/domain-notes.json").readText()
        val noteCount = Regex("\"domain\":").findAll(notes).count()

        assertEquals(sourceCount, noteCount)
        assertTrue(sourceCount > 0)
    }

    /** Test çalışma dizininden yukarı doğru depo kökünü bulur. */
    private fun repoRoot(): File {
        var directory = File(".").absoluteFile
        while (true) {
            if (File(directory, "rules/domains.json").isFile && File(directory, "android").isDirectory) {
                return directory
            }
            directory = directory.parentFile ?: break
        }
        throw AssertionError("Depo kökü bulunamadı (rules/domains.json aranıyor)")
    }
}
