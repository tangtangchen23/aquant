"""
broker_adapter.py - 多券商适配器
=================================

统一接口，底层实现：
  - EasyTraderAdapter: easytrader（银河/通达信/同花顺通用客户端）
  - XtQuantAdapter:    xtquant（迅投 QMT 极简版，中航/银河/QMT 通用）

auto_probe() 自动探测哪个可用，get_adapter(name) 按名称构造。
"""
from __future__ import annotations

from abc import ABC, abstractmethod
from typing import Any


# ==================== 抽象基类 ====================

class BrokerAdapter(ABC):
    name: str = "base"

    @abstractmethod
    def connect(self) -> None: ...

    @abstractmethod
    def disconnect(self) -> None: ...

    @abstractmethod
    def keepalive(self) -> None: ...

    @abstractmethod
    def account_id(self) -> str: ...

    @abstractmethod
    def asset(self) -> dict: ...

    @abstractmethod
    def positions(self) -> list[dict]: ...

    @abstractmethod
    def today_orders(self) -> list[dict]: ...

    @abstractmethod
    def buy(self, code: str, price: float, qty: int) -> None: ...

    @abstractmethod
    def sell(self, code: str, price: float, qty: int) -> None: ...

    @abstractmethod
    def cancel(self, order_id: str) -> None: ...

    def last_order_id(self, code_hint: str = "") -> str | None:
        orders = self.today_orders()
        for o in reversed(orders):
            if code_hint and o.get("symbol", "").startswith(code_hint[:4]):
                return o.get("order_id")
        return orders[-1].get("order_id") if orders else None


# ==================== EasyTrader ====================

class EasyTraderAdapter(BrokerAdapter):
    name = "EasyTrader"

    def __init__(self):
        self._trader = None

    def connect(self) -> None:
        import easytrader as et
        self._trader = et.use()  # 自动发现已登录客户端
        # 尝试识别具体券商名
        try:
            pos = self._trader.position
            # position.account_id 在部分券商上有
        except Exception:
            pass
        # 探测客户端类型（银河/通达信/同花顺）
        cls_name = type(self._trader).__name__
        self.name = f"EasyTrader/{cls_name}"

    def disconnect(self) -> None:
        # EasyTrader 无显式 disconnect
        self._trader = None

    def keepalive(self) -> None:
        # 调用一次 position 触发内部 refresh
        self._trader.position

    def account_id(self) -> str:
        try:
            return str(self._trader.account.account_id)
        except Exception:
            # easytrader 的 account_id 可能藏在 config 里
            try:
                cfg = self._trader._config if hasattr(self._trader, "_config") else {}
                return str(cfg.get("user", {}).get("account", ""))
            except Exception:
                return "unknown"

    def asset(self) -> dict:
        a = self._trader.asset
        return {
            "total": float(getattr(a, "total_assets", 0) or 0),
            "cash": float(getattr(a, "available_cash", 0) or 0),
            "market_value": float(getattr(a, "market_value", 0) or 0),
            "frozen": float(getattr(a, "frozen_cash", 0) or 0),
        }

    def positions(self) -> list[dict]:
        out = []
        for p in self._trader.position:
            out.append({
                "symbol": getattr(p, "stock_code", "") or getattr(p, "code", ""),
                "name": getattr(p, "stock_name", "") or getattr(p, "name", ""),
                "qty": int(getattr(p, "quantity", 0) or 0),
                "available": int(getattr(p, "enable_quantity", 0) or 0),
                "cost": float(getattr(p, "cost_price", 0) or 0),
                "price": float(getattr(p, "current_price", 0) or 0),
                "market_value": float(getattr(p, "market_value", 0) or 0),
                "profit": float(getattr(p, "profit_amount", 0) or 0),
            })
        return out

    def today_orders(self) -> list[dict]:
        out = []
        for o in self._trader.today_orders:
            out.append({
                "order_id": getattr(o, "order_id", ""),
                "symbol": getattr(o, "stock_code", ""),
                "side": getattr(o, "direction", ""),
                "price": float(getattr(o, "price", 0) or 0),
                "qty": int(getattr(o, "volume", 0) or 0),
                "status": getattr(o, "status", ""),
                "time": getattr(o, "order_time", ""),
            })
        return out

    def buy(self, code: str, price: float, qty: int) -> None:
        self._trader.buy(code, price=price, amount=qty)

    def sell(self, code: str, price: float, qty: int) -> None:
        self._trader.sell(code, price=price, amount=qty)

    def cancel(self, order_id: str) -> None:
        self._trader.cancel_entrust(order_id)


# ==================== XtQuant ====================

class XtQuantAdapter(BrokerAdapter):
    name = "XtQuant"

    def __init__(self, userdata_path: str | None = None):
        self._userdata = userdata_path  # 若 None，用 MiniQMT 默认路径
        self._xttrader = None
        self._account_id = ""

    def _import_xtquant(self):
        try:
            from xtquant import xttrader
            return xttrader
        except ImportError as e:
            raise RuntimeError(
                "未找到 xtquant 库。请把 QMT 安装目录下的 xtquant 文件夹拷到 "
                "Python site-packages 目录后再试。原始错误：%s" % e
            )

    def connect(self) -> None:
        xttrader = self._import_xtquant()
        # 发现 MiniQMT 端口
        from xtquant import xtdata
        port = xtdata.connect()
        # 交易 API：xttrader.XtQuantTrader(port)
        self._xttrader = xttrader.XtQuantTrader(port)
        self._xttrader.start()
        # 账号
        if self._userdata:
            self._account_id = self._userdata.rstrip("\\").split("userdata_")[-1]
        else:
            self._account_id = getattr(xtdata, "account_id", "unknown") or "unknown"
        self.name = "XtQuant/QMT"

    def disconnect(self) -> None:
        if self._xttrader:
            try:
                self._xttrader.stop()
            except Exception:
                pass
        self._xttrader = None

    def keepalive(self) -> None:
        # 读一次资产保活
        self.asset()

    def account_id(self) -> str:
        return self._account_id

    def _normalize_code(self, code: str) -> str:
        """xtquant 用 000001.SZ / 600000.SH 格式。"""
        if "." in code:
            return code
        if code.startswith(("6", "5")):
            return f"{code}.SH"
        return f"{code}.SZ"

    def asset(self) -> dict:
        a = self._xttrader.query_stock_asset()
        return {
            "total": float(getattr(a, "total_asset", 0) or 0),
            "cash": float(getattr(a, "cash", 0) or 0),
            "market_value": float(getattr(a, "market_value", 0) or 0),
            "frozen": float(getattr(a, "frozen_cash", 0) or 0),
        }

    def positions(self) -> list[dict]:
        out = []
        for p in self._xttrader.query_stock_positions():
            out.append({
                "symbol": getattr(p, "stock_code", ""),
                "name": getattr(p, "stock_name", ""),
                "qty": int(getattr(p, "volume", 0) or 0),
                "available": int(getattr(p, "can_use_volume", 0) or 0),
                "cost": float(getattr(p, "cost_price", 0) or 0),
                "price": float(getattr(p, "market_value", 0) or 0) / max(1, int(getattr(p, "volume", 1) or 1)),
                "market_value": float(getattr(p, "market_value", 0) or 0),
                "profit": float(getattr(p, "profit", 0) or 0),
            })
        return out

    def today_orders(self) -> list[dict]:
        out = []
        for o in self._xttrader.query_stock_orders():
            out.append({
                "order_id": getattr(o, "order_id", ""),
                "symbol": getattr(o, "stock_code", ""),
                "side": "买入" if getattr(o, "order_type", None) == 23 else "卖出",
                "price": float(getattr(o, "price", 0) or 0),
                "qty": int(getattr(o, "order_volume", 0) or 0),
                "status": {0: "待报", 1: "待撤", 2: "已报", 3: "部成", 4: "已成", 5: "部撤", 6: "已撤", 7: "废单"}
                          .get(getattr(o, "order_status", None), str(getattr(o, "order_status", ""))),
                "time": "",
            })
        return out

    def buy(self, code: str, price: float, qty: int) -> None:
        self._xttrader.order_stock(self._account_id, self._normalize_code(code),
                                   23, price, qty)

    def sell(self, code: str, price: float, qty: int) -> None:
        self._xttrader.order_stock(self._account_id, self._normalize_code(code),
                                   24, price, qty)

    def cancel(self, order_id: str) -> None:
        self._xttrader.cancel_order_stock(self._account_id, int(order_id))


# ==================== 入口 ====================

_ADAPTER_FACTORIES: dict[str, type[BrokerAdapter]] = {
    "easytrader": EasyTraderAdapter,
    "galaxy": EasyTraderAdapter,      # 银河证券默认 EasyTrader
    "avic": XtQuantAdapter,            # 中航证券优先 QMT
    "xtquant": XtQuantAdapter,
    "qmt": XtQuantAdapter,
}


def get_adapter(name: str) -> BrokerAdapter:
    key = name.lower()
    if key not in _ADAPTER_FACTORIES:
        raise ValueError(f"未知券商适配器 {name}，可用：{list(_ADAPTER_FACTORIES)}")
    return _ADAPTER_FACTORIES[key]()


def auto_probe() -> BrokerAdapter | None:
    """依次尝试 xtquant → easytrader，返回第一个可用的适配器。"""
    errors = []
    # 1. xtquant / QMT
    try:
        import xtquant  # noqa: F401
        ad = XtQuantAdapter()
        ad.connect()
        return ad
    except Exception as e:
        errors.append(f"xtquant: {e}")
    # 2. easytrader
    try:
        import easytrader  # noqa: F401
        ad = EasyTraderAdapter()
        ad.connect()
        return ad
    except Exception as e:
        errors.append(f"easytrader: {e}")
    raise RuntimeError("自动探测失败：\n" + "\n".join(errors))
