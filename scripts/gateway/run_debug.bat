@echo off
REM QuantApp Gateway - 调试模式（带控制台窗口）
REM 关闭窗口 = 停止网关
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
echo ============================================================
echo  QuantApp Broker Gateway - 调试模式
echo  按 Ctrl+C 停止。日志同写 logs\gateway_debug.log
echo ============================================================
call .venv\Scripts\activate
if not exist "logs" mkdir logs
python gateway.py
pause
