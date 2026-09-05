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
    private val dateTimeRegex = Regex(
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
}
