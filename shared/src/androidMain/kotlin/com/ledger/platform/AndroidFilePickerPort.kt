package com.ledger.platform

import com.ledger.domain.port.FileOperationError
import com.ledger.domain.port.FilePickerPort
import com.ledger.domain.port.PickedFile

/**
 * [FilePickerPort] 的 Android 实现（T25 的文件侧）。
 *
 * ### 为什么用「桥」而不是直接 startActivity
 * SAF（`ACTION_CREATE_DOCUMENT` / `ACTION_OPEN_DOCUMENT`）必须由 **Activity** 发起，
 * 并且只能通过 `ActivityResultLauncher` 收结果；而端口是 Koin 单例、拿不到 Activity。
 * 因此这里把「展示选择器」这件事委托给 UI 层注册的 [Bridge]：
 *  - Compose 侧用 `rememberLauncherForActivityResult` 实现 Bridge 并在进入组合时注册；
 *  - 端口负责保存回调、并在结果回来时把字节交回领域层。
 *
 * 回调风格是刻意的（见 `FilePickerPort` 的注释）：iOS 侧由 Swift 实现，不能用 `suspend`。
 */
class AndroidFilePickerPort : FilePickerPort {

    /** 由 UI 层（Compose）实现；未注册时视为「无法访问文件系统」。 */
    interface Bridge {
        fun launchSave(mimeType: String, suggestedName: String, data: ByteArray)

        fun launchOpen(mimeTypes: List<String>)
    }

    @Volatile
    var bridge: Bridge? = null

    private var pendingSave: ((PickedFile?, FileOperationError?) -> Unit)? = null
    private var pendingOpen: ((PickedFile?, FileOperationError?) -> Unit)? = null

    override fun saveFile(
        suggestedName: String,
        mimeType: String,
        data: ByteArray,
        onResult: (PickedFile?, FileOperationError?) -> Unit,
    ) {
        val b = bridge
        if (b == null) {
            onResult(null, FileOperationError.SAVE_FAILED)
            return
        }
        pendingSave = onResult
        b.launchSave(mimeType, suggestedName, data)
    }

    override fun openFile(
        mimeTypes: List<String>,
        onResult: (PickedFile?, FileOperationError?) -> Unit,
    ) {
        val b = bridge
        if (b == null) {
            onResult(null, FileOperationError.READ_FAILED)
            return
        }
        pendingOpen = onResult
        b.launchOpen(mimeTypes)
    }

    // ------------------------------------------------------- Bridge 回传入口

    /** 保存完成。[displayName] / [bytes] 任一为 null 表示失败或用户取消。 */
    fun deliverSave(displayName: String?, bytes: ByteArray?, error: FileOperationError?) {
        val callback = pendingSave ?: return
        pendingSave = null
        callback(
            bytes?.let { PickedFile(displayName ?: "ledger-export", it, it.size) },
            error,
        )
    }

    /** 读取完成。 */
    fun deliverOpen(displayName: String?, bytes: ByteArray?, error: FileOperationError?) {
        val callback = pendingOpen ?: return
        pendingOpen = null
        callback(
            bytes?.let { PickedFile(displayName ?: "ledger-import", it, it.size) },
            error,
        )
    }
}
