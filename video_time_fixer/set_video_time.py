# -*- coding: utf-8 -*-
# SPDX-License-Identifier: GPL-3.0-or-later
# Copyright (C) 2026 SiRan-Dev
#
# This program is free software: you can redistribute it and/or modify
# it under the terms of the GNU General Public License as published by
# the Free Software Foundation, either version 3 of the License, or
# (at your option) any later version.
#
# This program is distributed in the hope that it will be useful,
# but WITHOUT ANY WARRANTY; without even the implied warranty of
# MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
# GNU General Public License for more details.
#
# You should have received a copy of the GNU General Public License
# along with this program.  If not, see <https://www.gnu.org/licenses/>.
"""
把脚本所在文件夹（含子文件夹）下所有视频文件的【文件时间】统一为各自的【拍摄时间】。

依赖：ExifTool（自动定位：脚本目录 → 脚本目录/tools → 系统 PATH）。
用法：直接运行本脚本，或双击同目录下的「设置视频时间.bat」。
"""

import datetime
import os
import re
import shutil
import subprocess
import sys

# 强制 UTF-8 输出，配合 .bat 中的 chcp 65001 正确显示中文
try:
    sys.stdout.reconfigure(encoding="utf-8")
    sys.stderr.reconfigure(encoding="utf-8")
except Exception:
    pass

VIDEO_EXTENSIONS = {'.mp4', '.mov'}

# 拍摄时间标签读取优先级：DateTimeOriginal（+08:00）→ Keys:CreationDate（+08:00）→ QuickTime:CreateDate（UTC）
DATE_TAGS = [
    "-DateTimeOriginal",
    "-Keys:CreationDate",
    "-UserData:DateTimeOriginal",
    "-QuickTime:CreateDate",
]


def find_exiftool():
    """定位 ExifTool 可执行文件（脚本目录 → tools → 常见安装位置 → PATH）"""
    script_dir = os.path.dirname(os.path.abspath(__file__))
    candidates = [
        os.path.join(script_dir, "exiftool.exe"),
        os.path.join(script_dir, "tools", "exiftool.exe"),
        os.path.join(script_dir, "exiftool_files", "exiftool.exe"),
        # 常见安装位置（按需补充）
        r"D:\PortableApps\LivePhotoConvert\tools\exiftool.exe",
        r"C:\PortableApps\LivePhotoConvert\tools\exiftool.exe",
        r"C:\exiftool\exiftool.exe",
        r"C:\Tools\exiftool.exe",
    ]
    for c in candidates:
        if os.path.isfile(c):
            return c
    found = shutil.which("exiftool")
    if found:
        return found
    return None


def parse_datetime(text):
    """解析 exiftool 时间字符串，返回本地时间（东八区）。不带时区的按 UTC+8 处理。"""
    if not text or not text.strip():
        return None
    text = text.strip()
    if text.startswith("0000"):
        return None
    m = re.match(r"^(\d{4}):(\d{2}):(\d{2}) (\d{2}):(\d{2}):(\d{2})([+-]\d{2}:\d{2})?$", text)
    if not m:
        return None
    y, mo, d, h, mi, sec = (int(m.group(i)) for i in range(1, 7))
    tz = m.group(7)
    if tz:
        # 已带时区（通常是 +08:00），直接作为本地时间
        return datetime.datetime(y, mo, d, h, mi, sec)
    # 无时区：QuickTime CreateDate 为 UTC，转为东八区本地时间
    return datetime.datetime(y, mo, d, h, mi, sec) + datetime.timedelta(hours=8)


def read_taken_time(exiftool, video_path):
    """读取视频拍摄时间，返回本地 datetime 或 None"""
    try:
        result = subprocess.run(
            [exiftool, "-s3"] + DATE_TAGS + [video_path],
            capture_output=True, text=True, encoding="utf-8", errors="replace",
        )
    except OSError as exc:
        print(f"  无法调用 ExifTool：{exc}")
        return None
    if result.returncode != 0:
        return None
    # 输出可能是多行（每个 tag 一行），按优先级取第一个有效时间
    for line in result.stdout.splitlines():
        parsed = parse_datetime(line)
        if parsed:
            return parsed
    return None


def collect_videos(script_dir):
    """收集脚本目录（含子目录）下的视频文件"""
    videos = []
    for root, _, files in os.walk(script_dir):
        for name in files:
            if os.path.splitext(name)[1].lower() in VIDEO_EXTENSIONS:
                videos.append(os.path.join(root, name))
    return sorted(videos)


def wait_exit():
    """等待按键退出（非交互环境自动跳过）"""
    try:
        input("按回车键退出...")
    except EOFError:
        pass


def main():
    script_dir = os.path.dirname(os.path.abspath(__file__))
    exiftool = find_exiftool()
    if exiftool is None:
        print("未找到 ExifTool！请将 exiftool.exe 放到本脚本目录或其 tools 子目录下，")
        print("或加入系统 PATH。ExifTool 下载：https://exiftool.org/")
        wait_exit()
        return 1

    videos = collect_videos(script_dir)
    if not videos:
        print("本文件夹（含子文件夹）下没有找到视频文件（.mp4/.mov）。")
        wait_exit()
        return 0

    print(f"共找到 {len(videos)} 个视频，开始处理……\n")
    ok = 0
    failed = []
    for video in videos:
        rel = os.path.relpath(video, script_dir)
        taken = read_taken_time(exiftool, video)
        if taken is None:
            failed.append((rel, "无法读取拍摄时间"))
            print(f"  [跳过] {rel}：无法读取拍摄时间")
            continue
        timestamp = taken.timestamp()
        os.utime(video, (timestamp, timestamp))
        ok += 1
        print(f"  [完成] {rel} -> {taken.strftime('%Y-%m-%d %H:%M:%S')}")

    print("\n" + "=" * 50)
    print(f"处理完成：成功 {ok} 个，跳过 {len(failed)} 个。")
    if failed:
        print("以下文件未处理：")
        for rel, reason in failed:
            print(f"  {rel}：{reason}")
    print("=" * 50)
    wait_exit()
    return 0


if __name__ == "__main__":
    sys.exit(main())
