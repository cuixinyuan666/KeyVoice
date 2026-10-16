@echo off
rem ===========================================================================
rem  Start the KeyVoice receiver (pc\keyvoice_box.py) in the background.
rem  Uses pythonw when available (no console window), falls back to python.
rem  Stop it from the receiver window (the X button).
rem ===========================================================================
cd /d "%~dp0"

where pythonw >nul 2>&1
if %errorlevel%==0 goto :pyw

where python >nul 2>&1
if %errorlevel%==0 goto :pywin

echo.
echo   Python not found. Please install Python 3 (tick "Add to PATH"),
echo   then run this file again. Or run manually:  python keyvoice_box.py
echo.
pause
exit /b 1

:pyw
start "" pythonw keyvoice_box.py
exit /b 0

:pywin
start "" python keyvoice_box.py
exit /b 0
