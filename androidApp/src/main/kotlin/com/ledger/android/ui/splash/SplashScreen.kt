package com.ledger.android.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.ledger.android.ui.components.ErrorState
import com.ledger.android.ui.icons.LedgerIcons

/**
 * P01 启动页（PRD §4.2）。
 *
 * 它不只是「展示 Logo」——真正的职责是**等启动引导完成**：
 * 首次冷启动要打开 SQLCipher（密钥派生 + 解锁）并写入预置分类/账户，
 * 完成后才切到首页，避免首页查到一个还没建表的库。
 *
 * 引导失败时展示具体原因（而不是白屏或崩溃），并提供重试。
 */
@Composable
fun SplashScreen(
    modifier: Modifier = Modifier,
    failedReason: String? = null,
    onRetry: (() -> Unit)? = null,
) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
        contentAlignment = Alignment.Center,
    ) {
        if (failedReason != null) {
            ErrorState(
                message = "启动失败：$failedReason",
                onRetry = onRetry,
            )
            return@Box
        }

        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Box(
                modifier = Modifier
                    .size(88.dp)
                    .clip(RoundedCornerShape(24.dp))
                    .background(MaterialTheme.colorScheme.primary),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = LedgerIcons.ListIcon,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onPrimary,
                    modifier = Modifier.size(44.dp),
                )
            }
            Spacer(Modifier.height(20.dp))
            Text(
                text = "记账",
                style = MaterialTheme.typography.headlineMedium,
                color = MaterialTheme.colorScheme.onBackground,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                text = "3 步记一笔，账本自己算",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(horizontal = 32.dp),
            )
            Spacer(Modifier.height(32.dp))
            CircularProgressIndicator(strokeWidth = 3.dp)
            Spacer(Modifier.height(10.dp))
            Text(
                text = "正在解锁本地账本…",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
