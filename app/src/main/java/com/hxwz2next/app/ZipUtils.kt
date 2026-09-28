package com.hxwz2next.app

import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.util.zip.ZipInputStream

/**
 * ZIP 解压工具：
 * 1. 覆盖式解压（对应游戏端 unzip -o 语义），已存在的目标文件被替换；
 * 2. Zip Slip 防护——条目路径规范化校验，拒绝越出目标目录；
 * 3. 先写临时文件再原子替换，避免中断产生半截文件。
 *
 * 用途：
 * - 首次启动解压 APK 随包 app.zip（游戏框架）；
 * - 热更新时解压新下载的 app.zip（结构维护）。
 */
object ZipUtils {

    /** 从 zip 文件解压到目标目录。 */
    fun extract(zipFile: File, targetDir: File) {
        FileInputStream(zipFile).use { extract(it, targetDir) }
    }

    /** 从输入流解压到目标目录（支持直接读 APK assets，无需中间落盘）。 */
    fun extract(input: InputStream, targetDir: File) {
        targetDir.mkdirs()
        ZipInputStream(input.buffered()).use { zis ->
            while (true) {
                val entry = zis.nextEntry ?: break
                val name = entry.name.replace('\\', '/')
                if (name.isEmpty() || name.contains("..")) {
                    zis.closeEntry()
                    continue
                }
                if (entry.isDirectory) {
                    safeFile(targetDir, name).mkdirs()
                    zis.closeEntry()
                    continue
                }
                val outFile = safeFile(targetDir, name)
                outFile.parentFile?.mkdirs()
                val tmp = File(targetDir, outFile.name + ".ztmp")
                try {
                    FileOutputStream(tmp).use { out -> zis.copyTo(out) }
                    if (outFile.exists()) outFile.delete()
                    if (!tmp.renameTo(outFile)) {
                        tmp.copyTo(outFile, overwrite = true)
                        tmp.delete()
                    }
                } finally {
                    if (tmp.exists()) tmp.delete()
                }
                zis.closeEntry()
            }
        }
    }

    /** 校验解压目标不越出目标目录（Zip Slip 防护）。 */
    private fun safeFile(targetDir: File, entryName: String): File {
        val canonicalRoot = targetDir.canonicalPath
        val f = File(targetDir, entryName).canonicalFile
        if (!f.path.startsWith(canonicalRoot + File.separator)) {
            throw IOException("非法 zip 条目路径: $entryName")
        }
        return f
    }
}
