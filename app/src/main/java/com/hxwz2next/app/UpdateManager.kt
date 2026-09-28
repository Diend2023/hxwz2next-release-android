package com.hxwz2next.app

import android.content.Context
import android.content.res.AssetManager
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.URLEncoder
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** 更新过程状态，供界面展示。 */
data class UpdateState(
    val phase: Phase,
    val current: Int = 0,
    val total: Int = 0,
    val message: String = ""
) {
    enum class Phase { INIT, CHECKING, DOWNLOADING, DONE, FAILED }
}

/**
 * 热更新管理器（幻想纹章新篇）。
 * 协议与热更仓库 hxwz2-release 及游戏端 GameResourceUpdater 保持一致：
 *
 * 1. 首次启动：将 APK assets/www 基线复制到外部私有目录（getExternalFilesDir/web），
 *    其中随包 md5s.json 一并就位作为本地期望清单；随后**优先解压随包 app.zip**
 *    生成游戏框架（AnimeBattleNext.js、assets/、editerAssets/ 等），完成后删除 zip；
 * 2. 常规启动：拉取远程 md5s.json，与本地对比做差异收敛（差异覆盖、不删除多余文件）；
 * 3. 清单键到本地路径需经 [keyToLocalPath] 映射（abn/→assets/roles/ 等 4 条规则），
 *    与游戏端 GameResourceUpdater.keyToLocalPath 完全一致；
 * 4. app.zip 键变化时整体下载并覆盖解压（框架结构维护），失败保留旧框架不中断；
 * 5. 下载校验 MD5，失败重试后跳过，不中断整体。
 *
 * 约束：index.html / favicon.png 完全豁免——不下载、不覆盖、不删除，始终使用 APK 内置版。
 */
class UpdateManager(private val context: Context) {

    companion object {
        private const val TAG = "UpdateManager"

        /** CNB 仓库 raw 基地址（路径必须带 /-/git/raw/ 段）。 */
        const val BASE_URL = "https://cnb.cool/anime-battle-next/hxwz2-release/-/git/raw/main/"

        /** 远程/本地清单文件名。 */
        private const val MD5_FILE = "md5s.json"

        /** 框架更新包名（与游戏端 GameResourceUpdater.APP_ZIP_NAME 一致）。 */
        private const val APP_ZIP = "app.zip"

        /** 豁免文件白名单：永不参与热更新。 */
        private val LOCKED_FILES = setOf("index.html", "favicon.png")

        /** 下载失败最大重试次数。 */
        private const val MAX_RETRY = 2

        /** 并发下载数。 */
        private const val CONCURRENCY = 6

        /**
         * 解析游戏资源根目录（外部存储私有目录 files/web 的镜像）。
         * 优先使用外部私有目录（无需存储权限，卸载自动清除），
         * 外部存储不可用时回退到内部 files/web。
         */
        fun resolveWebDir(context: Context): File {
            val ext = context.getExternalFilesDir(null)
            return if (ext != null) File(ext, "web") else File(context.filesDir, "web")
        }

        /**
         * 清单键 → 本地相对路径，与游戏端 GameResourceUpdater.keyToLocalPath 完全一致。
         * 注意 maps → assets/map（单数）。
         */
        fun keyToLocalPath(key: String): String = when {
            key.startsWith("abn/") -> "assets/roles/" + key.substring("abn/".length)
            key.startsWith("icons/") -> "assets/icons/" + key.substring("icons/".length)
            key.startsWith("maps/") -> "assets/map/" + key.substring("maps/".length)
            key.startsWith("sounds/") -> "assets/sounds/" + key.substring("sounds/".length)
            else -> key
        }
    }

    private val webDir: File = resolveWebDir(context)

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .build()

    /** 执行更新流程，[onState] 在 UI 线程回调。 */
    suspend fun run(onState: (UpdateState) -> Unit) {
        withContext(Dispatchers.IO) {
            try {
                // 1) 首次启动：基线初始化（含优先解压 app.zip）
                if (!File(webDir, "index.html").exists()) {
                    post(onState, UpdateState(UpdateState.Phase.INIT, message = "初始化本地资源…"))
                    initBaseline { done, total, msg ->
                        post(onState, UpdateState(UpdateState.Phase.INIT, done, total, msg))
                    }
                } else {
                    // 已初始化：豁免文件始终同步为 APK 内置版（APK 升级后即生效）
                    syncLockedFiles()
                }

                // 2) 拉取远程清单
                post(onState, UpdateState(UpdateState.Phase.CHECKING, message = "检查更新…"))
                val remoteMd5s = fetchRemoteMd5s()
                    ?: throw IOException("无法获取远程更新清单 md5s.json")

                // 3) 差异计算
                val localMd5s = readLocalMd5s()
                val fileTasks = ArrayList<Pair<String, String>>() // (清单键, 期望MD5)
                var appZipChanged = false
                val keys = remoteMd5s.keys()
                while (keys.hasNext()) {
                    val key = keys.next()
                    if (LOCKED_FILES.contains(key)) continue
                    val remoteMd5 = remoteMd5s.optString(key, "")
                    if (key == APP_ZIP) {
                        // zip 键只对比清单 md5（本地不保留 zip 文件，与游戏端一致）
                        if (localMd5s.optString(key, null) != remoteMd5) appZipChanged = true
                        continue
                    }
                    val localFile = File(webDir, keyToLocalPath(key))
                    // 本地清单 MD5 与远程一致且文件存在 → 视为已就绪
                    if (localFile.exists() && localMd5s.optString(key, null) == remoteMd5) continue
                    fileTasks.add(key to remoteMd5)
                }

                // 4) app.zip 框架更新（优先执行，失败保留旧框架）
                var appZipOk = true
                if (appZipChanged) {
                    val appZipMd5 = remoteMd5s.optString(APP_ZIP, "")
                    post(onState, UpdateState(UpdateState.Phase.DOWNLOADING, message = "下载框架更新包…"))
                    appZipOk = updateAppZip(appZipMd5)
                    if (!appZipOk) Log.w(TAG, "app.zip 更新失败（保留旧框架继续）")
                }

                // 5) 增量下载资源文件
                if (fileTasks.isNotEmpty()) {
                    post(onState, UpdateState(UpdateState.Phase.DOWNLOADING, 0, fileTasks.size, "下载更新 0/${fileTasks.size}"))
                    val done = AtomicInteger(0)
                    val semaphore = Semaphore(CONCURRENCY)
                    coroutineScope {
                        fileTasks.map { (key, md5) ->
                            async(Dispatchers.IO) {
                                semaphore.withPermit {
                                    val ok = downloadWithRetry(key, md5)
                                    val n = done.incrementAndGet()
                                    post(onState, UpdateState(UpdateState.Phase.DOWNLOADING, n, fileTasks.size, "下载更新 $n/${fileTasks.size}"))
                                    if (!ok) {
                                        Log.w(TAG, "跳过失败文件: $key")
                                    }
                                }
                            }
                        }.awaitAll()
                    }
                }

                // 6) 收敛完成后写入本地期望清单（app.zip 失败时保留旧值，下次重试）
                saveLocalMd5s(remoteMd5s, localMd5s, keepAppZip = !appZipOk)
                post(onState, UpdateState(UpdateState.Phase.DONE, fileTasks.size, fileTasks.size, "更新完成"))
            } catch (e: CancellationException) {
                // 用户跳过：协程正常取消，不当作失败
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "update failed", e)
                post(onState, UpdateState(UpdateState.Phase.FAILED, message = e.message ?: "更新失败"))
            }
        }
    }

    // ------------------------------------------------------------------
    // 首次启动：基线初始化
    // ------------------------------------------------------------------

    /**
     * 初始化基线：
     * 1) 复制 APK assets/www 全部文件（app.zip 除外）到 webDir，随包 md5s.json 一并就位；
     * 2) 优先解压随包 app.zip 生成游戏框架，完成后删除 zip。
     */
    private suspend fun initBaseline(onProgress: suspend (Int, Int, String) -> Unit) {
        val am = context.assets
        val files = ArrayList<String>()
        // 收集相对 web 根的路径
        collectAssets(am, "www", "", files)
        webDir.mkdirs()

        var done = 0
        for (rel in files) {
            if (rel == APP_ZIP) continue // 框架包单独解压处理
            val target = File(webDir, rel)
            target.parentFile?.mkdirs()
            // assets 中实际路径需带 www/ 前缀
            am.open("www/$rel").use { input ->
                FileOutputStream(target).use { out -> input.copyTo(out) }
            }
            done++
            onProgress(done, files.size, "初始化本地资源 $done/${files.size}")
        }

        // 优先解压 app.zip（游戏框架：AnimeBattleNext.js、assets/、editerAssets/ 等）
        onProgress(done, files.size, "解压框架包 app.zip…")
        am.open("www/$APP_ZIP").use { input ->
            ZipUtils.extract(input, webDir)
        }
        // 与游戏端行为一致：解压完成后删除 zip 文件
        File(webDir, APP_ZIP).delete()
        onProgress(files.size, files.size, "初始化完成")
    }

    private fun collectAssets(am: AssetManager, assetsDir: String, relDir: String, out: MutableList<String>) {
        val entries = am.list(assetsDir) ?: return
        for (name in entries) {
            val assetsPath = if (assetsDir.isEmpty()) name else "$assetsDir/$name"
            val relPath = if (relDir.isEmpty()) name else "$relDir/$name"
            // 目录的 list 返回子项；文件返回空数组
            val sub = am.list(assetsPath)
            if (sub != null && sub.isNotEmpty()) {
                collectAssets(am, assetsPath, relPath, out)
            } else {
                out.add(relPath)
            }
        }
    }

    /**
     * 豁免文件（index.html / favicon.png）以 APK 内置版为准。
     * 每次启动覆盖同步，保证 APK 升级后豁免文件即时生效。
     * 仅在已初始化后调用，避免破坏首次初始化判断。
     */
    private fun syncLockedFiles() {
        val am = context.assets
        for (name in LOCKED_FILES) {
            try {
                val target = File(webDir, name)
                target.parentFile?.mkdirs()
                am.open("www/$name").use { input ->
                    FileOutputStream(target).use { out -> input.copyTo(out) }
                }
            } catch (e: Exception) {
                Log.w(TAG, "同步豁免文件失败: $name", e)
            }
        }
    }

    // ------------------------------------------------------------------
    // app.zip 框架更新
    // ------------------------------------------------------------------

    /**
     * 下载并覆盖解压 app.zip（对应游戏端 downloadAndExtractAppZip）。
     * 成功后立即将远程 md5 写入本地清单（防跳过后重复下载）。
     */
    private fun updateAppZip(expectedMd5: String): Boolean {
        val tmp = File(webDir, "$APP_ZIP.tmp")
        try {
            downloadFile(APP_ZIP, tmp)
            if (FileHasher.md5File(tmp) != expectedMd5) {
                Log.w(TAG, "app.zip MD5 校验失败")
                return false
            }
            // 覆盖解压（unzip -o 语义），维护框架目录结构；不删除框架旧文件
            ZipUtils.extract(tmp, webDir)
            updateLocalMd5Key(APP_ZIP, expectedMd5)
            return true
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "app.zip 下载/解压失败", e)
            return false
        } finally {
            tmp.delete()
        }
    }

    // ------------------------------------------------------------------
    // 清单对比
    // ------------------------------------------------------------------

    private fun fetchRemoteMd5s(): JSONObject? {
        val request = Request.Builder().url(BASE_URL + MD5_FILE).build()
        httpClient.newCall(request).execute().use { resp ->
            if (!resp.isSuccessful) return null
            val body = resp.body?.string() ?: return null
            return runCatching { JSONObject(stripBom(body)) }
                .onFailure { Log.w(TAG, "远程 md5s.json 解析失败", it) }
                .getOrNull()
        }
    }

    private fun readLocalMd5s(): JSONObject {
        val file = File(webDir, MD5_FILE)
        return if (file.exists()) {
            runCatching { JSONObject(stripBom(file.readText())) }.getOrElse {
                Log.w(TAG, "本地 md5s.json 解析失败，按空清单处理", it)
                JSONObject()
            }
        } else {
            JSONObject()
        }
    }

    /** 兼容带 UTF-8 BOM 的清单文本。 */
    private fun stripBom(text: String): String =
        if (text.isNotEmpty() && text[0] == '\uFEFF') text.substring(1) else text

    /**
     * 写入本地期望清单：
     * - 远程清单全量收录；
     * - 本地清单中远程已不存在的键保留（只增不删）；
     * - app.zip 更新失败时保留旧记录，下次启动重试。
     */
    private fun saveLocalMd5s(remote: JSONObject, local: JSONObject, keepAppZip: Boolean) {
        try {
            val obj = JSONObject()
            val rk = remote.keys()
            while (rk.hasNext()) {
                val k = rk.next()
                obj.put(k, remote.get(k))
            }
            val lk = local.keys()
            while (lk.hasNext()) {
                val k = lk.next()
                if (!obj.has(k)) obj.put(k, local.get(k))
            }
            if (keepAppZip && local.has(APP_ZIP)) {
                obj.put(APP_ZIP, local.get(APP_ZIP))
            }
            File(webDir, MD5_FILE).writeText(obj.toString())
        } catch (e: Exception) {
            Log.w(TAG, "保存本地清单失败", e)
        }
    }

    /** 单键更新本地清单（读-改-写）。 */
    private fun updateLocalMd5Key(key: String, value: String) {
        try {
            val file = File(webDir, MD5_FILE)
            val obj = if (file.exists()) {
                runCatching { JSONObject(stripBom(file.readText())) }.getOrElse { JSONObject() }
            } else {
                JSONObject()
            }
            obj.put(key, value)
            file.writeText(obj.toString())
        } catch (e: Exception) {
            Log.w(TAG, "更新本地清单键失败: $key", e)
        }
    }

    // ------------------------------------------------------------------
    // 增量下载
    // ------------------------------------------------------------------

    private fun downloadWithRetry(key: String, expectedMd5: String): Boolean {
        // URL 使用远程键，本地落盘使用映射后路径
        val targetPath = keyToLocalPath(key)
        var attempt = 0
        while (attempt <= MAX_RETRY) {
            val tmp = File(webDir, "$targetPath.tmp$attempt")
            try {
                downloadFile(key, tmp)
                if (FileHasher.md5File(tmp) == expectedMd5) {
                    val target = File(webDir, targetPath)
                    target.parentFile?.mkdirs()
                    if (target.exists()) target.delete()
                    if (!tmp.renameTo(target)) {
                        // renameTo 失败时退化为复制
                        tmp.copyTo(target, overwrite = true)
                        tmp.delete()
                    }
                    return true
                }
                Log.w(TAG, "MD5 校验失败: $key (attempt=$attempt)")
                tmp.delete()
            } catch (e: CancellationException) {
                // 用户跳过：清理临时文件后停止
                tmp.delete()
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "下载失败: $key (attempt=$attempt)", e)
                tmp.delete()
            }
            attempt++
        }
        return false
    }

    private fun downloadFile(remoteKey: String, target: File) {
        val url = BASE_URL + encodePath(remoteKey)
        val request = Request.Builder().url(url).build()
        httpClient.newCall(request).execute().use { resp ->
            if (!resp.isSuccessful) throw IOException("HTTP ${resp.code} for $remoteKey")
            val body = resp.body ?: throw IOException("empty body for $remoteKey")
            FileOutputStream(target).use { out ->
                body.byteStream().use { input -> input.copyTo(out) }
            }
        }
    }

    /** 按路径段分别 URL 编码（保留 / 分隔，兼容中文文件名）。 */
    private fun encodePath(path: String): String =
        path.split('/').joinToString("/") { URLEncoder.encode(it, "UTF-8").replace("+", "%20") }

    // ------------------------------------------------------------------
    // 工具
    // ------------------------------------------------------------------

    private suspend fun post(onState: (UpdateState) -> Unit, state: UpdateState) {
        withContext(Dispatchers.Main) { onState(state) }
    }
}
