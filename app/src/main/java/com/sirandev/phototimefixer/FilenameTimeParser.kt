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

import java.util.Calendar

/**
 * 从照片文件名解析拍摄时间的工具。
 *
 * 支持在文件名任意位置搜索「日期+时间」或「仅日期」模式，覆盖常见命名：
 * IMG_20230905_143022.jpg、MVIMG_20260828_170650.jpg、小米毫秒后缀
 * IMG_20230905_143022123.jpg、Screenshot_2023-09-05_14-30-22.png、
 * PXL_20260828_170650123.MP.jpg、IMG-20230905-WA0001.jpg（仅日期）等。
 *
 * 字段越界（如月份 > 12）视为无效，避免把普通数字串误判为时间；
 * 仅日期的文件名取当日 12:00，保证日内排序合理。
 * 解析失败返回 0。
 */
object FilenameTimeParser {

    /** 日期 + 时间，中间需有分隔符，如 20230905_143022、2023-09-05_14-30-22。 */
    val dateTimeRegex = Regex(
        """(\d{4})[-_/.]?(\d{2})[-_/.]?(\d{2})[\s_T-]+(\d{2})[:.\-_]?(\d{2})(?:[:.\-_]?(\d{2}))?"""
    )

    /** 仅日期，如 IMG-20230905-WA0001（时间信息缺失，取当日 12:00）。 */
    private val dateOnlyRegex = Regex("""(\d{4})[-_/.]?(\d{2})[-_/.]?(\d{2})(?!\d)""")

    fun parse(name: String): Long {
        val base = name.substringBeforeLast('.')
        dateTimeRegex.find(base)?.let { m ->
            val g = m.groupValues
            val year = g[1].toIntOrNull()
            val month = g[2].toIntOrNull()
            val day = g[3].toIntOrNull()
            val hour = g[4].toIntOrNull()
            val minute = g[5].toIntOrNull()
            val second = g.getOrNull(6)?.toIntOrNull() ?: 0
            if (year != null && month != null && day != null && hour != null && minute != null) {
                return buildMillis(year, month, day, hour, minute, second)
            }
        }
        dateOnlyRegex.find(base)?.let { m ->
            val year = m.groupValues[1].toIntOrNull() ?: return 0
            val month = m.groupValues[2].toIntOrNull() ?: return 0
            val day = m.groupValues[3].toIntOrNull() ?: return 0
            return buildMillis(year, month, day, hour = 12, minute = 0, second = 0)
        }
        return 0
    }

    /** 校验并组合时间为毫秒；字段越界（如月份 > 12）返回 0。 */
    private fun buildMillis(year: Int, month: Int, day: Int, hour: Int, minute: Int, second: Int): Long {
        if (year !in 1970..2100 || month !in 1..12 || day !in 1..31 ||
            hour !in 0..23 || minute !in 0..59 || second !in 0..59
        ) {
            return 0
        }
        val cal = Calendar.getInstance()
        cal.clear()
        cal.set(year, month - 1, day, hour, minute, second)
        return cal.timeInMillis
    }

    /**
     * 按新时间改写文件名中的时间部分，保留前缀/分隔符风格与扩展名：
     * IMG_20230905_143022.jpg → IMG_20250101_093045.jpg（紧凑风格）
     * Screenshot_2023-09-05_14-30-22.png → Screenshot_2025-01-01_09-30-45.png（分隔风格）
     * 小米毫秒后缀（_143022123 的 "123"）会被移除，避免新名字携带过期毫秒。
     * 通过正则捕获组（年/月/日/时/分/秒各自的数字区间）逐一替换，
     * 各分组间的分隔符原样保留；原文件名缺秒时新秒数不写入。
     * 文件名不含可解析的日期+时间时返回 null。
     */
    fun buildRenamedName(name: String, millis: Long): String? {
        val dot = name.lastIndexOf('.')
        val base = if (dot > 0) name.substring(0, dot) else name
        val ext = if (dot > 0) name.substring(dot) else ""
        val m = dateTimeRegex.find(base) ?: return null

        val cal = Calendar.getInstance()
        cal.clear()
        cal.timeInMillis = millis
        val numbers = listOf(
            cal.get(Calendar.YEAR),
            cal.get(Calendar.MONTH) + 1,
            cal.get(Calendar.DAY_OF_MONTH),
            cal.get(Calendar.HOUR_OF_DAY),
            cal.get(Calendar.MINUTE),
            cal.get(Calendar.SECOND),
        )
        val sb = StringBuilder(base)
        // 从后往前替换各捕获组，避免前面替换改变后面区间位置
        for (group in 6 downTo 1) {
            val range = m.groups[group]?.range ?: continue // 原名缺该字段（如秒）时跳过
            val width = range.last - range.first + 1
            val value = numbers[group - 1].toString()
            val replacement = if (value.length > width) value.takeLast(width) else value.padStart(width, '0')
            sb.replace(range.first, range.last + 1, replacement)
        }

        // 移除紧随其后的亚秒数字（如 _143022123 中被截断的 "123"）
        var after = base.substring(m.range.last + 1)
        val subSecond = after.takeWhile { it.isDigit() }
        if (subSecond.length in 1..3) {
            after = after.substring(subSecond.length)
        }
        return base.substring(0, m.range.first) + sb.substring(m.range.first, m.range.last + 1) + after + ext
    }
}
