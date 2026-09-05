/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 * Copyright (C) 2026 SiRan-Dev
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package com.sirandev.phototimefixer

import android.Manifest
import android.content.ContentUris
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Bitmap
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.provider.Settings
import android.util.Size
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import androidx.core.net.toUri
import androidx.core.view.WindowCompat
import androidx.exifinterface.media.ExifInterface
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs

/**
 * 修复相册照片「显示时间」异常的工具。
 *
 * 背景：通过数据线 / 小米互传导入的照片，在相册「全部照片」里显示的时间会变成
 * 导入那一刻，而点进详情又能看到正确的拍摄时间。原因是系统媒体库中
 * 「文件时间(date_modified)」与「拍摄时间(date_taken)」不一致。
 *
 * 本应用只处理照片；视频请使用工程根目录 video_time_fixer 下的脚本。
 */
class MainActivity : ComponentActivity() {

    companion object {
        /** 方案1（默认）：以 EXIF 拍摄时间与文件时间中较早者为正确时间。 */
        const val SCHEME_TAKEN = 0

        /** 方案2：从照片文件名解析拍摄时间（如 IMG_20230905_143022.jpg）。 */
        const val SCHEME_FILENAME = 1
    }

    private val prefs by lazy { getSharedPreferences("settings", MODE_PRIVATE) }

    private val readMediaLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { checkPermissionsAndScan() }

    private val manageStorageLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { checkPermissionsAndScan() }

    // ── Compose 状态 ──────────────────────────────────────

    private val mediaItems = mutableStateListOf<MediaItem>()
    private var thresholdSeconds by mutableLongStateOf(60 * 60L)
    private var fixScheme by mutableIntStateOf(SCHEME_TAKEN)
    private var writeExif by mutableStateOf(true)
    private var renameToExif by mutableStateOf(false)

    /** 非空时弹出「执行修复前」的二次确认对话框（涉及重命名 / 写 EXIF 时）。 */
    private var fixConfirm by mutableStateOf<FixConfirmPlan?>(null)
    private var scanning by mutableStateOf(false)
    private var fixing by mutableStateOf(false)

    /** 批量修复进度（已完成 / 总数），驱动进度条与状态行实时刷新。 */
    private var fixProgressDone by mutableIntStateOf(0)
    private var fixProgressTotal by mutableIntStateOf(0)

    /** 批量修复取消标记（后台线程读取，需原子类）。 */
    private val fixCancelRequested = AtomicBoolean(false)

    /** 正在计算修复计划（含磁盘检查），期间禁用「处理选中」。 */
    private var fixPlanning by mutableStateOf(false)
    private var statusText by mutableStateOf("")
    private var lastJumpedAbnormalIndex by mutableIntStateOf(-1)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        applySystemBarAppearance()
        thresholdSeconds = prefs.getLong("threshold_seconds", thresholdSeconds)
        fixScheme = prefs.getInt("fix_scheme", SCHEME_TAKEN)
        writeExif = prefs.getBoolean("write_exif", true)
        renameToExif = prefs.getBoolean("rename_to_exif", false)

        setContent {
            PhotoTimeFixerTheme {
                MainScreen()
            }
        }
        checkPermissionsAndScan()
    }

    /**
     * 从设置页返回时同步阈值与修复方案：任一发生变化时，
     * 立即刷新列表红字与状态计数，并重置跳转进度。
     */
    override fun onResume() {
        super.onResume()
        val savedThreshold = prefs.getLong("threshold_seconds", thresholdSeconds)
        val savedScheme = prefs.getInt("fix_scheme", SCHEME_TAKEN)
        val savedWriteExif = prefs.getBoolean("write_exif", true)
        val savedRenameToExif = prefs.getBoolean("rename_to_exif", false)
        val changed = savedThreshold != thresholdSeconds ||
            savedScheme != fixScheme ||
            savedWriteExif != writeExif ||
            savedRenameToExif != renameToExif
        if (savedThreshold != thresholdSeconds) thresholdSeconds = savedThreshold
        if (savedScheme != fixScheme) fixScheme = savedScheme
        if (savedWriteExif != writeExif) writeExif = savedWriteExif
        if (savedRenameToExif != renameToExif) renameToExif = savedRenameToExif
        if (changed) {
            lastJumpedAbnormalIndex = -1
            updateStatus()
        }
    }

    private fun applySystemBarAppearance() {
        val isNightMode = resources.configuration.uiMode and
            Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
        WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = !isNightMode
            isAppearanceLightNavigationBars = !isNightMode
        }
    }

    private fun updateStatus() {
        val abnormal = mediaItems.count { it.isAbnormal(fixScheme, thresholdSeconds, renameToExif) }
        statusText = getString(R.string.status_result, mediaItems.size, abnormal)
    }

    // ── 权限 ──────────────────────────────────────────────

    private fun checkPermissionsAndScan() {
        if (Build.VERSION.SDK_INT >= 33) {
            val needed = mutableListOf<String>()
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_MEDIA_IMAGES)
                != PackageManager.PERMISSION_GRANTED
            ) {
                needed.add(Manifest.permission.READ_MEDIA_IMAGES)
            }
            if (Build.VERSION.SDK_INT >= 34 &&
                ContextCompat.checkSelfPermission(this, Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED)
                != PackageManager.PERMISSION_GRANTED
            ) {
                needed.add(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED)
            }
            if (needed.isNotEmpty()) {
                readMediaLauncher.launch(needed.toTypedArray())
                return
            }
        } else {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE)
                != PackageManager.PERMISSION_GRANTED
            ) {
                readMediaLauncher.launch(arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE))
                return
            }
        }

        if (Build.VERSION.SDK_INT >= 30 && !Environment.isExternalStorageManager()) {
            Toast.makeText(this, R.string.toast_grant_storage, Toast.LENGTH_LONG).show()
            val intent = Intent(
                Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                "package:$packageName".toUri()
            )
            manageStorageLauncher.launch(intent)
            return
        }

        scanAndIdentify()
    }

    // ── 扫描 + 识别 ────────────────────────────────────────

    private fun scanAndIdentify() {
        scanning = true
        statusText = getString(R.string.status_scanning)
        lifecycleScope.launch {
            // 先恢复上次批量修复可能中断留下的 .ptfixer_tmp 文件
            recoverLeftoverTmpFiles()
            val items = withContext(Dispatchers.IO) { queryMedia() }
            mediaItems.clear()
            mediaItems.addAll(items)
            scanning = false
            lastJumpedAbnormalIndex = -1
            updateStatus()
        }
    }

    /**
     * 恢复残留的 .ptfixer_tmp 文件：批量修复中途被杀时，文件可能停在
     * 「原名 + .ptfixer_tmp」状态，相册不识别该扩展名导致照片"消失"。
     * 每次扫描前把它们改名回原名并触发重扫，保证照片不丢。
     */
    private suspend fun recoverLeftoverTmpFiles() = withContext(Dispatchers.IO) {
        val projection = arrayOf(MediaStore.MediaColumns._ID, MediaStore.MediaColumns.DATA)
        try {
            contentResolver.query(
                MediaStore.Files.getContentUri("external"),
                projection,
                "${MediaStore.MediaColumns.DATA} LIKE ?",
                arrayOf("%.ptfixer_tmp"),
                null
            )?.use { c ->
                val dataIdx = c.getColumnIndexOrThrow(MediaStore.MediaColumns.DATA)
                while (c.moveToNext()) {
                    val path = c.getString(dataIdx) ?: continue
                    val tmp = File(path)
                    val origin = File(path.removeSuffix(".ptfixer_tmp"))
                    if (tmp.exists() && !origin.exists()) {
                        if (tmp.renameTo(origin)) {
                            MediaScannerConnection.scanFile(
                                this@MainActivity, arrayOf(origin.absolutePath), null, null
                            )
                        }
                    }
                }
            }
        } catch (_: Exception) {
            // 某些系统版本对 Files 集合按 DATA 过滤有限制，恢复失败不阻塞扫描
        }
    }

    private fun queryMedia(): List<MediaItem> {
        val result = mutableListOf<MediaItem>()
        val projection = arrayOf(
            MediaStore.MediaColumns._ID,
            MediaStore.MediaColumns.DISPLAY_NAME,
            MediaStore.MediaColumns.DATA,
            MediaStore.MediaColumns.DATE_ADDED,
            MediaStore.MediaColumns.DATE_MODIFIED,
            MediaStore.MediaColumns.DATE_TAKEN,
        )
        try {
            contentResolver.query(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI, projection, null, null, null
            )?.use { cursor ->
                val idIdx = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
                val nameIdx = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)
                val dataIdx = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DATA)
                val addedIdx = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_ADDED)
                val modifiedIdx = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_MODIFIED)
                val takenIdx = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_TAKEN)
                while (cursor.moveToNext()) {
                    val id = cursor.getLong(idIdx)
                    val name = cursor.getString(nameIdx) ?: "unknown"
                    val path = cursor.getString(dataIdx) ?: ""
                    val dateAdded = cursor.getLong(addedIdx)
                    val dateModified = cursor.getLong(modifiedIdx)
                    val dateTaken = cursor.getLong(takenIdx)
                    result.add(
                        MediaItem(
                            uri = Uri.withAppendedPath(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id.toString()),
                            name = name,
                            path = path,
                            dateAddedSeconds = dateAdded,
                            dateModifiedSeconds = dateModified,
                            dateTakenMillis = dateTaken,
                            filenameMillis = parseTimeFromName(name),
                        )
                    )
                }
            }
        } catch (_: Exception) {
            // 某些设备可能没有 DATA 列，忽略
        }
        return result.sortedByDescending { it.dateTakenMillis }
    }

    // ── 修复 ──────────────────────────────────────────────

    /** 修复前的二次确认计划：本次将执行的 EXIF 写入与重命名。 */
    class FixConfirmPlan(
        val items: List<MediaItem>,
        val renames: Map<MediaItem, String>,
        val exifCount: Int,
    )

    private fun fixSelected() {
        if (fixing || fixPlanning) return
        val selected = mediaItems.filter { it.selected }
        if (selected.isEmpty()) {
            Toast.makeText(this, R.string.toast_select_hint, Toast.LENGTH_SHORT).show()
            return
        }

        // 计划本次修复将要执行的动作：重命名（方案1 + 开关）与 EXIF 写入（方案2 + 开关）。
        // 计划含磁盘检查（目标名是否占用），放 IO 线程，避免选中上千张时卡主线程。
        fixPlanning = true
        lifecycleScope.launch {
            val renames = if (fixScheme == SCHEME_TAKEN && renameToExif) {
                withContext(Dispatchers.IO) {
                    selected.mapNotNull { item ->
                        buildRenameTarget(item)?.let { item to it }
                    }.toMap()
                }
            } else {
                emptyMap()
            }
            val exifCount = if (fixScheme == SCHEME_FILENAME && writeExif) {
                selected.count { it.filenameMillis > 0 }
            } else {
                0
            }
            fixPlanning = false

            if (renames.isEmpty() && exifCount == 0) {
                startFix(selected, renames)
            } else {
                fixConfirm = FixConfirmPlan(selected, renames, exifCount)
            }
        }
    }

    private fun startFix(selected: List<MediaItem>, renames: Map<MediaItem, String>) {
        fixing = true
        fixCancelRequested.set(false)
        fixProgressDone = 0
        fixProgressTotal = selected.size
        statusText = getString(R.string.status_processing, 0, selected.size)
        lifecycleScope.launch {
            var ok = 0
            var fail = 0
            withContext(Dispatchers.IO) {
                for (item in selected) {
                    if (fixCancelRequested.get()) break // 用户取消，停止处理剩余项
                    if (fixOne(item, renames[item])) ok++ else fail++
                    // 实时刷新进度（状态写入切回主线程）
                    withContext(Dispatchers.Main) {
                        fixProgressDone++
                        statusText = getString(R.string.status_processing, fixProgressDone, fixProgressTotal)
                    }
                }
            }
            fixing = false
            val cancelled = ok + fail < selected.size
            statusText = if (cancelled) {
                getString(R.string.status_cancelled, ok, fail)
            } else {
                getString(R.string.status_done, ok, fail)
            }
            Toast.makeText(this@MainActivity, statusText, Toast.LENGTH_LONG).show()
            scanAndIdentify()
        }
    }

    /**
     * 计算方案1下按 EXIF 时间重命名后的完整路径。
     * 仅处理文件名中包含可解析时间的照片（时间乱码无法解析的文件名无替换基准）；
     * 文件名时间与 EXIF 时间一致时返回 null（不重命名）；
     * 目标名被占用时追加「-1」~「-9」后缀，保证弹窗承诺的重命名一定执行。
     */
    private fun buildRenameTarget(item: MediaItem): String? {
        if (item.dateTakenMillis <= 0 || item.path.isBlank()) return null
        val correctMillis = minOf(item.dateTakenMillis, item.dateModifiedSeconds * 1000)
        val newName = buildRenamedName(item.name, correctMillis) ?: return null
        if (newName == item.name) return null
        val parent = File(item.path).parent ?: return null

        var candidate = File(parent, newName)
        if (candidate.exists()) {
            val dot = newName.lastIndexOf('.')
            val stem = if (dot > 0) newName.substring(0, dot) else newName
            val ext = if (dot > 0) newName.substring(dot) else ""
            var found: File? = null
            for (i in 1..9) {
                val alt = File(parent, "$stem-$i$ext")
                if (!alt.exists()) {
                    found = alt
                    break
                }
            }
            return found?.absolutePath // 9 个后缀都被占时放弃（几乎不可能）
        }
        return candidate.absolutePath
    }

    /**
     * 按新时间改写文件名中的时间部分，保留前缀/分隔符风格与扩展名。
     * 实现在 FilenameTimeParser.buildRenamedName（可独立单元测试）。
     */
    private fun buildRenamedName(name: String, millis: Long): String? =
        FilenameTimeParser.buildRenamedName(name, millis)

    /**
     * 修复单张照片：
     * 1. 依据当前方案确定正确时间——
     *    · 方案1（默认）：取「文件时间与拍摄时间中更早者」；
     *    · 方案2：优先取从文件名解析出的拍摄时间，解析失败时回退到方案1 逻辑；
     * 2. 方案2 且文件名可解析、且「写入 EXIF」开关开启时，把该时间写入照片 EXIF——
     *    因为系统重扫时优先按 EXIF 重建 date_taken，若文件内 EXIF 本身错误或缺失，
     *    只改文件时间会导致相册日期又被「恢复」错误；
     * 3. 方案1 且传入 renameTo 时（「按 EXIF 重命名」开关，已经用户二次确认），
     *    先把文件重命名为 EXIF 时间对应的名字；
     * 4. 把文件时间设为该时间；
     * 5. 通过 rename 走 → 删记录 → rename 回 → 触发重扫，让系统按文件时间重建 date_taken。
     */
    private fun fixOne(item: MediaItem, renameTo: String? = null): Boolean {
        if (item.path.isBlank()) return false
        var file = File(item.path)
        if (!file.exists()) return false

        val modifiedMillis = item.dateModifiedSeconds * 1000
        val takenMillis = item.dateTakenMillis
        val fallbackMillis = if (takenMillis > 0) minOf(takenMillis, modifiedMillis) else modifiedMillis
        val useFilenameTime = fixScheme == SCHEME_FILENAME && item.filenameMillis > 0
        val correctMillis = if (useFilenameTime) item.filenameMillis else fallbackMillis

        return try {
            // 先重命名（新名字），再做其它改动
            if (renameTo != null) {
                val target = File(renameTo)
                if (!target.exists() && file.renameTo(target)) {
                    file = target
                }
            }
            // 先写 EXIF 再改文件时间：saveAttributes 重写文件会更新修改时间
            if (useFilenameTime && writeExif) {
                writeExifDateTime(file, item.filenameMillis)
            }
            file.setLastModified(correctMillis)

            // rename 走 → 删记录 → rename 回 → 触发扫描，等价于「删除重放」但保留文件内容
            val tmp = File(file.path + ".ptfixer_tmp")
            if (file.renameTo(tmp)) {
                try {
                    contentResolver.delete(item.uri, null, null)
                } catch (_: Exception) {
                }
                tmp.renameTo(file)
            }
            MediaScannerConnection.scanFile(this, arrayOf(file.path), null, null)
            true
        } catch (_: Exception) {
            false
        }
    }

    /**
     * 把文件名时间写入照片 EXIF（DateTimeOriginal / DateTime / DateTimeDigital），
     * 供系统重扫时按 EXIF 正确重建 date_taken。
     * 不支持 EXIF 写入的格式或写入失败时静默忽略，仅保留文件时间修复，不影响主流程。
     */
    private fun writeExifDateTime(file: File, millis: Long) {
        try {
            val exif = ExifInterface(file.absolutePath)
            val formatted = SimpleDateFormat("yyyy:MM:dd HH:mm:ss", Locale.US).format(Date(millis))
            exif.setAttribute(ExifInterface.TAG_DATETIME_ORIGINAL, formatted)
            exif.setAttribute(ExifInterface.TAG_DATETIME, formatted)
            exif.setAttribute(ExifInterface.TAG_DATETIME_DIGITIZED, formatted)
            exif.saveAttributes()
        } catch (_: Exception) {
        }
    }

    private fun toggleSelectAll() {
        val abnormalItems = mediaItems.filter { it.isAbnormal(fixScheme, thresholdSeconds, renameToExif) }
        val allAbnormalSelected = abnormalItems.isNotEmpty() && abnormalItems.all { it.selected }
        val target = !allAbnormalSelected
        abnormalItems.forEach { it.selected = target }
    }

    // ── 数据模型 ───────────────────────────────────────────

    class MediaItem(
        val uri: Uri,
        val name: String,
        val path: String,
        val dateAddedSeconds: Long,
        val dateModifiedSeconds: Long,
        val dateTakenMillis: Long,
        /** 从文件名解析出的拍摄时间（毫秒），解析失败为 0。 */
        val filenameMillis: Long = 0,
    ) {
        var selected by mutableStateOf(false)

        /**
         * 判断显示时间是否异常：
         * · 方案2：比较「文件名时间（或拍摄时间）」与「文件时间」；
         * · 方案1：比较「拍摄时间」与「文件时间」；若开启「按 EXIF 重命名」，
         *   文件名时间与拍摄时间不符（如文件名时区少 8 小时）也标记为异常，
         *   以便「全选异常」后通过重命名修复。
         */
        fun isAbnormal(scheme: Int, thresholdSeconds: Long, renameEnabled: Boolean = false): Boolean {
            if (scheme == SCHEME_FILENAME) {
                val referenceMillis = if (filenameMillis > 0) filenameMillis else dateTakenMillis
                if (referenceMillis <= 0) return false
                return abs(referenceMillis / 1000 - dateModifiedSeconds) > thresholdSeconds
            }
            // 方案1：拍摄时间 vs 文件时间
            if (dateTakenMillis > 0 &&
                abs(dateTakenMillis / 1000 - dateModifiedSeconds) > thresholdSeconds
            ) {
                return true
            }
            // 方案1 + 重命名开关：文件名时间 vs 拍摄时间
            if (renameEnabled && filenameMillis > 0 && dateTakenMillis > 0 &&
                abs(filenameMillis / 1000 - dateTakenMillis / 1000) > thresholdSeconds
            ) {
                return true
            }
            return false
        }
    }

    // ── 文件名时间解析 ────────────────────────────────────
    // 解析逻辑在 FilenameTimeParser（可独立单元测试）

    private fun parseTimeFromName(name: String): Long = FilenameTimeParser.parse(name)

    private fun formatTime(millis: Long): String {
        if (millis <= 0) return getString(R.string.time_unknown)
        val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
        return fmt.format(Date(millis))
    }

    private suspend fun loadThumbnail(item: MediaItem): Bitmap? = withContext(Dispatchers.IO) {
        try {
            if (Build.VERSION.SDK_INT >= 29) {
                contentResolver.loadThumbnail(item.uri, Size(256, 256), null)
            } else {
                @Suppress("DEPRECATION")
                val id = ContentUris.parseId(item.uri)
                @Suppress("DEPRECATION")
                MediaStore.Images.Thumbnails.getThumbnail(
                    contentResolver, id, MediaStore.Images.Thumbnails.MINI_KIND, null
                )
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun openInGallery(item: MediaItem) {
        if (fixing) {
            // 批量修复中旧记录会被删除重建，此时跳转相册可能失效
            Toast.makeText(this, R.string.toast_fixing, Toast.LENGTH_SHORT).show()
            return
        }
        try {
            val intent = Intent(Intent.ACTION_VIEW)
            intent.setDataAndType(item.uri, "image/*")
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            startActivity(intent)
        } catch (_: Exception) {
            Toast.makeText(this, R.string.toast_open_fail, Toast.LENGTH_SHORT).show()
        }
    }

    // ── Compose UI ─────────────────────────────────────────

    @Composable
    private fun MainScreen() {
        val listState = rememberLazyListState()
        val scope = rememberCoroutineScope()
        val context = LocalContext.current

        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.surface
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .windowInsetsPadding(WindowInsets.safeDrawing)
                    .padding(horizontal = 16.dp)
            ) {
                // 顶栏：标题 + 设置
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = stringResource(R.string.app_name),
                        style = MaterialTheme.typography.titleLarge,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.weight(1f)
                    )
                    IconButton(onClick = {
                        context.startActivity(Intent(context, SettingsActivity::class.java))
                    }) {
                        Icon(
                            painter = painterResource(R.drawable.ic_settings),
                            contentDescription = stringResource(R.string.settings),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                // 状态行
                Text(
                    text = statusText,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp, bottom = 8.dp)
                )

                // 批量修复进度条 + 取消
                if (fixing && fixProgressTotal > 0) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        LinearProgressIndicator(
                            progress = { fixProgressDone.toFloat() / fixProgressTotal },
                            modifier = Modifier
                                .weight(1f)
                        )
                        TextButton(onClick = { fixCancelRequested.set(true) }) {
                            Text(stringResource(R.string.fix_confirm_cancel))
                        }
                    }
                }

                // 列表
                LazyColumn(
                    state = listState,
                    modifier = Modifier.weight(1f)
                ) {
                    itemsIndexed(mediaItems, key = { _, item -> item.uri.toString() }) { _, item ->
                        MediaRow(
                            item = item,
                            onToggle = { item.selected = !item.selected },
                            onOpen = { openInGallery(item) },
                            loadThumb = { loadThumbnail(item) }
                        )
                    }
                }

                // 底部 2×2 按钮区
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 12.dp, bottom = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        FilledTonalButton(
                            onClick = { checkPermissionsAndScan() },
                            enabled = !scanning,
                            modifier = Modifier
                                .weight(1f)
                                .height(56.dp)
                        ) {
                            Text(stringResource(R.string.rescan))
                        }
                        OutlinedButton(
                            onClick = {
                                val abnormalIdx = mediaItems.indices.filter {
                                    mediaItems[it].isAbnormal(fixScheme, thresholdSeconds, renameToExif)
                                }
                                if (abnormalIdx.isNotEmpty()) {
                                    // 列表按拍摄时间降序（新→旧），索引越大越旧。
                                    // 从最旧的异常开始，逐条向更新的方向走，到底后回绕到最旧。
                                    val pos = abnormalIdx.lastOrNull { it < lastJumpedAbnormalIndex }
                                        ?: abnormalIdx.last()
                                    lastJumpedAbnormalIndex = pos
                                    scope.launch {
                                        listState.animateScrollToItem(pos)
                                    }
                                }
                            },
                            enabled = mediaItems.any { it.isAbnormal(fixScheme, thresholdSeconds, renameToExif) },
                            modifier = Modifier
                                .weight(1f)
                                .height(56.dp)
                        ) {
                            Text(stringResource(R.string.jump_to_abnormal))
                        }
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        FilledTonalButton(
                            onClick = { toggleSelectAll() },
                            modifier = Modifier
                                .weight(1f)
                                .height(56.dp)
                        ) {
                            Text(stringResource(R.string.select_all_abnormal))
                        }
                        Button(
                            onClick = { fixSelected() },
                            enabled = !fixing,
                            modifier = Modifier
                                .weight(1f)
                                .height(56.dp)
                        ) {
                            Text(stringResource(R.string.fix_selected))
                        }
                    }
                }

                // 修复前二次确认：涉及重命名 / EXIF 写入时弹出
                fixConfirm?.let { plan ->
                    AlertDialog(
                        onDismissRequest = { fixConfirm = null },
                        title = { Text(stringResource(R.string.fix_confirm_title)) },
                        text = {
                            Column {
                                if (plan.renames.isNotEmpty()) {
                                    Text(
                                        text = stringResource(R.string.fix_confirm_rename, plan.renames.size),
                                        color = MaterialTheme.colorScheme.onSurface
                                    )
                                    plan.renames.entries.take(3).forEach { (item, target) ->
                                        Text(
                                            text = "${item.name} → ${File(target).name}",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }
                                    if (plan.renames.size > 3) {
                                        Text(
                                            text = stringResource(R.string.fix_confirm_more, plan.renames.size - 3),
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
                                if (plan.exifCount > 0) {
                                    Text(
                                        text = stringResource(R.string.fix_confirm_exif, plan.exifCount),
                                        color = MaterialTheme.colorScheme.onSurface,
                                        modifier = Modifier.padding(top = 4.dp)
                                    )
                                }
                            }
                        },
                        confirmButton = {
                            TextButton(onClick = {
                                fixConfirm = null
                                startFix(plan.items, plan.renames)
                            }) {
                                Text(stringResource(R.string.fix_confirm_ok))
                            }
                        },
                        dismissButton = {
                            TextButton(onClick = { fixConfirm = null }) {
                                Text(stringResource(R.string.fix_confirm_cancel))
                            }
                        }
                    )
                }
            }
        }
    }

    @Composable
    private fun MediaRow(
        item: MediaItem,
        onToggle: () -> Unit,
        onOpen: () -> Unit,
        loadThumb: suspend () -> Bitmap?,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Checkbox(
                checked = item.selected,
                onCheckedChange = { onToggle() }
            )

            // 缩略图（异步加载）
            var thumb by remember(item.uri) { mutableStateOf<Bitmap?>(null) }
            LaunchedEffect(item.uri) {
                thumb = loadThumb()
            }
            thumb?.let { bmp ->
                Image(
                    bitmap = bmp.asImageBitmap(),
                    contentDescription = stringResource(R.string.thumbnail_desc),
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .padding(start = 4.dp, end = 8.dp)
                        .size(64.dp)
                        .clip(MaterialTheme.shapes.medium)
                        .clickable { onOpen() }
                )
            } ?: Spacer(
                modifier = Modifier
                    .padding(start = 4.dp, end = 8.dp)
                    .size(64.dp)
                    .clip(MaterialTheme.shapes.medium)
                    .clickable { onOpen() }
            )

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = item.name,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                // 方案2；或方案1 开启重命名开关时，都以「文件名时间」为主时间展示，便于核对
                val useFilenameTime = item.filenameMillis > 0 &&
                    (fixScheme == SCHEME_FILENAME || (fixScheme == SCHEME_TAKEN && renameToExif))
                val primaryTime = formatTime(if (useFilenameTime) item.filenameMillis else item.dateTakenMillis)
                val modified = formatTime(item.dateModifiedSeconds * 1000)
                Text(
                    text = stringResource(
                        if (useFilenameTime) R.string.item_info_name else R.string.item_info,
                        primaryTime, modified
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = if (item.isAbnormal(fixScheme, thresholdSeconds, renameToExif)) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    }
                )
            }
        }
    }
}