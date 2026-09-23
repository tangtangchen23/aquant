@echo off
REM 卸载 QuantApp Gateway Windows 服务
chcp 65001 >nul
set SERVICE_NAME=QuantAppGateway

net session >nul 2>&1
if errorlevel 1 (
    echo [错误] 请右键本文件 - 以管理员身份运行
    pause
    exit /b 1
)

where nssm >nul 2>&1
if errorlevel 1 (
    set NSSM=%~dp0tools\nssm\win64\nssm.exe
    if not exist "%NSSM%" (
        echo 找不到 nssm.exe，尝试用 sc 原生卸载
        sc stop %SERVICE_NAME% 2>nul
        sc delete %SERVICE_NAME% 2>nul
        goto :done
    )
) else (
    set NSSM=nssm
)

"%NSSM%" stop %SERVICE_NAME%
"%NSSM%" remove %SERVICE_NAME% confirm
echo 服务 %SERVICE_NAME% 已卸载
:done
pause
