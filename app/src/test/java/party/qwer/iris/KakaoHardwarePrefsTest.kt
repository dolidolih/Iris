package party.qwer.iris

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.io.File

class KakaoHardwarePrefsTest {
    @get:Rule
    val temp = TemporaryFolder()

    @Test
    fun parsesStringValuesFromDataStore() {
        val bytes = preferenceMap(
            stringEntry("display_resolution", "1440x2560"),
            boolEntry("is24HourformatLocale", false),
            stringEntry("NotificationReferer", "abcDEF123/+="),
            stringEntry("d_id", "0123abcd"),
        )

        val parsed = KakaoHardwarePrefs.parseDataStoreStrings(bytes)

        assertEquals("abcDEF123/+=", parsed["NotificationReferer"])
        assertEquals("0123abcd", parsed["d_id"])
        assertEquals("1440x2560", parsed["display_resolution"])
        assertNull(parsed["is24HourformatLocale"])
    }

    @Test
    fun parsesValuesLongerThanOneVarintByte() {
        val longValue = "x".repeat(300)

        val parsed = KakaoHardwarePrefs.parseDataStoreStrings(preferenceMap(stringEntry("k", longValue)))

        assertEquals(longValue, parsed["k"])
    }

    @Test
    fun emptyDataStoreHasNoValues() {
        assertEquals(emptyMap<String, String>(), KakaoHardwarePrefs.parseDataStoreStrings(ByteArray(0)))
    }

    @Test
    fun truncatedDataStoreIsRejected() {
        val bytes = preferenceMap(stringEntry("NotificationReferer", "abcdef"))

        assertThrows(IllegalArgumentException::class.java) {
            KakaoHardwarePrefs.parseDataStoreStrings(bytes.copyOf(bytes.size - 3))
        }
    }

    @Test
    fun malformedEntryIsSkippedWithoutLosingOthers() {
        val brokenEntry = byteArrayOf(0x0a, 0x7f, 0x01)
        val bytes = lengthDelimited(1, brokenEntry) +
            preferenceMap(stringEntry("NotificationReferer", "abcdef"))

        val parsed = KakaoHardwarePrefs.parseDataStoreStrings(bytes)

        assertEquals(mapOf("NotificationReferer" to "abcdef"), parsed)
    }

    @Test
    fun entryWithValueBeforeKeyIsParsed() {
        val entry = lengthDelimited(2, lengthDelimited(5, "abcdef".toByteArray())) +
            lengthDelimited(1, "NotificationReferer".toByteArray())

        val parsed = KakaoHardwarePrefs.parseDataStoreStrings(preferenceMap(entry))

        assertEquals("abcdef", parsed["NotificationReferer"])
    }

    @Test
    fun nonStringValuesAreIgnored() {
        val doubleValue = byteArrayOf(0x39, 0, 0, 0, 0, 0, 0, 0, 0)
        val floatValue = byteArrayOf(0x15, 0, 0, 0, 0)
        val bytes = preferenceMap(
            lengthDelimited(1, "d".toByteArray()) + lengthDelimited(2, doubleValue),
            lengthDelimited(1, "f".toByteArray()) + lengthDelimited(2, floatValue),
            stringEntry("s", "kept"),
        )

        assertEquals(mapOf("s" to "kept"), KakaoHardwarePrefs.parseDataStoreStrings(bytes))
    }

    @Test
    fun unsupportedWireTypeIsRejected() {
        val startGroupTag = byteArrayOf(0x0b)

        assertThrows(IllegalArgumentException::class.java) {
            KakaoHardwarePrefs.parseDataStoreStrings(startGroupTag)
        }
    }

    @Test
    fun overlongVarintIsRejected() {
        val bytes = ByteArray(11) { 0xff.toByte() }

        assertThrows(IllegalArgumentException::class.java) {
            KakaoHardwarePrefs.parseDataStoreStrings(bytes)
        }
    }

    @Test
    fun readStringFallsBackToXmlWhenDataStoreLacksKey() {
        val appPath = appPathWith(
            dataStore = preferenceMap(stringEntry("d_id", "0123abcd")),
            xml = """<map><string name="NotificationReferer">fromXml</string></map>""",
        )

        assertEquals("fromXml", KakaoHardwarePrefs.readString(appPath, "NotificationReferer"))
        assertEquals("0123abcd", KakaoHardwarePrefs.readString(appPath, "d_id"))
    }

    @Test
    fun parsesLegacyXml() {
        val xml = """
            <?xml version='1.0' encoding='utf-8' standalone='yes' ?>
            <map>
                <string name="d_id"> 0123abcd </string>
                <string name="NotificationReferer">legacyReferer</string>
            </map>
        """.trimIndent()

        assertEquals("legacyReferer", KakaoHardwarePrefs.parseXmlString(xml, "NotificationReferer"))
        assertEquals("0123abcd", KakaoHardwarePrefs.parseXmlString(xml, "d_id"))
        assertNull(KakaoHardwarePrefs.parseXmlString(xml, "missing"))
    }

    @Test
    fun readStringPrefersDataStoreOverXml() {
        val appPath = appPathWith(
            dataStore = preferenceMap(stringEntry("NotificationReferer", "fromDataStore")),
            xml = """<map><string name="NotificationReferer">fromXml</string></map>""",
        )

        assertEquals("fromDataStore", KakaoHardwarePrefs.readString(appPath, "NotificationReferer"))
    }

    @Test
    fun readStringFallsBackToXmlOnOlderKakaoTalk() {
        val appPath = appPathWith(
            dataStore = null,
            xml = """<map><string name="NotificationReferer">fromXml</string></map>""",
        )

        assertEquals("fromXml", KakaoHardwarePrefs.readString(appPath, "NotificationReferer"))
    }

    @Test
    fun readStringFallsBackToXmlWhenDataStoreIsCorrupt() {
        val appPath = appPathWith(
            dataStore = byteArrayOf(0x0a, 0x7f, 0x01),
            xml = """<map><string name="NotificationReferer">fromXml</string></map>""",
        )

        assertEquals("fromXml", KakaoHardwarePrefs.readString(appPath, "NotificationReferer"))
    }

    @Test
    fun readStringReturnsNullWhenNothingIsStored() {
        val appPath = appPathWith(dataStore = null, xml = null)

        assertNull(KakaoHardwarePrefs.readString(appPath, "NotificationReferer"))
    }

    private fun appPathWith(dataStore: ByteArray?, xml: String?): String {
        val root = temp.newFolder()
        if (dataStore != null) {
            val file = File(root, KakaoHardwarePrefs.DATASTORE_RELATIVE_PATH)
            file.parentFile!!.mkdirs()
            file.writeBytes(dataStore)
        }
        if (xml != null) {
            val file = File(root, KakaoHardwarePrefs.XML_RELATIVE_PATH)
            file.parentFile!!.mkdirs()
            file.writeText(xml)
        }
        return root.path + "/"
    }

    // ── DataStore Preferences(protobuf) 인코딩 헬퍼 ─────────────────

    private fun preferenceMap(vararg entries: ByteArray): ByteArray =
        entries.fold(ByteArray(0)) { acc, entry -> acc + lengthDelimited(1, entry) }

    private fun stringEntry(key: String, value: String): ByteArray =
        lengthDelimited(1, key.toByteArray()) +
            lengthDelimited(2, lengthDelimited(5, value.toByteArray()))

    private fun boolEntry(key: String, value: Boolean): ByteArray =
        lengthDelimited(1, key.toByteArray()) +
            lengthDelimited(2, byteArrayOf(0x08, if (value) 1 else 0))

    private fun lengthDelimited(fieldNumber: Int, payload: ByteArray): ByteArray =
        varint((fieldNumber shl 3) or 2) + varint(payload.size) + payload

    private fun varint(value: Int): ByteArray {
        val out = ByteArrayOutputStream()
        var remaining = value
        while (remaining >= 0x80) {
            out.write((remaining and 0x7f) or 0x80)
            remaining = remaining ushr 7
        }
        out.write(remaining)
        return out.toByteArray()
    }
}
