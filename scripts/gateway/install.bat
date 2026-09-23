@echo off
REM QuantApp Gateway 一键安装脚本
REM 双击运行。成功后会创建 .venv 和 config.ini

chcp 65001 >nul
cd /d "%~dp0"

echo ============================================================
echo  QuantApp Broker Gateway - 首次安装
echo ============================================================

REM ---- 1. 检查 Python 3.9+ ----
where python >nul 2>&1
if errorlevel 1 (
    echo [错误] 未检测到 Python。请先安装 Python 3.9~3.11 64 位：
    echo        https://www.python.org/downloads/release/python-3119/
    echo        安装时请勾选 "Add Python to PATH"
    pause
    exit /b 1
)

python --version
echo.

REM ---- 2. 创建虚拟环境 ----
if not exist ".venv" (
    echo [1/4] 创建虚拟环境 .venv ...
    python -m venv .venv
) else (
    echo [1/4] 虚拟环境已存在，跳过
)

call .venv\Scripts\activate.bat

REM ---- 3. 升级 pip + 装依赖 ----
echo [2/4] 升级 pip ...
python -m pip install --upgrade pip
echo.

echo [3/4] 安装依赖（requirements.txt）...
pip install -r requirements.txt
echo.

REM ---- 4. 生成 config.ini ----
if not exist "config.ini" (
    echo [4/4] 生成 config.ini ...
    copy config.ini.example config.ini >nul
    echo       请编辑 config.ini，把 secret 改成自己的随机字符串
    echo       至少 32 位，例如：QUANTAPP_$(openssl rand 16 | xxd -p 2>nul || echo 直接改就行)
) else (
    echo [4/4] config.ini 已存在，保留不覆盖
)

echo.
echo ============================================================
echo  安装完成！下一步：
echo   1. 编辑 config.ini（改 secret）
echo   2. 登录券商客户端（银河/中航 QMT、通达信等）并保持运行
echo   3. 双击 run_debug.bat 调试启动（带窗口能看到日志）
echo   4. 验证：浏览器访问 http://127.0.0.1:8765/ping
echo   5. 日常使用双击 run.bat 后台启动
echo   6. 想开机自启：双击 install_service.bat
echo ============================================================
echo.
pause
