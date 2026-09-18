package com.quantapp.trader.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.ListView
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import com.quantapp.trader.R
import com.quantapp.trader.data.MarketService
import com.quantapp.trader.strategy.Backtester
import com.quantapp.trader.strategy.strategies
import com.quantapp.trader.trading.ActiveStrategy
import com.quantapp.trader.trading.App
import com.quantapp.trader.trading.TradingEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class StrategyFragment : Fragment() {

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        val root = inflater.inflate(R.layout.fragment_strategy, container, false)
        val etSymbol = root.findViewById<EditText>(R.id.et_strat_symbol)
        val spinner = root.findViewById<Spinner>(R.id.spinner_strategy)
        val btnBacktest = root.findViewById<Button>(R.id.btn_backtest)
        val btnStart = root.findViewById<Button>(R.id.btn_start)
        val btnStop = root.findViewById<Button>(R.id.btn_stop)
        val tvBacktest = root.findViewById<TextView>(R.id.tv_backtest)
        val tvStatus = root.findViewById<TextView>(R.id.tv_running_status)
        val listActive = root.findViewById<ListView>(R.id.list_active)

        val strategyList = strategies()
        spinner.adapter = ArrayAdapter(requireContext(), android.R.layout.simple_list_item_1, strategyList.map { "${it.name} (${it.paramsSummary()})" })

        refreshStatus(tvStatus)
        refreshActive(listActive)

        fun selectedStrategyId() = strategyList[spinner.selectedItemPosition.coerceIn(0, strategyList.size - 1)].id

        btnBacktest.setOnClickListener { b ->
            val code = etSymbol.text.toString().trim()
            if (code.isEmpty()) return@setOnClickListener
            b.isEnabled = false
            tvBacktest.text = "回测中..."
            AppScope.launch {
                try {
                    val bars = withContext(Dispatchers.IO) { MarketService.fetchKline(code, 260) }
                    if (bars.size < 40) { tvBacktest.text = "数据不足，无法回测"; return@launch }
                    val result = withContext(Dispatchers.IO) {
                        Backtester.run(TradingEngine.strategyOf(selectedStrategyId()), bars)
                    }
                    tvBacktest.text = buildString {
                        append("回测(${result.trades.size}笔)\n")
                        append("策略收益：${String.format("%.2f", result.returnPct)}%\n")
                        append("基准(买入持有)：${String.format("%.2f", result.benchmarkReturnPct)}%\n")
                        append("最终权益：${String.format("%.0f", result.finalEquity)} / 初始 ${String.format("%.0f", result.initialCapital)}\n")
                        append("胜率：${String.format("%.1f", result.winRate)}%\n")
                        append("最大回撤：${String.format("%.1f", result.maxDrawdown)}%")
                    }
                } catch (e: Exception) {
                    tvBacktest.text = "回测失败：${e.message}"
                } finally {
                    b.isEnabled = true
                }
            }
        }

        btnStart.setOnClickListener {
            val code = etSymbol.text.toString().trim()
            if (code.isEmpty()) { Toast.makeText(requireContext(), "请输入股票代码", Toast.LENGTH_SHORT).show(); return@setOnClickListener }
            val bid = selectedStrategyId()
            val as_ = ActiveStrategy(code, "", bid)
            App.appStore.addStrategy(as_)
            TradingEngine.start()
            refreshStatus(tvStatus)
            refreshActive(listActive)
            Toast.makeText(requireContext(), "已启动 $code 的自动化交易", Toast.LENGTH_SHORT).show()
        }

        btnStop.setOnClickListener {
            TradingEngine.stop()
            refreshStatus(tvStatus)
            Toast.makeText(requireContext(), "引擎已停止", Toast.LENGTH_SHORT).show()
        }

        listActive.setOnItemClickListener { _, _, position, _ ->
            val now = App.appStore.activeStrategies()
            // 空列表时显示的占位行 / 下标越界都要兜底，避免闪退
            if (position !in now.indices) return@setOnItemClickListener
            val item = now[position]
            App.appStore.removeStrategy(item.symbol)
            refreshActive(listActive)
            Toast.makeText(requireContext(), "已移除 ${item.symbol}", Toast.LENGTH_SHORT).show()
        }

        TradingEngine.onTicker = { _, _, _, runningCount ->
            activity?.runOnUiThread {
                refreshStatus(tvStatus)
                refreshActive(listActive)
            }
        }
        return root
    }

    private fun refreshStatus(tv: TextView) {
        val running = TradingEngine.isRunning
        val mode = App.appStore.mode
        tv.text = if (running) "引擎状态：运行中（${modeText(mode)}）" else "引擎状态：已停止"
    }

    private fun modeText(mode: String) = if (mode == "live") "实盘信号模式" else "模拟盘模式"

    private fun refreshActive(list: ListView) {
        val list_ = App.appStore.activeStrategies()
        list.adapter = ArrayAdapter(requireContext(), android.R.layout.simple_list_item_1,
            if (list_.isEmpty()) listOf("（暂无运行中策略，点“启动自动交易”添加）")
            else list_.map { "${it.symbol}  ${it.name}  ${strategyLabel(it.strategyId)}  [${it.lastAction}] ${it.lastReason}" })
    }

    private fun strategyLabel(id: String) = when (id) { "rsi" -> "RSI"; "macd" -> "MACD"; else -> "双均线" }
}