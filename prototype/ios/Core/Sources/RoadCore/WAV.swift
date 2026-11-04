import Foundation

public struct PCM: Sendable { public let bytes: Data, sampleCount: Int }
public enum WAV {
    public static let maximumBytes = 484_096
    public static func encode(_ samples: [Int16]) throws -> Data {
        guard (16_000...240_000).contains(samples.count) else { throw RoadError.invalid("원음은 1–15초여야 해요.") }
        var data = Data()
        func word(_ value: String) { data.append(contentsOf: value.utf8) }
        func u16(_ value: UInt16) { data.append(UInt8(truncatingIfNeeded: value)); data.append(UInt8(truncatingIfNeeded: value >> 8)) }
        func u32(_ value: UInt32) { u16(UInt16(truncatingIfNeeded: value)); u16(UInt16(truncatingIfNeeded: value >> 16)) }
        word("RIFF"); u32(UInt32(36 + samples.count * 2)); word("WAVEfmt "); u32(16); u16(1); u16(1); u32(16000); u32(32000); u16(2); u16(16); word("data"); u32(UInt32(samples.count * 2))
        for sample in samples { u16(UInt16(bitPattern: sample)) }; return data
    }
    public static func decode(_ data: Data) throws -> PCM {
        let b = [UInt8](data)
        func fail() throws -> Never { throw RoadError.invalid("PCM16 mono 16kHz WAV 원음이 필요해요.") }
        guard (44...maximumBytes).contains(b.count) else { try fail() }
        func u16(_ i: Int) -> Int { Int(b[i]) | Int(b[i+1]) << 8 }
        func u32(_ i: Int) -> Int { u16(i) | u16(i+2) << 16 }
        func word(_ i: Int) -> String { String(decoding: b[i..<i+4], as: UTF8.self) }
        guard word(0) == "RIFF", word(8) == "WAVE", u32(4) == b.count - 8 else { try fail() }
        var offset = 12, format = false; var pcm: Range<Int>?
        while offset < b.count {
            guard offset + 8 <= b.count else { try fail() }
            let kind = word(offset), count = u32(offset + 4), start = offset + 8
            guard count <= b.count - start else { try fail() }; let end = start + count, padded = end + count % 2
            guard padded <= b.count else { try fail() }
            if kind == "fmt " {
                guard !format, pcm == nil, [16,18].contains(count) else { try fail() }
                guard count != 18 || u16(start+16) == 0 else { try fail() }
                guard u16(start) == 1, u16(start+2) == 1, u32(start+4) == 16000, u32(start+8) == 32000, u16(start+12) == 2, u16(start+14) == 16 else { try fail() }; format = true
            } else if kind == "data" {
                guard format, pcm == nil, count % 2 == 0, (16000...240000).contains(count/2) else { try fail() }; pcm = start..<end
            }; offset = padded
        }
        guard let pcm, b.count - pcm.count <= 4096 else { try fail() }
        return PCM(bytes: Data(b[pcm]), sampleCount: pcm.count/2)
    }
}
