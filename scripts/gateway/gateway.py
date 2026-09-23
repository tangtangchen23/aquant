"""
QuantApp Broker Gateway - 多券商实盘网关
========================================

对接 Android App 的实盘下单请求，通过 EasyTrader / xtquant 调用
Windows 上已登录的券商客户端执行下单。

启动：
    python gateway.py                 # 使用 ./config.ini
    GATEWAY_PORT=8766 python gateway.py  # 环境变量覆盖

API：
    GET  /ping             健康检查 + 券商状态
    GET  /account          资产 / 持仓 / 今日委托
    POST /trade            下单  body: {symbol, side, price, qty?, amount?, reason, broker}
    POST /cancel/{orderId} 撤单

安全：
    - 所有请求可选 Header X-Secret；若 config.ini 中指定了 secret，则必须匹配
    - 网关**不**持有券商账号密码；券商客户端由用户手动登录并保持运行

日志：logs/gateway_YYYYMMDD.log（滚动，每天 10MB 保留 7 天）
"""
from __future__ import annotations

import os, sys, asyncio, signal, socket, json, traceback
from pathlib import Path
from contextlib import asynccontextmanager
from datetime import datetime

import uvicorn
from fastapi import FastAPI, HTTPException, Header, Request
from fastapi.responses import JSONResponse
from pydantic import BaseModel, Field

# 日志
try:
    from loguru import logger
    LOGURU_OK = True
except ImportError:
    LOGURU_OK = False
    import logging
    logger = logging.getLogger("gateway")
    logging.basicConfig(level=logging.INFO,
                        format="%(asctime)s %(levelname)s %(message)s")

# 适配器
import broker_adapter


# ==================== 配置加载 ====================

CONFIG_PATH = Path(os.environ.get("GATEWAY_CONFIG", "config.ini"))


def load_config() -> dict:
    cfg = {
        "gateway": {"port": 8765, "secret": "", "host": "0.0.0.0"},
        "broker": {"backend": "auto", "refresh_interval": 300},
        "logging": {"level": "INFO", "dir": "logs"},
    }
    # 读 INI
    if CONFIG_PATH.exists():
        import configparser
        cp = configparser.ConfigParser()
        cp.read(CONFIG_PATH, encoding="utf-8")
        for section in cfg:
            if section in cp:
                for k, v in cp[section].items():
                    if k == "port":
                        cfg[section][k] = int(v)
                    elif k == "refresh_interval":
                        cfg[section][k] = int(v)
                    else:
                        cfg[section][k] = v.strip()
    # 环境变量覆盖
    if os.environ.get("GATEWAY_PORT"):
        cfg["gateway"]["port"] = int(os.environ["GATEWAY_PORT"])
    if os.environ.get("GATEWAY_SECRET"):
        cfg["gateway"]["secret"] = os.environ["GATEWAY_SECRET"]
    if os.environ.get("GATEWAY_BACKEND"):
        cfg["broker"]["backend"] = os.environ["GATEWAY_BACKEND"]
    return cfg


CFG = load_config()

# 日志初始化（loguru）
if LOGURU_OK:
    log_dir = Path(CFG["logging"]["dir"])
    log_dir.mkdir(parents=True, exist_ok=True)
    logger.remove()
    logger.add(
        sys.stderr, level=CFG["logging"]["level"],
        format="<green>{time:HH:mm:ss}</green> <level>{level}</level> {message}",
    )
    logger.add(
        log_dir / "gateway_{time:YYYYMMDD}.log",
        level="DEBUG", rotation="10 MB", retention="7 days", encoding="utf-8",
    )


def _local_ip() -> str:
    try:
        s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        s.connect(("8.8.8.8", 80))
        ip = s.getsockname()[0]
        s.close()
        return ip
    except Exception:
        return "127.0.0.1"


# ==================== Broker 会话 ====================

broker_session: broker_adapter.BrokerAdapter | None = None
refresh_task: asyncio.Task | None = None


def init_broker() -> broker_adapter.BrokerAdapter | None:
    backend = CFG["broker"]["backend"].lower()
    try:
        if backend == "auto":
            adapter = broker_adapter.auto_probe()
        else:
            adapter = broker_adapter.get_adapter(backend)
        if adapter is None:
            logger.error("未找到可用券商：backend={}", backend)
            return None
        adapter.connect()
        logger.info("券商适配器就绪：{}  账户 {}", adapter.name, adapter.account_id())
        return adapter
    except Exception as e:
        logger.error("券商连接失败：{}", e)
        logger.debug(traceback.format_exc())
        return None


async def refresh_loop():
    global broker_session
    interval = max(60, CFG["broker"]["refresh_interval"])
    logger.info("会话保活循环已启动，每 {} 秒", interval)
    while True:
        await asyncio.sleep(interval)
        if broker_session is None:
            broker_session = init_broker()
            continue
        try:
            broker_session.keepalive()
            logger.debug("keepalive OK")
        except Exception as e:
            logger.warning("keepalive 失败，重连中：{}", e)
            try:
                broker_session.disconnect()
            except Exception:
                pass
            broker_session = init_broker()


# ==================== FastAPI 应用 ====================

@asynccontextmanager
async def lifespan(app: FastAPI):
    global broker_session, refresh_task
    broker_session = init_broker()
    refresh_task = asyncio.create_task(refresh_loop())
    logger.info("QuantApp Broker Gateway v1.0  监听 http://{}:{}",
                CFG["gateway"]["host"], CFG["gateway"]["port"])
    logger.info("本机局域网地址：http://{}:{}", _local_ip(), CFG["gateway"]["port"])
    try:
        yield
    finally:
        if refresh_task:
            refresh_task.cancel()
        if broker_session:
            try:
                broker_session.disconnect()
            except Exception:
                pass
        logger.info("网关已关闭")


app = FastAPI(title="QuantApp Broker Gateway", version="1.0", lifespan=lifespan)


# ---------- 认证 ----------

def _check_secret(x_secret: str | None) -> None:
    expected = CFG["gateway"]["secret"]
    if not expected:
        return  # 没设密钥 = 不强制
    if x_secret != expected:
        raise HTTPException(status_code=401, detail="网关密钥错误")


# ---------- 数据模型 ----------

class TradeSignal(BaseModel):
    symbol: str                  # 如 "000001.SZ" 或 "600000.SH"
    side: str = Field(pattern=r"^(买入|卖出)$")
    price: float = Field(gt=0)
    qty: int | None = Field(default=None, ge=0)
    amount: float | None = Field(default=None, ge=0)
    reason: str = ""
    broker: str = "auto"


# ---------- API ----------

@app.get("/")
@app.get("/ping")
def ping(x_secret: str | None = Header(default=None)):
    _check_secret(x_secret)
    if broker_session is None:
        return {"ok": False, "broker": "disconnected", "error": "券商未连接"}
    try:
        # 读一次账户做活体验证
        acc = broker_session.account_id()
        return {
            "ok": True,
            "broker": broker_session.name,
            "account": acc,
        }
    except Exception as e:
        return {"ok": False, "broker": broker_session.name, "error": str(e)}


@app.get("/account")
def account(x_secret: str | None = Header(default=None)):
    _check_secret(x_secret)
    if broker_session is None:
        raise HTTPException(status_code=503, detail="券商未连接")
    try:
        asset = broker_session.asset()
        positions = broker_session.positions()
        orders = broker_session.today_orders()
        return {"ok": True, "asset": asset, "positions": positions, "orders": orders}
    except Exception as e:
        logger.error("account 查询失败：{}", e)
        logger.debug(traceback.format_exc())
        raise HTTPException(status_code=500, detail=str(e))


@app.post("/trade")
def trade(signal: TradeSignal, x_secret: str | None = Header(default=None)):
    _check_secret(x_secret)
    if broker_session is None:
        broker_session = init_broker()
        if broker_session is None:
            raise HTTPException(status_code=503, detail="券商未连接")
    # symbol 规范化："000001.SZ" → "000001"
    code = signal.symbol.split(".")[0]
    if not code or not code.isdigit():
        raise HTTPException(status_code=400, detail=f"无效标的代码 {signal.symbol}")

    qty = signal.qty
    if (qty is None or qty <= 0) and signal.amount and signal.amount > 0:
        qty = int(signal.amount / signal.price / 100) * 100
    if qty is None or qty <= 0:
        raise HTTPException(status_code=400, detail="下单股数或金额无效")
    if qty % 100 != 0:
        qty = qty // 100 * 100

    logger.info("📤 {} {} {} ×{} @{}  原因: {}",
                signal.side, code, signal.symbol, qty, signal.price, signal.reason)
    try:
        if signal.side == "买入":
            broker_session.buy(code, price=signal.price, qty=qty)
        else:
            broker_session.sell(code, price=signal.price, qty=qty)
        order_id = broker_session.last_order_id(code)
        logger.info("✅ 下单成功 order_id={}", order_id)
        return {"ok": True, "order_id": order_id,
                "message": f"{signal.side} {signal.symbol} ×{qty} @{signal.price}"}
    except HTTPException:
        raise
    except Exception as e:
        logger.error("❌ 下单失败：{}", e)
        logger.debug(traceback.format_exc())
        return {"ok": False, "order_id": None, "message": str(e)}


@app.post("/cancel/{order_id}")
def cancel(order_id: str, x_secret: str | None = Header(default=None)):
    _check_secret(x_secret)
    if broker_session is None:
        broker_session = init_broker()
        if broker_session is None:
            raise HTTPException(status_code=503, detail="券商未连接")
    try:
        broker_session.cancel(order_id)
        return {"ok": True, "message": f"已撤单 {order_id}"}
    except Exception as e:
        logger.error("撤单失败：{}", e)
        return {"ok": False, "message": str(e)}


@app.exception_handler(Exception)
async def fallback_handler(request: Request, exc: Exception):
    logger.error("未捕获异常 {} {}", request.method, request.url.path)
    logger.exception(exc)
    return JSONResponse(status_code=500,
                        content={"ok": False, "detail": str(exc)})


# ==================== 入口 ====================

def _install_signal_handlers():
    def _sig(signum, frame):
        logger.info("收到信号 {}，优雅退出...", signum)
        sys.exit(0)
    signal.signal(signal.SIGINT, _sig)
    signal.signal(signal.SIGTERM, _sig)


if __name__ == "__main__":
    _install_signal_handlers()
    uvicorn.run(
        app,
        host=CFG["gateway"]["host"],
        port=CFG["gateway"]["port"],
        log_level="info",
    )
