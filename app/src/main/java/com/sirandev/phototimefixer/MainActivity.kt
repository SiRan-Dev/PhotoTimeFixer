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
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.CheckBox
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import androidx.core.net.toUri
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.color.DynamicColors
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
class MainActivity : AppCompatActivity() {

    private val Number.dpToPx: Int
        get() = (this.toFloat() * resources.displayMetrics.density + 0.5f).toInt()

    private lateinit var recyclerView: RecyclerView
    private lateinit var adapter: MediaAdapter
    private lateinit var btnScan: Button
    private lateinit var btnFixSelected: Button
    private lateinit var btnSelectAll: Button
    private lateinit var btnJumpAbnormal: Button
    private lateinit var btnSettings: ImageButton
    private lateinit var tvStatus: TextView

    private val mediaItems = mutableListOf<MediaItem>()

    // 上次跳转到的异常项位置，用于实现「逐条查看异常照片」
    private var lastJumpedAbnormalIndex = -1

    // 判定「时间异常」的阈值（秒），在设置页中修改
    private val prefs by lazy { getSharedPreferences("settings", MODE_PRIVATE) }
    private var thresholdSeconds = 60 * 60L  // 默认 1 小时，运行时从 prefs 恢复

    override fun onCreate(savedInstanceState: Bundle?) {
        // Android 12+：跟随系统壁纸/主题动态取色（低版本自动忽略，使用 values 下的非紫配色）
        DynamicColors.applyToActivityIfAvailable(this)
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // 根据当前 UI 模式显式控制状态栏 / 导航栏前景色。
        // 主题里已经设置过 android:windowLightStatusBar，但 v31+ DynamicColors
        // 会重新生成主题，因此用 controller 兜底一次，确保状态栏文字/图标颜色与
        // 当前背景匹配（浅色背景→深色图标，深色背景→浅色图标）。
        val isNightMode = resources.configuration.uiMode and
            Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
        WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = !isNightMode
            isAppearanceLightNavigationBars = !isNightMode
        }

        // 为刘海/挖孔、状态栏、手势条及屏幕圆角预留安全空间。
        // 注：圆角的精确 inset（roundedCorners）在 androidx 兼容层未公开，
        // 平台 API 在 SDK 36/37 中也不再暴露，故用较大的基础安全间距兜底。
        val mainView = findViewById<View>(R.id.main)
        val bottomBar = findViewById<View>(R.id.bottomBar)
        ViewCompat.setOnApplyWindowInsetsListener(mainView) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            val displayCutout = insets.getInsets(WindowInsetsCompat.Type.displayCutout())
            v.setPadding(
                maxOf(systemBars.left, displayCutout.left, 16.dpToPx),
                maxOf(systemBars.top, displayCutout.top, 16.dpToPx),
                maxOf(systemBars.right, displayCutout.right, 16.dpToPx),
                maxOf(systemBars.bottom, displayCutout.bottom, 16.dpToPx)
            )
            // 底部操作栏额外留空，避免被屏幕圆角或手势条遮挡
            bottomBar.setPadding(
                bottomBar.paddingLeft,
                bottomBar.paddingTop,
                bottomBar.paddingRight,
                8.dpToPx
            )
            insets
        }

        // 恢复上次选择的阈值
        thresholdSeconds = prefs.getLong("threshold_seconds", thresholdSeconds)

        recyclerView = findViewById(R.id.recyclerView)
        btnScan = findViewById(R.id.btnScan)
        btnFixSelected = findViewById(R.id.btnFixSelected)
        btnSelectAll = findViewById(R.id.btnSelectAll)
        btnJumpAbnormal = findViewById(R.id.btnJumpAbnormal)
        btnSettings = findViewById(R.id.btnSettings)
        tvStatus = findViewById(R.id.tvStatus)

        updateJumpAbnormalButton()

        adapter = MediaAdapter(
            items = mediaItems,
            onToggle = { item ->
                item.selected = !item.selected
                val pos = mediaItems.indexOf(item)
                if (pos >= 0) {
                    adapter.notifyItemChanged(pos)
                }
            },
            onOpen = { item -> openInGallery(item) },
        )
        recyclerView.layoutManager = LinearLayoutManager(this)
        recyclerView.adapter = adapter

        btnScan.setOnClickListener { checkPermissionsAndScan() }
        btnFixSelected.setOnClickListener { fixSelected() }
        btnSelectAll.setOnClickListener { toggleSelectAll() }
        btnSettings.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
        btnJumpAbnormal.setOnClickListener { jumpToNextAbnormal() }

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
            applyThresholdChange()
        }
    }

    /**
     * 阈值变更后刷新列表红字与状态计数，并重置跳转进度。
     */
    private fun applyThresholdChange() {
        val abnormal = mediaItems.count { it.isAbnormal(thresholdSeconds) }
        tvStatus.text = getString(R.string.status_result, mediaItems.size, abnormal)
        adapter.notifyItemRangeChanged(0, mediaItems.size)
        updateJumpAbnormalButton()
    }

    // ── 跳转到异常照片 ─────────────────────────────────────

    /**
     * 存在异常照片时启用「跳到异常」按钮，否则置灰。
     * 列表重新扫描 / 阈值变化后调用，重置跳转进度。
     */
    private fun updateJumpAbnormalButton() {
        lastJumpedAbnormalIndex = -1
        val hasAbnormal = mediaItems.any { it.isAbnormal(thresholdSeconds) }
        btnJumpAbnormal.isEnabled = hasAbnormal
    }

    /**
     * 依次跳转到下一条异常照片；看完最后一条后回到第一条。
     * 跳转目标行的时间信息会以红色显示，方便确认。
     */
    private fun jumpToNextAbnormal() {
        val abnormalIdx = mediaItems.indices.filter { mediaItems[it].isAbnormal(thresholdSeconds) }
        if (abnormalIdx.isEmpty()) return
        val pos = abnormalIdx.firstOrNull { it > lastJumpedAbnormalIndex } ?: abnormalIdx.first()
        lastJumpedAbnormalIndex = pos
        (recyclerView.layoutManager as? LinearLayoutManager)
            ?.scrollToPositionWithOffset(pos, 0)
    }

    // ── 权限 ──────────────────────────────────────────────

    private val readMediaLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { checkPermissionsAndScan() }

    private val manageStorageLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { checkPermissionsAndScan() }

    private fun checkPermissionsAndScan() {
        if (Build.VERSION.SDK_INT >= 33) {
            // Android 14+ 支持「选择性照片访问」：请求完整读取 + 选择性读取，
            // 系统会弹出「全部照片 / 选中照片」供用户选择
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
        tvStatus.text = getString(R.string.status_scanning)
        btnScan.isEnabled = false
        Thread {
            val items = queryMedia()
            runOnUiThread {
                val oldItems = ArrayList(mediaItems)
                val diff = DiffUtil.calculateDiff(object : DiffUtil.Callback() {
                    override fun getOldListSize() = oldItems.size
                    override fun getNewListSize() = items.size
                    override fun areItemsTheSame(oldPos: Int, newPos: Int) =
                        oldItems[oldPos].uri == items[newPos].uri
                    override fun areContentsTheSame(oldPos: Int, newPos: Int) =
                        oldItems[oldPos] == items[newPos]
                })
                mediaItems.clear()
                mediaItems.addAll(items)
                diff.dispatchUpdatesTo(adapter)
                btnScan.isEnabled = true
                val abnormal = items.count { it.isAbnormal(thresholdSeconds) }
                tvStatus.text = getString(R.string.status_result, items.size, abnormal)
                updateJumpAbnormalButton()
            }
        }.start()
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
        fixItems(selected)
    }

    private fun fixItems(items: List<MediaItem>) {
        tvStatus.text = getString(R.string.status_processing, items.size)
        btnFixSelected.isEnabled = false
        Thread {
            var ok = 0
            var fail = 0
            for (item in items) {
                if (fixOne(item)) ok++ else fail++
            }
            runOnUiThread {
                btnFixSelected.isEnabled = true
                tvStatus.text = getString(R.string.status_done, ok, fail)
                Toast.makeText(this, getString(R.string.status_done, ok, fail), Toast.LENGTH_LONG).show()
                scanAndIdentify()
            }
        }.start()
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
        adapter.notifyItemRangeChanged(0, mediaItems.size)
        btnSelectAll.text = getString(
            if (target) R.string.cancel_select_all else R.string.select_all_abnormal
        )
    }

    // ── 数据模型 ───────────────────────────────────────────

    data class MediaItem(
        val uri: Uri,
        val name: String,
        val path: String,
        val dateAddedSeconds: Long,
        val dateModifiedSeconds: Long,
        val dateTakenMillis: Long,
        var selected: Boolean = false,
    ) {
        fun isAbnormal(thresholdSeconds: Long): Boolean {
            if (dateTakenMillis <= 0) return false
            return abs(dateTakenMillis / 1000 - dateModifiedSeconds) > thresholdSeconds
        }
    }

    // ── 适配器 ─────────────────────────────────────────────

    private inner class MediaAdapter(
        private val items: List<MediaItem>,
        private val onToggle: (MediaItem) -> Unit,
        private val onOpen: (MediaItem) -> Unit,
    ) : RecyclerView.Adapter<MediaAdapter.VH>() {

        inner class VH(view: View) : RecyclerView.ViewHolder(view) {
            val checkBox: CheckBox = view.findViewById(R.id.checkBox)
            val ivThumb: ImageView = view.findViewById(R.id.ivThumb)
            val tvName: TextView = view.findViewById(R.id.tvName)
            val tvInfo: TextView = view.findViewById(R.id.tvInfo)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val view = LayoutInflater.from(parent.context).inflate(R.layout.item_media, parent, false)
            return VH(view)
        }

        override fun onBindViewHolder(holder: VH, position: Int) {
            val item = items[position]
            // 先清空监听器再设置勾选状态，避免复用 ViewHolder 时触发旧回调导致 notifyItemChanged 在布局中崩溃
            holder.checkBox.setOnCheckedChangeListener(null)
            holder.checkBox.isChecked = item.selected
            holder.checkBox.setOnCheckedChangeListener { _, _ -> onToggle(item) }
            holder.tvName.text = item.name
            val taken = formatTime(item.dateTakenMillis)
            val modified = formatTime(item.dateModifiedSeconds * 1000)
            holder.tvInfo.text = getString(R.string.item_info, taken, modified)
            holder.tvInfo.setTextColor(
                if (item.isAbnormal(thresholdSeconds)) 0xFFE53935.toInt() else 0xFF616161.toInt()
            )
            // 缩略图（异步加载，避免阻塞列表滚动）
            holder.ivThumb.setImageBitmap(null)
            loadThumbnailAsync(item) { bmp ->
                if (holder.absoluteAdapterPosition == position) {
                    holder.ivThumb.setImageBitmap(bmp)
                }
            }
            // 点击预览图跳转到相册对应照片
            holder.ivThumb.setOnClickListener { onOpen(item) }
        }

        override fun getItemCount(): Int = items.size
    }

    private fun formatTime(millis: Long): String {
        if (millis <= 0) return getString(R.string.time_unknown)
        val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
        return fmt.format(Date(millis))
    }

    private val thumbExecutor = java.util.concurrent.Executors.newSingleThreadExecutor()

    private fun loadThumbnailAsync(item: MediaItem, onResult: (Bitmap?) -> Unit) {
        thumbExecutor.execute {
            val bmp = loadThumbnail(item)
            runOnUiThread { onResult(bmp) }
        }
    }

    @Suppress("DEPRECATION")
    private fun loadThumbnail(item: MediaItem): Bitmap? {
        return try {
            if (Build.VERSION.SDK_INT >= 29) {
                contentResolver.loadThumbnail(item.uri, Size(256, 256), null)
            } else {
                val id = ContentUris.parseId(item.uri)
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
}
