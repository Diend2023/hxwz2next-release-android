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
 *    随后**优先解压随包 app.zip** 生成游戏框架（AnimeBattleNext.js、assets/、editerAssets/ 等）；
 *    index.html 最后落盘，作为"基线完整"标志（中断时它不存在 → 下次重新完整初始化）；
 * 2. 常规启动：同步豁免文件为 APK 内置版 → 清理残留临时文件 → 拉取远程 md5s.json →
 *    差异计算 → 增量下载（6 并发、失败重试 2 次），热更新阶段可跳过；
 * 3. 清单键到本地路径需经 [keyToLocalPath] 映射（abn/→assets/roles/ 等 4 条规则），
 *    与游戏端 GameResourceUpdater.keyToLocalPath 完全一致；
 * 4. app.zip 键变化时整体下载并覆盖解压（框架结构维护），失败保留旧框架不中断；
 * 5. 下载校验 MD5；远程清单 MD5 过期（声明值与内容不符但内容与本地一致）时按声明值收敛，
 *    避免每次启动重复下载；失败重试后跳过、不中断整体。
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

        /**
         * 豁免文件白名单：永不参与热更新（不下载、不写入本地清单）。
         * - index.html / favicon.png：始终使用 APK 内置版本
         * - md5s.json：本地期望清单由本类维护，防止远程清单含自身条目时被下载覆盖
         */
        private val LOCKED_FILES = setOf("index.html", "favicon.png", MD5_FILE)

        /**
         * 每次启动从 APK 同步的豁免文件（APK 升级即生效）。
         * 不含 md5s.json——本地清单由热更新流程维护，不能被随包版本覆盖。
         */
        private val SYNC_LOCKED_FILES = setOf("index.html", "favicon.png")

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
        .readTimeout(180, TimeUnit.SECONDS) // 单文件最大 71MB，放宽读超时
        .build()

    /** 执行更新流程，[onState] 在 UI 线程回调。 */
    suspend fun run(onState: (UpdateState) -> Unit) {
        withContext(Dispatchers.IO) {
            // 提升到 try 外：取消/异常时也要能收敛已成功的部分
            val failedPaths = mutableSetOf<String>()
            var remoteMd5s: JSONObject? = null
            var taskCount = 0
            var appZipOk = true
            try {
                // 0) 清理上次中断残留的临时文件（下载 .tmpN、md5s.json.tmp、解压 .ztmp）
                cleanTmpFiles()

                // 1) 首次启动：基线初始化（含优先解压 app.zip）。
                //    以 index.html 是否存在判断完成（initBaseline 保证它最后落盘），
                //    中断时它尚未写入 → 下次重新完整初始化；旧版本升级用户直接跳过。
                if (!File(webDir, "index.html").exists()) {
                    post(onState, UpdateState(UpdateState.Phase.INIT, message = "初始化本地资源…"))
                    initBaseline { done, total, msg ->
                        post(onState, UpdateState(UpdateState.Phase.INIT, done, total, msg))
                    }
                } else {
                    // 已初始化：豁免文件始终同步为 APK 内置版
                    syncLockedFiles()
                }

                // 2) 拉取远程清单
                post(onState, UpdateState(UpdateState.Phase.CHECKING, message = "检查更新…"))
                remoteMd5s = fetchRemoteMd5s()
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
                        // zip 键只比对清单 MD5（本地不留存 zip，与游戏端一致）
                        if (localMd5s.optString(key, null) != remoteMd5) appZipChanged = true
                        continue
                    }
                    val localFile = File(webDir, keyToLocalPath(key))
                    // 本地清单 MD5 与远程一致且文件存在 → 视为已就绪
                    if (localFile.exists() && localMd5s.optString(key, null) == remoteMd5) continue
                    fileTasks.add(key to remoteMd5)
                }

                // 4) app.zip 框架更新（优先执行，失败保留旧框架）
                if (appZipChanged) {
                    val appZipMd5 = remoteMd5s.optString(APP_ZIP, "")
                    post(onState, UpdateState(UpdateState.Phase.DOWNLOADING, message = "下载框架更新包…"))
                    appZipOk = updateAppZip(appZipMd5)
                    if (!appZipOk) Log.w(TAG, "app.zip 更新失败（保留旧框架继续）")
                }

                // 5) 增量下载资源文件（记录失败文件，供清单合并时保留本地条目）
                taskCount = fileTasks.size
                if (fileTasks.isNotEmpty()) {
                    post(onState, UpdateState(UpdateState.Phase.DOWNLOADING, 0, taskCount, "下载更新 0/$taskCount"))
                    val done = AtomicInteger(0)
                    val semaphore = Semaphore(CONCURRENCY)
                    coroutineScope {
                        fileTasks.map { (key, md5) ->
                            async(Dispatchers.IO) {
                                semaphore.withPermit {
                                    val ok = downloadWithRetry(key, md5)
                                    val n = done.incrementAndGet()
                                    post(onState, UpdateState(UpdateState.Phase.DOWNLOADING, n, taskCount, "下载更新 $n/$taskCount"))
                                    if (!ok) {
                                        Log.w(TAG, "跳过失败文件: $key")
                                        synchronized(failedPaths) { failedPaths.add(key) }
                                    }
                                }
                            }
                        }.awaitAll()
                    }
                }

                // 6) 收敛后写回本地期望清单（原子写；app.zip 失败时保留旧值，下次重试）
                if (!appZipOk) failedPaths.add(APP_ZIP)
                saveLocalMd5s(remoteMd5s, failedPaths)

                if (taskCount > 0 || appZipChanged) {
                    post(onState, UpdateState(UpdateState.Phase.DONE, taskCount, taskCount, "更新完成"))
                } else {
                    post(onState, UpdateState(UpdateState.Phase.DONE, message = "已是最新版本"))
                }
            } catch (e: CancellationException) {
                // 用户跳过：也收敛已成功的文件，避免下次启动重复下载
                remoteMd5s?.let { rm ->
                    if (!appZipOk) failedPaths.add(APP_ZIP)
                    saveLocalMd5s(rm, failedPaths)
                }
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
     * 1) 复制 APK assets/www 基线文件（app.zip 除外，随包 md5s.json 一并就位）；
     * 2) 优先解压 app.zip 生成游戏框架，完成后删除 zip；
     * 3) **index.html 最后复制**——它是 WebView 入口，落盘即代表基线完整
     *    （含框架解压）；中断时它尚未写入，下次启动检测到缺失会重新完整初始化。
     */
    private suspend fun initBaseline(onProgress: suspend (Int, Int, String) -> Unit) {
        val am = context.assets
        val files = ArrayList<String>()
        collectAssets(am, "www", "", files)
        webDir.mkdirs()

        // 1) 复制基线文件（app.zip 单独解压；index.html 留到最后）
        val toCopy = files.filter { it != APP_ZIP && it != "index.html" }
        var done = 0
        for (rel in toCopy) {
            val target = File(webDir, rel)
            target.parentFile?.mkdirs()
            am.open("www/$rel").use { input ->
                FileOutputStream(target).use { out -> input.copyTo(out) }
            }
            done++
            onProgress(done, files.size, "初始化本地资源 $done/${files.size}")
        }

        // 2) 解压 app.zip（游戏框架：AnimeBattleNext.js、assets/、editerAssets/ 等）
        onProgress(done, files.size, "解压框架包 app.zip…")
        am.open("www/$APP_ZIP").use { input ->
            ZipUtils.extract(input, webDir)
        }
        // 与游戏端行为一致：解压完成后删除 zip 文件
        File(webDir, APP_ZIP).delete()
        done++
        onProgress(done, files.size, "框架解压完成")

        // 3) index.html 最后落盘（基线完整标志）
        val target = File(webDir, "index.html")
        target.parentFile?.mkdirs()
        am.open("www/index.html").use { input ->
            FileOutputStream(target).use { out -> input.copyTo(out) }
        }
        done++
        onProgress(done, files.size, "初始化完成")
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
     * 仅在已初始化后调用，避免破坏首次初始化判断；md5s.json 不在此列（由热更流程维护）。
     */
    private fun syncLockedFiles() {
        val am = context.assets
        for (name in SYNC_LOCKED_FILES) {
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
     * 写回本地期望清单：**以远程清单为准**，但：
     * - 白名单条目（index.html / favicon.png / md5s.json）永不写入；
     * - 本次下载失败的文件（含 app.zip 键）保留本地原条目，下次启动继续尝试；
     * - 远程已移除、本地仍保留的文件（只增不删策略）：保留本地条目。
     *
     * 原子写：先写临时文件再改名，避免写一半断电导致清单损坏
     * （损坏会被视为空清单，触发全量重新下载）。
     */
    private fun saveLocalMd5s(remote: JSONObject, failedPaths: Set<String>) {
        try {
            val local = readLocalMd5s()
            val merged = JSONObject()
            // 1) 远程条目为准（跳过白名单与本次失败文件）
            val rk = remote.keys()
            while (rk.hasNext()) {
                val name = rk.next()
                if (LOCKED_FILES.contains(name)) continue
                if (name in failedPaths) continue
                merged.put(name, remote.getString(name))
            }
            // 2) 失败文件保留本地原条目（本地没有则不加，下次仍判差异重试）
            for (name in failedPaths) {
                if (local.has(name)) merged.put(name, local.getString(name))
            }
            // 3) 远程已移除但本地保留的文件：保留本地条目（只增不删）
            val lk = local.keys()
            while (lk.hasNext()) {
                val name = lk.next()
                if (LOCKED_FILES.contains(name)) continue
                if (!merged.has(name) && !remote.has(name)) {
                    merged.put(name, local.getString(name))
                }
            }
            // 原子写
            val tmp = File(webDir, "$MD5_FILE.tmp")
            tmp.writeText(merged.toString())
            val target = File(webDir, MD5_FILE)
            if (target.exists()) target.delete()
            if (!tmp.renameTo(target)) {
                tmp.copyTo(target, overwrite = true)
                tmp.delete()
            }
        } catch (e: Exception) {
            Log.w(TAG, "保存本地清单失败", e)
        }
    }

    /** 单键更新本地清单（读-改-写，app.zip 更新成功后立即调用）。 */
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

    /** 清理上次运行中断残留的临时文件（下载 .tmpN、md5s.json.tmp、解压 .ztmp）。 */
    private fun cleanTmpFiles() {
        if (!webDir.isDirectory) return
        var cleaned = 0
        webDir.walkTopDown().forEach { file ->
            val name = file.name
            if (file.isFile && (name.endsWith(".tmp") || name.endsWith(".ztmp") ||
                    Regex(""".*\.tmp\d+$""").matches(name))
            ) {
                if (file.delete()) cleaned++
            }
        }
        if (cleaned > 0) Log.i(TAG, "清理残留临时文件: $cleaned 个")
    }

    // ------------------------------------------------------------------
    // 增量下载
    // ------------------------------------------------------------------

    /**
     * 下载单文件并原子替换（URL 用远程清单键，落盘用映射后路径）。
     * 三级处理：
     * 1. 实际 MD5 == 远程声明值 → 正常安装，返回 true；
     * 2. 不符但本地已有相同内容 → 远程清单过期（声明值与实际文件不符），
     *    内容已是最新无需替换，返回 true 使下次启动 0 差异，避免重复下载
     *    （典型场景：repo 改了资源但忘记重新生成 md5s.json）；
     * 3. 其余 → 视为损坏/异常，拒绝并重试，最终返回 false。
     */
    private fun downloadWithRetry(key: String, expectedMd5: String): Boolean {
        // URL 使用远程键，本地落盘使用映射后路径
        val targetPath = keyToLocalPath(key)
        var attempt = 0
        while (attempt <= MAX_RETRY) {
            val tmp = File(webDir, "$targetPath.tmp$attempt")
            try {
                downloadFile(key, tmp)
                val actualMd5 = FileHasher.md5File(tmp)
                if (actualMd5 == expectedMd5) {
                    installFile(tmp, File(webDir, targetPath))
                    return true
                }
                val target = File(webDir, targetPath)
                if (target.isFile && FileHasher.md5File(target) == actualMd5) {
                    // 远程清单过期：下载内容与本地一致，仅收敛清单条目
                    Log.w(TAG, "远程清单 MD5 过期（内容与本地一致，按声明值收敛）: $key")
                    tmp.delete()
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

    /** 原子替换目标文件（rename 失败时退化为复制）。 */
    private fun installFile(tmp: File, target: File) {
        target.parentFile?.mkdirs()
        if (target.exists()) target.delete()
        if (!tmp.renameTo(target)) {
            tmp.copyTo(target, overwrite = true)
            tmp.delete()
        }
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
