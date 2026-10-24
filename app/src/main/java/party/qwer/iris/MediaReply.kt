package party.qwer.iris

import android.content.Intent
import android.net.Uri
import android.util.Base64
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import java.io.File

/**
 * MediaReply — /reply 페이로드의 미디어 항목(image/video/audio/file) 공통 해석.
 *
 * 호환 규칙 (irispy-client):
 *  - 기존 type "image"/"image_multiple" 과 data 의 b64 문자열 형식은 그대로 허용.
 *  - 신규: 이미지 항목은 object {"name", "b64"} 로 보내 원본 파일명이 보존됨.
 *  - 신규 단일 전용 type "media"/"video"/"audio"/"file" — data 는 object 하나.
 *    (multiple 는 이미지만 허용 — 명명 규칙)
 *
 * 파일명 표시: Kakao 는 URI 의 DISPLAY_NAME(= 임시파일명)에서 이름을 따르므로,
 * 원본 이름을 그대로 disk 에 적는 것이 곧 "파일명이 제대로 보인다"의 실현이다.
 */
enum class MediaKind { IMAGE, VIDEO, AUDIO, FILE }

class MediaItem(
    val name: String,
    val mime: String,
    val kind: MediaKind,
    val bytes: ByteArray
) {
    val extension: String get() = name.substringAfterLast('.', "")
}

object MediaPayload {

    private val imageExt = setOf("jpg", "jpeg", "png", "gif", "webp", "bmp", "heic", "heif", "avif")
    private val videoExt = setOf("mp4", "mov", "avi", "mkv", "webm", "3gp", "m4v", "flv", "wmv", "ts")
    private val audioExt = setOf("mp3", "m4a", "aac", "wav", "ogg", "oga", "opus", "flac", "wma", "amr")

    private val mimeByExt: Map<String, String> = mapOf(
        "jpg" to "image/jpeg", "jpeg" to "image/jpeg", "png" to "image/png", "gif" to "image/gif",
        "webp" to "image/webp", "bmp" to "image/bmp", "heic" to "image/heic", "heif" to "image/heif",
        "avif" to "image/avif",
        "mp4" to "video/mp4", "mov" to "video/quicktime", "avi" to "video/x-msvideo",
        "mkv" to "video/x-matroska", "webm" to "video/webm", "3gp" to "video/3gpp",
        "mp3" to "audio/mpeg", "m4a" to "audio/mp4", "aac" to "audio/aac", "wav" to "audio/wav",
        "ogg" to "audio/ogg", "oga" to "audio/ogg", "opus" to "audio/opus", "flac" to "audio/flac",
        "pdf" to "application/pdf",
        "zip" to "application/zip",
        "txt" to "text/plain", "md" to "text/markdown", "json" to "application/json",
    )

    /** 응답 intent 의 MIME — 카테고리만 맞으면 Kakao 는 사진/동영상/파일 첨부 경로로 탄다. */
    fun intentType(kind: MediaKind): String = when (kind) {
        MediaKind.IMAGE -> "image/*"
        MediaKind.VIDEO -> "video/*"
        MediaKind.AUDIO -> "audio/*"
        MediaKind.FILE -> "*/*"
    }

    /**
     * image/image_multiple 해석 — b64 문자열(旧), object, 그리고 그 혼합 배열 전부.
     * 이름이 없는 항목은 과거처럼 기본명이 붙는다.
     */
    fun parseImages(data: JsonElement): List<MediaItem> {
        val raw: List<JsonElement> = when (data) {
            is JsonArray -> data.jsonArray.toList()
            else -> listOf(data)
        }
        return raw.mapNotNull { parseItem(it, MediaKind.IMAGE) }
    }

    /** media/video/audio/file — data 는 object 하나(single). 단일이미지만 허용하는 규칙. */
    fun parseSingle(data: JsonElement, fallbackKind: MediaKind): MediaItem? {
        val el = if (data is JsonArray) data.jsonArray.firstOrNull() else data
        return parseItem(el, fallbackKind)
    }

    internal fun parseItem(el: JsonElement?, fallbackKind: MediaKind): MediaItem? {
        if (el == null) return null
        if (el is JsonPrimitive) {
            val b64 = el.contentOrNull ?: return null
            val name = defaultName(fallbackKind)
            return decode(b64)?.let { MediaItem(name, mimeOf(name, fallbackKind), fallbackKind, it) }
        }
        val obj = el as? JsonObject ?: return null
        val b64 = listOf("data", "b64", "base64", "file", "content")
            .firstNotNullOfOrNull { (obj[it] as? JsonPrimitive)?.contentOrNull } ?: return null
        val bytes = decode(b64) ?: return null

        val givenName = listOf("name", "filename", "file_name")
            .firstNotNullOfOrNull { (obj[it] as? JsonPrimitive)?.contentOrNull }?.trim().orEmpty()
        val givenMime = listOf("mime", "mime_type")
            .firstNotNullOfOrNull { (obj[it] as? JsonPrimitive)?.contentOrNull }?.trim()?.lowercase().orEmpty()
        // 항목 안 "kind"/"media_type" 은 명시적 카테고리 지정자 ("image","video","audio","file")
        val kindHint = listOf("kind", "media_type")
            .firstNotNullOfOrNull { (obj[it] as? JsonPrimitive)?.contentOrNull }?.trim()?.lowercase()

        val ext = extOf(givenName)
        val kind = when {
            kindHint == "image" -> MediaKind.IMAGE
            kindHint == "video" -> MediaKind.VIDEO
            kindHint == "audio" -> MediaKind.AUDIO
            kindHint == "file" -> MediaKind.FILE
            givenMime.startsWith("image/") -> MediaKind.IMAGE
            givenMime.startsWith("video/") -> MediaKind.VIDEO
            givenMime.startsWith("audio/") -> MediaKind.AUDIO
            givenMime.isNotEmpty() && givenMime != "*/*" -> when {
                ext in imageExt -> MediaKind.IMAGE
                ext in videoExt -> MediaKind.VIDEO
                ext in audioExt -> MediaKind.AUDIO
                else -> MediaKind.FILE
            }
            ext.isNotEmpty() -> when {
                ext in imageExt -> MediaKind.IMAGE
                ext in videoExt -> MediaKind.VIDEO
                ext in audioExt -> MediaKind.AUDIO
                else -> fallbackKind
            }
            else -> fallbackKind
        }

        val name = if (givenName.isNotEmpty()) normalizeName(givenName) else defaultName(kind)
        return MediaItem(name, mimeOf(name, kind, givenMime), kind, bytes)
    }

    private fun extOf(name: String): String {
        val dot = name.lastIndexOf('.')
        return if (dot > 0) name.substring(dot + 1).lowercase() else ""
    }

    /** 이름(확장자 포함) 기준 MIME — 명시 MIME 이 있으면 우선, 없으면 확장자/카테고리 추정. */
    private fun mimeOf(name: String, kind: MediaKind, givenMime: String = ""): String {
        if (givenMime.isNotEmpty() && givenMime != "*/*") return givenMime
        val ext = extOf(name)
        mimeByExt[ext]?.let { return it }
        return when (kind) {
            MediaKind.IMAGE -> "image/$ext".ifBlank { "image/png" }
            MediaKind.VIDEO -> "video/$ext".ifBlank { "video/mp4" }
            MediaKind.AUDIO -> "audio/$ext".ifBlank { "audio/mpeg" }
            MediaKind.FILE -> "application/octet-stream"
        }
    }

    private fun defaultName(kind: MediaKind): String = when (kind) {
        MediaKind.IMAGE -> "image.png"
        MediaKind.VIDEO -> "video.mp4"
        MediaKind.AUDIO -> "audio.mp3"
        MediaKind.FILE -> "file.bin"
    }

    /**
     * 파일명 위생 — 경로 분리자/제어문자 제거, 앞 점 제거, base 길이 제한.
     */
    fun normalizeName(raw: String): String {
        var name = raw.replace('\\', '/').substringAfterLast('/').trim()
        name = name.filterNot { it.code < 32 || it == ':' || it == '*' || it == '?' ||
            it == '"' || it == '<' || it == '>' || it == '|' }
        name = name.trimStart('.', ' ').take(120)
        if (name.isEmpty()) return "untethered"
        val dot = name.lastIndexOf('.')
        val base = if (dot > 0) name.substring(0, dot).take(100) else name.take(100)
        val ext = if (dot > 0) name.substring(dot) else ""
        return base + ext
    }

    /** dir 내 충돌 회피 + 요청 내 used 집합 — 같은 이름 재사용으로 내용 유실되지 않게 회피. */
    fun uniqueName(dir: File?, used: MutableSet<String>, name: String): String {
        var candidate = name
        if ((dir != null && File(dir, candidate).exists()) || used.contains(candidate)) {
            val dot = candidate.lastIndexOf('.')
            val base = if (dot > 0) candidate.substring(0, dot) else candidate
            val ext = if (dot > 0) candidate.substring(dot) else ""
            candidate = "$base-${System.currentTimeMillis()}$ext"
        }
        used.add(candidate)
        return candidate
    }

    /** dir 아래 MediaItem 을 "이름 그대로" 적는다 — 파일명 보존의 핵심. */
    fun writeTo(dir: File, item: MediaItem, used: MutableSet<String> = HashSet()): File {
        val target = File(dir, uniqueName(dir, used, item.name))
        target.outputStream().use { it.write(item.bytes) }
        return target
    }

    /**
     * ACTION_SEND_MULTIPLE 첨부 intent 공통 생성 — key_id/key_type/direct_share 는
     * 기존 이미지 경로와 동일 플래그.
     */
    fun buildSendIntent(
        items: List<MediaItem>,
        uris: ArrayList<Uri>,
        room: Long,
        grantRead: Boolean
    ): Intent = Intent(Intent.ACTION_SEND_MULTIPLE).apply {
        setPackage("com.kakao.talk")
        type = intentType(items.firstOrNull()?.kind ?: MediaKind.IMAGE)
        putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
        putExtra("key_id", room)
        putExtra("key_type", 1)
        putExtra("key_from_direct_share", true)
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        if (grantRead) addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }

    private fun decode(b64: String): ByteArray? = runCatching {
        Base64.decode(b64.replace(" ", "").replace("\n", "").replace("\r", ""), Base64.DEFAULT)
    }.getOrNull() ?: runCatching { Base64.decode(b64, Base64.NO_WRAP) }.getOrNull()
}
