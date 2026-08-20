package com.friday.wimm.util

import com.friday.wimm.data.model.Transaction
import java.util.Calendar

/** 单一时段的统计结果 */
data class PeriodStat(
    val label: String,
    val expense: Double,
    val income: Double,
    val expenseCount: Int,
    val incomeCount: Int
)

/**
 * 分时段统计：今日 / 本周（周一起）/ 本月 / 本年
 * 纯 Kotlin 实现，无 Android 依赖，可单元测试。
 */
object PeriodStats {

    fun compute(transactions: List<Transaction>, now: Long = System.currentTimeMillis()): List<PeriodStat> {
        val todayStart = startOfDay(now)

        // 本周起点（周一 00:00）
        val dayOfWeek = Calendar.getInstance().apply { timeInMillis = now }.get(Calendar.DAY_OF_WEEK) // SUNDAY=1..SATURDAY=7
        val daysSinceMonday = (dayOfWeek + 5) % 7
        val weekStart = Calendar.getInstance().apply {
            timeInMillis = todayStart
            add(Calendar.DAY_OF_YEAR, -daysSinceMonday)
        }.timeInMillis

        // 本月起点（1 号 00:00）
        val monthStart = Calendar.getInstance().apply {
            timeInMillis = now
            set(Calendar.DAY_OF_MONTH, 1)
            set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }.timeInMillis

        // 本年起点（1 月 1 日 00:00）
        val yearStart = Calendar.getInstance().apply {
            timeInMillis = now
            set(Calendar.MONTH, Calendar.JANUARY)
            set(Calendar.DAY_OF_MONTH, 1)
            set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }.timeInMillis

        fun stat(label: String, start: Long): PeriodStat {
            var expense = 0.0
            var income = 0.0
            var expenseCount = 0
            var incomeCount = 0
            for (t in transactions) {
                if (t.timestamp < start || t.timestamp > now) continue
                if (t.type == "expense") {
                    expense += t.amount
                    expenseCount++
                } else {
                    income += t.amount
                    incomeCount++
                }
            }
            return PeriodStat(label, expense, income, expenseCount, incomeCount)
        }

        return listOf(
            stat("今日", todayStart),
            stat("本周", weekStart),
            stat("本月", monthStart),
            stat("本年", yearStart)
        )
    }

    private fun startOfDay(millis: Long): Long = Calendar.getInstance().apply {
        timeInMillis = millis
        set(Calendar.HOUR_OF_DAY, 0)
        set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
    }.timeInMillis
}
