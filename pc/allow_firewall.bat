@echo off
chcp 65001 >nul
setlocal enabledelayedexpansion

rem ===========================================================================
rem  KeyVoice 防火墙一键放行
rem ---------------------------------------------------------------------------
rem  做两件事，缺一不可：
rem
rem  【1】解除针对 Python 的入站「阻止」规则（关键，最容易被忽略）
rem    Windows 防火墙的规则里【阻止优先于允许】。首次运行接收端时系统会弹
rem    「是否允许 Python 访问网络」，如果当时点了取消，系统就留下一条名为
rem    Python 的入站阻止规则——此后无论怎么放行端口，接收端都收不到任何
rem    入站数据。症状极具迷惑性：电脑能发现手机，手机却永远「未发现设备」。
rem    这里用「禁用」而不是「删除」，随时可以改回 enable=yes 恢复。
rem
rem  【2】放行三个端口
rem    TCP 17890  文字接收（手机 → 电脑）
rem    UDP 17891  自动发现·老协议（仅电脑应答）
rem    UDP 17893  自动发现·新协议（电脑与手机都应答，支持多设备互发现）
rem
rem  用法：直接双击本文件，在弹出的 UAC 窗口点「是」。
rem        （脚本会自己请求管理员权限，不必右键「以管理员身份运行」）
rem ===========================================================================

rem ---- 准备自身路径 ----
rem 项目可能位于中文目录（如 D:\下载\...），把含中文的路径交给提权进程有编码风险，
rem 优先取 8.3 短路径（纯 ASCII）；短名被禁用时再退回完整路径。
set "SELF=%~f0"
for %%I in ("%~f0") do set "SELF8=%%~sI"
if defined SELF8 set "SELF=%SELF8%"

rem ---- 自提权 ----
net session >nul 2>&1
if "%errorlevel%"=="0" goto :run

echo.
echo   需要管理员权限，正在请求提权……
echo   请在弹出的「用户账户控制」窗口点【是】。
echo.
powershell -NoProfile -ExecutionPolicy Bypass -Command "Start-Process -FilePath '%SELF%' -Verb RunAs"
exit /b

:run
echo.
echo   [管理员权限已获取]
echo.

rem ---------- 1. 解除 Python 入站阻止规则 ----------
rem 阻止规则绑定到具体程序路径，规则名取自 exe 的文件描述，
rem 常见写法有几种，逐个尝试，命中即禁用。
echo   [1/2] 检查并解除 Python 入站阻止规则……
set "KVDIS=0"
for %%N in (Python python.exe Python3 pythonw.exe) do (
    netsh advfirewall firewall show rule name="%%N" dir=in >"%TEMP%\kv_pyfw.txt" 2>&1
    findstr /I /C:"%%N" "%TEMP%\kv_pyfw.txt" >nul 2>&1
    if not errorlevel 1 (
        netsh advfirewall firewall set rule name="%%N" dir=in new enable=no >nul 2>&1
        if errorlevel 1 (
            echo         规则 "%%N"：存在但禁用失败
        ) else (
            echo         规则 "%%N"：已禁用
            set "KVDIS=1"
        )
    )
)
del /q "%TEMP%\kv_pyfw.txt" >nul 2>&1
if "!KVDIS!"=="0" echo         未发现 Python 阻止规则（可能已处理过，或规则名不在常见之列）。

rem ---------- 2. 放行端口 ----------
echo.
echo   [2/2] 放行端口 17890 / 17891 / 17893……
netsh advfirewall firewall delete rule name="KeyVoice TCP 17890" >nul 2>&1
netsh advfirewall firewall delete rule name="KeyVoice UDP 17891" >nul 2>&1
netsh advfirewall firewall delete rule name="KeyVoice UDP 17893" >nul 2>&1

netsh advfirewall firewall add rule name="KeyVoice TCP 17890" dir=in action=allow protocol=TCP localport=17890
netsh advfirewall firewall add rule name="KeyVoice UDP 17891" dir=in action=allow protocol=UDP localport=17891
netsh advfirewall firewall add rule name="KeyVoice UDP 17893" dir=in action=allow protocol=UDP localport=17893

echo.
echo   ---- 核对结果（三条都应为「启用 / 允许」）----
netsh advfirewall firewall show rule name="KeyVoice TCP 17890" | findstr /C:"启用" /C:"Enabled" /C:"操作" /C:"Action" /C:"允许" /C:"Allow"
netsh advfirewall firewall show rule name="KeyVoice UDP 17891" | findstr /C:"启用" /C:"Enabled" /C:"操作" /C:"Action" /C:"允许" /C:"Allow"
netsh advfirewall firewall show rule name="KeyVoice UDP 17893" | findstr /C:"启用" /C:"Enabled" /C:"操作" /C:"Action" /C:"允许" /C:"Allow"

echo.
echo   完成。现在重新打开 KeyVoice 接收端，手机点「搜索设备」就能看到这台电脑了。
echo.
echo   ---- 如需恢复原状（用管理员身份执行）----
echo     恢复 Python 阻止规则： netsh advfirewall firewall set rule name="Python" dir=in new enable=yes
echo     删除端口放行规则：   netsh advfirewall firewall delete rule name="KeyVoice TCP 17890"
echo                          netsh advfirewall firewall delete rule name="KeyVoice UDP 17891"
echo                          netsh advfirewall firewall delete rule name="KeyVoice UDP 17893"
echo.
pause
endlocal
