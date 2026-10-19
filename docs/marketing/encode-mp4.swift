// Encodes PNG frames into an H.264 MP4 with macOS's AVFoundation (no ffmpeg needed).
// Usage: swift encode-mp4.swift <frames.json> <out.mp4>
// frames.json: {"width": 1080, "height": 1080, "bitrate": 1500000,
//               "frames": [{"path": "/abs/0001.png", "seconds": 0.0}, ...], "end": 42.0}
// Each frame shows from its "seconds" until the next one; the last until "end".
import AVFoundation
import AppKit

struct Frame: Decodable { let path: String; let seconds: Double }
struct Plan: Decodable { let width: Int; let height: Int; let bitrate: Int; let frames: [Frame]; let end: Double }

let arguments = CommandLine.arguments
guard arguments.count == 3 else {
    FileHandle.standardError.write("usage: swift encode-mp4.swift <frames.json> <out.mp4>\n".data(using: .utf8)!)
    exit(2)
}
let plan = try JSONDecoder().decode(Plan.self, from: Data(contentsOf: URL(fileURLWithPath: arguments[1])))
let output = URL(fileURLWithPath: arguments[2])
try? FileManager.default.removeItem(at: output)

let writer = try AVAssetWriter(outputURL: output, fileType: .mp4)
let input = AVAssetWriterInput(mediaType: .video, outputSettings: [
    AVVideoCodecKey: AVVideoCodecType.h264,
    AVVideoWidthKey: plan.width,
    AVVideoHeightKey: plan.height,
    AVVideoCompressionPropertiesKey: [
        AVVideoAverageBitRateKey: plan.bitrate,
        AVVideoProfileLevelKey: AVVideoProfileLevelH264HighAutoLevel,
        AVVideoMaxKeyFrameIntervalKey: 60,
    ],
])
input.expectsMediaDataInRealTime = false
let adaptor = AVAssetWriterInputPixelBufferAdaptor(assetWriterInput: input, sourcePixelBufferAttributes: [
    kCVPixelBufferPixelFormatTypeKey as String: kCVPixelFormatType_32ARGB,
    kCVPixelBufferWidthKey as String: plan.width,
    kCVPixelBufferHeightKey as String: plan.height,
])
writer.add(input)
writer.startWriting()
writer.startSession(atSourceTime: .zero)

let scale: Int32 = 600
func time(_ seconds: Double) -> CMTime { CMTime(value: CMTimeValue((seconds * Double(scale)).rounded()), timescale: scale) }

func pixelBuffer(_ path: String) -> CVPixelBuffer {
    guard let image = NSImage(contentsOfFile: path),
          let cgImage = image.cgImage(forProposedRect: nil, context: nil, hints: nil) else {
        fatalError("can't read \(path)")
    }
    var buffer: CVPixelBuffer?
    CVPixelBufferPoolCreatePixelBuffer(nil, adaptor.pixelBufferPool!, &buffer)
    let pixels = buffer!
    CVPixelBufferLockBaseAddress(pixels, [])
    let context = CGContext(data: CVPixelBufferGetBaseAddress(pixels), width: plan.width, height: plan.height,
                            bitsPerComponent: 8, bytesPerRow: CVPixelBufferGetBytesPerRow(pixels),
                            space: CGColorSpaceCreateDeviceRGB(), bitmapInfo: CGImageAlphaInfo.noneSkipFirst.rawValue)!
    context.draw(cgImage, in: CGRect(x: 0, y: 0, width: plan.width, height: plan.height))
    CVPixelBufferUnlockBaseAddress(pixels, [])
    return pixels
}

// The last frame is repeated at the end, so the video lasts until "end".
let frames = plan.frames + [Frame(path: plan.frames.last!.path, seconds: plan.end)]
for frame in frames {
    while !input.isReadyForMoreMediaData { Thread.sleep(forTimeInterval: 0.01) }
    adaptor.append(pixelBuffer(frame.path), withPresentationTime: time(frame.seconds))
}
input.markAsFinished()
let done = DispatchSemaphore(value: 0)
writer.finishWriting { done.signal() }
done.wait()
if writer.status != .completed {
    FileHandle.standardError.write("encoding failed: \(String(describing: writer.error))\n".data(using: .utf8)!)
    exit(1)
}
print("\(output.path): \(plan.frames.count) frames, \(plan.end) s")
