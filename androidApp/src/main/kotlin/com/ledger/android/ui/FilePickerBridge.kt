package com.ledger.android.ui

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import com.ledger.android.di.inject
import com.ledger.domain.port.FileOperationError
import com.ledger.platform.AndroidFilePickerPort
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 把 [AndroidFilePickerPort] 接上 Compose 的 `ActivityResultLauncher`（P13 数据页）。
 *
 * 领域层不感知 Activity；端口在 Android 侧的 `Bridge` 由这里实现并在进入组合时注册
 * （`DisposableEffect`），离开组合时注销，避免端口持有一个已销毁 Activity 的 launcher。
 *
 * 必须在 `NavHost` **之外**调用一次即可——它注册的是全局单例的桥，
 * 与当前停留在哪个页面无关（用户在数据页点「导出」时桥已经就绪）。
 */
@Composable
fun FilePickerBridgeEffect() {
    val context = LocalContext.current
    val port = remember { inject<AndroidFilePickerPort>() }
    val scope = rememberCoroutineScope()

    // 保存：先把字节暂存，等用户在系统选择器里确认位置后再写
    var pendingBytes by remember { mutableStateOf<ByteArray?>(null) }

    val saveLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("*/*"),
    ) { uri: Uri? ->
        val bytes = pendingBytes
        pendingBytes = null
        if (uri == null || bytes == null) {
            // null = 用户取消（不是错误）
            port.deliverSave(displayName = null, bytes = null, error = null)
            return@rememberLauncherForActivityResult
        }
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    context.contentResolver.openOutputStream(uri)?.use { it.write(bytes) }
                        ?: error("openOutputStream 返回 null")
                }
            }
            if (result.isSuccess) {
                port.deliverSave(displayName = displayNameOf(context, uri), bytes = bytes, error = null)
            } else {
                port.deliverSave(null, null, FileOperationError.SAVE_FAILED)
            }
        }
    }

    val openLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
    ) { uri: Uri? ->
        if (uri == null) {
            port.deliverOpen(displayName = null, bytes = null, error = null)
            return@rememberLauncherForActivityResult
        }
        scope.launch {
            // 读文件是 IO，切到 IO 线程；backup 文件通常只有几百 KB，一次性读入没问题。
            val read = withContext(Dispatchers.IO) {
                runCatching { context.contentResolver.openInputStream(uri)?.use { it.readBytes() } }
            }
            val bytes = read.getOrNull()
            if (bytes != null) {
                port.deliverOpen(displayNameOf(context, uri), bytes, null)
            } else {
                port.deliverOpen(null, null, FileOperationError.READ_FAILED)
            }
        }
    }

    DisposableEffect(port, saveLauncher, openLauncher) {
        port.bridge = object : AndroidFilePickerPort.Bridge {
            override fun launchSave(mimeType: String, suggestedName: String, data: ByteArray) {
                pendingBytes = data
                saveLauncher.launch(suggestedName)
            }

            override fun launchOpen(mimeTypes: List<String>) {
                // OpenDocument 需要至少一个 MIME；端口传入的列表已含 `*/*` 兜底。
                openLauncher.launch(mimeTypes.toTypedArray())
            }
        }
        onDispose { port.bridge = null }
    }
}

/** 取 DocumentProvider 暴露的文件名；查不到就退回 URI 的最后一段。 */
private fun displayNameOf(context: Context, uri: Uri): String {
    runCatching {
        context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (index >= 0 && cursor.moveToFirst()) {
                val name = cursor.getString(index)
                if (!name.isNullOrBlank()) return name
            }
        }
    }
    return uri.lastPathSegment ?: "ledger-file"
}
