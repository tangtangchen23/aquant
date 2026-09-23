@echo off
REM QuantApp Gateway - 注册为 Windows 服务（NSSM）
REM 服务名：QuantAppGateway，开机自启
chcp 65001 >nul
cd /d "%~dp0"

set SERVICE_NAME=QuantAppGateway
set NSSM_URL=https://nssm.cc/release/nssm-2.24.zip
set NSSM_DIR=%cd%\tools\nssm

echo ============================================================
echo  注册服务 %SERVICE_NAME%
echo ============================================================

REM 管理员检测（需要管理员权限装服务）
net session >nul 2>&1
if errorlevel 1 (
    echo [错误] 请右键本文件 - 以管理员身份运行
    pause
    exit /b 1
)

if not exist ".venv" (
    echo 先运行 install.bat 安装
    pause
    exit /b 1
)

REM 下载 NSSM
where nssm >nul 2>&1
if errorlevel 1 (
    if not exist "%NSSM_DIR%\nssm.exe" (
        echo [1/3] 下载 NSSM ...
        mkdir tools 2>nul
        powershell -Command "Invoke-WebRequest -Uri '%NSSM_URL%' -OutFile '%cd%\tools\nssm.zip'" || goto :download_failed
        echo        解压 ...
        powershell -Command "Expand-Archive -Path '%cd%\tools\nssm.zip' -DestinationPath '%NSSM_DIR%' -Force" || goto :unzip_failed
    )
    set NSSM=%NSSM_DIR%\win64\nssm.exe
) else (
    set NSSM=nssm
)

echo [2/3] 注册服务 %SERVICE_NAME% ...
"%NSSM%" install %SERVICE_NAME% "%cd%\.venv\Scripts\python.exe" "%cd%\gateway.py"
"%NSSM%" set %SERVICE_NAME% AppDirectory "%cd%"
"%NSSM%" set %SERVICE_NAME% AppStdout "%cd%\logs\service.stdout.log"
"%NSSM%" set %SERVICE_NAME% AppStderr  "%cd%\logs\service.stderr.log"
"%NSSM%" set %SERVICE_NAME% AppRotateFiles 1
"%NSSM%" set %SERVICE_NAME% Start SERVICE_AUTO_START
"%NSSM%" set %SERVICE_NAME% DisplayName "QuantApp Broker Gateway"
"%NSSM%" set %SERVICE_NAME% Description "QuantApp 实盘网关：对接银河/中航/EasyTrader券商客户端"

echo [3/3] 启动服务 ...
"%NSSM%" start %SERVICE_NAME%

echo.
echo ============================================================
echo  服务已注册并启动：%SERVICE_NAME%
echo  状态： sc query %SERVICE_NAME%
echo  日志：%cd%\logs\service.stdout.log
echo  卸载：run remove_service.bat
echo ============================================================
timeout /t 4 >nul
exit /b 0

:download_failed
echo 下载 NSSM 失败。请手动下载 https://nssm.cc/release/nssm-2.24.zip
echo 解压后把 win64\nssm.exe 放到 tools\nssm\win64\nssm.exe 再试
pause
exit /b 1

:unzip_failed
echo 解压 NSSM 失败，请手动解压
pause
exit /b 1
