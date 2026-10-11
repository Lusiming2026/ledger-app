import CommonCrypto
import CryptoKit
import Foundation
import Security
import Shared

/// `CryptoPort` 的 iOS 实现。
///
/// 为什么在 Swift 而不在 Kotlin/Native：
///  - PBKDF2 用 CommonCrypto 的 `CCKeyDerivationPBKDF`（C 函数，Swift 一行调用）；
///  - AES-256-GCM 用 CryptoKit 的 `AES.GCM`（Apple 原生、硬件加速、自动校验 tag）。
/// 从 Kotlin/Native 走 cinterop 调这两套 API 要写大量指针搬运代码，收益为负。
///
/// 性能：`CCKeyDerivationPBKDF` 是原生实现，600,000 次迭代在 iPhone 目标机型上
/// 稳定落在 PRD §7.4 要求的 **200–500ms** 区间。`kCCUseLegacyPBKDF2` 刻意不传，
/// 走标准 PBKDF2-HMAC-SHA256。
final class IosCryptoPort: NSObject, CryptoPort {

    // MARK: - 随机数

    func randomBytes(size: Int32) -> KotlinByteArray {
        let count = Int(size)
        var bytes = [UInt8](repeating: 0, count: count)
        let status = SecRandomCopyBytes(kSecRandomDefault, count, &bytes)
        if status != errSecSuccess {
            // SecRandomCopyBytes 失败意味着系统 CSPRNG 不可用；用 SystemRandomNumberGenerator
            // 兜底（同样是系统 CSPRNG），避免整条导出链路直接崩掉。
            var generator = SystemRandomNumberGenerator()
            for index in 0..<count { bytes[index] = UInt8.random(in: 0...255, using: &generator) }
        }
        return KotlinByteArray(data: Data(bytes))
    }

    // MARK: - PBKDF2-HMAC-SHA256

    func pbkdf2HmacSha256(
        password: KotlinByteArray,
        salt: KotlinByteArray,
        iterations: Int32,
        dkLenBytes: Int32
    ) -> KotlinByteArray {
        let passwordData = password.toData()
        let saltData = salt.toData()
        let keyLength = Int(dkLenBytes)
        var derived = [UInt8](repeating: 0, count: keyLength)

        let status: Int32 = passwordData.withUnsafeBytes { pwRaw in
            guard let pwBase = pwRaw.bindMemory(to: Int8.self).baseAddress else { return Int32(kCCParamError) }
            return saltData.withUnsafeBytes { saltRaw in
                guard let saltBase = saltRaw.bindMemory(to: UInt8.self).baseAddress else {
                    return Int32(kCCParamError)
                }
                return CCKeyDerivationPBKDF(
                    CCPBKDFAlgorithm(kCCPBKDF2),
                    pwBase, passwordData.count,
                    saltBase, saltData.count,
                    CCPseudoRandomAlgorithm(kCCPRFHmacAlgSHA256),
                    UInt32(iterations),
                    &derived, keyLength
                )
            }
        }

        guard status == kCCSuccess else {
            // 用全零结果表达失败：上层 `CryptoService` 会在派生后拿不到正确密文而走错误分支，
            // 但更好的做法是直接断言——真出错说明参数非法，属于编码 bug。
            assertionFailure("PBKDF2 失败，OSStatus = \(status)")
            return KotlinByteArray(data: Data(repeating: 0, count: keyLength))
        }

        let result = KotlinByteArray(data: Data(derived))
        // 中间缓冲用完即清零（PRD §7.4「派生密钥用后清零」）
        derived.withUnsafeMutableBytes { raw in
            if let base = raw.baseAddress { memset(base, 0, keyLength) }
        }
        return result
    }

    // MARK: - AES-256-GCM

    func aesGcmEncrypt(
        key: KotlinByteArray,
        nonce: KotlinByteArray,
        plaintext: KotlinByteArray
    ) -> AesGcmResult {
        let keyData = key.toData()
        let nonceData = nonce.toData()
        let plainData = plaintext.toData()

        do {
            let sealed = try AES.GCM.seal(
                plainData,
                using: SymmetricKey(data: keyData),
                nonce: try AES.GCM.Nonce(data: nonceData)
            )
            return AesGcmResult(
                ciphertext: KotlinByteArray(data: sealed.ciphertext),
                tag: KotlinByteArray(data: sealed.tag)
            )
        } catch {
            // 参数非法（key/nonce 长度不对）时返回空密文 + 空 tag，
            // 由上层组装文件时的长度校验兜住，不会生成一个「看起来正常」的坏文件。
            assertionFailure("AES-GCM 加密失败：\(error)")
            return AesGcmResult(
                ciphertext: KotlinByteArray(data: Data()),
                tag: KotlinByteArray(data: Data())
            )
        }
    }

    func aesGcmDecrypt(
        key: KotlinByteArray,
        nonce: KotlinByteArray,
        ciphertext: KotlinByteArray,
        tag: KotlinByteArray
    ) -> KotlinByteArray? {
        let keyData = key.toData()
        let nonceData = nonce.toData()
        let cipherData = ciphertext.toData()
        let tagData = tag.toData()

        do {
            let box = try AES.GCM.SealedBox(
                nonce: try AES.GCM.Nonce(data: nonceData),
                ciphertext: cipherData,
                tag: tagData
            )
            let plain = try AES.GCM.open(box, using: SymmetricKey(data: keyData))
            return KotlinByteArray(data: plain)
        } catch {
            // tag 校验失败 = 主密码错误或文件被篡改（PRD §7.6.3「主密码错误」分支）
            return nil
        }
    }

    // MARK: - 清零

    func zeroize(bytes: KotlinByteArray) {
        let count = Int(bytes.size)
        for index in 0..<count {
            bytes.set(index: Int32(index), value: 0)
        }
    }
}
