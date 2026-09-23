@echo off
REM QuantApp Gateway - 后台静默启动
REM 窗口会立即关闭，日志写入 logs/
chcp 65001 >nul
cd /d "%~dp0"
if not exist ".venv" (
    echo 先运行 install.bat 安装
    pause
    exit /b 1
)
if not exist "config.ini" (
    echo 先编辑 config.ini（复制自 config.ini.example）
    pause
    exit /b 1
)
if not exist "logs" mkdir logs
start "QuantAppGateway" /MIN cmd /c "call .venv\Scripts\activate && python gateway.py >> logs\gateway_stdout.log 2>&1"
echo 网关已在后台启动，日志见 logs\gateway_stdout.log
timeout /t 3 >nul
