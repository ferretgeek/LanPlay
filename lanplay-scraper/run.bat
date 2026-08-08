@echo off
chcp 65001 >nul
setlocal
cd /d "%~dp0"
set "PYTHONUTF8=1"

if not exist "config.toml" (
  copy /y "config.example.toml" "config.toml" >nul
  echo [需要配置] 已生成 config.toml，请填写 video_dir 和 output_dir 后重新运行。
  pause
  exit /b 2
)
findstr /c:"<VIDEO_DIR>" /c:"<OUTPUT_DIR>" "config.toml" >nul
if not errorlevel 1 (
  echo [需要配置] config.toml 仍包含占位符，请填写真实目录后重新运行。
  pause
  exit /b 2
)

if not exist ".venv\Scripts\python.exe" (
  echo [提示] 尚未安装运行环境，正在先执行一次安装……
  call "install.bat"
  if errorlevel 1 exit /b 1
)

".venv\Scripts\python.exe" "scraper.py" --config "config.toml"
set "LANPLAY_EXIT=%ERRORLEVEL%"
echo.
if "%LANPLAY_EXIT%"=="0" (
  echo [完成] 刮削任务已结束。
) else (
  echo [失败] 刮削任务异常退出，错误码 %LANPLAY_EXIT%。
)
pause
exit /b %LANPLAY_EXIT%
