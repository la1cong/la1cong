package com.friday.wimm.ui.add

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import com.friday.wimm.MyApplication
import com.friday.wimm.data.model.Transaction
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 默认一级分类（来自真实账单数据 843 条：17 个一级分类） */
val DEFAULT_CATEGORIES = listOf(
    "餐饮", "购物", "转账", "消费", "娱乐", "收转账", "交通",
    "生活日用", "教育", "通讯", "其他", "收红包", "红包",
    "旅行", "美容", "还款", "医疗"
)

/** 记一笔（中央大加号 Tab）：金额键盘 + 收支切换 + 宫格分类 + 备注 + 再记一笔 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AddTransactionScreen(onSubPageChanged: (Boolean) -> Unit = {}) {
    LaunchedEffect(Unit) { onSubPageChanged(false) }

    val context = LocalContext.current
    val application = context.applicationContext as MyApplication
    val scope = rememberCoroutineScope()

    var amountText by remember { mutableStateOf("") }
    var type by remember { mutableStateOf("expense") }
    var selectedCategory by remember { mutableStateOf("餐饮") }
    var merchant by remember { mutableStateOf("") }
    var note by remember { mutableStateOf("") }
    var savedTip by remember { mutableStateOf("") }

    fun saveAndClear(clear: Boolean) {
        val amount = amountText.toDoubleOrNull()
        if (amount == null || amount <= 0) {
            savedTip = "请输入有效金额"
            return
        }
        scope.launch {
            val id = withContext(Dispatchers.IO) {
                application.transactionRepository.insert(
                    Transaction(
                        amount = amount,
                        merchant = merchant.ifBlank { "手动记账" },
                        categoryId = null,
                        source = "manual",
                        timestamp = System.currentTimeMillis(),
                        note = note,
                        type = type,
                        transactionNo = null,
                        dataSource = "manual",
                        categoryTop = selectedCategory,
                        categorySub = "",
                        status = "confirmed"
                    )
                )
            }
            if (id > 0) {
                savedTip = if (clear) "已保存，可继续记账" else "已保存"
                if (clear) {
                    amountText = ""
                    merchant = ""
                    note = ""
                }
            } else {
                savedTip = "保存失败（可能重复）"
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("记一笔", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)

        // 金额
        OutlinedTextField(
            value = amountText,
            onValueChange = { amountText = it },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("金额") },
            prefix = { Text("¥") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            textStyle = MaterialTheme.typography.headlineMedium
        )

        // 收支切换
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(
                selected = type == "expense",
                onClick = { type = "expense" },
                label = { Text("支出") }
            )
            FilterChip(
                selected = type == "income",
                onClick = { type = "income" },
                label = { Text("收入") }
            )
        }

        // 分类宫格（FlowRow：可滚动 Column 内不允许嵌套滚动容器，用自动换行布局替代网格）
        Text("分类", style = MaterialTheme.typography.titleSmall)
        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            DEFAULT_CATEGORIES.forEach { category ->
                FilterChip(
                    selected = selectedCategory == category,
                    onClick = { selectedCategory = category },
                    label = { Text(category, fontSize = 13.sp, maxLines = 1) }
                )
            }
        }

        // 商户/备注
        OutlinedTextField(
            value = merchant,
            onValueChange = { merchant = it },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("商户/对方") },
            singleLine = true
        )
        OutlinedTextField(
            value = note,
            onValueChange = { note = it },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("备注") }
        )

        // 保存 + 再记一笔
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(onClick = { saveAndClear(false) }, modifier = Modifier.weight(1f)) {
                Text("保存")
            }
            OutlinedButton(onClick = { saveAndClear(true) }, modifier = Modifier.weight(1f)) {
                Text("再记一笔")
            }
        }

        if (savedTip.isNotEmpty()) {
            Text(savedTip, color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodySmall)
        }
    }
}
