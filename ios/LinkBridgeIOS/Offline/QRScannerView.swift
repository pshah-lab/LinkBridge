import AVFoundation
import SwiftUI
import UIKit

/// Camera view that reports each QR code it reads. Asks for camera access the first time and
/// explains, with Open Settings, when it was refused.
struct QRScannerView: View {
    let onCode: (String) -> Void
    @State private var access = AVCaptureDevice.authorizationStatus(for: .video)
    @Environment(\.openURL) private var openURL

    var body: some View {
        switch access {
        case .authorized:
            CameraPreview(onCode: onCode)
                .ignoresSafeArea()
                .accessibilityLabel("Camera viewfinder")
        case .notDetermined:
            Color.black
                .task {
                    let granted = await AVCaptureDevice.requestAccess(for: .video)
                    access = granted ? .authorized : .denied
                }
        default:
            ContentUnavailableView {
                Label("Camera access is off", systemImage: "camera")
            } description: {
                Text("Allow camera access in Settings to scan the code on your Android device.")
            } actions: {
                Button("Open Settings") {
                    if let url = URL(string: UIApplication.openSettingsURLString) { openURL(url) }
                }
                .buttonStyle(.glass)
            }
        }
    }
}

private final class PreviewView: UIView {
    override class var layerClass: AnyClass { AVCaptureVideoPreviewLayer.self }
    var previewLayer: AVCaptureVideoPreviewLayer { layer as! AVCaptureVideoPreviewLayer }
}

private struct CameraPreview: UIViewRepresentable {
    let onCode: (String) -> Void

    func makeCoordinator() -> Scanner { Scanner(onCode: onCode) }

    func makeUIView(context: Context) -> PreviewView {
        let view = PreviewView()
        view.previewLayer.session = context.coordinator.session
        view.previewLayer.videoGravity = .resizeAspectFill
        context.coordinator.start()
        return view
    }

    func updateUIView(_ view: PreviewView, context: Context) {
        context.coordinator.onCode = onCode
    }

    static func dismantleUIView(_ view: PreviewView, coordinator: Scanner) {
        coordinator.stop()
    }

    /// Owns the capture session. Session work runs on its own queue; codes arrive on main.
    final class Scanner: NSObject, AVCaptureMetadataOutputObjectsDelegate {
        let session = AVCaptureSession()
        var onCode: (String) -> Void
        private let queue = DispatchQueue(label: "linkbridge.qrscanner")
        private var configured = false
        /// The camera sees the same code many times a second; report it once.
        private var lastCode: String?

        init(onCode: @escaping (String) -> Void) { self.onCode = onCode }

        func start() {
            queue.async { [self] in
                if !configured { configure(); configured = true }
                session.startRunning()
            }
        }

        func stop() {
            queue.async { [session] in session.stopRunning() }
        }

        private func configure() {
            session.beginConfiguration()
            defer { session.commitConfiguration() }
            guard let camera = AVCaptureDevice.default(for: .video),
                  let input = try? AVCaptureDeviceInput(device: camera), session.canAddInput(input) else { return }
            session.addInput(input)
            let output = AVCaptureMetadataOutput()
            guard session.canAddOutput(output) else { return }
            session.addOutput(output)
            output.setMetadataObjectsDelegate(self, queue: .main)
            output.metadataObjectTypes = [.qr]
        }

        func metadataOutput(_ output: AVCaptureMetadataOutput, didOutput metadataObjects: [AVMetadataObject],
                            from connection: AVCaptureConnection) {
            guard let code = metadataObjects.compactMap({ ($0 as? AVMetadataMachineReadableCodeObject)?.stringValue }).first,
                  code != lastCode else { return }
            lastCode = code
            onCode(code)
        }
    }
}
