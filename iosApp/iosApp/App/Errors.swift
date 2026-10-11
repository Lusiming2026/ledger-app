import Foundation
import Shared

/// Kotlin 异常 → 用户可读文案。
///
/// 领域层的 `DomainError` 都自带中文 `message`（见 `domain/error/Errors.kt`），
/// 直接展示即可，UI 层不需要再维护一份 code → 文案的映射表。
///
/// K/N 抛出的异常在 Swift 里被桥接成 `KotlinThrowable`（它实现了 `Error`），
/// 用 `error.localizedDescription` 也能拿到文案，但那是经 NSError 桥接的路径，
/// 在 `message` 为 null 时会回落成 `"The operation couldn't be completed..."` 这种
/// 对用户毫无意义的英文。这里显式取 `message`，取不到才回落。
func ledgerMessage(_ error: Error) -> String {
    if let throwable = error as? KotlinThrowable, let text = throwable.message, !text.isEmpty {
        return text
    }
    return error.localizedDescription
}

/// 与 Android 侧 `Fmt.kt` 一致的输入过滤：只允许数字、小数点和负号。
///
/// 放在 Swift 侧而不是 Kotlin 侧，是因为它是**逐字符的输入过滤**（键盘中间态，
/// 如 `-`、`12.` 都不是合法金额），与「最终解析」是两件事：
/// 解析仍走 `Fmt.parseAmount`（Kotlin 的唯一实现）。
func isNumericInput(_ text: String) -> Bool {
    text.allSatisfy { $0.isNumber || $0 == "." || $0 == "-" }
}
