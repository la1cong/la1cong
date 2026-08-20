package com.friday.wimm.util

import com.friday.wimm.data.model.Transaction
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Calendar

/**
 * PeriodStats 分时段统计单元测试
 * 固定 now = 2026-08-20 12:00:00（周四），验证 今日/本周/本月/本年 的边界归属。
 */
class PeriodStatsTest {

    private fun ts(year: Int, month: Int, day: Int, hour: Int = 9, minute: Int = 0): Long =
        Calendar.getInstance().apply {
            set(year, month - 1, day, hour, minute, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis

    private fun tx(timestamp: Long, amount: Double, type: String = "expense") = Transaction(
        amount = amount,
        merchant = "测试商户",
        source = "excel",
        timestamp = timestamp,
        type = type
    )

    private val now: Long = ts(2026, 8, 20, 12, 0) // 周四

    private fun compute(vararg txs: Transaction): Map<String, PeriodStat> =
        PeriodStats.compute(txs.toList(), now).associateBy { it.label }

    @Test
    fun `各时段正确归属`() {
        val t1 = tx(ts(2026, 8, 20, 10), 10.0)                 // 今日 10:00（周四）
        val t2 = tx(ts(2026, 8, 18, 9), 20.0, "income")        // 本周二
        val t3 = tx(ts(2026, 8, 16, 9), 30.0)                  // 上周日（不在本周）
        val t4 = tx(ts(2026, 7, 31, 9), 40.0)                  // 上月（不在本月）
        val t5 = tx(ts(2026, 1, 1, 0, 30), 50.0, "income")     // 本年 1 月 1 日
        val t6 = tx(ts(2025, 12, 31, 23), 60.0)                // 去年（不在本年）
        val t7 = tx(ts(2026, 8, 20, 13), 70.0)                 // 晚于 now（不计入任何时段）

        val stats = compute(t1, t2, t3, t4, t5, t6, t7)

        // 今日：只有 t1
        assertEquals(10.0, stats.getValue("今日").expense, 0.001)
        assertEquals(0.0, stats.getValue("今日").income, 0.001)
        assertEquals(1, stats.getValue("今日").expenseCount)
        assertEquals(0, stats.getValue("今日").incomeCount)

        // 本周（周一 8/17 起）：t1 + t2
        assertEquals(10.0, stats.getValue("本周").expense, 0.001)
        assertEquals(20.0, stats.getValue("本周").income, 0.001)
        assertEquals(1, stats.getValue("本周").expenseCount)
        assertEquals(1, stats.getValue("本周").incomeCount)

        // 本月（8 月起）：t1 + t2 + t3
        assertEquals(40.0, stats.getValue("本月").expense, 0.001)
        assertEquals(20.0, stats.getValue("本月").income, 0.001)
        assertEquals(2, stats.getValue("本月").expenseCount)
        assertEquals(1, stats.getValue("本月").incomeCount)

        // 本年（2026 起）：t1 + t2 + t3 + t4 + t5
        assertEquals(80.0, stats.getValue("本年").expense, 0.001)
        assertEquals(70.0, stats.getValue("本年").income, 0.001)
        assertEquals(3, stats.getValue("本年").expenseCount)
        assertEquals(2, stats.getValue("本年").incomeCount)
    }

    @Test
    fun `空数据所有时段为零`() {
        val stats = compute()
        PeriodStats.compute(emptyList(), now).forEach { ps ->
            assertEquals(0.0, ps.expense, 0.001)
            assertEquals(0.0, ps.income, 0.001)
            assertEquals(0, ps.expenseCount)
            assertEquals(0, ps.incomeCount)
        }
        assertEquals(4, stats.size)
    }

    @Test
    fun `时段标签顺序固定`() {
        val labels = PeriodStats.compute(emptyList(), now).map { it.label }
        assertEquals(listOf("今日", "本周", "本月", "本年"), labels)
    }
}
