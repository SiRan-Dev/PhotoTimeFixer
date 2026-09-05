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

import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Calendar

class FilenameTimeParserTest {

    /** 用与实现相同的本地时区构造期望值，保证测试不受时区影响。 */
    private fun expectedMillis(
        year: Int, month: Int, day: Int, hour: Int = 0, minute: Int = 0, second: Int = 0
    ): Long {
        val cal = Calendar.getInstance()
        cal.clear()
        cal.set(year, month - 1, day, hour, minute, second)
        return cal.timeInMillis
    }

    @Test
    fun `标准相机命名`() {
        assertEquals(
            expectedMillis(2023, 9, 5, 14, 30, 22),
            FilenameTimeParser.parse("IMG_20230905_143022.jpg")
        )
    }

    @Test
    fun `小米毫秒后缀命名`() {
        assertEquals(
            expectedMillis(2023, 9, 5, 14, 30, 22),
            FilenameTimeParser.parse("IMG_20230905_143022123.jpg")
        )
    }

    @Test
    fun `截图短横线命名`() {
        assertEquals(
            expectedMillis(2023, 9, 5, 14, 30, 22),
            FilenameTimeParser.parse("Screenshot_20230905-143022.png")
        )
        assertEquals(
            expectedMillis(2023, 9, 5, 14, 30, 22),
            FilenameTimeParser.parse("Screenshot_2023-09-05_14-30-22.png")
        )
    }

    @Test
    fun `动态照片 MVIMG 与 Google PXL 命名`() {
        assertEquals(
            expectedMillis(2026, 8, 28, 17, 6, 50),
            FilenameTimeParser.parse("MVIMG_20260828_170650.jpg")
        )
        assertEquals(
            expectedMillis(2026, 8, 28, 17, 6, 50),
            FilenameTimeParser.parse("PXL_20260828_170650123.MP.jpg")
        )
    }

    @Test
    fun `无秒与点分隔命名`() {
        assertEquals(
            expectedMillis(2023, 9, 5, 14, 30, 0),
            FilenameTimeParser.parse("photo_20230905_1430.jpg")
        )
        assertEquals(
            expectedMillis(2023, 9, 5, 14, 30, 22),
            FilenameTimeParser.parse("IMG_2023_09_05_14.30.22.jpg")
        )
    }

    @Test
    fun `仅日期命名取当日中午`() {
        assertEquals(
            expectedMillis(2023, 9, 5, 12, 0, 0),
            FilenameTimeParser.parse("IMG-20230905-WA0001.jpg")
        )
    }

    @Test
    fun `普通数字串不误判`() {
        assertEquals(0L, FilenameTimeParser.parse("DSC_0001.jpg"))
        assertEquals(0L, FilenameTimeParser.parse("IMG_1234.jpg"))
        assertEquals(0L, FilenameTimeParser.parse("12345678901.jpg"))
        assertEquals(0L, FilenameTimeParser.parse("20230905143022.jpg"))
        assertEquals(0L, FilenameTimeParser.parse("IMG_20231305_143022.jpg")) // 月份 13
        assertEquals(0L, FilenameTimeParser.parse("IMG_20230905_253022.jpg")) // 小时 25
    }

    @Test
    fun `无扩展名与多段后缀`() {
        assertEquals(
            expectedMillis(2023, 9, 5, 14, 30, 22),
            FilenameTimeParser.parse("IMG_20230905_143022")
        )
        assertEquals(
            expectedMillis(2023, 9, 5, 14, 30, 22),
            FilenameTimeParser.parse("IMG_20230905_143022.jpg.jpg")
        )
    }

    // ── buildRenamedName：按 EXIF 时间重命名 ──────────────

    private val renameTarget: Long by lazy { expectedMillis(2025, 1, 1, 9, 30, 45) }

    @Test
    fun `重命名_紧凑风格`() {
        assertEquals("IMG_20250101_093045.jpg", FilenameTimeParser.buildRenamedName("IMG_20230905_143022.jpg", renameTarget))
        assertEquals(
            "MVIMG_20250101_093045.jpg",
            FilenameTimeParser.buildRenamedName("MVIMG_20260828_170650.jpg", renameTarget)
        )
    }

    @Test
    fun `重命名_分隔风格保留原样式`() {
        assertEquals(
            "Screenshot_2025-01-01_09-30-45.png",
            FilenameTimeParser.buildRenamedName("Screenshot_2023-09-05_14-30-22.png", renameTarget)
        )
        assertEquals(
            "IMG_2025_01_01_09.30.45.jpg",
            FilenameTimeParser.buildRenamedName("IMG_2023_09_05_14.30.22.jpg", renameTarget)
        )
    }

    @Test
    fun `重命名_移除毫秒后缀`() {
        assertEquals(
            "IMG_20250101_093045.jpg",
            FilenameTimeParser.buildRenamedName("IMG_20230905_143022123.jpg", renameTarget)
        )
    }

    @Test
    fun `重命名_原名缺秒则新秒不写入`() {
        assertEquals(
            "photo_20250101_0930.jpg",
            FilenameTimeParser.buildRenamedName("photo_20230905_1430.jpg", renameTarget)
        )
    }

    @Test
    fun `重命名_无时间或仅日期不重命名`() {
        assertEquals(null, FilenameTimeParser.buildRenamedName("DSC_0001.jpg", renameTarget))
        // 仅日期的文件名（如 WhatsApp）缺少时间结构，不做重命名
        assertEquals(null, FilenameTimeParser.buildRenamedName("IMG-20230905-WA0001.jpg", renameTarget))
    }

    @Test
    fun `重命名_时间相同时名字不变`() {
        assertEquals(
            "IMG_20250101_093045.jpg",
            FilenameTimeParser.buildRenamedName("IMG_20250101_093045.jpg", renameTarget)
        )
    }
}
