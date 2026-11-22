@echo off
title PixelFerry Send
cd /d "%~dp0"

rem ---- 1. Python: use one that's installed, or fetch a private copy from python.org (no install, no admin) ----
set "PY="
py -3 -c "import ssl" >nul 2>nul && set "PY=py -3"
if not defined PY python -c "import ssl" >nul 2>nul && set "PY=python"
if not defined PY if exist "%~dp0python\python.exe" set "PY="%~dp0python\python.exe""
if not defined PY (
  echo First time only: getting Python from python.org, about 10 MB...
  powershell -NoProfile -ExecutionPolicy Bypass -Command "$ProgressPreference='SilentlyContinue'; [Net.ServicePointManager]::SecurityProtocol='Tls12'; Invoke-WebRequest -UseBasicParsing 'https://www.python.org/ftp/python/3.12.7/python-3.12.7-embed-amd64.zip' -OutFile \"$env:TEMP\pf-python.zip\"; Expand-Archive -Force \"$env:TEMP\pf-python.zip\" '%~dp0python'"
  if exist "%~dp0python\python.exe" (
    set "PY="%~dp0python\python.exe""
  ) else (
    echo Couldn't get Python. Check the internet connection and double-click again.
    pause
    exit /b 1
  )
)

rem ---- 2. Choose the photo folder with a normal Windows window ----
set "FOLDER="
for /f "usebackq delims=" %%F in (`powershell -NoProfile -STA -Command "Add-Type -AssemblyName System.Windows.Forms; $d = New-Object System.Windows.Forms.FolderBrowserDialog; $d.Description = 'Choose the folder with your photos'; if ($d.ShowDialog() -eq 'OK') { $d.SelectedPath }"`) do set "FOLDER=%%F"
if not defined FOLDER (
  echo No folder chosen. Nothing was sent.
  pause
  exit /b 0
)

rem ---- 3. Send ----
%PY% pixelferry_send.py "%FOLDER%"
