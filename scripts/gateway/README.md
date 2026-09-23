# QuantApp 实盘网关 Windows 侧部署手册

本手册覆盖 **Windows 主机** 上部署 QuantApp 实盘网关的完整流程，支持 **银河证券**、**中航证券**（及其他 EasyTrader/xtquant 兼容券商）。

---

## 目录

1. [架构概览](#1-架构概览)
2. [前置条件](#2-前置条件)
3. [一键安装（推荐）](#3-一键安装推荐)
4. [手动安装](#4-手动安装)
5. [券商客户端登录](#5-券商客户端登录)
6. [启动网关](#6-启动网关)
7. [App 端配置](#7-app-端配置)
8. [注册为 Windows 服务（开机自启）](#8-注册为-windows-服务开机自启)
9. [更新与升级](#9-更新与升级)
10. [常见问题](#10-常见问题)

---

## 1. 架构概览

```
QuantApp Android App
     │  HTTP/JSON（局域网或公网）
     ▼
  gateway.py  (FastAPI, 端口 8765)
     │
     ├─ broker_adapter.EasyTraderAdapter  ──► 银河/中航/通达信/同花顺客户端（进程内存）
     │
     └─ broker_adapter.XtQuantAdapter     ──► MiniQMT（迅投 xtquant 本地端口 58620+）
                                                  │
                                                  ▼
                                            券商柜台（私有 TCP）
```

**关键点**：网关脚本**不持有券商账号密码**。券商账号密码只存在于你手动登录的 Windows 客户端进程里（银河 QMT、中航 QMT、通达信等）。网关进程只是那个客户端的"代理"——通过 EasyTrader 或 xtquant 库与其共享内存/本地端口通信。

---

## 2. 前置条件

| 项目 | 要求 |
|---|---|
| 操作系统 | Windows 10 64 位 / Windows Server 2019/2022 |
| Python | 3.9 – 3.11（64 位）。**不要 3.12+**，EasyTrader 某些依赖不兼容 |
| 网络 | 券商客户端能正常登录下单；App 能访问到网关（同局域网或公网） |
| 云主机 | 可以（阿里云/腾讯云/AWS EC2 最低配 2C2G 即可） |
| 固定 IP | 公网部署建议使用弹性 IP / 包年包月实例，避免重启换 IP |

---

## 3. 一键安装（推荐）

### Step 1：下载/拷贝脚本
把整个 `scripts/gateway/` 目录拷到 Windows 电脑，比如 `D:\QuantApp\gateway\`。

目录结构：
```
gateway/
├─ install.bat           ← 双击运行（首次安装）
├─ run.bat               ← 日常启动（无窗口后台运行）
├─ run_debug.bat         ← 带窗口启动（调试用，能看到实时日志）
├─ install_service.bat   ← 注册为开机自启服务（NSSM）
├─ remove_service.bat    ← 卸载服务
├─ gateway.py            ← FastAPI 主入口
├─ broker_adapter.py     ← 券商适配器（EasyTrader / xtquant）
├─ config.ini.example    ← 配置模板（复制为 config.ini 后修改）
├─ requirements.txt      ← Python 依赖列表
└─ README.md             ← 本文件
```

### Step 2：双击 `install.bat`
脚本会自动：
- 下载 Python 3.11 64 位（如果没装）
- 创建虚拟环境 `.venv`
- pip install -r requirements.txt

### Step 3：编辑 `config.ini`
复制 `config.ini.example` 为 `config.ini`，修改：
```ini
[gateway]
port = 8765
secret = 至少32位随机串_与App端一致

[broker]
# auto / galaxy / avic / xtquant / easytrader
# 银河/通达信选 easytrader；中航 QMT 或银河 QMT 选 xtquant
backend = auto

[logging]
level = INFO
dir = logs
```

### Step 4：继续看 [第 5 节 券商客户端登录](#5-券商客户端登录)

---

## 4. 手动安装

### 4.1 装 Python
去 https://www.python.org/downloads/release/python-3119/ 下载 **64-bit installer**。
安装时勾选 **Add Python to PATH**。

验证：
```cmd
python --version     :: 应显示 Python 3.11.x
pip --version
```

### 4.2 装依赖
```cmd
cd D:\QuantApp\gateway
python -m venv .venv
.venv\Scripts\activate
pip install -r requirements.txt
```

---

## 5. 券商客户端登录

**这一步不能省，而且在网关机上完成。** 网关不负责登录券商，它只连已经登录好的客户端。

### 5.1 银河证券

1. 下载银河证券交易客户端（"至诚版" 或 "银河证券App" PC 端）
2. 或下载银河证券 QMT 极简版：https://www.chinastock.com.cn → 下载中心 → QMT
3. **双击打开客户端 → 输入账号密码 → 登录 → 保持窗口不要关闭**
4. 推荐用 QMT 极简版 + xtquant 模式（更稳定，支持多账户/撤单查询）

### 5.2 中航证券

1. 下载中航证券 QMT 极简版：https://www.avicsec.com → 下载中心 → QMT
2. 或下载通达信版中航证券客户端（`new_zhzq_v6_gm.exe`）
3. **登录后保持客户端运行**
4. QMT 模式下启动后，会在本地开 MiniQMT 端口（默认 58620），xtquant 会自动连；EasyTrader 模式下读客户端进程内存

### 5.3 切换券商适配器

编辑 `config.ini`：
```ini
[broker]
backend = easytrader   ; 银河/通达信
# backend = xtquant   ; QMT 极简版
# backend = auto      ; 自动探测（先 xtquant，再 easytrader）
```

---

## 6. 启动网关

### 6.1 调试模式（首次推荐，能看到日志）
双击 `run_debug.bat`，或命令行：
```cmd
cd D:\QuantApp\gateway
.venv\Scripts\activate
python gateway.py
```

成功输出示例：
```
[INFO] QuantApp Broker Gateway v1.0  监听 http://0.0.0.0:8765
[INFO] 网关密钥已加载
[INFO] 探测券商适配器...
[INFO] EasyTrader: 找到已登录客户端（银河证券）账户 12345678
[INFO] 就绪。GET http://127.0.0.1:8765/ping 验证
```

### 6.2 后台模式（日常使用）
双击 `run.bat`，窗口会立刻关闭。日志写 `logs/` 目录。

### 6.3 本机验证
```cmd
curl http://127.0.0.1:8765/ping
```
应返回：`{"ok":true,"broker":"银河证券","account":"12345678"}`

### 6.4 局域网验证
手机连同一个 WiFi，浏览器访问：
```
http://<电脑IP>:8765/ping
```
如果手机访问不了：
1. Windows Defender 防火墙入站规则放行 8765
2. 路由器没做 AP 隔离（企业 WiFi 常见）

---

## 7. App 端配置

1. 打开 QuantApp → 设置 → 实盘设置
2. **网关 URL**：`http://<电脑IP>:8765`（同一局域网）或公网地址
3. **网关密钥**：和 config.ini 里 `secret` 一致
4. **券商**：银河证券 / 中航证券 / EasyTrader 通用 / 自动探测
5. 点 **连接测试** → 应显示"网关已连接"
6. 保存
7. 设置 → 交易模式 → 选 **实盘信号**

**测试手动买入/卖出**：K线页 → 手动买入 → 输入价格股数 → 确认 → Toast 提示"实盘买入成功（委托号：xxx）"

---

## 8. 注册为 Windows 服务（开机自启）

### 8.1 用 NSSM（推荐）
脚本目录下已含 `install_service.bat`，双击即可。它会：
1. 下载 NSSM 到 `tools/nssm/`
2. 注册 `QuantAppGateway` 服务
3. 启动服务并设为自动

### 8.2 手动注册
```cmd
nssm install QuantAppGateway "D:\QuantApp\gateway\.venv\Scripts\python.exe" ^
    "D:\QuantApp\gateway\gateway.py" ^
    --AppDirectory "D:\QuantApp\gateway" ^
    --StdOutput "D:\QuantApp\gateway\logs\service.stdout.log" ^
    --StdError "D:\QuantApp\gateway\logs\service.stderr.log"

nssm set QuantAppGateway Start SERVICE_AUTO_START
nssm start QuantAppGateway
```

### 8.3 卸载服务
双击 `remove_service.bat` 或：
```cmd
nssm stop QuantAppGateway
nssm remove QuantAppGateway confirm
```

### 8.4 多账户
每个账户起一个不同端口：
```cmd
set GATEWAY_PORT=8766
set GATEWAY_SECRET=另一个密钥
nssm install QuantAppGateway_Second ...
```
网关脚本会读这些环境变量覆盖 config.ini。

---

## 9. 更新与升级

```cmd
cd D:\QuantApp\gateway
.venv\Scripts\activate
pip install -U -r requirements.txt
# 替换 gateway.py / broker_adapter.py 为新版本
net stop QuantAppGateway
net start QuantAppGateway
```

---

## 10. 常见问题

### Q1. EasyTrader 报"找不到已登录客户端"
确保银河/通达信客户端**已经登录并保持窗口打开**。有些版本需要把客户端窗口拉到前台一次。也可能是 64 位/32 位不匹配（客户端 32 位 + Python 32 位）。

### Q2. xtquant 报"连接 MiniQMT 失败"
QMT 极简版设置里开启"极简模式"（不是普通投研模式），MiniQMT 会在任务管理器进程列表里出现，本地端口默认 58620–58630。确认 QMT 已登录。

### Q3. App 端"连接测试"超时
1. 电脑上 `curl http://127.0.0.1:8765/ping` 能通吗？能通 = 网关 OK，问题在网络。
2. Windows 防火墙：控制面板 → Windows Defender 防火墙 → 高级设置 → 入站规则 → 新建 → TCP 8765 允许
3. 云主机：安全组入站 TCP 8765 放行（**源建议写你手机/宽带公网 IP**，开放给 0.0.0.0/0 有被扫风险）

### Q4. 下单返回"券商连接失败"
网关脚本先 ping 一下，看券商客户端还活着吗。长时间挂在那可能会话过期，手动重登一次。也可以在 config.ini 里调：
```ini
[broker]
refresh_interval = 300   ; 每 300 秒主动 keepalive()
```

### Q5. 下单返回"可用资金不足"/"持仓不足"
这是券商端返回的真实错误，网关脚本没拦截过。看 `/account` 端点的 `asset.cash` 和 `positions[].available`。

### Q6. 撤单接口怎么调
网关 `POST /cancel/{order_id}`，App 端暂未提供入口但底层已支持。如果策略误触发或你想撤单，也可以直接去券商客户端手动撤。

### Q7. 云端怎么把网关暴露给 App
- **同云内网穿透**：Cloudflare Tunnel / frp / ngrok，给网关一个 HTTPS 域名
- **云厂商安全组直接放行**：8765 入站 → App 直连 `http://公网IP:8765`
- **反代 HTTPS**：Caddy / Nginx 443 → 127.0.0.1:8765，App 端走 HTTPS + 密钥

### Q8. 券商客户端会不会自动登出
会。一般 8 小时无操作会超时。建议云主机设定时任务：
```cmd
schtasks /create /tn "QMT_KeepAlive" /tr "run_all.bat" /sc hourly /mo 6
```
（`run_all.bat` 内容：模拟一次最小化交易查询，或直接关闭再重启 QMT）

### Q9. 安全建议
1. 网关密钥至少 32 位随机串，别用默认
2. 云主机上**不要**把密钥写死，用环境变量：`set GATEWAY_SECRET=xxx`
3. 券商账号密码**不要**存任何配置文件，只在券商客户端里手动登录
4. 云安全组入站源写窄范围 IP（App 使用的手机/家庭网络）
5. 定期看 `logs/` 下的网关日志，异常请求一目了然

### Q10. 可以在 macOS/Linux 跑网关吗
**不行**。EasyTrader 和 xtquant 都依赖 Windows 本地进程/COM/内存读取。Mac/Linux 上要用 Docker 跑 Wine 模拟，成功率低，不推荐。

---

## 附：API 契约速查

| 方法 | 路径 | 说明 | 返回 |
|---|---|---|---|
| GET | `/ping` | 健康检查 | `{ok, broker, account?, error?}` |
| GET | `/account` | 资产+持仓+今日委托 | `{ok, asset, positions[], orders[]}` |
| POST | `/trade` | 下单 | `{ok, order_id?, message}` |
| POST | `/cancel/{id}` | 撤单 | `{ok, message}` |
| Header | `X-Secret` | 网关密钥（可选） | — |

下单 body：
```json
{
  "symbol": "000001.SZ",
  "side": "买入",
  "price": 10.50,
  "qty": 1000,
  "amount": null,
  "reason": "RSI 金叉",
  "broker": "galaxy"
}
```
