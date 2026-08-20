package com.friday.wimm.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 用户 Excel（843 条真实账单）全量解析验收测试：
 * 表头 = 账单日期 | 分类筛选 | 记账分类 | 收支类型 | 备注 | 金额 | 备注图片1-4
 */
class XLSXParserTest {

    private fun resourceStream() =
        javaClass.classLoader.getResourceAsStream("bills_sample.xlsx")
            ?: error("bills_sample.xlsx 未找到，请先复制用户的 Excel 到 app/src/test/resources/")

    @Test
    fun `解析用户Excel共843条`() {
        val transactions = XLSXParser.parse(resourceStream())
        assertEquals(843, transactions.size)
    }

    @Test
    fun `支出797条收入46条`() {
        val transactions = XLSXParser.parse(resourceStream())
        val expense = transactions.count { it.type == "expense" }
        val income = transactions.count { it.type == "income" }
        assertEquals(797, expense)
        assertEquals(46, income)
    }

    @Test
    fun `解析出的分类与商户字段对齐`() {
        val transactions = XLSXParser.parse(resourceStream())
        val withCategoryTop = transactions.count { it.categoryTop.isNotBlank() }
        // 备注列为空的行回退为「未知」，因此商户字段恒非空
        val withMerchant = transactions.count { it.merchant.isNotBlank() }
        assertTrue("一级分类应几乎全部有值", withCategoryTop > 800)
        assertEquals("商户字段恒非空（空备注回退「未知」）", transactions.size, withMerchant)
        assertTrue("所有金额非负（含 29 条 0 元账单）", transactions.all { it.amount >= 0 })
        assertTrue("所有行都有去重哈希", transactions.all { it.dedupHash.isNotEmpty() })
    }

    @Test
    fun `去重哈希在文件内无碰撞`() {
        val transactions = XLSXParser.parse(resourceStream())
        val hashes = transactions.map { it.dedupHash }.toSet()
        assertEquals("843 条记录的 dedupHash 应互不相同（重复 0）", transactions.size, hashes.size)
    }
}
