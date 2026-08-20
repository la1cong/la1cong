package com.friday.wimm.data.model

/**
 * 交易记录（对应 Excel 列：账单日期 | 分类筛选 | 记账分类 | 收支类型 | 备注 | 金额 | 备注图片1-4）
 */
data class Transaction(
    val id: Long = 0,
    val amount: Double,
    val merchant: String,
    val categoryId: Long? = null,
    val source: String,
    val timestamp: Long,
    val note: String = "",
    val type: String = "expense", // "expense" 或 "income"
    val transactionNo: String? = null, // 交易单号，用于文件导入去重
    val dataSource: String = "file", // "file" 文件导入 或 "notification" 通知监听
    val fileId: Long = 0, // 关联的导入文件ID，0表示非文件导入
    // ===== 以下为自动记账 App 扩展字段 =====
    val categoryTop: String = "", // 分类筛选（一级分类，对应 Excel「分类筛选」）
    val categorySub: String = "", // 记账分类（二级分类，对应 Excel「记账分类」）
    val images: List<String> = emptyList(), // 备注图片1-4（文本引用或本地文件路径）
    val status: String = "confirmed", // confirmed=已确认入库 / pending=待核对
    val dedupHash: String = "" // 去重哈希 md5(账单日期|金额|商户)，重复自动跳过
) {
    /** 来源标签（展示用）：支付宝自动记账 / 微信自动记账 / 云闪付自动记账 / 银行通知 / 手动记账 / Excel导入 等 */
    val sourceLabel: String
        get() = when (source) {
            "alipay" -> "支付宝自动记账"
            "wechat" -> "微信自动记账"
            "unionpay" -> "云闪付自动记账"
            "bank" -> "银行通知"
            "douyin" -> "抖音自动记账"
            "meituan" -> "美团自动记账"
            "sms" -> "银行短信"
            "manual" -> "手动记账"
            "excel" -> "Excel导入"
            "xlsx" -> "Excel导入"
            "csv" -> "CSV导入"
            "ocr" -> "OCR识别"
            "notification" -> "自动记账"
            else -> "自动记账"
        }

    val isPending: Boolean get() = status == "pending"
}
