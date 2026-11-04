package ir.bazr.inspection

import android.content.ContentResolver
import android.net.Uri
import android.provider.OpenableColumns
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets
import java.util.UUID

class ApiException(val status: Int, val error: String, val details: String) : IOException("$error (HTTP $status): $details")

/** Network calls are synchronous: invoke from the Activity's background executor. */
class ApiClient(private val base: String) {
    init { require(base.startsWith("https://") && base.endsWith("/")) }

    fun post(path: String, body: JSONObject, bearer: String? = null, idempotencyKey: String? = null): JSONObject {
        val conn = open(path, "POST", bearer)
        conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
        idempotencyKey?.let { conn.setRequestProperty("Idempotency-Key", it) }
        conn.doOutput = true
        conn.outputStream.use { it.write(body.toString().toByteArray(StandardCharsets.UTF_8)) }
        return read(conn)
    }

    fun get(path: String, bearer: String): JSONObject = read(open(path, "GET", bearer))

    private fun open(path: String, method: String, bearer: String?): HttpURLConnection {
        require(path.matches(Regex("[A-Za-z0-9_/-]+\\.php")))
        val url = URL(base + path)
        val conn = (url.openConnection() as HttpURLConnection)
        conn.instanceFollowRedirects = false
        conn.requestMethod = method
        conn.connectTimeout = 15_000
        conn.readTimeout = 30_000
        conn.setRequestProperty("Accept", "application/json")
        if (!bearer.isNullOrBlank()) conn.setRequestProperty("Authorization", "Bearer $bearer")
        return conn
    }

    fun uploadEvidence(
        bearer: String, resolver: ContentResolver, uri: Uri, mission: String, question: String,
    ): JSONObject {
        val max = 12 * 1024 * 1024
        val bytes = resolver.openInputStream(uri)?.use { input ->
            val out = ByteArrayOutputStream()
            val buf = ByteArray(8192)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                if (out.size() + n > max) throw IOException("حجم فایل از ۱۲ مگابایت بیشتر است")
                out.write(buf, 0, n)
            }
            out.toByteArray()
        } ?: throw IOException("فایل انتخاب‌شده خوانده نشد")
        require(bytes.isNotEmpty()) { "فایل خالی است" }
        val mime = resolver.getType(uri) ?: "application/octet-stream"
        if (mime !in setOf("image/jpeg", "image/png", "image/webp", "application/pdf")) {
            throw IOException("نوع فایل پشتیبانی نمی‌شود: $mime")
        }
        var fileName = "evidence"
        resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) fileName = cursor.getString(0) ?: fileName
        }
        fileName = fileName.replace(Regex("[\\r\\n\"\\\\]"), "_").take(100)
        val boundary = "BAZR-" + UUID.randomUUID().toString()
        val conn = open("mobile_upload.php", "POST", bearer)
        conn.doOutput = true
        conn.setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
        fun part(name: String, value: String): ByteArray =
            ("--$boundary\r\nContent-Disposition: form-data; name=\"$name\"\r\n\r\n$value\r\n").toByteArray(StandardCharsets.UTF_8)
        val start = ByteArrayOutputStream()
        start.write(part("mission_key", mission))
        start.write(part("question_key", question))
        start.write(part("file_kind", if (mime.startsWith("image/")) "image" else "document"))
        start.write(("--$boundary\r\nContent-Disposition: form-data; name=\"file\"; filename=\"$fileName\"\r\nContent-Type: $mime\r\n\r\n").toByteArray())
        val end = "\r\n--$boundary--\r\n".toByteArray()
        conn.setFixedLengthStreamingMode(start.size() + bytes.size + end.size)
        conn.outputStream.use { stream -> stream.write(start.toByteArray()); stream.write(bytes); stream.write(end) }
        return read(conn)
    }

    private fun read(conn: HttpURLConnection): JSONObject {
        try {
            val status = conn.responseCode
            val stream = if (status in 200..299) conn.inputStream else conn.errorStream
            val response = stream?.bufferedReader(StandardCharsets.UTF_8)?.use { it.readText() } ?: ""
            val obj = try { JSONObject(response) } catch (_: Exception) { JSONObject().put("error", "invalid_server_response") }
            if (status !in 200..299 || obj.optString("error").isNotBlank()) {
                throw ApiException(status, obj.optString("error", "request_failed"), response.take(400))
            }
            return obj
        } finally { conn.disconnect() }
    }
}
