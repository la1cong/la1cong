package com.friday.wimm.util

import com.friday.wimm.data.model.Transaction
import org.apache.poi.ss.usermodel.CellType
import org.apache.poi.ss.usermodel.Workbook
import org.apache.poi.ss.usermodel.WorkbookFactory
import org.apache.poi.ss.usermodel.DateUtil
import java.io.InputStream
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

/**
 * Excel 解析：
 * 1. 用户账单格式：表头为「账单日期 | 分类筛选 | 记账分类 | 收支类型 | 备注 | 金额 | 备注图片1-4」
 * 2. 微信/支付宝导出格式：表头为「交易时间 | 交易对方 | 金额(元) | 收/支 | …」（原有逻辑保留）
 */
object XLSXParser {

    fun parse(inputStream: InputStream): List<Transaction> {
        val workbook = WorkbookFactory.create(inputStream)
        return try {
            if (hasUserHeader(workbook)) parseUserExcel(workbook) else parseStandardExcel(workbook)
        } finally {
            workbook.close()
        }
    }

    // ===== 用户账单格式（分类筛选 表头） =====

    private fun hasUserHeader(workbook: Workbook): Boolean {
        val sheet = workbook.getSheetAt(0)
        for (i in 0..sheet.lastRowNum) {
            val row = sheet.getRow(i) ?: continue
            var hasDate = false
            var hasAmount = false
            var hasCategory = false
            for (j in 0 until row.lastCellNum) {
                val text = row.getCell(j)?.toString()?.trim() ?: ""
                if (text.contains("账单日期")) hasDate = true
                if (text.contains("金额")) hasAmount = true
                if (text.contains("分类筛选")) hasCategory = true
            }
            if (hasDate && hasAmount && hasCategory) return true
        }
        return false
    }

    private fun parseUserExcel(workbook: Workbook): List<Transaction> {
        val transactions = mutableListOf<Transaction>()
        val sheet = workbook.getSheetAt(0)

        var headerRowIndex = -1
        var headerMap = mutableMapOf<String, Int>()
        for (i in 0..sheet.lastRowNum) {
            val row = sheet.getRow(i) ?: continue
            for (j in 0 until row.lastCellNum) {
                val headerText = row.getCell(j)?.toString()?.trim() ?: ""
                if (headerText.contains("账单日期")) {
                    headerRowIndex = i
                    for (k in 0 until row.lastCellNum) {
                        val t = row.getCell(k)?.toString()?.trim() ?: ""
                        if (t.isNotEmpty()) headerMap[t] = k
                    }
                    break
                }
            }
            if (headerRowIndex != -1) break
        }

        if (headerRowIndex == -1 || headerMap.isEmpty()) return transactions

        val dateCol = findColumn(headerMap, "账单日期")
        val categoryTopCol = findColumn(headerMap, "分类筛选")
        val categorySubCol = findColumn(headerMap, "记账分类")
        val typeCol = findColumn(headerMap, "收支类型")
        val noteCol = findColumn(headerMap, "备注")
        val amountCol = findColumn(headerMap, "金额")
        val imageCols = (1..4).mapNotNull { idx ->
            val c = findColumn(headerMap, "备注图片$idx")
            if (c >= 0) c else null
        }

        for (i in (headerRowIndex + 1)..sheet.lastRowNum) {
            val row = sheet.getRow(i) ?: continue
            try {
                // 金额（允许 0 元账单，真实文件中有 29 条金额为 0 的记录）
                val amountCell = if (amountCol >= 0) row.getCell(amountCol) else null
                val amount = getNumericValue(amountCell) ?: continue

                // 收支类型
                val typeStr = (if (typeCol >= 0) row.getCell(typeCol) else null)?.toString()?.trim() ?: ""
                val type = when {
                    typeStr.contains("收入") -> "income"
                    typeStr.contains("支出") -> "expense"
                    else -> continue // 无法识别（如"/"不计收支）跳过
                }

                // 日期
                val dateCell = if (dateCol >= 0) row.getCell(dateCol) else null
                val timestamp = parseUserDate(dateCell)
                // 哈希用秒级时间（账单日期带时分秒；同分钟不同秒是独立交易，不能误并）
                val dateStr = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.CHINA).format(java.util.Date(timestamp))

                // 备注（商户名存于此列；空单元格返回 "" 而非 null，用 ifBlank 回退）
                val merchant = (if (noteCol >= 0) row.getCell(noteCol) else null)
                    ?.toString()?.trim()?.ifBlank { "未知" } ?: "未知"
                if (merchant.contains("合计") || merchant.contains("总计")) continue

                // 分类
                val categoryTop = (if (categoryTopCol >= 0) row.getCell(categoryTopCol) else null)?.toString()?.trim() ?: ""
                val categorySub = (if (categorySubCol >= 0) row.getCell(categorySubCol) else null)?.toString()?.trim() ?: ""

                // 备注图片1-4（文本引用；若为本地文件路径可直接展示）
                val images = imageCols.mapNotNull { c ->
                    row.getCell(c)?.toString()?.trim()?.takeIf { it.isNotEmpty() }
                }

                // 去重：hash(日期|金额|商户)
                val dedupHash = HashUtil.md5("$dateStr|${"%.2f".format(amount)}|$merchant")

                transactions.add(
                    Transaction(
                        amount = amount,
                        merchant = merchant,
                        source = "excel",
                        timestamp = timestamp,
                        type = type,
                        transactionNo = null,
                        dataSource = "file",
                        categoryTop = categoryTop,
                        categorySub = categorySub,
                        images = images,
                        status = "confirmed",
                        dedupHash = dedupHash
                    )
                )
            } catch (e: Exception) {
                // 跳过解析失败的行
            }
        }
        return transactions
    }

    /** 用户 Excel 日期：Excel 序列号 / 日期单元格 / 文本日期 */
    private fun parseUserDate(cell: org.apache.poi.ss.usermodel.Cell?): Long {
        if (cell == null) return System.currentTimeMillis()
        return try {
            when (cell.cellType) {
                CellType.NUMERIC -> {
                    if (DateUtil.isCellDateFormatted(cell)) {
                        cell.dateCellValue.time
                    } else {
                        val serial = cell.numericCellValue
                        // Excel 序列日期 → epoch ms（1900 系统，25569 = 1970-01-01）
                        DateUtil.getJavaDate(serial).time
                    }
                }
                CellType.STRING -> {
                    val dateStr = cell.stringCellValue.trim()
                    val formats = listOf(
                        "yyyy-MM-dd HH:mm:ss", "yyyy/MM/dd HH:mm:ss",
                        "yyyy-MM-dd HH:mm", "yyyy/MM/dd HH:mm",
                        "yyyy-MM-dd", "yyyy/MM/dd", "yyyy.MM.dd"
                    )
                    for (format in formats) {
                        try {
                            val sdf = SimpleDateFormat(format, Locale.CHINA)
                            sdf.isLenient = false
                            val date = sdf.parse(dateStr)
                            if (date != null) return date.time
                        } catch (_: Exception) {}
                    }
                    System.currentTimeMillis()
                }
                else -> System.currentTimeMillis()
            }
        } catch (e: Exception) {
            System.currentTimeMillis()
        }
    }

    // ===== 微信/支付宝导出格式（原有逻辑） =====

    private fun parseStandardExcel(workbook: Workbook): List<Transaction> {
        val transactions = mutableListOf<Transaction>()
        val sheet = workbook.getSheetAt(0)

        // 找到表头行（包含"交易时间"的行）
        var headerRowIndex = -1
        var headerMap = mutableMapOf<String, Int>()

        for (i in 0..sheet.lastRowNum) {
            val row = sheet.getRow(i) ?: continue
            val firstCell = row.getCell(0)?.toString() ?: continue
            if (firstCell.contains("交易时间")) {
                headerRowIndex = i
                for (j in 0 until row.lastCellNum) {
                    val headerText = row.getCell(j)?.toString()?.trim() ?: ""
                    if (headerText.isNotEmpty()) {
                        headerMap[headerText] = j
                    }
                }
                break
            }
        }

        if (headerRowIndex == -1 || headerMap.isEmpty()) {
            return transactions
        }

        val timeCol = findColumn(headerMap, "交易时间")
        val merchantCol = findColumn(headerMap, "交易对方", "对方")
        val amountCol = findColumn(headerMap, "金额(元)", "金额")
        val typeCol = findColumn(headerMap, "收/支", "收/支 ")
        val noteCol = findColumn(headerMap, "备注", "备注 ")
        val productCol = findColumn(headerMap, "商品", "商品 ")
        val transactionNoCol = findColumn(headerMap, "交易单号")

        for (i in (headerRowIndex + 1)..sheet.lastRowNum) {
            val row = sheet.getRow(i) ?: continue
            try {
                val amountCell = if (amountCol >= 0) row.getCell(amountCol) else null
                val amount = getNumericValue(amountCell)
                if (amount == null || amount == 0.0) continue

                val typeCell = if (typeCol >= 0) row.getCell(typeCol) else null
                val typeStr = typeCell?.toString()?.trim() ?: ""
                if (typeStr == "/" || typeStr.isEmpty()) continue

                val type = when {
                    typeStr.contains("收入") -> "income"
                    typeStr.contains("支出") -> "expense"
                    else -> continue
                }

                val merchantCell = if (merchantCol >= 0) row.getCell(merchantCol) else null
                val merchant = merchantCell?.toString()?.trim() ?: ""

                val noteCell = if (noteCol >= 0) row.getCell(noteCol) else null
                val note = noteCell?.toString()?.trim() ?: ""

                val productCell = if (productCol >= 0) row.getCell(productCol) else null
                val product = productCell?.toString()?.trim() ?: ""

                val transactionNoCell = if (transactionNoCol >= 0) row.getCell(transactionNoCol) else null
                val transactionNo = transactionNoCell?.toString()?.trim() ?: ""

                val timeCell = if (timeCol >= 0) row.getCell(timeCol) else null
                val timestamp = parseTimestamp(timeCell)

                val finalMerchant = merchant.ifEmpty { product.ifEmpty { "未知" } }
                if (finalMerchant.contains("合计") || finalMerchant.contains("总计")) continue

                transactions.add(
                    Transaction(
                        amount = amount,
                        merchant = finalMerchant,
                        source = "xlsx",
                        timestamp = timestamp,
                        note = note.ifEmpty { product },
                        type = type,
                        transactionNo = transactionNo.ifEmpty { null }
                    )
                )
            } catch (e: Exception) {
                // 跳过解析失败的行
            }
        }
        return transactions
    }

    private fun findColumn(headerMap: Map<String, Int>, vararg names: String): Int {
        for (name in names) {
            headerMap[name]?.let { return it }
        }
        for (name in names) {
            for ((key, value) in headerMap) {
                if (key.startsWith(name) || key.contains(name)) return value
            }
        }
        return -1
    }

    private fun getNumericValue(cell: org.apache.poi.ss.usermodel.Cell?): Double? {
        if (cell == null) return null
        return when (cell.cellType) {
            CellType.NUMERIC -> cell.numericCellValue
            CellType.STRING -> cell.stringCellValue.trim().toDoubleOrNull()
            CellType.FORMULA -> try { cell.numericCellValue } catch (e: Exception) { null }
            else -> null
        }
    }

    private fun parseTimestamp(cell: org.apache.poi.ss.usermodel.Cell?): Long {
        if (cell == null) return System.currentTimeMillis()
        return try {
            when (cell.cellType) {
                CellType.NUMERIC -> {
                    if (DateUtil.isCellDateFormatted(cell)) {
                        cell.dateCellValue.time
                    } else {
                        System.currentTimeMillis()
                    }
                }
                CellType.STRING -> {
                    val dateStr = cell.stringCellValue.trim()
                    val formats = listOf(
                        "yyyy-MM-dd HH:mm:ss",
                        "yyyy/MM/dd HH:mm:ss",
                        "yyyy-MM-dd HH:mm",
                        "yyyy/MM/dd HH:mm"
                    )
                    for (format in formats) {
                        try {
                            val sdf = SimpleDateFormat(format, Locale.CHINA)
                            val date = sdf.parse(dateStr)
                            if (date != null) return date.time
                        } catch (_: Exception) {}
                    }
                    System.currentTimeMillis()
                }
                else -> System.currentTimeMillis()
            }
        } catch (e: Exception) {
            System.currentTimeMillis()
        }
    }
}
