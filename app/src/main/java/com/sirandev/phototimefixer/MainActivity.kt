package com.sirandev.phototimefixer

import android.Manifest
import android.content.ContentUris
import android.content.Intent
import android.content.pm.PackageManager
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
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
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
 * 本应用只处理照片；视频请使用工程根目录下的脚本（脚本在电脑上修改文件时间后重新导入）。
 */
class MainActivity : AppCompatActivity() {

    private lateinit var recyclerView: RecyclerView
    private lateinit var adapter: MediaAdapter
    private lateinit var btnScan: Button
    private lateinit var btnFixSelected: Button
    private lateinit var btnSelectAll: Button
    private lateinit var btnThreshold: Button
    private lateinit var tvStatus: TextView

    private val mediaItems = mutableListOf<MediaItem>()

    // 判定「时间异常」的阈值（秒），可通过按钮循环切换
    private var thresholdSeconds = 60 * 60L  // 默认 1 小时
    private val thresholdOptions = arrayOf(
        "1 分钟" to 60L,
        "10 分钟" to 600L,
        "1 小时" to 3600L,
        "1 天" to 86400L,
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        recyclerView = findViewById(R.id.recyclerView)
        btnScan = findViewById(R.id.btnScan)
        btnFixSelected = findViewById(R.id.btnFixSelected)
        btnSelectAll = findViewById(R.id.btnSelectAll)
        btnThreshold = findViewById(R.id.btnThreshold)
        tvStatus = findViewById(R.id.tvStatus)

        updateThresholdButton()

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
        btnThreshold.setOnClickListener { cycleThreshold() }

        checkPermissionsAndScan()
    }

    private fun updateThresholdButton() {
        val label = thresholdOptions.firstOrNull { it.second == thresholdSeconds }?.first ?: "1 小时"
        btnThreshold.text = "阈值：$label"
    }

    private fun cycleThreshold() {
        val idx = thresholdOptions.indexOfFirst { it.second == thresholdSeconds }
        val next = thresholdOptions[(idx + 1) % thresholdOptions.size]
        thresholdSeconds = next.second
        updateThresholdButton()
        adapter.notifyDataSetChanged()
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
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_MEDIA_IMAGES)
                != PackageManager.PERMISSION_GRANTED
            ) {
                readMediaLauncher.launch(arrayOf(Manifest.permission.READ_MEDIA_IMAGES))
                return
            }
        } else if (Build.VERSION.SDK_INT <= 32) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE)
                != PackageManager.PERMISSION_GRANTED
            ) {
                readMediaLauncher.launch(arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE))
                return
            }
        }

        if (Build.VERSION.SDK_INT >= 30 && !Environment.isExternalStorageManager()) {
            Toast.makeText(this, "请授予「所有文件访问权限」以修改照片时间", Toast.LENGTH_LONG).show()
            val intent = Intent(
                Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                Uri.parse("package:$packageName")
            )
            manageStorageLauncher.launch(intent)
            return
        }

        scanAndIdentify()
    }

    // ── 扫描 + 识别 ────────────────────────────────────────

    private fun scanAndIdentify() {
        tvStatus.text = "正在扫描相册……"
        btnScan.isEnabled = false
        Thread {
            val items = queryMedia()
            runOnUiThread {
                mediaItems.clear()
                mediaItems.addAll(items)
                adapter.notifyDataSetChanged()
                btnScan.isEnabled = true
                val abnormal = items.count { it.isAbnormal(thresholdSeconds) }
                tvStatus.text = "共 ${items.size} 张照片，疑似时间异常 $abnormal 张"
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
            Toast.makeText(this, "请先勾选要处理的照片", Toast.LENGTH_SHORT).show()
            return
        }
        fixItems(selected)
    }

    private fun fixItems(items: List<MediaItem>) {
        tvStatus.text = "正在处理 ${items.size} 张……"
        btnFixSelected.isEnabled = false
        Thread {
            var ok = 0
            var fail = 0
            for (item in items) {
                if (fixOne(item)) ok++ else fail++
            }
            runOnUiThread {
                btnFixSelected.isEnabled = true
                tvStatus.text = "处理完成：成功 $ok，失败 $fail"
                Toast.makeText(this, "处理完成：成功 $ok，失败 $fail", Toast.LENGTH_LONG).show()
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
        adapter.notifyDataSetChanged()
        btnSelectAll.text = if (target) "取消全选" else "全选异常"
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
            holder.tvInfo.text = "拍摄 $taken · 文件 $modified"
            holder.tvInfo.setTextColor(
                if (item.isAbnormal(thresholdSeconds)) 0xFFE53935.toInt() else 0xFF616161.toInt()
            )
            // 缩略图（异步加载，避免阻塞列表滚动）
            holder.ivThumb.setImageBitmap(null)
            loadThumbnailAsync(item) { bmp ->
                if (holder.bindingAdapterPosition == position) {
                    holder.ivThumb.setImageBitmap(bmp)
                }
            }
            // 点击预览图跳转到相册对应照片
            holder.ivThumb.setOnClickListener { onOpen(item) }
        }

        override fun getItemCount(): Int = items.size
    }

    private fun formatTime(millis: Long): String {
        if (millis <= 0) return "未知"
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
            Toast.makeText(this, "无法打开相册", Toast.LENGTH_SHORT).show()
        }
    }
}
