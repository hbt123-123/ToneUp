package com.toneup.app.ui.components.charts

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.toneup.app.data.remote.dto.DailyTrendPointDto
import com.toneup.app.data.remote.dto.SectionItem
import com.toneup.app.ui.components.EmptyState

/**
 * 首页仪表盘图表（EC-03，任务 12）：Compose Canvas 自绘，零网络请求，
 * 数据由 VM 注入；空数据渲染占位文案而非崩溃/无限加载。
 * 契约与 PC 端 components/charts/types.ts 对齐。
 */

/** 每日趋势点（对齐后端 §6.11 daily-trend points 项） */
data class DailyTrendPoint(
    val date: String,
    val attempts: Int,
    val correctRate: Double
)

/** 专题进度项（分节已做/总数） */
data class TopicProgressItem(
    val title: String,
    val done: Int,
    val total: Int
)

/** 成绩历史点（会话序号 → 得分率，跨端契约预留） */
data class ScoreHistoryPoint(
    val index: Int,
    val scoreRate: Double
)

/** DTO → 图表契约映射（供 VM 层组装） */
fun DailyTrendPointDto.toChartPoint(): DailyTrendPoint =
    DailyTrendPoint(date = date, attempts = attempts, correctRate = correctRate)

/** 真题分组（title 空）映射为年份标题；专题取原 title */
fun SectionItem.toProgressItem(): TopicProgressItem =
    TopicProgressItem(
        title = title ?: year?.let { "$it 年真题" } ?: "未命名分节",
        done = done,
        total = total
    )

/** 作答量归一化柱高（0..1）；空列表 → emptyList（对应组件占位状态） */
internal fun barFractions(points: List<DailyTrendPoint>): List<Float> {
    if (points.isEmpty()) return emptyList()
    val max = points.maxOf { it.attempts }.coerceAtLeast(1)
    return points.map { (it.attempts.toFloat() / max).coerceIn(0f, 1f) }
}

/** 进度比例；total<=0 保护为 0（不崩溃） */
internal fun TopicProgressItem.fraction(): Float =
    if (total <= 0) 0f else (done.toFloat() / total).coerceIn(0f, 1f)

/** 近 N 天作答量趋势：圆角柱状图（范式对齐 StatsTab AccuracyBarChart） */
@Composable
fun AttemptTrendChart(points: List<DailyTrendPoint>, modifier: Modifier = Modifier) {
    if (points.isEmpty()) {
        // M-112：空态需转发调用方 modifier，否则占位与图表占用的尺寸/位置不一致
        EmptyState("暂无作答数据", modifier = modifier)
        return
    }
    val barColor = MaterialTheme.colorScheme.primary
    val trackColor = MaterialTheme.colorScheme.surfaceVariant
    val fractions = barFractions(points)
    Canvas(modifier.fillMaxWidth().height(96.dp)) {
        val n = points.size
        // M-113：点数过多时按可用宽度收窄间距，保证 barW 恒为正，避免向 drawRoundRect 传入负尺寸
        val gap = minOf(4.dp.toPx(), size.width / (2 * n))
        val barW = (size.width - gap * (n - 1)) / n
        fractions.forEachIndexed { i, f ->
            val x = i * (barW + gap)
            drawRoundRect(
                color = trackColor,
                topLeft = Offset(x, 0f),
                size = Size(barW, size.height),
                cornerRadius = CornerRadius(6f, 6f)
            )
            val h = size.height * f
            if (h > 0f) {
                drawRoundRect(
                    color = barColor,
                    topLeft = Offset(x, size.height - h),
                    size = Size(barW, h),
                    cornerRadius = CornerRadius(6f, 6f)
                )
            }
        }
    }
}

/** 正确率趋势折线（correctRate 0..1 → 高度映射） */
@Composable
fun AccuracyTrendChart(points: List<DailyTrendPoint>, modifier: Modifier = Modifier) {
    if (points.isEmpty()) {
        // M-114：空态需转发调用方 modifier，与 AttemptTrendChart 同因
        EmptyState("暂无正确率数据", modifier = modifier)
        return
    }
    val lineColor = MaterialTheme.colorScheme.tertiary
    Canvas(modifier.fillMaxWidth().height(96.dp)) {
        val n = points.size
        // M-115：单点时 Path 只有 moveTo、描边为空，唯一数据点会丢失——改绘圆点标记
        if (n == 1) {
            val y = size.height * (1f - points.first().correctRate.toFloat().coerceIn(0f, 1f))
            drawCircle(color = lineColor, radius = 6f, center = Offset(size.width / 2f, y))
            return@Canvas
        }
        val stepX = size.width / (n - 1)
        val path = Path()
        points.forEachIndexed { i, p ->
            val x = i * stepX
            val y = size.height * (1f - p.correctRate.toFloat().coerceIn(0f, 1f))
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        drawPath(path, color = lineColor, style = Stroke(width = 6f, cap = StrokeCap.Round))
    }
}

/** 专题进度条列表（照 AccuracyBarChart 行式进度范式） */
@Composable
fun TopicProgressList(items: List<TopicProgressItem>, modifier: Modifier = Modifier) {
    if (items.isEmpty()) {
        // M-116：空态需转发调用方 modifier，与另两个图表同因
        EmptyState("暂无专题进度", modifier = modifier)
        return
    }
    val barColor = MaterialTheme.colorScheme.primary
    val trackColor = MaterialTheme.colorScheme.surfaceVariant
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        items.forEach { item ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    item.title,
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Box(Modifier.weight(1f).padding(horizontal = 8.dp).height(14.dp)) {
                    val f = item.fraction()
                    Canvas(Modifier.fillMaxWidth().height(14.dp)) {
                        drawRoundRect(
                            color = trackColor,
                            cornerRadius = CornerRadius(6f, 6f)
                        )
                        if (f > 0f) {
                            drawRoundRect(
                                color = barColor,
                                topLeft = Offset.Zero,
                                size = Size(size.width * f, size.height),
                                cornerRadius = CornerRadius(6f, 6f)
                            )
                        }
                    }
                }
                Text(
                    "${item.done}/${item.total}",
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.padding(start = 8.dp)
                )
            }
        }
    }
}
