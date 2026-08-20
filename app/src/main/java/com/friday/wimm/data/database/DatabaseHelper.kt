package com.friday.wimm.data.database

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.friday.wimm.data.model.Card
import com.friday.wimm.data.model.CardCategory
import com.friday.wimm.data.model.CardMerchantCategory
import com.friday.wimm.data.model.Category
import com.friday.wimm.data.model.MerchantCategory
import com.friday.wimm.data.model.Transaction

class DatabaseHelper(context: Context) : SQLiteOpenHelper(context, DATABASE_NAME, null, DATABASE_VERSION) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(SQL_CREATE_TRANSACTIONS)
        db.execSQL(SQL_CREATE_CATEGORIES)
        db.execSQL(SQL_CREATE_MERCHANT_CATEGORIES)
        db.execSQL(SQL_CREATE_IMPORT_RECORDS)
        db.execSQL(SQL_CREATE_CARDS)
        db.execSQL(SQL_CREATE_CARD_CATEGORIES)
        db.execSQL(SQL_CREATE_CARD_MERCHANT_CATEGORIES)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) {
            db.execSQL("ALTER TABLE transactions ADD COLUMN type TEXT DEFAULT 'expense'")
        }
        if (oldVersion < 3) {
            db.execSQL("ALTER TABLE transactions ADD COLUMN transactionNo TEXT")
        }
        if (oldVersion < 4) {
            // 升级到版本4：添加 dataSource 字段标记来源（file/notification）
            db.execSQL("ALTER TABLE transactions ADD COLUMN dataSource TEXT DEFAULT 'file'")
            // 创建导入记录表
            db.execSQL(SQL_CREATE_IMPORT_RECORDS)
        }
        if (oldVersion < 5) {
            // 升级到版本5：添加 fileId 字段关联导入文件
            db.execSQL("ALTER TABLE transactions ADD COLUMN fileId INTEGER DEFAULT 0")
        }
        if (oldVersion < 6) {
            // 升级到版本6：添加卡片和分类表
            db.execSQL(SQL_CREATE_CARDS)
            db.execSQL(SQL_CREATE_CARD_CATEGORIES)
            db.execSQL(SQL_CREATE_CARD_MERCHANT_CATEGORIES)
        }
        if (oldVersion < 7) {
            // 升级到版本7：自动记账扩展字段（分类筛选/记账分类/备注图片/待核对状态/去重哈希）
            db.execSQL("ALTER TABLE transactions ADD COLUMN categoryTop TEXT DEFAULT ''")
            db.execSQL("ALTER TABLE transactions ADD COLUMN categorySub TEXT DEFAULT ''")
            db.execSQL("ALTER TABLE transactions ADD COLUMN images TEXT DEFAULT ''")
            db.execSQL("ALTER TABLE transactions ADD COLUMN status TEXT DEFAULT 'confirmed'")
            db.execSQL("ALTER TABLE transactions ADD COLUMN dedupHash TEXT DEFAULT ''")
            db.execSQL("CREATE INDEX IF NOT EXISTS idx_transactions_dedup ON transactions(dedupHash)")
            db.execSQL("CREATE INDEX IF NOT EXISTS idx_transactions_status ON transactions(status)")
        }
    }

    // 清空所有交易记录
    fun clearAllTransactions() {
        val db = writableDatabase
        db.delete("transactions", null, null)
        db.delete("import_records", null, null)
    }

    // 删除指定文件的分析数据
    fun deleteFileAnalysis(fileId: Long) {
        val db = writableDatabase
        db.delete("transactions", "fileId = ?", arrayOf(fileId.toString()))
        db.delete("import_records", "id = ?", arrayOf(fileId.toString()))
    }

    // 删除所有文件分析数据（只删除文件导入的数据，保留通知监听的数据）
    fun deleteAllFileAnalysis() {
        val db = writableDatabase
        db.delete("transactions", "dataSource = 'file'", null)
        db.delete("import_records", null, null)
    }

    // 获取指定文件的交易记录
    fun getTransactionsByFileId(fileId: Long): List<Transaction> {
        val transactions = mutableListOf<Transaction>()
        val db = readableDatabase
        val cursor = db.query("transactions", null, "fileId = ?", arrayOf(fileId.toString()), null, null, "timestamp DESC")
        with(cursor) {
            while (moveToNext()) {
                transactions.add(cursorToTransaction(this))
            }
        }
        cursor.close()
        return transactions
    }

    private fun cursorToTransaction(cursor: android.database.Cursor): Transaction {
        return Transaction(
            id = cursor.getLong(cursor.getColumnIndexOrThrow("id")),
            amount = cursor.getDouble(cursor.getColumnIndexOrThrow("amount")),
            merchant = cursor.getString(cursor.getColumnIndexOrThrow("merchant")),
            categoryId = if (cursor.isNull(cursor.getColumnIndexOrThrow("categoryId"))) null else cursor.getLong(cursor.getColumnIndexOrThrow("categoryId")),
            source = cursor.getString(cursor.getColumnIndexOrThrow("source")),
            timestamp = cursor.getLong(cursor.getColumnIndexOrThrow("timestamp")),
            note = cursor.getString(cursor.getColumnIndexOrThrow("note")),
            type = cursor.getString(cursor.getColumnIndexOrThrow("type")),
            transactionNo = try { cursor.getString(cursor.getColumnIndexOrThrow("transactionNo")) } catch (e: Exception) { null },
            dataSource = try { cursor.getString(cursor.getColumnIndexOrThrow("dataSource")) } catch (e: Exception) { "file" },
            fileId = try { cursor.getLong(cursor.getColumnIndexOrThrow("fileId")) } catch (e: Exception) { 0 },
            categoryTop = try { cursor.getString(cursor.getColumnIndexOrThrow("categoryTop")) } catch (e: Exception) { "" },
            categorySub = try { cursor.getString(cursor.getColumnIndexOrThrow("categorySub")) } catch (e: Exception) { "" },
            images = try { decodeImages(cursor.getString(cursor.getColumnIndexOrThrow("images"))) } catch (e: Exception) { emptyList() },
            status = try { cursor.getString(cursor.getColumnIndexOrThrow("status")) } catch (e: Exception) { "confirmed" },
            dedupHash = try { cursor.getString(cursor.getColumnIndexOrThrow("dedupHash")) } catch (e: Exception) { "" }
        )
    }

    // 待核对（pending）队列：用于 AI 核对弹窗「昨日 N 笔待核对」
    fun getPendingSince(sinceMs: Long): List<Transaction> {
        val transactions = mutableListOf<Transaction>()
        val db = readableDatabase
        val cursor = db.query(
            "transactions", null,
            "status = 'pending' AND timestamp >= ?",
            arrayOf(sinceMs.toString()), null, null, "timestamp DESC"
        )
        with(cursor) {
            while (moveToNext()) {
                transactions.add(cursorToTransaction(this))
            }
        }
        cursor.close()
        return transactions
    }

    fun countPendingSince(sinceMs: Long): Int {
        val db = readableDatabase
        val cursor = db.rawQuery(
            "SELECT COUNT(*) FROM transactions WHERE status = 'pending' AND timestamp >= ?",
            arrayOf(sinceMs.toString())
        )
        val count = if (cursor.moveToFirst()) cursor.getInt(0) else 0
        cursor.close()
        return count
    }

    /** 批量标记状态 */
    fun markStatus(ids: List<Long>, status: String) {
        if (ids.isEmpty()) return
        val db = writableDatabase
        db.beginTransaction()
        try {
            ids.chunked(500).forEach { chunk ->
                val placeholders = chunk.joinToString(",") { "?" }
                db.execSQL(
                    "UPDATE transactions SET status = ? WHERE id IN ($placeholders)",
                    arrayOf(status) + chunk.map { it.toString() }
                )
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    fun markAllConfirmedSince(sinceMs: Long) {
        val db = writableDatabase
        db.execSQL(
            "UPDATE transactions SET status = 'confirmed' WHERE status = 'pending' AND timestamp >= ?",
            arrayOf(sinceMs.toString())
        )
    }

    /** 按去重哈希查询是否已存在 */
    fun existsByHash(dedupHash: String): Boolean {
        if (dedupHash.isEmpty()) return false
        val db = readableDatabase
        val cursor = db.rawQuery(
            "SELECT COUNT(*) FROM transactions WHERE dedupHash = ?",
            arrayOf(dedupHash)
        )
        val exists = cursor.moveToFirst() && cursor.getInt(0) > 0
        cursor.close()
        return exists
    }

    // 导入记录管理
    fun addImportRecord(fileName: String, startTime: Long, endTime: Long, count: Int): Long {
        val db = writableDatabase
        val values = ContentValues().apply {
            put("fileName", fileName)
            put("startTime", startTime)
            put("endTime", endTime)
            put("count", count)
            put("importTime", System.currentTimeMillis())
        }
        return db.insert("import_records", null, values)
    }

    fun getAllImportRecords(): List<ImportRecord> {
        val records = mutableListOf<ImportRecord>()
        val db = readableDatabase
        val cursor = db.query("import_records", null, null, null, null, null, "importTime DESC")
        with(cursor) {
            while (moveToNext()) {
                records.add(ImportRecord(
                    id = getLong(getColumnIndexOrThrow("id")),
                    fileName = getString(getColumnIndexOrThrow("fileName")),
                    startTime = getLong(getColumnIndexOrThrow("startTime")),
                    endTime = getLong(getColumnIndexOrThrow("endTime")),
                    count = getInt(getColumnIndexOrThrow("count")),
                    importTime = getLong(getColumnIndexOrThrow("importTime"))
                ))
            }
        }
        cursor.close()
        return records
    }

    // 检查时间范围重叠
    fun checkTimeRangeOverlap(startTime: Long, endTime: Long): List<ImportRecord> {
        val records = mutableListOf<ImportRecord>()
        val db = readableDatabase
        val cursor = db.rawQuery(
            "SELECT * FROM import_records WHERE startTime <= ? AND endTime >= ?",
            arrayOf(endTime.toString(), startTime.toString())
        )
        with(cursor) {
            while (moveToNext()) {
                records.add(ImportRecord(
                    id = getLong(getColumnIndexOrThrow("id")),
                    fileName = getString(getColumnIndexOrThrow("fileName")),
                    startTime = getLong(getColumnIndexOrThrow("startTime")),
                    endTime = getLong(getColumnIndexOrThrow("endTime")),
                    count = getInt(getColumnIndexOrThrow("count")),
                    importTime = getLong(getColumnIndexOrThrow("importTime"))
                ))
            }
        }
        cursor.close()
        return records
    }

    // 交易记录操作
    fun insertTransaction(transaction: Transaction): Long {
        // 去重：按哈希跳过重复（通知监听等单条插入路径）
        if (transaction.dedupHash.isNotEmpty() && existsByHash(transaction.dedupHash)) {
            return -1
        }
        val db = writableDatabase
        val values = ContentValues().apply {
            put("amount", transaction.amount)
            put("merchant", transaction.merchant)
            put("categoryId", transaction.categoryId)
            put("source", transaction.source)
            put("timestamp", transaction.timestamp)
            put("note", transaction.note)
            put("type", transaction.type)
            put("transactionNo", transaction.transactionNo)
            put("dataSource", transaction.dataSource)
            put("fileId", transaction.fileId)
            put("categoryTop", transaction.categoryTop)
            put("categorySub", transaction.categorySub)
            put("images", encodeImages(transaction.images))
            put("status", transaction.status)
            put("dedupHash", transaction.dedupHash)
        }
        return db.insert("transactions", null, values)
    }

    fun insertTransactions(transactions: List<Transaction>, fileId: Long = 0): Int {
        val db = writableDatabase
        var insertedCount = 0
        db.beginTransaction()
        try {
            for (transaction in transactions) {
                // 去重检查0：去重哈希 md5(日期|金额|商户)（Excel 导入等）
                if (transaction.dedupHash.isNotEmpty() && existsByHash(transaction.dedupHash)) {
                    continue
                }

                // 去重检查1：交易单号（文件导入）
                if (!transaction.transactionNo.isNullOrEmpty()) {
                    val cursor = db.rawQuery(
                        "SELECT COUNT(*) FROM transactions WHERE transactionNo = ?",
                        arrayOf(transaction.transactionNo)
                    )
                    val exists = cursor.moveToFirst() && cursor.getInt(0) > 0
                    cursor.close()
                    if (exists) continue
                }

                // 去重检查2：时间戳(分钟级) + 金额 + 收款方 —— 仅对通知监听生效
                // （文件导入有精确时间戳+秒级哈希去重；手动记账不受窗口限制）
                if (transaction.transactionNo.isNullOrEmpty() && transaction.dataSource == "notification") {
                    val minuteTimestamp = transaction.timestamp / 60000 * 60000 // 分钟级
                    val cursor = db.rawQuery(
                        "SELECT COUNT(*) FROM transactions WHERE timestamp BETWEEN ? AND ? AND amount = ? AND merchant = ?",
                        arrayOf(
                            (minuteTimestamp - 60000).toString(),
                            (minuteTimestamp + 60000).toString(),
                            transaction.amount.toString(),
                            transaction.merchant
                        )
                    )
                    val exists = cursor.moveToFirst() && cursor.getInt(0) > 0
                    cursor.close()
                    if (exists) continue
                }

                val values = ContentValues().apply {
                    put("amount", transaction.amount)
                    put("merchant", transaction.merchant)
                    put("categoryId", transaction.categoryId)
                    put("source", transaction.source)
                    put("timestamp", transaction.timestamp)
                    put("note", transaction.note)
                    put("type", transaction.type)
                    put("transactionNo", transaction.transactionNo)
                    put("dataSource", transaction.dataSource)
                    put("fileId", fileId)
                    put("categoryTop", transaction.categoryTop)
                    put("categorySub", transaction.categorySub)
                    put("images", encodeImages(transaction.images))
                    put("status", transaction.status)
                    put("dedupHash", transaction.dedupHash)
                }
                db.insert("transactions", null, values)
                insertedCount++
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        return insertedCount
    }

    fun updateTransactionAmount(id: Long, amount: Double) {
        val db = writableDatabase
        val values = ContentValues().apply {
            put("amount", amount)
        }
        db.update("transactions", values, "id = ?", arrayOf(id.toString()))
    }

    fun getPendingTransactions(): List<Transaction> {
        val transactions = mutableListOf<Transaction>()
        val db = readableDatabase
        val cursor = db.query("transactions", null, "amount <= 0", null, null, null, "timestamp DESC")
        with(cursor) {
            while (moveToNext()) {
                transactions.add(cursorToTransaction(this))
            }
        }
        cursor.close()
        return transactions
    }

    fun getPendingTransactionsByCard(card: Card): List<Transaction> {
        val transactions = mutableListOf<Transaction>()
        val db = readableDatabase
        val baseCondition = "amount <= 0"
        val selection = if (card.isGlobal) {
            baseCondition
        } else if (card.endTime > 0) {
            "$baseCondition AND timestamp >= ? AND timestamp <= ?"
        } else {
            "$baseCondition AND timestamp >= ?"
        }
        val selectionArgs = if (card.isGlobal) {
            null
        } else if (card.endTime > 0) {
            arrayOf(card.startTime.toString(), card.endTime.toString())
        } else {
            arrayOf(card.startTime.toString())
        }
        val cursor = db.query("transactions", null, selection, selectionArgs, null, null, "timestamp DESC")
        with(cursor) {
            while (moveToNext()) {
                transactions.add(cursorToTransaction(this))
            }
        }
        cursor.close()
        return transactions
    }

    fun deleteTransaction(id: Long) {
        val db = writableDatabase
        db.delete("transactions", "id = ?", arrayOf(id.toString()))
    }

    fun updateTransaction(id: Long, amount: Double?, merchant: String?) {
        val db = writableDatabase
        val values = ContentValues()
        if (amount != null) values.put("amount", amount)
        if (merchant != null) values.put("merchant", merchant)
        if (values.size() > 0) {
            db.update("transactions", values, "id = ?", arrayOf(id.toString()))
        }
    }

    /** 按商户重命名（该商户的所有交易一并改名） */
    fun updateMerchantName(oldName: String, newName: String) {
        val values = ContentValues().apply { put("merchant", newName) }
        writableDatabase.update("transactions", values, "merchant = ?", arrayOf(oldName))
    }

    /** 按商户+收支类型批量改金额 */
    fun updateMerchantAmount(merchant: String, amount: Double, type: String) {
        val values = ContentValues().apply { put("amount", amount) }
        writableDatabase.update(
            "transactions", values,
            "merchant = ? AND type = ?", arrayOf(merchant, type)
        )
    }

    /** 按商户删除该商户的所有交易 */
    fun deleteByMerchant(merchant: String) {
        writableDatabase.delete("transactions", "merchant = ?", arrayOf(merchant))
    }

    fun getTransactionsByMerchant(merchant: String): List<Transaction> {
        val transactions = mutableListOf<Transaction>()
        val db = readableDatabase
        val cursor = db.query("transactions", null, "merchant = ?", arrayOf(merchant), null, null, "timestamp DESC")
        with(cursor) {
            while (moveToNext()) {
                transactions.add(cursorToTransaction(this))
            }
        }
        cursor.close()
        return transactions
    }

    fun getAllTransactions(): List<Transaction> {
        val transactions = mutableListOf<Transaction>()
        val db = readableDatabase
        val cursor = db.query("transactions", null, null, null, null, null, "timestamp DESC")

        with(cursor) {
            while (moveToNext()) {
                transactions.add(cursorToTransaction(this))
            }
        }
        cursor.close()
        return transactions
    }

    // 按时间范围查询交易记录
    fun getTransactionsByTimeRange(startTime: Long, endTime: Long): List<Transaction> {
        val transactions = mutableListOf<Transaction>()
        val db = readableDatabase
        val cursor = db.query(
            "transactions",
            null,
            "timestamp BETWEEN ? AND ?",
            arrayOf(startTime.toString(), endTime.toString()),
            null,
            null,
            "timestamp DESC"
        )

        with(cursor) {
            while (moveToNext()) {
                transactions.add(cursorToTransaction(this))
            }
        }
        cursor.close()
        return transactions
    }

    fun getMerchantTotals(): List<MerchantTotal> {
        val totals = mutableListOf<MerchantTotal>()
        val db = readableDatabase
        val cursor = db.rawQuery(
            "SELECT merchant, SUM(amount) as total, type FROM transactions GROUP BY merchant, type ORDER BY total DESC",
            null
        )

        with(cursor) {
            while (moveToNext()) {
                val total = MerchantTotal(
                    merchant = getString(getColumnIndexOrThrow("merchant")),
                    total = getDouble(getColumnIndexOrThrow("total")),
                    type = getString(getColumnIndexOrThrow("type"))
                )
                totals.add(total)
            }
        }
        cursor.close()
        return totals
    }

    fun getTotalAmount(): Pair<Double, Double> {
        val db = readableDatabase
        val cursor = db.rawQuery(
            "SELECT SUM(CASE WHEN type = 'expense' THEN amount ELSE 0 END) as totalExpense, " +
            "SUM(CASE WHEN type = 'income' THEN amount ELSE 0 END) as totalIncome FROM transactions",
            null
        )
        var totalExpense = 0.0
        var totalIncome = 0.0
        if (cursor.moveToFirst()) {
            totalExpense = cursor.getDouble(0)
            totalIncome = cursor.getDouble(1)
        }
        cursor.close()
        return Pair(totalExpense, totalIncome)
    }

    // 分类操作
    fun insertCategory(category: Category): Long {
        val db = writableDatabase
        val values = ContentValues().apply {
            put("name", category.name)
            put("color", category.color)
        }
        return db.insert("categories", null, values)
    }

    fun getAllCategories(): List<Category> {
        val categories = mutableListOf<Category>()
        val db = readableDatabase
        val cursor = db.query("categories", null, null, null, null, null, "name ASC")

        with(cursor) {
            while (moveToNext()) {
                val category = Category(
                    id = getLong(getColumnIndexOrThrow("id")),
                    name = getString(getColumnIndexOrThrow("name")),
                    color = getString(getColumnIndexOrThrow("color"))
                )
                categories.add(category)
            }
        }
        cursor.close()
        return categories
    }

    fun deleteCategory(category: Category) {
        val db = writableDatabase
        db.delete("categories", "id = ?", arrayOf(category.id.toString()))
    }

    // 收款方分类映射
    fun setMerchantCategory(merchant: String, categoryId: Long) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            db.delete("merchant_categories", "merchant = ?", arrayOf(merchant))
            val values = ContentValues().apply {
                put("merchant", merchant)
                put("categoryId", categoryId)
            }
            db.insert("merchant_categories", null, values)
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    fun getMerchantCategory(merchant: String): MerchantCategory? {
        val db = readableDatabase
        val cursor = db.query(
            "merchant_categories",
            null,
            "merchant = ?",
            arrayOf(merchant),
            null,
            null,
            null
        )

        var merchantCategory: MerchantCategory? = null
        if (cursor.moveToFirst()) {
            merchantCategory = MerchantCategory(
                merchant = cursor.getString(cursor.getColumnIndexOrThrow("merchant")),
                categoryId = cursor.getLong(cursor.getColumnIndexOrThrow("categoryId"))
            )
        }
        cursor.close()
        return merchantCategory
    }

    // 卡片操作
    fun insertCard(card: Card): Long {
        val db = writableDatabase
        val values = ContentValues().apply {
            put("name", card.name)
            put("startTime", card.startTime)
            put("endTime", card.endTime)
            put("isGlobal", if (card.isGlobal) 1 else 0)
            put("createdAt", card.createdAt)
        }
        return db.insert("cards", null, values)
    }

    fun getAllCards(): List<Card> {
        val cards = mutableListOf<Card>()
        val db = readableDatabase
        val cursor = db.query("cards", null, null, null, null, null, "createdAt ASC")
        with(cursor) {
            while (moveToNext()) {
                cards.add(Card(
                    id = getLong(getColumnIndexOrThrow("id")),
                    name = getString(getColumnIndexOrThrow("name")),
                    startTime = getLong(getColumnIndexOrThrow("startTime")),
                    endTime = getLong(getColumnIndexOrThrow("endTime")),
                    isGlobal = getInt(getColumnIndexOrThrow("isGlobal")) == 1,
                    createdAt = getLong(getColumnIndexOrThrow("createdAt"))
                ))
            }
        }
        cursor.close()
        return cards
    }

    fun getCard(cardId: Long): Card? {
        val db = readableDatabase
        val cursor = db.query("cards", null, "id = ?", arrayOf(cardId.toString()), null, null, null)
        var card: Card? = null
        if (cursor.moveToFirst()) {
            card = Card(
                id = cursor.getLong(cursor.getColumnIndexOrThrow("id")),
                name = cursor.getString(cursor.getColumnIndexOrThrow("name")),
                startTime = cursor.getLong(cursor.getColumnIndexOrThrow("startTime")),
                endTime = cursor.getLong(cursor.getColumnIndexOrThrow("endTime")),
                isGlobal = cursor.getInt(cursor.getColumnIndexOrThrow("isGlobal")) == 1,
                createdAt = cursor.getLong(cursor.getColumnIndexOrThrow("createdAt"))
            )
        }
        cursor.close()
        return card
    }

    fun updateCard(card: Card) {
        val db = writableDatabase
        val values = ContentValues().apply {
            put("name", card.name)
            put("startTime", card.startTime)
            put("endTime", card.endTime)
        }
        db.update("cards", values, "id = ?", arrayOf(card.id.toString()))
    }

    fun deleteCard(cardId: Long) {
        val db = writableDatabase
        db.delete("card_merchant_categories", "cardId = ?", arrayOf(cardId.toString()))
        db.delete("card_categories", "cardId = ?", arrayOf(cardId.toString()))
        db.delete("cards", "id = ?", arrayOf(cardId.toString()))
    }

    // 卡片分类操作
    fun insertCardCategory(cardCategory: CardCategory): Long {
        val db = writableDatabase
        val values = ContentValues().apply {
            put("cardId", cardCategory.cardId)
            put("name", cardCategory.name)
            put("color", cardCategory.color)
        }
        return db.insert("card_categories", null, values)
    }

    fun getCardCategories(cardId: Long): List<CardCategory> {
        val categories = mutableListOf<CardCategory>()
        val db = readableDatabase
        val cursor = db.query("card_categories", null, "cardId = ?", arrayOf(cardId.toString()), null, null, "name ASC")
        with(cursor) {
            while (moveToNext()) {
                categories.add(CardCategory(
                    id = getLong(getColumnIndexOrThrow("id")),
                    cardId = getLong(getColumnIndexOrThrow("cardId")),
                    name = getString(getColumnIndexOrThrow("name")),
                    color = getString(getColumnIndexOrThrow("color"))
                ))
            }
        }
        cursor.close()
        return categories
    }

    fun deleteCardCategory(categoryId: Long) {
        val db = writableDatabase
        db.delete("card_merchant_categories", "categoryId = ?", arrayOf(categoryId.toString()))
        db.delete("card_categories", "id = ?", arrayOf(categoryId.toString()))
    }

    // 卡片收款方分类映射
    fun setCardMerchantCategory(cardId: Long, merchant: String, categoryId: Long) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            db.delete("card_merchant_categories", "cardId = ? AND merchant = ?", arrayOf(cardId.toString(), merchant))
            val values = ContentValues().apply {
                put("cardId", cardId)
                put("merchant", merchant)
                put("categoryId", categoryId)
            }
            db.insert("card_merchant_categories", null, values)
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    fun getCardMerchantCategory(cardId: Long, merchant: String): Long? {
        val db = readableDatabase
        val cursor = db.query(
            "card_merchant_categories",
            arrayOf("categoryId"),
            "cardId = ? AND merchant = ?",
            arrayOf(cardId.toString(), merchant),
            null, null, null
        )
        var categoryId: Long? = null
        if (cursor.moveToFirst()) {
            categoryId = cursor.getLong(cursor.getColumnIndexOrThrow("categoryId"))
        }
        cursor.close()
        return categoryId
    }

    fun getCardMerchantsByCategory(cardId: Long, categoryId: Long): List<String> {
        val merchants = mutableListOf<String>()
        val db = readableDatabase
        val cursor = db.query(
            "card_merchant_categories",
            arrayOf("merchant"),
            "cardId = ? AND categoryId = ?",
            arrayOf(cardId.toString(), categoryId.toString()),
            null, null, "merchant ASC"
        )
        with(cursor) {
            while (moveToNext()) {
                merchants.add(getString(getColumnIndexOrThrow("merchant")))
            }
        }
        cursor.close()
        return merchants
    }

    // 获取卡片的交易记录（按时间范围）
    fun getTransactionsByCard(card: Card): List<Transaction> {
        val transactions = mutableListOf<Transaction>()
        val db = readableDatabase
        val selection = if (card.isGlobal) {
            "timestamp >= ?"
        } else if (card.endTime > 0) {
            "timestamp >= ? AND timestamp <= ?"
        } else {
            "timestamp >= ?"
        }
        val selectionArgs = if (card.isGlobal) {
            arrayOf(card.startTime.toString())
        } else if (card.endTime > 0) {
            arrayOf(card.startTime.toString(), card.endTime.toString())
        } else {
            arrayOf(card.startTime.toString())
        }
        val cursor = db.query("transactions", null, selection, selectionArgs, null, null, "timestamp DESC")
        with(cursor) {
            while (moveToNext()) {
                transactions.add(cursorToTransaction(this))
            }
        }
        cursor.close()
        return transactions
    }

    companion object {
        const val DATABASE_NAME = "expense_database"
        const val DATABASE_VERSION = 7

        /** 图片列表 <-> JSON 文本 */
        fun encodeImages(images: List<String>): String {
            if (images.isEmpty()) return ""
            val arr = org.json.JSONArray()
            images.forEach { arr.put(it) }
            return arr.toString()
        }

        fun decodeImages(text: String?): List<String> {
            if (text.isNullOrBlank()) return emptyList()
            return try {
                val arr = org.json.JSONArray(text)
                (0 until arr.length()).map { arr.getString(it) }
            } catch (e: Exception) {
                emptyList()
            }
        }

        private const val SQL_CREATE_TRANSACTIONS = """
            CREATE TABLE transactions (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                amount REAL NOT NULL,
                merchant TEXT NOT NULL,
                categoryId INTEGER,
                source TEXT NOT NULL,
                timestamp INTEGER NOT NULL,
                note TEXT DEFAULT '',
                type TEXT DEFAULT 'expense',
                transactionNo TEXT,
                dataSource TEXT DEFAULT 'file',
                fileId INTEGER DEFAULT 0,
                categoryTop TEXT DEFAULT '',
                categorySub TEXT DEFAULT '',
                images TEXT DEFAULT '',
                status TEXT DEFAULT 'confirmed',
                dedupHash TEXT DEFAULT ''
            )
        """

        private const val SQL_CREATE_CATEGORIES = """
            CREATE TABLE categories (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                name TEXT NOT NULL,
                color TEXT NOT NULL
            )
        """

        private const val SQL_CREATE_MERCHANT_CATEGORIES = """
            CREATE TABLE merchant_categories (
                merchant TEXT PRIMARY KEY,
                categoryId INTEGER NOT NULL
            )
        """

        private const val SQL_CREATE_IMPORT_RECORDS = """
            CREATE TABLE import_records (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                fileName TEXT NOT NULL,
                startTime INTEGER NOT NULL,
                endTime INTEGER NOT NULL,
                count INTEGER NOT NULL,
                importTime INTEGER NOT NULL
            )
        """

        private const val SQL_CREATE_CARDS = """
            CREATE TABLE cards (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                name TEXT NOT NULL,
                startTime INTEGER NOT NULL,
                endTime INTEGER DEFAULT 0,
                isGlobal INTEGER DEFAULT 0,
                createdAt INTEGER NOT NULL
            )
        """

        private const val SQL_CREATE_CARD_CATEGORIES = """
            CREATE TABLE card_categories (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                cardId INTEGER NOT NULL,
                name TEXT NOT NULL,
                color TEXT DEFAULT '#E57373',
                FOREIGN KEY (cardId) REFERENCES cards(id) ON DELETE CASCADE
            )
        """

        private const val SQL_CREATE_CARD_MERCHANT_CATEGORIES = """
            CREATE TABLE card_merchant_categories (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                cardId INTEGER NOT NULL,
                merchant TEXT NOT NULL,
                categoryId INTEGER NOT NULL,
                FOREIGN KEY (cardId) REFERENCES cards(id) ON DELETE CASCADE,
                FOREIGN KEY (categoryId) REFERENCES card_categories(id) ON DELETE CASCADE
            )
        """
    }
}

data class MerchantTotal(
    val merchant: String,
    val total: Double,
    val type: String = "expense",
    val lastTimestamp: Long = 0L
)

data class ImportRecord(
    val id: Long,
    val fileName: String,
    val startTime: Long,
    val endTime: Long,
    val count: Int,
    val importTime: Long
)
