package com.toneup.app.ui.components.charts

import com.toneup.app.data.remote.dto.DailyTrendPointDto
import com.toneup.app.data.remote.dto.SectionItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * EC-03 首页图表契约映射单测（任务 12 QA，JVM 纯函数——不依赖 Compose 运行时）：
 * DTO→图表数据转换、空列表→占位状态。
 */
class TrendChartsContractTest {

    // ---------- DTO → 图表契约映射 ----------

    @Test
    fun `dto maps snake_case fields to chart point`() {
        val p = DailyTrendPointDto(date = "2026-09-23", attempts = 5, correctRate = 0.5).toChartPoint()
        assertEquals("2026-09-23", p.date)
        assertEquals(5, p.attempts)
        assertEquals(0.5, p.correctRate, 1e-9)
    }

    @Test
    fun `section item with title maps topic progress`() {
        val item = SectionItem(title = "定语从句", done = 3, total = 10).toProgressItem()
        assertEquals("定语从句", item.title)
        assertEquals(3, item.done)
        assertEquals(10, item.total)
    }

    @Test
    fun `real-exam section without title falls back to year label`() {
        val item = SectionItem(year = 2024, done = 0, total = 20).toProgressItem()
        assertEquals("2024 年真题", item.title)
    }

    // ---------- 空数据 → 占位状态 ----------

    @Test
    fun `empty points yield empty fractions (placeholder state)`() {
        assertTrue(barFractions(emptyList()).isEmpty())
    }

    @Test
    fun `single point yields single fraction of 1`() {
        val f = barFractions(listOf(DailyTrendPoint("2026-09-23", 3, 0.5)))
        assertEquals(listOf(1f), f)
    }

    // ---------- 归一化与保护 ----------

    @Test
    fun `barFractions normalize by max`() {
        val pts = listOf(
            DailyTrendPoint("2026-09-20", 10, 0.0),
            DailyTrendPoint("2026-09-21", 5, 0.0),
            DailyTrendPoint("2026-09-22", 0, 0.0)
        )
        val f = barFractions(pts)
        assertEquals(3, f.size)
        assertEquals(1f, f[0], 1e-6f)
        assertEquals(0.5f, f[1], 1e-6f)
        assertEquals(0f, f[2], 1e-6f)
    }

    @Test
    fun `topic progress guards zero total`() {
        assertEquals(0f, TopicProgressItem("空专题", 0, 0).fraction(), 1e-6f)
    }

    @Test
    fun `topic progress fraction coerced in range`() {
        assertEquals(0.25f, TopicProgressItem("专题", 25, 100).fraction(), 1e-6f)
    }
}
