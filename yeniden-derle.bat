@echo off
chcp 65001 >nul
cd /d "%~dp0"
rem Kodu değiştirdiysen botu yeniden derlemek için bunu çalıştır, sonra baslat.bat
if exist "ModBot.jar" del /q "ModBot.jar"
echo ModBot.jar silindi. Simdi baslat.bat'i calistirinca yeniden derlenecek.
pause
