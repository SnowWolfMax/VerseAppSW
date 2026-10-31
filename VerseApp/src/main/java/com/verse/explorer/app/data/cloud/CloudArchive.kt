package com.verse.explorer.app.data.cloud

import android.content.Context
import com.verse.explorer.core.config.GameConfig
import com.verse.explorer.core.data.VersionSettingsRepository
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * 云盘存档的本地打包 / 还原。
 *
 * 与桌面端 Rust `pack_saves` / `cloud_download` 的行为对齐：
 * - 上传：把某版本的整个 `saves/` 目录递归打成 zip，zip 内保留世界文件夹结构；
 * - 还原：按 zip 顶层目录还原世界；同名世界不覆盖，自动改成 `名字 (2)`（桌面端是覆盖，
 *   手机上更保守，避免误删玩家进度）。
 */
object CloudArchive {

    data class PackResult(
        val file: File,
        val size: Long,
        val worlds: List<String>
    )

    /**
     * 版本存档目录。隔离语义与启动器一致：`usesGlobal || isolateGameDir`
     * 走 `versions/<id>/saves`，否则走游戏根目录 `saves`。
     */
    fun savesDir(context: Context, versionId: String): File {
        val gameDir = GameConfig.getGameDir(context)
        val settings = VersionSettingsRepository(context).getVersionSettings(versionId)
        val isolated = settings.usesGlobal || settings.isolateGameDir
        return if (isolated) {
            File(GameConfig.getVersionsDir(gameDir), "$versionId/saves")
        } else {
            GameConfig.getSavesDir(gameDir)
        }
    }

    /** 打包该版本的 saves 目录；没有存档时抛出可展示的错误 */
    fun pack(context: Context, versionId: String): PackResult {
        val savesDir = savesDir(context, versionId)
        if (!savesDir.isDirectory) throw CloudException("该版本还没有存档目录")

        val worlds = savesDir.listFiles()
            ?.filter { it.isDirectory }
            ?.map { it.name }
            .orEmpty()
        if (worlds.isEmpty()) throw CloudException("该版本没有可上传的存档")

        val zip = File(context.cacheDir, "verse-cloud-${System.currentTimeMillis()}.zip")
        try {
            zipDirectory(savesDir, zip)
        } catch (e: Exception) {
            zip.delete()
            throw CloudException("打包存档失败：${e.message ?: "未知错误"}")
        }
        return PackResult(file = zip, size = zip.length(), worlds = worlds)
    }

    /**
     * 把下载的 zip 还原到该版本 saves 目录。
     * @return 实际写入的世界文件夹名（同名时可能是 `名字 (2)`）
     */
    fun restore(context: Context, versionId: String, zipFile: File): List<String> {
        val savesDir = savesDir(context, versionId)
        if (!savesDir.exists() && !savesDir.mkdirs()) {
            throw CloudException("无法创建存档目录：${savesDir.absolutePath}")
        }
        val savesCanonical = savesDir.canonicalPath + File.separator
        // zip 顶层目录名 → 实际写入目录名（同名世界自动改名）
        val rootMap = mutableMapOf<String, String>()

        ZipInputStream(zipFile.inputStream().buffered()).use { zipIn ->
            var entry = zipIn.nextEntry
            while (entry != null) {
                val rawName = entry.name.replace('\\', '/')
                if (rawName.startsWith("/") || rawName.contains("..") || rawName.contains(":")) {
                    zipIn.closeEntry()
                    entry = zipIn.nextEntry
                    continue
                }
                val hasDir = rawName.contains('/')
                val top = if (hasDir) rawName.substringBefore('/') else "导入的存档"
                val rootName = if (hasDir) {
                    rootMap.getOrPut(top) {
                        if (File(savesDir, top).exists()) uniqueName(savesDir, top) else top
                    }
                } else {
                    "导入的存档"
                }
                val relative = if (hasDir) rawName.substringAfter('/') else rawName
                val target = File(File(savesDir, rootName), relative)
                if (!target.canonicalPath.startsWith(savesCanonical)) {
                    zipIn.closeEntry()
                    entry = zipIn.nextEntry
                    continue
                }
                target.parentFile?.mkdirs()
                if (!entry.isDirectory) {
                    target.outputStream().use { out -> zipIn.copyTo(out) }
                }
                zipIn.closeEntry()
                entry = zipIn.nextEntry
            }
        }

        val names = rootMap.values.toMutableList()
        if (names.isEmpty() && File(savesDir, "导入的存档").exists()) {
            names += "导入的存档"
        }
        return names
    }

    /** 生成不冲突的目录名：`名字 (2)`、`名字 (3)`… */
    private fun uniqueName(parent: File, base: String): String {
        var index = 2
        while (index < 1000) {
            val candidate = "$base ($index)"
            if (!File(parent, candidate).exists()) return candidate
            index++
        }
        return "$base (${System.currentTimeMillis()})"
    }

    private fun zipDirectory(sourceDir: File, targetFile: File) {
        targetFile.parentFile?.mkdirs()
        ZipOutputStream(targetFile.outputStream().buffered()).use { zipOut ->
            sourceDir.walkTopDown().forEach { file ->
                if (file.isFile) {
                    val entry = ZipEntry(file.relativeTo(sourceDir).path.replace('\\', '/'))
                    zipOut.putNextEntry(entry)
                    file.inputStream().use { input -> input.copyTo(zipOut) }
                    zipOut.closeEntry()
                }
            }
        }
    }
}
