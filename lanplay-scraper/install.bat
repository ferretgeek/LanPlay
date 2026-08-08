@echo off
chcp 65001 >nul
setlocal
cd /d "%~dp0"
set "PYTHONUTF8=1"

where py >nul 2>nul
if errorlevel 1 (
  echo [失败] 没有找到 Python。请先安装 Python 3.11 或更高版本，并勾选“Add Python to PATH”。
  pause
  exit /b 1
)

py -3.11 -c "import sys; raise SystemExit(0 if sys.version_info >= (3, 11) else 1)"
if errorlevel 1 (
  echo [失败] 需要 Python 3.11 或更高版本，且必须可通过 py -3.11 启动。
  pause
  exit /b 1
)

py -3.11 -m venv ".venv"
if errorlevel 1 (
  echo [失败] 无法创建 Python 虚拟环境。
  pause
  exit /b 1
)

".venv\Scripts\python.exe" -m pip --version >nul
if errorlevel 1 goto :failed
".venv\Scripts\python.exe" -m pip install --require-hashes -r "requirements.lock"
if errorlevel 1 goto :failed

echo.
echo [完成] 依赖安装成功。以后双击 run.bat 即可。
pause
exit /b 0

:failed
echo.
echo [失败] 安装没有完成，请检查上方错误和网络或代理设置。
pause
exit /b 1
