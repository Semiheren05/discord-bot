@echo off
chcp 65001 >nul
title Moderasyon Botu
cd /d "%~dp0"

rem ---------- Java'yı bul ----------
set "JAVA_EXE="
if defined JAVA_HOME if exist "%JAVA_HOME%\bin\java.exe" set "JAVA_EXE=%JAVA_HOME%\bin\java.exe"
if not defined JAVA_EXE (
  for /d %%D in ("%USERPROFILE%\.jdk\*") do (
    if exist "%%D\bin\javac.exe" (
      set "JAVA_HOME=%%D"
      set "JAVA_EXE=%%D\bin\java.exe"
    )
  )
)
if not defined JAVA_EXE (
  where java >nul 2>nul && set "JAVA_EXE=java"
)
if not defined JAVA_EXE (
  echo.
  echo [HATA] Java bulunamadi. https://adoptium.net adresinden Java 21 veya ustunu kur.
  pause
  exit /b 1
)

rem ---------- Ayar dosyası ----------
if not exist "config.properties" (
  copy /y "config.example.properties" "config.properties" >nul
  echo.
  echo Ilk calistirma: config.properties olusturuldu.
  echo Acilan Not Defteri'nde token= satirina botunun token'ini yapistir, kaydet ve kapat.
  echo.
  notepad "config.properties"
)

rem ---------- İlk seferde derle ----------
if not exist "ModBot.jar" (
  echo.
  echo Bot derleniyor, ilk seferde birkac dakika surebilir...
  call gradlew.bat botJar --no-daemon -q
  if errorlevel 1 (
    echo.
    echo [HATA] Derleme basarisiz. Yukaridaki hata mesajini kontrol et.
    pause
    exit /b 1
  )
  copy /y "build\libs\ModBot.jar" "ModBot.jar" >nul
)

rem ---------- Çalıştır ----------
echo.
echo Bot baslatiliyor... Kapatmak icin bu pencereyi kapat.
echo.
"%JAVA_EXE%" -Dfile.encoding=UTF-8 -Dstdout.encoding=UTF-8 -Dstderr.encoding=UTF-8 -Xmx256m -jar ModBot.jar
echo.
echo Bot durdu.
pause
