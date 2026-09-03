@echo off
rem SPDX-License-Identifier: GPL-3.0-or-later
rem Copyright (C) 2026 SiRan-Dev. Licensed under GPL v3.
rem See https://www.gnu.org/licenses/gpl-3.0.html for details.
chcp 65001 >nul
title Set Video Time
echo ==================================================
echo   Set all videos' file time to their taken time
echo   in this folder (including subfolders).
echo ==================================================
echo.
python "%~dp0set_video_time.py"
if errorlevel 1 (
    echo.
    echo [ERROR] Run failed. Make sure Python 3 is installed.
)
echo.
pause
