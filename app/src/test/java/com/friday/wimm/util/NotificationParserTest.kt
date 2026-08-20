package com.friday.wimm.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 通知解析模块单元测试（验收标准：至少 1 个单元测试覆盖通知解析）
 */
class NotificationParserTest {

    @Test
    fun `微信支付通知解析出金额商户和支出类型`() {
        val parsed = NotificationParser.parse(
            pkg = "com.tencent.mm",
            title = "微信支付",
            text = "微信支付凭证\n付款给 某某超市\n¥25.00"
        )
        assertNotNull(parsed)
        parsed!!.let {
            assertEquals(25.0, it.amount, 0.001)
            assertEquals("expense", it.type)
            assertEquals("某某超市", it.merchant)
        }
    }

    @Test
    fun `支付宝收款到账解析为收入`() {
        val parsed = NotificationParser.parse(
            pkg = "com.eg.android.AlipayGphone",
            title = "支付宝",
            text = "收款到账 ¥30.00 来自 小明"
        )
        assertNotNull(parsed)
        parsed!!.let {
            assertEquals(30.0, it.amount, 0.001)
            assertEquals("income", it.type)
        }
    }

    @Test
    fun `非交易通知返回null`() {
        val parsed = NotificationParser.parse(
            pkg = "com.android.systemui",
            title = "系统通知",
            text = "充电完成，已拔下电源"
        )
        assertNull(parsed)
    }

    @Test
    fun `微信消息通知按设计会被捕获为待核对记录`() {
        // 设计说明：微信所有通知标题都是「微信」，为「不漏记」会先捕获，
        // 金额为 0 走待核对（昨日 N 笔待核对）由用户确认或删除
        val parsed = NotificationParser.parse(
            pkg = "com.tencent.mm",
            title = "微信",
            text = "你有一条新消息，请查看"
        )
        assertNotNull(parsed)
        assertEquals(0.0, parsed!!.amount, 0.001)
    }

    @Test
    fun `红包通知识别为收入`() {
        val parsed = NotificationParser.parse(
            pkg = "com.tencent.mm",
            title = "微信",
            text = "收到红包 8.88元"
        )
        assertNotNull(parsed)
        assertEquals(8.88, parsed!!.amount, 0.001)
        assertEquals("income", parsed.type)
    }

    @Test
    fun `金额带单位与关键词格式`() {
        val parsed = NotificationParser.parse(
            pkg = "com.unionpay",
            title = "云闪付",
            text = "消费金额：88.5元"
        )
        assertNotNull(parsed)
        assertEquals(88.5, parsed!!.amount, 0.001)
        assertEquals("expense", parsed.type)
    }

    @Test
    fun `银行扣款通知解析`() {
        val parsed = NotificationParser.parse(
            pkg = "com.cmbchina.ccd.pluto.cmbActivity",
            title = "招商银行",
            text = "您尾号1234的信用卡支付成功 199.00元，商户：京东商城"
        )
        assertNotNull(parsed)
        assertEquals(199.0, parsed!!.amount, 0.001)
        assertEquals("expense", parsed.type)
    }

    @Test
    fun `无金额的交易通知仍返回记录但金额为0`() {
        val parsed = NotificationParser.parse(
            pkg = "com.tencent.mm",
            title = "微信支付",
            text = "你收到一笔转账，请查收"
        )
        assertNotNull(parsed)
        assertEquals(0.0, parsed!!.amount, 0.001)
    }

    @Test
    fun `去重哈希一致性`() {
        val h1 = HashUtil.md5("2025-10-01|25.00|某某超市")
        val h2 = HashUtil.md5("2025-10-01|25.00|某某超市")
        val h3 = HashUtil.md5("2025-10-02|25.00|某某超市")
        assertEquals(h1, h2)
        assertTrue(h1 != h3)
        assertEquals(32, h1.length) // md5 十六进制 32 位
    }
}
