package com.ledger.android.util

import java.util.UUID

/**
 * ID 生成。
 *
 * PRD §7.7 / D4：`transaction_id` 由**客户端生成 UUID**，作为幂等键。
 * 领域层刻意不依赖平台 UUID API（见 `ManageAccountUseCase.reconcile` 的注释），
 * 因此生成动作落在 Android/iOS 两侧的 UI 层。这里就是 Android 侧的唯一入口。
 */
object Ids {

    fun transaction(): String = UUID.randomUUID().toString()

    fun account(): String = "acc-${UUID.randomUUID()}"

    fun category(): String = "cat-${UUID.randomUUID()}"

    fun budget(): String = "bud-${UUID.randomUUID()}"

    fun reconciliation(): String = "rec-${UUID.randomUUID()}"
}
