package com.quantapp.trader.ui

import android.content.Context
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import com.quantapp.trader.R

/**
 * 查询历史“一行式”展示工具：在搜索框下方用一行胶囊按钮展示查询记录。
 * - 一行能放下则全部显示；
 * - 放不下时，只显示能容纳的前若干个，末尾用“更多”入口承接剩余记录；
 * - 点击“更多”弹出对话框，列出并支持点击其余全部记录；
 * - 点击任意历史记录执行 onClick（行情页=查询，策略页=填入标的）。
 */
object QueryHistoryRow {

    fun bind(container: LinearLayout, histories: List<String>, onClick: (String) -> Unit) {
        container.removeAllViews()
        if (histories.isEmpty()) { container.visibility = View.GONE; return }
        container.visibility = View.VISIBLE

        val ctx = container.context
        val density = ctx.resources.displayMetrics.density
        val hMargin = (6f * density).toInt()
        val moreText = "更多"

        fun widthOf(text: String): Int {
            val tv = TextView(ctx).apply {
                setText(text)
                textSize = 12f
                setPadding((10f * density).toInt(), (4f * density).toInt(),
                    (10f * density).toInt(), (4f * density).toInt())
            }
            tv.measure(
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
            )
            return tv.measuredWidth
        }

        // 可用宽度优先取容器自身；容器曾被隐藏(getWidth==0)时退回其父容器宽度
        val parent = container.parent as? View
        val availWidth =
            if (container.width > 0) container.width - container.paddingLeft - container.paddingRight
            else if (parent != null) parent.width - parent.paddingLeft - parent.paddingRight
            else 1

        fun chip(text: String, isMore: Boolean = false): TextView {
            val tv = TextView(ctx)
            tv.text = text
            tv.textSize = 12f
            tv.setPadding((10f * density).toInt(), (4f * density).toInt(),
                (10f * density).toInt(), (4f * density).toInt())
            tv.setBackgroundResource(R.drawable.bg_indicator_off)
            tv.setTextColor(ContextCompat.getColor(ctx, R.color.text_primary))
            tv.isClickable = true
            if (isMore) tv.alpha = 0.85f
            return tv
        }

        // 决策：计算出可见的前几个 + 是否溢出
        val visible = mutableListOf<String>()
        var used = 0
        val moreW = widthOf(moreText) + hMargin

        if (histories.sumBy { widthOf(it) + hMargin } <= availWidth) {
            visible.addAll(histories)
        } else {
            for (h in histories) {
                val w = widthOf(h) + hMargin
                if (used + w + moreW > availWidth) break
                visible.add(h)
                used += w
            }
        }
        val showMore = visible.size < histories.size

        // 一个都放不下时：仅显示一个“更多”
        if (visible.isEmpty()) {
            chip(moreText, isMore = true).also {
                it.setOnClickListener { showAllDialog(ctx, histories, onClick) }
                container.addView(it, LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT))
            }
            return
        }

        for (h in visible) {
            container.addView(chip(h).also { it.setOnClickListener { onClick(h) } },
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { marginEnd = hMargin })
        }
        if (showMore) {
            container.addView(chip(moreText, isMore = true).also {
                it.setOnClickListener { showAllDialog(ctx, histories, onClick) }
            }, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        }
    }

    private fun showAllDialog(ctx: Context, histories: List<String>, onClick: (String) -> Unit) {
        AlertDialog.Builder(ctx)
            .setTitle("查询历史")
            .setItems(histories.toTypedArray()) { _, index ->
                if (index in histories.indices) onClick(histories[index])
            }
            .setNegativeButton("取消", null)
            .show()
    }
}