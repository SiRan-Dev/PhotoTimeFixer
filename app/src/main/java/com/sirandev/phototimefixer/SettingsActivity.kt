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

import android.content.Intent
import android.content.res.Configuration
import android.os.Bundle
import android.view.View
import android.widget.ImageButton
import android.widget.RadioGroup
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.edit
import androidx.core.net.toUri
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import com.google.android.material.color.DynamicColors

/**
 * 设置页：时间异常判定阈值 + 项目主页 / 视频脚本下载链接。
 * 阈值变化写入 SharedPreferences，返回主页面时立即生效。
 */
class SettingsActivity : AppCompatActivity() {

    private val prefs by lazy { getSharedPreferences("settings", MODE_PRIVATE) }

    private val Number.dpToPx: Int
        get() = (this.toFloat() * resources.displayMetrics.density + 0.5f).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        // Android 12+：跟随系统壁纸/主题动态取色
        DynamicColors.applyToActivityIfAvailable(this)
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        // 根据 UI 模式显式控制状态栏 / 导航栏前景色
        val isNightMode = resources.configuration.uiMode and
            Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
        WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = !isNightMode
            isAppearanceLightNavigationBars = !isNightMode
        }

        // 为状态栏 / 手势条预留安全空间
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.settingsRoot)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            val displayCutout = insets.getInsets(WindowInsetsCompat.Type.displayCutout())
            v.setPadding(
                maxOf(systemBars.left, displayCutout.left, 16.dpToPx),
                maxOf(systemBars.top, displayCutout.top, 8.dpToPx),
                maxOf(systemBars.right, displayCutout.right, 16.dpToPx),
                maxOf(systemBars.bottom, displayCutout.bottom, 16.dpToPx)
            )
            insets
        }

        // 返回按钮
        findViewById<ImageButton>(R.id.btnBack).setOnClickListener { finish() }

        // 版本号
        findViewById<TextView>(R.id.tvVersion).text =
            getString(R.string.version_name, packageManager.getPackageInfo(packageName, 0).versionName)

        // 阈值单选
        val rg = findViewById<RadioGroup>(R.id.rgThreshold)
        when (prefs.getLong("threshold_seconds", 3600L)) {
            60L -> rg.check(R.id.rbThresholdMin)
            600L -> rg.check(R.id.rbThresholdMin10)
            86400L -> rg.check(R.id.rbThresholdDay)
            else -> rg.check(R.id.rbThresholdHour)
        }
        rg.setOnCheckedChangeListener { _, checkedId ->
            val seconds = when (checkedId) {
                R.id.rbThresholdMin -> 60L
                R.id.rbThresholdMin10 -> 600L
                R.id.rbThresholdDay -> 86400L
                else -> 3600L
            }
            prefs.edit { putLong("threshold_seconds", seconds) }
        }

        // 链接
        findViewById<View>(R.id.rowHomepage).setOnClickListener { openUrl(getString(R.string.url_homepage)) }
        findViewById<View>(R.id.rowVideoScript).setOnClickListener { openUrl(getString(R.string.url_video_script)) }
    }

    private fun openUrl(url: String) {
        startActivity(Intent(Intent.ACTION_VIEW, url.toUri()))
    }
}