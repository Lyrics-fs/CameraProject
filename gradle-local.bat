@echo off
setlocal
rem 使用本机已解压的 Gradle，不经过 gradlew 下载发行版。
rem 若 Gradle 安装路径不同，可设置环境变量 GRADLE_INSTALL 覆盖默认值。
if not defined GRADLE_INSTALL set "GRADLE_INSTALL=D:\softwareDownload\gradle-8.10.2-bin\gradle-8.10.2"
cd /d "%~dp0"
if not exist "%GRADLE_INSTALL%\bin\gradle.bat" (
  echo ERROR: 未找到 "%GRADLE_INSTALL%\bin\gradle.bat"
  echo 请安装 Gradle 或设置 GRADLE_INSTALL 为解压目录（内含 bin\gradle.bat）。
  exit /b 1
)
if "%~1"=="" (
  call "%GRADLE_INSTALL%\bin\gradle.bat" :app:assembleDebug
) else (
  call "%GRADLE_INSTALL%\bin\gradle.bat" %*
)
exit /b %ERRORLEVEL%
