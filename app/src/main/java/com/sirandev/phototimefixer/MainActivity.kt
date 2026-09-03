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
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
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
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
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
    private var scanning by mutableStateOf(false)
    private var fixing by mutableStateOf(false)
    private var statusText by mutableStateOf("")
    private var lastJumpedAbnormalIndex by mutableIntStateOf(-1)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        applySystemBarAppearance()
        thresholdSeconds = prefs.getLong("threshold_seconds", thresholdSeconds)

        setContent {
            PhotoTimeFixerTheme {
                MainScreen()
            }
        }
        checkPermissionsAndScan()
    }

    /**
     * 从设置页返回时同步阈值：若用户在设置页调整了阈值，
     * 立即刷新列表红字与状态计数，并重置跳转进度。
     */
    override fun onResume() {
        super.onResume()
        val saved = prefs.getLong("threshold_seconds", thresholdSeconds)
        if (saved != thresholdSeconds) {
            thresholdSeconds = saved
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
        val abnormal = mediaItems.count { it.isAbnormal(thresholdSeconds) }
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
            val items = withContext(Dispatchers.IO) { queryMedia() }
            mediaItems.clear()
            mediaItems.addAll(items)
            scanning = false
            lastJumpedAbnormalIndex = -1
            updateStatus()
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

    private fun fixSelected() {
        val selected = mediaItems.filter { it.selected }
        if (selected.isEmpty()) {
            Toast.makeText(this, R.string.toast_select_hint, Toast.LENGTH_SHORT).show()
            return
        }
        fixing = true
        statusText = getString(R.string.status_processing, selected.size)
        lifecycleScope.launch {
            var ok = 0
            var fail = 0
            withContext(Dispatchers.IO) {
                for (item in selected) {
                    if (fixOne(item)) ok++ else fail++
                }
            }
            fixing = false
            statusText = getString(R.string.status_done, ok, fail)
            Toast.makeText(this@MainActivity, getString(R.string.status_done, ok, fail), Toast.LENGTH_LONG).show()
            scanAndIdentify()
        }
    }

    /**
     * 修复单张照片：
     * 1. 以「文件时间与拍摄时间中更早者」作为正确拍摄时间；
     * 2. 把文件时间设为该时间；
     * 3. 通过 rename 走 → 删记录 → rename 回 → 触发重扫，让系统按文件时间重建 date_taken。
     */
    private fun fixOne(item: MediaItem): Boolean {
        if (item.path.isBlank()) return false
        val file = File(item.path)
        if (!file.exists()) return false

        val modifiedMillis = item.dateModifiedSeconds * 1000
        val takenMillis = item.dateTakenMillis
        val correctMillis = if (takenMillis > 0) minOf(takenMillis, modifiedMillis) else modifiedMillis

        return try {
            file.setLastModified(correctMillis)

            // rename 走 → 删记录 → rename 回 → 触发扫描，等价于「删除重放」但保留文件内容
            val tmp = File(item.path + ".ptfixer_tmp")
            if (file.renameTo(tmp)) {
                try {
                    contentResolver.delete(item.uri, null, null)
                } catch (_: Exception) {
                }
                tmp.renameTo(file)
            }
            MediaScannerConnection.scanFile(this, arrayOf(item.path), null, null)
            true
        } catch (_: Exception) {
            false
        }
    }

    private fun toggleSelectAll() {
        val abnormalItems = mediaItems.filter { it.isAbnormal(thresholdSeconds) }
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
    ) {
        var selected by mutableStateOf(false)

        fun isAbnormal(thresholdSeconds: Long): Boolean {
            if (dateTakenMillis <= 0) return false
            return abs(dateTakenMillis / 1000 - dateModifiedSeconds) > thresholdSeconds
        }
    }

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
                                    mediaItems[it].isAbnormal(thresholdSeconds)
                                }
                                if (abnormalIdx.isNotEmpty()) {
                                    val pos = abnormalIdx.firstOrNull { it > lastJumpedAbnormalIndex }
                                        ?: abnormalIdx.first()
                                    lastJumpedAbnormalIndex = pos
                                    scope.launch {
                                        listState.animateScrollToItem(pos)
                                    }
                                }
                            },
                            enabled = mediaItems.any { it.isAbnormal(thresholdSeconds) },
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
                val taken = formatTime(item.dateTakenMillis)
                val modified = formatTime(item.dateModifiedSeconds * 1000)
                Text(
                    text = stringResource(R.string.item_info, taken, modified),
                    style = MaterialTheme.typography.bodySmall,
                    color = if (item.isAbnormal(thresholdSeconds)) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    }
                )
            }
        }
    }
}