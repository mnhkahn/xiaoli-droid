package com.cyeam.xiaoli

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.content.FileProvider
import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import org.json.JSONObject

data class UpdateInfo(
    val version: String,
    val code: Long,
    val apkUrl: String,
    val sha256: String,
    val notes: String,
)

fun parseUpdate(json: JSONObject, installedCode: Long): UpdateInfo? {
    val code = json.getLong("versionCode")
    if (code <= installedCode) return null
    val url = json.getString("apkUrl")
    require(url.startsWith("https://github.com/mnhkahn/xiaoli-droid/releases/download/")) {
        "更新地址无效"
    }
    val hash = json.getString("sha256").lowercase()
    require(hash.matches(Regex("[a-f0-9]{64}"))) { "更新校验信息无效" }
    return UpdateInfo(json.getString("versionName"), code, url, hash, json.optString("notes"))
}

class Updates(private val context: Context) {
    suspend fun check(): UpdateInfo? =
        withContext(Dispatchers.IO) {
            Api.client
                .newCall(
                    Request.Builder()
                        .url(BuildConfig.UPDATE_URL)
                        .header("Accept", "application/vnd.github+json")
                        .build()
                )
                .execute()
                .use { r ->
                    if (r.code == 404) return@withContext null
                    check(r.isSuccessful) { "暂时无法检查更新" }
                    val release = JSONObject(r.body!!.string())
                    val manifest =
                        release
                            .objects("assets")
                            .firstOrNull { it.optString("name") == "update.json" }
                            ?.getString("browser_download_url") ?: return@withContext null
                    require(
                        manifest.startsWith(
                            "https://github.com/mnhkahn/xiaoli-droid/releases/download/"
                        )
                    )
                    Api.client.newCall(Request.Builder().url(manifest).build()).execute().use { m ->
                        check(m.isSuccessful)
                        parseUpdate(
                            JSONObject(m.body!!.string()),
                            BuildConfig.VERSION_CODE.toLong(),
                        )
                    }
                }
        }

    suspend fun download(info: UpdateInfo, progress: (Int) -> Unit): File =
        withContext(Dispatchers.IO) {
            val dir = File(context.cacheDir, "updates").apply { mkdirs() }
            val file = File(dir, "xiaoli-${info.code}.apk")
            val temp = File(dir, "download.part")
            try {
                Api.client.newCall(Request.Builder().url(info.apkUrl).build()).execute().use { r ->
                    check(r.isSuccessful) { "下载失败" }
                    val body = r.body!!
                    val length = body.contentLength()
                    require(length in 1..150_000_000) { "安装包大小异常" }
                    val hash = MessageDigest.getInstance("SHA-256")
                    var count = 0L
                    var last = -1
                    body.byteStream().use { input ->
                        temp.outputStream().use { output ->
                            val buffer = ByteArray(64 * 1024)
                            while (true) {
                                val n = input.read(buffer)
                                if (n < 0) break
                                count += n
                                require(count <= 150_000_000)
                                output.write(buffer, 0, n)
                                hash.update(buffer, 0, n)
                                val p = (count * 100 / length).toInt()
                                if (p != last) {
                                    progress(p)
                                    last = p
                                }
                            }
                        }
                    }
                    check(
                        count == length &&
                            hash.digest().joinToString("") { "%02x".format(it) } == info.sha256
                    ) {
                        "安装包校验失败，请重新下载"
                    }
                }
                val pm = context.packageManager
                @Suppress("DEPRECATION")
                val archive =
                    pm.getPackageArchiveInfo(temp.path, PackageManager.GET_SIGNING_CERTIFICATES)
                        ?: error("安装包无效")
                @Suppress("DEPRECATION")
                val installed =
                    pm.getPackageInfo(context.packageName, PackageManager.GET_SIGNING_CERTIFICATES)
                check(
                    archive.packageName == context.packageName &&
                        archive.longVersionCode == info.code
                ) {
                    "安装包版本不匹配"
                }
                val accepted =
                    installed.signingInfo!!.apkContentsSigners.map { it.toCharsString() }.toSet()
                check(
                    archive.signingInfo!!.apkContentsSigners.map { it.toCharsString() }.toSet() ==
                        accepted
                ) {
                    "安装包签名不匹配"
                }
                check(temp.renameTo(file)) { "保存安装包失败" }
                file
            } finally {
                temp.delete()
            }
        }

    fun install(file: File) {
        val uri = FileProvider.getUriForFile(context, context.packageName + ".files", file)
        context.startActivity(
            Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }
}
