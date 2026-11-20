package party.qwer.iris

import java.io.File

/**
 * KakaoTalk 의 "hardware" 설정값(NotificationReferer, d_id 등) 리더.
 *
 * KakaoTalk 26.10 부터 이 값들이 `shared_prefs/KakaoTalk.hw.perferences.xml` 에서
 * Jetpack DataStore 파일 `files/datastore/Hardware.preferences_pb` 로 옮겨 갔다.
 * DataStore 를 먼저 읽고, 없으면 구버전 KakaoTalk 용 XML 로 넘어간다.
 */
object KakaoHardwarePrefs {
    const val DATASTORE_RELATIVE_PATH = "files/datastore/Hardware.preferences_pb"
    const val XML_RELATIVE_PATH = "shared_prefs/KakaoTalk.hw.perferences.xml"

    private const val WIRE_VARINT = 0
    private const val WIRE_FIXED64 = 1
    private const val WIRE_LENGTH_DELIMITED = 2
    private const val WIRE_FIXED32 = 5

    /** PreferenceMap.preferences (map<string, Value>) */
    private const val FIELD_MAP_ENTRY = 1
    private const val FIELD_ENTRY_KEY = 1
    private const val FIELD_ENTRY_VALUE = 2

    /** Value.string (oneof) */
    private const val FIELD_VALUE_STRING = 5

    /**
     * [appPath] 는 `/data/data/com.kakao.talk/` 처럼 `/` 로 끝나는 KakaoTalk 데이터 경로.
     * 값을 찾지 못하면 null — 읽기 실패 원인은 stderr 에 남긴다.
     */
    fun readString(appPath: String, key: String): String? {
        val dataStore = File(appPath + DATASTORE_RELATIVE_PATH)
        readFromDataStore(dataStore, key)?.let { return it }
        val fromXml = readFromXml(File(appPath + XML_RELATIVE_PATH), key)
        if (fromXml != null && dataStore.exists()) {
            // DataStore 로 넘어간 KakaoTalk 에 구버전 XML 이 남아 있으면 값이 낡았을 수 있다.
            System.err.println(
                "KakaoHardwarePrefs: '$key' missing in ${dataStore.path}, using legacy XML value"
            )
        }
        return fromXml
    }

    /** 두 저장소 중 하나라도 읽을 수 있는 상태인가 (오류 메시지용). */
    fun describeSources(appPath: String): String =
        listOf(DATASTORE_RELATIVE_PATH, XML_RELATIVE_PATH).joinToString(", ") { relative ->
            val file = File(appPath + relative)
            "${file.path}(exists=${file.exists()}, readable=${file.canRead()})"
        }

    private fun readFromDataStore(file: File, key: String): String? {
        if (!file.exists() || !file.canRead()) return null
        return try {
            parseDataStoreStrings(file.readBytes())[key]?.takeIf { it.isNotBlank() }
        } catch (e: Exception) {
            System.err.println("KakaoHardwarePrefs: failed to read ${file.path}: ${e.message}")
            null
        }
    }

    private fun readFromXml(file: File, key: String): String? {
        if (!file.exists() || !file.canRead()) return null
        return try {
            parseXmlString(file.readText(Charsets.UTF_8), key)
        } catch (e: Exception) {
            System.err.println("KakaoHardwarePrefs: failed to read ${file.path}: ${e.message}")
            null
        }
    }

    internal fun parseXmlString(xml: String, key: String): String? {
        val regex = Regex("""<string name="${Regex.escape(key)}">\s*(.*?)\s*</string>""")
        return regex.find(xml)?.groupValues?.get(1)?.takeIf { it.isNotBlank() }
    }

    /**
     * DataStore Preferences 파일(protobuf `PreferenceMap`)에서 string 값만 뽑는다.
     *
     * ```
     * message PreferenceMap { map<string, Value> preferences = 1; }
     * message Value { oneof value { ...; string string = 5; ... } }
     * ```
     * protobuf 런타임 의존성을 늘리지 않으려고 필요한 만큼만 직접 해석한다.
     */
    internal fun parseDataStoreStrings(bytes: ByteArray): Map<String, String> {
        val result = LinkedHashMap<String, String>()
        for (entry in readFields(bytes)) {
            if (entry.number != FIELD_MAP_ENTRY || entry.payload == null) continue
            // 항목 하나가 깨져도 나머지 값은 살린다.
            val pair = try {
                parseStringEntry(entry.payload)
            } catch (e: IllegalArgumentException) {
                System.err.println("KakaoHardwarePrefs: skipping malformed entry: ${e.message}")
                null
            } ?: continue
            result[pair.first] = pair.second
        }
        return result
    }

    /** map entry 하나 → (key, string 값). string 이 아닌 값이면 null. */
    private fun parseStringEntry(entry: ByteArray): Pair<String, String>? {
        val entryFields = readFields(entry)
        val key = entryFields.lastOrNull { it.number == FIELD_ENTRY_KEY }?.payload
            ?.toString(Charsets.UTF_8) ?: return null
        val value = entryFields.lastOrNull { it.number == FIELD_ENTRY_VALUE }?.payload ?: return null
        val text = readFields(value).lastOrNull { it.number == FIELD_VALUE_STRING }?.payload
            ?.toString(Charsets.UTF_8) ?: return null
        return key to text
    }

    /** length-delimited 필드만 payload 를 갖는다. 나머지 wire type 은 건너뛴다. */
    private class Field(val number: Int, val payload: ByteArray?)

    private fun readFields(bytes: ByteArray): List<Field> {
        val fields = ArrayList<Field>()
        var offset = 0
        while (offset < bytes.size) {
            val (tag, afterTag) = readVarint(bytes, offset)
            offset = afterTag
            val number = (tag ushr 3).toInt()
            when ((tag and 0x7).toInt()) {
                WIRE_VARINT -> {
                    offset = readVarint(bytes, offset).second
                    fields.add(Field(number, null))
                }
                WIRE_FIXED64 -> {
                    offset = advance(bytes, offset, 8)
                    fields.add(Field(number, null))
                }
                WIRE_FIXED32 -> {
                    offset = advance(bytes, offset, 4)
                    fields.add(Field(number, null))
                }
                WIRE_LENGTH_DELIMITED -> {
                    val (length, afterLength) = readVarint(bytes, offset)
                    require(length in 0..Int.MAX_VALUE) { "invalid length $length at $offset" }
                    val end = advance(bytes, afterLength, length.toInt())
                    fields.add(Field(number, bytes.copyOfRange(afterLength, end)))
                    offset = end
                }
                else -> throw IllegalArgumentException("unsupported wire type in tag $tag at $offset")
            }
        }
        return fields
    }

    private fun advance(bytes: ByteArray, offset: Int, count: Int): Int {
        val end = offset + count
        require(end in offset..bytes.size) { "truncated field at $offset (need $count bytes)" }
        return end
    }

    private fun readVarint(bytes: ByteArray, start: Int): Pair<Long, Int> {
        var result = 0L
        var shift = 0
        var offset = start
        while (true) {
            require(offset < bytes.size) { "truncated varint at $start" }
            require(shift < 64) { "varint too long at $start" }
            val byte = bytes[offset].toInt()
            offset += 1
            result = result or ((byte and 0x7f).toLong() shl shift)
            if (byte and 0x80 == 0) return result to offset
            shift += 7
        }
    }
}
