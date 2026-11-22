@echo off
title PixelFerry Send
cd /d "%~dp0"
where py >nul 2>nul && (py -3 pixelferry_send.py & goto :eof)
where python >nul 2>nul && (python pixelferry_send.py & goto :eof)
echo Python 3 is not installed.
echo Opening the Microsoft Store page for Python. Install it, then double-click this file again.
start ms-windows-store://pdp/?productid=9NCVDN91XZQP
pause
