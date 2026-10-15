@echo off
chcp 65001 >nul
rem ===========================================================================
rem  KeyVoice 接收端 一键启动（无黑窗口后台运行）
rem  · 双击本文件即可；会优先用 pythonw（不带控制台窗口）
rem  · 找不到 pythonw 就退回 python（会带一个控制台窗口，便于看日志）
rem  · 想停止：直接关闭 KeyVoice 小窗右上角的 ×
rem ===========================================================================
cd /d "%~dp0"

where pythonw >nul 2>&1
if %errorlevel%==0 (
    echo   正在启动 KeyVoice 接收端（pythonw，无黑窗口）……
    start "" pythonw keyvoice_box.py
    exit /b
)

where python >nul 2>&1
if %errorlevel%==0 (
    echo   没有找到 pythonw，改用 python 启动（会显示控制台日志）……
    start "" python keyvoice_box.py
    exit /b
)

echo   没有找到 Python。请先安装 Python 3 并在安装时勾选 "Add to PATH"，
echo   然后重新双击本文件；或在命令行里执行：
echo       python keyvoice_box.py
pause
