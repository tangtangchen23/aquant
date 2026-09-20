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
import com.quantapp.trader.strategy.ScreenHit
import com.quantapp.trader.strategy.Screener
import com.quantapp.trader.strategy.buildStrategy
import com.quantapp.trader.strategy.strategies
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.util.Locale

/**
 * 选股页：按所选量化策略，在选定股票池（东财按成交额排序的前 N 只）中扫描
 * 出当前处于“买入信号”的股票，点击即可跳转到 K 线图。
 */
class ScreenerFragment : Fragment() {

    /** 股票池选项：显示名 -> 东财 fs 市场过滤串 */
    private val boards = linkedMapOf(
        "全部A股" to "m:0+t:6,m:0+t:81,m:1+t:2,m:1+t:23",
        "沪深主板" to "m:0+t:6,m:1+t:2",
        "创业板" to "m:0+t:81",
        "科创板" to "m:1+t:23"
    )

    private var scanJob: Job? = null

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        val root = inflater.inflate(R.layout.fragment_screener, container, false)
        val boardSpinner = root.findViewById<Spinner>(R.id.spinner_board)
        val strategySpinner = root.findViewById<Spinner>(R.id.spinner_strategy)
        val etLimit = root.findViewById<EditText>(R.id.et_limit)
        val btn = root.findViewById<Button>(R.id.btn_screen)
        val tvProgress = root.findViewById<TextView>(R.id.tv_progress)
        val tvStats = root.findViewById<TextView>(R.id.tv_stats)
        val list = root.findViewById<ListView>(R.id.list_screen)

        boardSpinner.adapter = ArrayAdapter(requireContext(),
            android.R.layout.simple_list_item_1, boards.keys.toList())
        val strategyList = strategies()
        strategySpinner.adapter = ArrayAdapter(requireContext(),
            android.R.layout.simple_list_item_1, strategyList.map { it.name })

        list.setOnItemClickListener { _, _, position, _ ->
            val hits = lastHits
            if (position in hits.indices) {
                (activity as? MainActivity)?.openChart(hits[position].code)
            }
        }

        btn.setOnClickListener { v ->
            if (scanJob?.isActive == true) {
                scanJob?.cancel()       // 再次点击 => 停止
                scanJob = null
                btn.setText(R.string.tab_screener_start)
                tvProgress.text = "已停止。"
                return@setOnClickListener
            }
            val fs = boards.values.toList()[boardSpinner.selectedItemPosition.coerceIn(0, boards.size - 1)]
            val sid = strategyList[strategySpinner.selectedItemPosition.coerceIn(0, strategyList.size - 1)].id
            val limit = etLimit.text.toString().toIntOrNull()?.coerceIn(10, 500) ?: 120
            startScan(v, fs, sid, limit, btn, tvProgress, tvStats, list)
        }
        return root
    }

    @Volatile private var lastHits: List<ScreenHit> = emptyList()

    private fun startScan(
        btn: View, fs: String, strategyId: String, limit: Int,
        btnScreen: Button, tvProgress: TextView, tvStats: TextView, list: ListView
    ) {
        btnScreen.setText(R.string.tab_screener_stop)
        tvProgress.text = "读取股票池中..."
        tvStats.text = ""
        list.adapter = ArrayAdapter(requireContext(), android.R.layout.simple_list_item_1, emptyList<String>())
        scanJob = AppScope.launch {
            try {
                val pool = com.quantapp.trader.data.MarketService.fetchAStockList(fs, limit)
                if (pool.isEmpty()) {
                    tvProgress.text = "未能获取股票池，请检查网络后重试。"
                    btnScreen.setText(R.string.tab_screener_start)
                    return@launch
                }
                val strategy = buildStrategy(strategyId, emptyMap()) // 用该策略默认参数
                tvProgress.text = "扫描中 0/${pool.size} · 命中 0"
                val hits = Screener.hunt(strategy, pool) { done, total, hit ->
                    tvProgress.text = String.format(Locale.CHINA, "扫描中 %d/%d · 命中 %d", done, total, hit)
                    if (done % 10 == 0 || done == total) tvStats.text = "已发现 ${hit} 只符合 ${strategy.name}"
                }
                lastHits = hits
                if (hits.isEmpty()) {
                    tvStats.text = "扫描完成 · 该策略未发现买入信号"
                    list.adapter = ArrayAdapter(requireContext(), android.R.layout.simple_list_item_1, listOf("（无符合条件标的，可换策略或增大扫描数量）"))
                } else {
                    tvStats.text = String.format(Locale.CHINA, "扫描完成 · 命中 %d 只（%s）", hits.size, strategy.name)
                    list.adapter = ArrayAdapter(requireContext(), android.R.layout.simple_list_item_1,
                        hits.map {
                            String.format(Locale.CHINA, "%s  %s\n%s · %+.2f%%",
                                it.name, it.code, it.signal.reason, it.changePct)
                        })
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                tvStats.text = ""
            } catch (e: Exception) {
                tvStats.text = "选股失败：${e.message}"
                Toast.makeText(requireContext(), "选股失败：${e.message}", Toast.LENGTH_LONG).show()
            } finally {
                btnScreen.setText(R.string.tab_screener_start)
                scanJob = null
            }
        }
    }
}