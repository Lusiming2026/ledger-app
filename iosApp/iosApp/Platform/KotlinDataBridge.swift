import Foundation
import Shared

/// Kotlin `ByteArray` ↔ Swift `Data` 的互转。
///
/// Kotlin/Native 把 `ByteArray` 导出为 `KotlinByteArray`，Swift 侧只能逐元素读写，
/// 因此统一在这里转换一次，业务代码只跟 `Data` 打交道。
extension KotlinByteArray {

    /// 用 `Data` 构造 `KotlinByteArray`。
    convenience init(data: Data) {
        self.init(size: Int32(data.count))
        let count = data.count
        if count > 0 {
            data.withUnsafeBytes { raw in
                guard let base = raw.bindMemory(to: Int8.self).baseAddress else { return }
                for index in 0..<count {
                    self.set(index: Int32(index), value: base[index])
                }
            }
        }
    }

    /// 转为 `Data`。
    func toData() -> Data {
        let count = Int(size)
        guard count > 0 else { return Data() }
        var bytes = [UInt8](repeating: 0, count: count)
        for index in 0..<count {
            bytes[index] = UInt8(bitPattern: get(index: Int32(index)))
        }
        return Data(bytes)
    }
}

/// Base64 便捷封装（与 Kotlin 侧 `Base64` 对象的编码一致）。
enum LedgerBase64 {
    static func encode(_ data: Data) -> String { data.base64EncodedString() }

    static func decode(_ text: String) -> Data? { Data(base64Encoded: text) }
}
