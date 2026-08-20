package com.friday.wimm.ui.home

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.text.font.FontWeight

/**
 * AI 账单核对弹窗（参考截图 2）：
 * 启动/每日首次弹出「昨日 N 笔账单待核对」
 * 主按钮：「很准确，点赞」+「有漏记，去补充」
 */
@Composable
fun AiCheckDialog(
    pendingCount: Int,
    onThumbsUp: () -> Unit,
    onAddMissing: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Text(text = "🤖", fontSize = androidx.compose.ui.unit.TextUnit.Unspecified) },
        title = {
            Text(
                text = "昨日 $pendingCount 笔账单待核对",
                fontWeight = FontWeight.Bold
            )
        },
        text = {
            Text("AI 已从通知栏自动识别到 $pendingCount 笔账单，请确认是否准确。确认后入库；如有漏记，可去「记一笔」补充。")
        },
        confirmButton = {
            Button(onClick = onThumbsUp) {
                Text("很准确，点赞 👍")
            }
        },
        dismissButton = {
            OutlinedButton(onClick = onAddMissing) {
                Text("有漏记，去补充")
            }
        }
    )
}
