import Foundation
import Shared
import UIKit
import UniformTypeIdentifiers

/// `FilePickerPort` 的 iOS 实现。
///
/// - **保存**：iOS 没有「让用户选位置并写入字节」的单步 API，标准做法是先把字节
///   落到临时目录，再用 `UIDocumentPickerViewController(forExporting:)` 让用户选去处
///   （可存到「文件」App、iCloud Drive、第三方网盘）。导出完成即清理临时文件。
/// - **读取**：`UIDocumentPickerViewController(forOpeningContentTypes:)`，
///   注意要开 `startAccessingSecurityScopedResource()` 才能读沙箱外文件。
final class IosFilePickerPort: NSObject, FilePickerPort, UIDocumentPickerDelegate {

    private var pendingSaveCallback: ((PickedFile?, FileOperationError?) -> Void)?
    private var pendingOpenCallback: ((PickedFile?, FileOperationError?) -> Void)?

    /// 保存时的字节与文件名（导出成功后回传给领域层，用于展示「已导出 xxx」）
    private var pendingSaveData: Data?
    private var pendingSaveName: String?

    // MARK: - FilePickerPort

    func saveFile(
        suggestedName: String,
        mimeType: String,
        data: KotlinByteArray,
        onResult: @escaping (PickedFile?, FileOperationError?) -> Void
    ) {
        let payload = data.toData()
        let tempURL = FileManager.default.temporaryDirectory
            .appendingPathComponent(suggestedName)

        do {
            try payload.write(to: tempURL, options: .atomic)
        } catch {
            onResult(nil, Self.saveFailed)
            return
        }

        pendingSaveData = payload
        pendingSaveName = suggestedName
        pendingSaveCallback = onResult

        let picker = UIDocumentPickerViewController(forExporting: [tempURL], asCopy: true)
        picker.delegate = self
        present(picker) {
            // 拿不到可展示的控制器：清理状态并报错
            self.pendingSaveCallback = nil
            self.pendingSaveData = nil
            onResult(nil, Self.saveFailed)
        }
    }

    func openFile(
        mimeTypes: [String],
        onResult: @escaping (PickedFile?, FileOperationError?) -> Void
    ) {
        pendingOpenCallback = onResult

        // `.enc` 是自定义后缀，用 .data 兜住（否则在「浏览」里会被置灰）
        let types: [UTType] = [.data, .item]
        let picker = UIDocumentPickerViewController(forOpeningContentTypes: types, asCopy: true)
        picker.delegate = self
        picker.allowsMultipleSelection = false
        present(picker) {
            self.pendingOpenCallback = nil
            onResult(nil, Self.readFailed)
        }
    }

    // MARK: - UIDocumentPickerDelegate

    func documentPicker(
        _ controller: UIDocumentPickerViewController,
        didPickDocumentsAt urls: [URL]
    ) {
        guard let url = urls.first else {
            deliverCancelled(controller)
            return
        }

        if let saveCallback = pendingSaveCallback {
            pendingSaveCallback = nil
            let name = pendingSaveName ?? url.lastPathComponent
            let payload = pendingSaveData ?? Data()
            pendingSaveData = nil
            cleanupTempFile(for: name)
            saveCallback(
                PickedFile(
                    displayName: name,
                    bytes: KotlinByteArray(data: payload),
                    byteSize: Int32(payload.count)
                ),
                nil
            )
            return
        }

        if let openCallback = pendingOpenCallback {
            pendingOpenCallback = nil

            let needsScope = url.startAccessingSecurityScopedResource()
            defer { if needsScope { url.stopAccessingSecurityScopedResource() } }

            do {
                let payload = try Data(contentsOf: url)
                openCallback(
                    PickedFile(
                        displayName: url.lastPathComponent,
                        bytes: KotlinByteArray(data: payload),
                        byteSize: Int32(payload.count)
                    ),
                    nil
                )
            } catch {
                openCallback(nil, Self.readFailed)
            }
        }
    }

    func documentPickerWasCancelled(_ controller: UIDocumentPickerViewController) {
        deliverCancelled(controller)
    }

    // MARK: - 内部

    private func deliverCancelled(_ controller: UIDocumentPickerViewController) {
        // 用户取消：file 与 error 都传 nil，领域层会识别成 Cancelled
        if let saveCallback = pendingSaveCallback {
            pendingSaveCallback = nil
            pendingSaveData = nil
            saveCallback(nil, nil)
        }
        if let openCallback = pendingOpenCallback {
            pendingOpenCallback = nil
            openCallback(nil, nil)
        }
    }

    private func cleanupTempFile(for name: String) {
        let url = FileManager.default.temporaryDirectory.appendingPathComponent(name)
        try? FileManager.default.removeItem(at: url)
    }

    private func present(_ controller: UIViewController, onFailure: @escaping () -> Void) {
        guard let root = Self.topViewController() else {
            onFailure()
            return
        }
        controller.modalPresentationStyle = .formSheet
        root.present(controller, animated: true)
    }

    private static func topViewController() -> UIViewController? {
        let scene = UIApplication.shared.connectedScenes
            .compactMap { $0 as? UIWindowScene }
            .first { $0.activationState == .foregroundActive }
        var controller = scene?.windows.first { $0.isKeyWindow }?.rootViewController
        while let presented = controller?.presentedViewController {
            controller = presented
        }
        return controller
    }

    // Kotlin 的 `FileOperationError` 伴生对象在 Swift 侧访问方式随版本而异，
    // 这里直接构造实例，避免依赖 `companion` 的导出形态。
    private static var saveFailed: FileOperationError {
        FileOperationError(code: "SAVE_FAILED", message: "保存失败")
    }

    private static var readFailed: FileOperationError {
        FileOperationError(code: "READ_FAILED", message: "读取文件失败")
    }
}
