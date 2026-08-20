package com.friday.wimm.util

/**
 * 通知解析器（纯 Kotlin，无 Android 依赖，可单元测试）
 * 从支付 App 通知文本中提取：金额 / 收支类型 / 商户
 */
object NotificationParser {

    data class Parsed(val amount: Double, val type: String, val merchant: String)

    /**
     * 解析通知文本。非交易通知返回 null。
     */
    fun parse(pkg: String, title: String, text: String): Parsed? {
        // 清理文本：移除 [2条] 等前缀
        val cleanText = text.replace(Regex("""\[?\d+条\]?"""), "").trim()

        // 提取金额（优先从text，其次从title）
        val amount = extractAmount(cleanText) ?: extractAmount(title)

        // 判断是否是交易相关通知
        if (!isPaymentNotification(title, cleanText)) return null

        val finalAmount = amount ?: 0.0

        // 判断收入/支出
        val type = detectType(title, cleanText)

        // 提取商户/对方名称
        val merchant = extractMerchant(title, cleanText) ?: if (type == "income") "未知收入" else "未知支出"

        return Parsed(finalAmount, type, merchant)
    }

    /** 支付通知识别 */
    fun isPaymentNotification(title: String, text: String): Boolean {
        val combined = "$title $text"

        // 1. 微信支付官方通知
        if (title == "微信支付" || title == "微信收款助手" || title == "微信") return true

        // 2. 支付宝通知
        if (title.contains("支付宝")) return true

        // 3. 明确的支付行为短语（优先匹配）
        val paymentPhrases = listOf(
            "你收到一笔转账", "你收到一个红包", "向你转账", "给你转账",
            "收款到账", "付款成功", "支付成功", "转账成功",
            "红包已存入零钱", "已收钱", "收款成功", "向你付款",
            "发出了红包", "成功收款", "收到红包", "领取了你的红包",
            "已转账", "转账收款", "红包来了", "给你发了一个红包",
            "向你付钱", "收款通知", "到账通知", "付款通知",
            "已收钱", "转账已到账", "请收款", "请收钱",
            "发起了一笔转账", "发来了红包", "转账到银行卡",
            "已被接收", "已被领取", "已被接受", "已接收", "已领取", "已接受",
            "转账已被", "红包已被", "已存入零钱", "已转入"
        )
        for (phrase in paymentPhrases) {
            if (combined.contains(phrase)) return true
        }

        // 4. 包含 "[转账]" 或 "[红包]" 标记（兼容全角/半角括号）
        if (combined.contains("[转账]") || combined.contains("[红包]") ||
            combined.contains("[付款]") || combined.contains("[收款]") ||
            combined.contains("［转账］") || combined.contains("［红包］") ||
            combined.contains("［付款］") || combined.contains("［收款］") ||
            combined.contains("微信红包") || combined.contains("恭喜发财")) return true

        // 5. 同时包含"转账"和"收款"/"收钱"
        if (combined.contains("转账") && (combined.contains("收款") || combined.contains("收钱"))) return true

        // 6. 有明确金额符号的支付相关消息
        val hasExplicitAmount = combined.contains("¥") || combined.contains("￥") ||
                Regex("""\d+\.?\d*元""").containsMatchIn(combined)

        if (hasExplicitAmount) {
            val paymentKeywords = listOf(
                "转账", "收款", "付款", "支付", "到账", "红包",
                "零钱", "已收", "已付", "成功", "消费", "金额"
            )
            for (keyword in paymentKeywords) {
                if (combined.contains(keyword)) return true
            }
        }

        // 7. 红包 + 有明确金额
        if (combined.contains("红包") && hasExplicitAmount) return true

        return false
    }

    /** 收入/支出判断 */
    fun detectType(title: String, text: String): String {
        val combined = "$title$text"
        val incomeKeywords = listOf(
            "到账", "收入", "收到", "收款", "转入", "进账",
            "红包", "退款", "已收", "已到账", "入账",
            "给你转账", "转给你", "向你付款", "给你发了一个红包",
            "收款成功", "转账收款"
        )
        val expenseKeywords = listOf(
            "支付", "付款", "消费", "支出", "转出", "扣费",
            "已付", "扣款", "成功支付", "缴费", "充值",
            "转账成功", "发出了红包", "已转账"
        )

        for (keyword in incomeKeywords) {
            if (combined.contains(keyword)) return "income"
        }
        for (keyword in expenseKeywords) {
            if (combined.contains(keyword)) return "expense"
        }
        return "expense"
    }

    /** 金额提取 */
    fun extractAmount(text: String): Double? {
        val patterns = listOf(
            Regex("""[¥￥]\s*(\d+\.?\d*)"""),
            Regex("""(\d+\.?\d*)\s*元"""),
            Regex("""(?:金额|收款|付款|转账|红包|到账|成功)[：:\s]*(\d+\.?\d*)"""),
            Regex("""(\d+\.\d{1,2})"""),
        )

        for (pattern in patterns) {
            val match = pattern.find(text)
            val value = match?.groupValues?.get(1)?.toDoubleOrNull()
            if (value != null && value > 0) {
                return value
            }
        }

        // 兜底：任何看起来像金额的数字（带小数点且不超过10位整数）
        val fallback = Regex("""(\d{1,10}\.\d{1,2})""").find(text)
        val fallbackValue = fallback?.groupValues?.get(1)?.toDoubleOrNull()
        if (fallbackValue != null && fallbackValue > 0) {
            return fallbackValue
        }

        return null
    }

    /** 商户/对方名称提取 */
    fun extractMerchant(title: String, text: String): String? {
        // 微信转账：title就是对方昵称
        if (text.contains("转账") || text.contains("收款") || text.contains("红包")) {
            if (title.isNotBlank() && title.length <= 20 && !title.contains("微信")) {
                return title
            }
        }

        val patterns = listOf(
            Regex("""商户[：:\s]*(.+?)(?:\s|$)"""),
            Regex("""收款方[：:\s]*(.+?)(?:\s|$)"""),
            Regex("""向(.+?)付款"""),
            Regex("""(.+?)向你付款"""),
            Regex("""给(.+?)转账"""),
            Regex("""(.+?)给你转账"""),
            Regex("""来自(.+?)(?:\s|$)"""),
            Regex("""付款给(.+?)(?:\s|$)"""),
            Regex("""(.+?)付款成功"""),
            Regex("""在(.+?)支付"""),
            Regex("""于(.+?)支付"""),
            Regex("""(.+?)订单支付"""),
            Regex("""向(.+?)转账"""),
        )

        for (pattern in patterns) {
            val match = pattern.find(text) ?: pattern.find(title)
            val merchant = match?.groupValues?.get(1)?.trim()
            if (!merchant.isNullOrBlank() && merchant.length <= 20) {
                return merchant
            }
        }

        // 针对"已支付:xx元"这类没有商户名的通知，根据来源返回默认名称
        if (text.contains("已支付") || text.contains("支付成功")) {
            return if (title.contains("微信")) "微信支付" else if (title.contains("支付宝")) "支付宝" else "快捷支付"
        }
        if (text.contains("已收款") || text.contains("收款成功")) {
            return if (title.contains("微信")) "微信收款" else if (title.contains("支付宝")) "支付宝收款" else "快捷收款"
        }
        return null
    }
}
