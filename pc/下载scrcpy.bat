@echo off
rem ===========================================================================
rem  Download scrcpy (PC controls the phone / phone mirroring) for KeyVoice.
rem  Everything is done by get_scrcpy.py next to this file; this wrapper only
rem  finds a Python interpreter and keeps the window open.
rem  Manual fallback: https://github.com/Genymobile/scrcpy/releases/latest
rem  Unzip the win64 package into: pc\tools\scrcpy\  (scrcpy.exe must be there)
rem ===========================================================================
cd /d "%~dp0"

where python >nul 2>&1
if %errorlevel%==0 goto :runpy

where py >nul 2>&1
if %errorlevel%==0 goto :runpy_launcher

echo.
echo   Python not found. Please install Python 3 (tick "Add to PATH"),
echo   or download scrcpy manually:
echo     https://github.com/Genymobile/scrcpy/releases/latest
echo   and unzip into: %~dp0tools\scrcpy\
echo.
pause
exit /b 1

:runpy
python "%~dp0get_scrcpy.py"
goto :done

:runpy_launcher
py -3 "%~dp0get_scrcpy.py"
goto :done

:done
echo.
pause
exit /b 0
