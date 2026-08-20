package com.friday.wimm.service

import android.app.Notification
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.RemoteInput
import com.friday.wimm.MyApplication
import com.friday.wimm.data.model.Transaction
import com.friday.wimm.util.HashUtil
import com.friday.wimm.util.NotificationParser
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class PaymentNotificationListener : NotificationListenerService() {
    private val scope = CoroutineScope(Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "===== 通知监听服务已创建 =====")
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        Log.d(TAG, "===== 通知监听服务已连接 =====")
        // 列出当前所有通知（调试用）
        try {
            val activeNotifications = activeNotifications
            Log.d(TAG, "当前活跃通知数量: ${activeNotifications?.size ?: 0}")
            activeNotifications?.forEach { sbn ->
                if (sbn.packageName == WECHAT_PACKAGE || sbn.packageName == ALIPAY_PACKAGE) {
                    Log.d(TAG, "  已有通知: ${sbn.packageName} - ${sbn.notification.extras.getString(Notification.EXTRA_TITLE)}")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "获取活跃通知失败", e)
        }
    }

    override fun onListenerDisconnected() {
        super.onListenerDisconnected()
        Log.d(TAG, "===== 通知监听服务已断开，尝试重绑 =====")
        // 系统杀进程/监听断开后自动重绑，保证「不漏记」
        try {
            requestRebind(
                android.content.ComponentName(
                    this,
                    NotificationListenerService::class.java
                )
            )
        } catch (e: Exception) {
            Log.e(TAG, "请求重绑失败", e)
        }
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        val packageName = sbn.packageName

        // 记录所有收到的通知（调试用）
        Log.d(TAG, "========== 收到通知 ==========")
        Log.d(TAG, "包名: $packageName")

        if (!WHITELIST_PACKAGES.contains(packageName)) return

        val notification = sbn.notification
        val extras = notification.extras

        // 尝试多种方式获取通知内容
        val title = extras.getString(Notification.EXTRA_TITLE) ?: ""
        val text = extras.getString(Notification.EXTRA_TEXT) ?: ""
        val bigText = extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString() ?: ""
        val subText = extras.getCharSequence(Notification.EXTRA_SUB_TEXT)?.toString() ?: ""
        val summaryText = extras.getCharSequence(Notification.EXTRA_SUMMARY_TEXT)?.toString() ?: ""
        val infoText = extras.getCharSequence("android.infoText")?.toString() ?: ""
        val tickerText = notification.tickerText?.toString() ?: ""

        // 合并所有可能的文本内容
        val allText = listOf(title, text, bigText, subText, summaryText, infoText, tickerText)
            .filter { it.isNotBlank() }
            .joinToString(" | ")

        Log.d(TAG, "title: $title")
        Log.d(TAG, "text: $text")
        Log.d(TAG, "bigText: $bigText")
        Log.d(TAG, "subText: $subText")
        Log.d(TAG, "summaryText: $summaryText")
        Log.d(TAG, "infoText: $infoText")
        Log.d(TAG, "tickerText: $tickerText")
        Log.d(TAG, "合并文本: $allText")

        // 用合并后的文本尝试解析
        val transaction = parseNotification(packageName, title, allText)
        if (transaction != null) {
            val id = saveTransaction(transaction)
            Log.d(TAG, ">>> 解析成功: ${transaction.type} ${transaction.merchant} ${transaction.amount}, id=$id")
            if (id > 0) {
                // 通知桌宠
                com.friday.wimm.ui.mascot.MascotService.notifyTransaction(
                    this,
                    isIncome = transaction.type == "income",
                    merchant = transaction.merchant
                )
                if (transaction.amount <= 0) {
                    // 金额为0，发送带输入框的通知
                    notifyMissingAmount(title, allText, id)
                } else {
                    // 有金额，发送记录确认通知（带删除按钮）
                    notifyTransactionRecorded(transaction, id)
                }
            }
        } else {
            Log.d(TAG, ">>> 未匹配任何交易模式")
        }
        Log.d(TAG, "==============================")
    }

    private fun parseNotification(pkg: String, title: String, text: String): Transaction? {
        val parsed = NotificationParser.parse(pkg, title, text) ?: return null
        if (parsed.amount <= 0) {
            Log.d(TAG, "交易通知无金额，记录为0: title=$title, text=$text")
        }
        // 去重哈希：md5(分钟级时间|金额|商户)
        val minute = System.currentTimeMillis() / 60000
        val dedupHash = HashUtil.md5("$minute|${"%.2f".format(parsed.amount)}|${parsed.merchant}")
        Log.d(TAG, "解析结果: amount=${parsed.amount}, type=${parsed.type}, merchant=${parsed.merchant}")

        return Transaction(
            amount = parsed.amount,
            merchant = parsed.merchant,
            source = sourceFor(pkg),
            timestamp = System.currentTimeMillis(),
            type = parsed.type,
            transactionNo = null,
            dataSource = "notification",
            status = "pending" // 待核对：进入「昨日 N 笔待核对」弹窗
        )
    }

    private fun notifyMissingAmount(title: String, text: String, transactionId: Long) {
        try {
            val type = if (text.contains("收款") || text.contains("到账") || text.contains("红包")) "income" else "expense"
            val typeLabel = if (type == "income") "收入" else "支出"
            val merchant = NotificationParser.extractMerchant(title, text) ?: "未知"

            val notificationManager = getSystemService(NOTIFICATION_SERVICE) as android.app.NotificationManager
            val channelId = "wimm_transaction_alert"
            val notificationId = transactionId.toInt()

            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                notificationManager.deleteNotificationChannel(channelId)
                val channel = android.app.NotificationChannel(
                    channelId,
                    "交易提醒",
                    android.app.NotificationManager.IMPORTANCE_HIGH
                ).apply {
                    description = "交易金额提醒，支持悬浮通知"
                    enableVibration(true)
                    enableLights(true)
                    setBypassDnd(true)
                    lockscreenVisibility = android.app.Notification.VISIBILITY_PUBLIC
                    setShowBadge(true)
                }
                notificationManager.createNotificationChannel(channel)
            }

            // RemoteInput - 直接在通知中输入金额
            val remoteInput = RemoteInput.Builder(AmountReplyReceiver.KEY_TEXT_REPLY)
                .setLabel("输入金额 (如 88.5)")
                .build()

            val replyIntent = android.content.Intent(this, AmountReplyReceiver::class.java).apply {
                putExtra(AmountReplyReceiver.EXTRA_TRANSACTION_ID, transactionId)
                putExtra(AmountReplyReceiver.EXTRA_NOTIFICATION_ID, notificationId)
                putExtra(AmountReplyReceiver.EXTRA_TYPE, type)
                putExtra(AmountReplyReceiver.EXTRA_MERCHANT, merchant)
            }
            val replyPendingIntent = android.app.PendingIntent.getBroadcast(
                this,
                System.currentTimeMillis().toInt(),
                replyIntent,
                android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_MUTABLE
            )

            val replyAction = NotificationCompat.Action.Builder(
                android.R.drawable.ic_menu_edit,
                "输入金额",
                replyPendingIntent
            ).addRemoteInput(remoteInput).build()

            // 删除按钮 - 误识别时删除记录
            val deleteIntent = android.content.Intent(this, DeleteTransactionReceiver::class.java).apply {
                putExtra(DeleteTransactionReceiver.EXTRA_TRANSACTION_ID, transactionId)
                putExtra(DeleteTransactionReceiver.EXTRA_NOTIFICATION_ID, notificationId)
            }
            val deletePendingIntent = android.app.PendingIntent.getBroadcast(
                this,
                System.currentTimeMillis().toInt() + 1,
                deleteIntent,
                android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE
            )
            val deleteAction = NotificationCompat.Action.Builder(
                android.R.drawable.ic_menu_delete,
                "误识别·删除",
                deletePendingIntent
            ).build()

            // 点击通知打开app
            val intent = packageManager.getLaunchIntentForPackage(packageName)?.apply {
                flags = android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP
            }
            val pendingIntent = android.app.PendingIntent.getActivity(
                this, 0, intent,
                android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE
            )

            val notification = NotificationCompat.Builder(this, channelId)
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentTitle("\uD83D\uDCB0 检测到${typeLabel}交易")
                .setContentText("${merchant} - 请在下方输入金额")
                .setStyle(NotificationCompat.BigTextStyle()
                    .bigText("${merchant}\n检测到${typeLabel}交易，请直接在通知中输入金额记录"))
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setCategory(NotificationCompat.CATEGORY_MESSAGE)
                .setAutoCancel(true)
                .setContentIntent(pendingIntent)
                .setFullScreenIntent(pendingIntent, true)
                .setDefaults(NotificationCompat.DEFAULT_ALL)
                .addAction(replyAction)
                .addAction(deleteAction)
                .build()

            notificationManager.notify(notificationId, notification)
            Log.d(TAG, "已发送输入金额提醒: $typeLabel $merchant")
        } catch (e: Exception) {
            Log.e(TAG, "发送提醒失败", e)
        }
    }

    private fun notifyTransactionRecorded(transaction: Transaction, transactionId: Long) {
        try {
            val typeLabel = if (transaction.type == "income") "收入" else "支出"
            val notificationManager = getSystemService(NOTIFICATION_SERVICE) as android.app.NotificationManager
            // 统一使用同一个 channel，确保 heads-up 弹出
            val channelId = "wimm_transaction_alert"
            val notificationId = transactionId.toInt()

            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                notificationManager.deleteNotificationChannel(channelId)
                val channel = android.app.NotificationChannel(
                    channelId,
                    "交易提醒",
                    android.app.NotificationManager.IMPORTANCE_HIGH
                ).apply {
                    description = "交易金额提醒，支持悬浮通知"
                    enableVibration(true)
                    enableLights(true)
                    setBypassDnd(true)
                    lockscreenVisibility = android.app.Notification.VISIBILITY_PUBLIC
                    setShowBadge(true)
                }
                notificationManager.createNotificationChannel(channel)
            }

            // 删除按钮
            val deleteIntent = android.content.Intent(this, DeleteTransactionReceiver::class.java).apply {
                putExtra(DeleteTransactionReceiver.EXTRA_TRANSACTION_ID, transactionId)
                putExtra(DeleteTransactionReceiver.EXTRA_NOTIFICATION_ID, notificationId)
            }
            val deletePendingIntent = android.app.PendingIntent.getBroadcast(
                this,
                System.currentTimeMillis().toInt(),
                deleteIntent,
                android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE
            )
            val deleteAction = NotificationCompat.Action.Builder(
                android.R.drawable.ic_menu_delete,
                "误识别·删除",
                deletePendingIntent
            ).build()

            // 点击通知打开 app
            val launchIntent = packageManager.getLaunchIntentForPackage(packageName)?.apply {
                flags = android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP
            }
            val pendingIntent = android.app.PendingIntent.getActivity(
                this, 0, launchIntent,
                android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE
            )

            val notification = NotificationCompat.Builder(this, channelId)
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentTitle("已记录${typeLabel}: ¥${String.format("%.2f", transaction.amount)}")
                .setContentText("${transaction.merchant} - 如误识别请点删除")
                .setStyle(NotificationCompat.BigTextStyle()
                    .bigText("${transaction.merchant}\n已记录${typeLabel} ¥${String.format("%.2f", transaction.amount)}\n如误识别请点击下方删除按钮"))
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setCategory(NotificationCompat.CATEGORY_MESSAGE)
                .setAutoCancel(true)
                .setContentIntent(pendingIntent)
                .setFullScreenIntent(pendingIntent, true)
                .setDefaults(NotificationCompat.DEFAULT_ALL)
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                .addAction(deleteAction)
                .build()

            notificationManager.notify(notificationId, notification)
            Log.d(TAG, "已发送记录通知: $typeLabel ${transaction.merchant} ${transaction.amount}")
        } catch (e: Exception) {
            Log.e(TAG, "发送记录通知失败", e)
        }
    }

    private fun saveTransaction(transaction: Transaction): Long {
        return try {
            val application = applicationContext as MyApplication
            kotlinx.coroutines.runBlocking {
                val id = application.transactionRepository.insert(transaction)
                Log.d(TAG, "保存成功: ${transaction.type} ${transaction.merchant} ${transaction.amount}, id=$id")
                id
            }
        } catch (e: Exception) {
            Log.e(TAG, "保存失败", e)
            -1
        }
    }

    companion object {
        private const val TAG = "PaymentNotification"
        private const val WECHAT_PACKAGE = "com.tencent.mm"
        private const val ALIPAY_PACKAGE = "com.eg.android.AlipayGphone"

        /** 自动记账白名单：支付/银行/电商 App（通知栏抓取通道） */
        private val WHITELIST_PACKAGES = setOf(
            // 支付
            "com.tencent.mm",                       // 微信
            "com.eg.android.AlipayGphone",          // 支付宝
            "com.unionpay",                         // 云闪付
            "com.sankuai.meituan",                  // 美团
            "com.sankuai.meituan.takeoutnew",       // 美团外卖
            "com.ss.android.ugc.aweme",             // 抖音
            "com.jingdong.app.mall",                // 京东
            "com.taobao.taobao",                    // 淘宝
            "com.xunmeng.pinduoduo",                // 拼多多
            "me.ele",                               // 饿了么
            "com.sankuai.waimai",                   // 美团外卖（旧）
            // 银行（常见）
            "com.cmbchina.ccd.pluto.cmbActivity",   // 招商银行
            "com.icbc",                             // 工商银行
            "com.chinamworld.main",                 // 农业银行
            "com.ccb.longji",                       // 建设银行
            "com.bankcomm.Bankcomm",                // 交通银行
            "com.spdbccc.app",                      // 浦发银行
            "com.pingan.paces.ccms",                // 平安银行
            "com.cgbchina.xpt",                     // 广发银行
            "cn.com.cmbc.mbank",                    // 民生银行
            "com.chinamobile.gd.ums",               // 移动
            "com.tencent.mm.bank",                  // 微信银行（备用）
            "com.netease.money",                    // 网易支付
            "com.jd.jrapp",                         // 京东金融
            "com.eg.android.AlipayGphoneGC",        // 支付宝（国际）
            "com.xingin.xhs",                       // 小红书
            "com.smile.gifmaker",                   // 快手
            "com.baidu.searchbox",                  // 百度（备用）
            "com.huawei.wallet",                    // 华为钱包
            "com.miui.payment",                     // 小米钱包
            "com.oppo.wallet",                      // OPPO 钱包
            "vivo.vivo.wallet",                     // vivo 钱包
        )

        /** 包名 -> 来源标签 */
        fun sourceFor(pkg: String): String = when (pkg) {
            WECHAT_PACKAGE -> "wechat"
            ALIPAY_PACKAGE -> "alipay"
            "com.unionpay" -> "unionpay"
            "com.sankuai.meituan", "com.sankuai.meituan.takeoutnew", "com.sankuai.waimai" -> "meituan"
            "com.ss.android.ugc.aweme" -> "douyin"
            else -> {
                // 银行包名统一标记为 bank
                if (pkg.startsWith("com.cmbchina") || pkg.startsWith("com.icbc") ||
                    pkg.startsWith("com.chinamworld") || pkg.startsWith("com.ccb") ||
                    pkg.startsWith("com.bankcomm") || pkg.startsWith("com.spdbccc") ||
                    pkg.startsWith("com.pingan") || pkg.startsWith("com.cgbchina") ||
                    pkg.startsWith("cn.com.cmbc")) "bank" else "notification"
            }
        }

        /** md5 哈希（去重用） */
        fun md5(text: String): String {
            return try {
                val digest = java.security.MessageDigest.getInstance("MD5")
                val bytes = digest.digest(text.toByteArray(Charsets.UTF_8))
                bytes.joinToString("") { "%02x".format(it) }
            } catch (e: Exception) {
                text.hashCode().toString()
            }
        }
    }
}
