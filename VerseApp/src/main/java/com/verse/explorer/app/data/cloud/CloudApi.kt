package com.verse.explorer.app.data.cloud

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okio.BufferedSink
import org.json.JSONObject
import java.io.File
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/**
 * Verse 云盘客户端（手机端直连官网，不经过 PC 端的 Rust 中继）。
 *
 * 与桌面端的协议保持一致：
 * - 登录：POST https://www.verselauncher.cn/api/cloud/pc-login {uid,password}
 * - 其余：https://www.verselauncher.cn/cloudapi，Authorization: Bearer <token>
 *   GET  /overview
 *   POST /redeem {outTradeNo}
 *   POST /files/{id}/delete
 *   POST /upload（原始字节流，文件名放 x-file-name，URL 编码）
 *   GET  /files/{id}（zip 字节流）
 */

/** 登录态：token 24 小时有效，过期后服务端返回 401 */
data class CloudAccount(
    val token: String,
    val name: String,
    val uid: String
)

data class CloudFileItem(
    val id: String,
    val name: String,
    val size: Long,
    val uploadedAt: Long
)

data class CloudOrderInfo(
    val gb: Int,
    val expiresAt: Long
)

data class CloudOverview(
    val capacityBytes: Long,
    val usedBytes: Long,
    val files: List<CloudFileItem>,
    val orders: List<CloudOrderInfo>,
    val poolRemainingBytes: Long,
    val poolBytes: Long
) {
    /** 剩余可用字节；容量未知时返回 null（不做拦截） */
    val freeBytes: Long?
        get() = if (capacityBytes > 0) (capacityBytes - usedBytes).coerceAtLeast(0) else null
}

/** 云盘业务异常，message 可直接展示给用户 */
class CloudException(message: String, val status: Int = 0) : Exception(message)

/** 登录态本地持久化（与桌面端 localStorage 的字段一一对应） */
object CloudSession {

    private const val PREFS = "verse_cloud"
    private const val KEY_TOKEN = "token"
    private const val KEY_NAME = "name"
    private const val KEY_UID = "uid"

    fun load(context: Context): CloudAccount? {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val token = prefs.getString(KEY_TOKEN, null).orEmpty()
        if (token.isBlank()) return null
        return CloudAccount(
            token = token,
            name = prefs.getString(KEY_NAME, null).orEmpty(),
            uid = prefs.getString(KEY_UID, null).orEmpty()
        )
    }

    fun save(context: Context, account: CloudAccount) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_TOKEN, account.token)
            .putString(KEY_NAME, account.name)
            .putString(KEY_UID, account.uid)
            .apply()
    }

    fun clear(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .remove(KEY_TOKEN)
            .remove(KEY_NAME)
            .remove(KEY_UID)
            .apply()
    }
}

object CloudApi {

    private const val ORIGIN = "https://www.verselauncher.cn"
    private const val API = "$ORIGIN/cloudapi"

    private val jsonType: MediaType = "application/json; charset=utf-8".toMediaType()

    /** 常规小请求：短超时，快速失败 */
    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .writeTimeout(120, TimeUnit.SECONDS)
            .build()
    }

    /** 上传/下载：存档 zip 可能很大，读写超时放宽到 1 小时 */
    private val transferClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(3600, TimeUnit.SECONDS)
            .writeTimeout(3600, TimeUnit.SECONDS)
            .build()
    }

    private fun jsonBody(obj: JSONObject): RequestBody =
        obj.toString().toRequestBody(jsonType)

    private fun parse(body: String?): JSONObject =
        runCatching { JSONObject(body.orEmpty()) }.getOrDefault(JSONObject())

    private fun messageOf(j: JSONObject, code: Int): String =
        j.optString("msg").ifBlank { j.optString("error").ifBlank { "HTTP $code" } }

    /** 统一发送 cloudapi 请求，非 2xx / 401 抛 CloudException */
    private fun call(method: String, path: String, token: String, body: JSONObject?): JSONObject {
        val builder = Request.Builder()
            .url(API + path)
            .header("authorization", "Bearer $token")
        if (method == "POST") builder.post(jsonBody(body ?: JSONObject()))
        else builder.get()
        client.newCall(builder.build()).execute().use { resp ->
            val j = parse(resp.body?.string())
            if (resp.code == 401) throw CloudException("登录已过期，请重新登录", 401)
            if (!resp.isSuccessful) throw CloudException(messageOf(j, resp.code), resp.code)
            return j
        }
    }

    // ---------------- 登录 ----------------

    suspend fun login(uid: String, password: String): CloudAccount = withContext(Dispatchers.IO) {
        val payload = JSONObject().put("uid", uid).put("password", password)
        val request = Request.Builder()
            .url("$ORIGIN/api/cloud/pc-login")
            .post(jsonBody(payload))
            .build()
        client.newCall(request).execute().use { resp ->
            val j = parse(resp.body?.string())
            if (!resp.isSuccessful && !j.optBoolean("ok")) {
                throw CloudException(messageOf(j, resp.code))
            }
            if (!j.optBoolean("ok")) {
                throw CloudException(j.optString("msg").ifBlank { "登录失败，请检查 UID 和密码" })
            }
            val token = j.optString("token")
            if (token.isBlank()) throw CloudException("登录失败：服务端没有返回 token")
            CloudAccount(
                token = token,
                name = j.optString("name"),
                uid = j.optString("uid").ifBlank { uid }
            )
        }
    }

    // ---------------- 概览 ----------------

    suspend fun overview(token: String): CloudOverview = withContext(Dispatchers.IO) {
        val j = call("GET", "/overview", token, null)
        if (!j.optBoolean("ok")) throw CloudException(j.optString("msg").ifBlank { "获取云盘信息失败" })

        val files = mutableListOf<CloudFileItem>()
        j.optJSONArray("files")?.let { arr ->
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val id = o.optString("id")
                if (id.isBlank()) continue
                files += CloudFileItem(
                    id = id,
                    name = o.optString("name").ifBlank { id },
                    size = o.optLong("size", 0L),
                    uploadedAt = normalizeTimestamp(o.optLong("uploadedAt", 0L))
                )
            }
        }

        val orders = mutableListOf<CloudOrderInfo>()
        j.optJSONArray("orders")?.let { arr ->
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                orders += CloudOrderInfo(
                    gb = o.optInt("gb", 0),
                    expiresAt = normalizeTimestamp(o.optLong("expiresAt", 0L))
                )
            }
        }

        CloudOverview(
            capacityBytes = j.optLong("capacityBytes", 0L),
            usedBytes = j.optLong("usedBytes", 0L),
            files = files.sortedByDescending { it.uploadedAt },
            orders = orders,
            poolRemainingBytes = j.optLong("poolRemainingBytes", 0L),
            poolBytes = j.optLong("poolBytes", 0L)
        )
    }

    /** 服务端时间可能是秒或毫秒，统一成毫秒 */
    private fun normalizeTimestamp(ts: Long): Long = when {
        ts <= 0 -> 0L
        ts < 1_000_000_000_000L -> ts * 1000L
        else -> ts
    }

    // ---------------- 兑换 / 删除 ----------------

    suspend fun redeem(token: String, outTradeNo: String): String = withContext(Dispatchers.IO) {
        val body = JSONObject().put("outTradeNo", outTradeNo)
        val j = call("POST", "/redeem", token, body)
        if (!j.optBoolean("ok")) throw CloudException(j.optString("msg").ifBlank { "兑换失败" })
        j.optString("msg").ifBlank { "兑换成功" }
    }

    suspend fun delete(token: String, fileId: String): String = withContext(Dispatchers.IO) {
        val j = call("POST", "/files/$fileId/delete", token, null)
        if (!j.optBoolean("ok")) throw CloudException(j.optString("msg").ifBlank { "删除失败" })
        "已删除"
    }

    // ---------------- 上传 ----------------

    /**
     * 流式上传 zip。
     * @param onProgress (已发送字节, 总字节)，在 IO 线程回调，UI 直接写 state 即可。
     */
    suspend fun upload(
        token: String,
        file: File,
        name: String,
        onProgress: (Long, Long) -> Unit
    ): String = withContext(Dispatchers.IO) {
        val total = file.length()
        val encodedName = URLEncoder.encode(name, "UTF-8").replace("+", "%20")
        val body = object : RequestBody() {
            override fun contentType(): MediaType? = "application/octet-stream".toMediaType()
            override fun contentLength(): Long = total
            override fun writeTo(sink: BufferedSink) {
                var sent = 0L
                file.inputStream().use { input ->
                    val buffer = ByteArray(256 * 1024)
                    while (true) {
                        val read = input.read(buffer)
                        if (read == -1) break
                        sink.write(buffer, 0, read)
                        sent += read
                        onProgress(sent, total)
                    }
                }
            }
        }
        val request = Request.Builder()
            .url("$API/upload")
            .header("authorization", "Bearer $token")
            .header("x-file-name", encodedName)
            .post(body)
            .build()
        transferClient.newCall(request).execute().use { resp ->
            val j = parse(resp.body?.string())
            if (!resp.isSuccessful || !j.optBoolean("ok")) {
                throw CloudException(messageOf(j, resp.code), resp.code)
            }
            j.optString("msg").ifBlank { "上传成功" }
        }
    }

    // ---------------- 下载 ----------------

    /**
     * 下载 zip 到 target。
     * @param onProgress (已接收字节, 总字节；总字节未知时为 -1)
     */
    suspend fun download(
        token: String,
        fileId: String,
        target: File,
        onProgress: (Long, Long) -> Unit
    ) = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url("$API/files/$fileId")
            .header("authorization", "Bearer $token")
            .get()
            .build()
        transferClient.newCall(request).execute().use { resp ->
            if (!resp.isSuccessful) {
                val j = parse(runCatching { resp.body?.string() }.getOrNull())
                throw CloudException(messageOf(j, resp.code), resp.code)
            }
            val source = resp.body ?: throw CloudException("下载失败：响应为空")
            val total = source.contentLength()
            target.parentFile?.mkdirs()
            source.byteStream().use { input ->
                target.outputStream().use { out ->
                    val buffer = ByteArray(256 * 1024)
                    var received = 0L
                    while (true) {
                        val read = input.read(buffer)
                        if (read == -1) break
                        out.write(buffer, 0, read)
                        received += read
                        onProgress(received, total)
                    }
                }
            }
        }
    }
}
