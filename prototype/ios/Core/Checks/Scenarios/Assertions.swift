import Foundation
public struct CheckFailure: Error, CustomStringConvertible { public let description: String; public init(_ description: String) { self.description = description } }
func expectEqual<T: Equatable>(_ a: T, _ b: T, _ message: String = "", file: StaticString = #fileID, line: UInt = #line) throws { if a != b { throw CheckFailure("\(file):\(line): expected equal values. \(message)") } }
func expectTrue(_ value: Bool, _ message: String = "", file: StaticString = #fileID, line: UInt = #line) throws { if !value { throw CheckFailure("\(file):\(line): expected true. \(message)") } }
func expectFalse(_ value: Bool, _ message: String = "", file: StaticString = #fileID, line: UInt = #line) throws { try expectTrue(!value, message, file: file, line: line) }
func expectNil<T>(_ value: T?, _ message: String = "", file: StaticString = #fileID, line: UInt = #line) throws { try expectTrue(value == nil, message, file: file, line: line) }
func unwrap<T>(_ value: T?, file: StaticString = #fileID, line: UInt = #line) throws -> T { guard let value else { throw CheckFailure("\(file):\(line): expected non-nil") }; return value }
func expectThrows<T>(_ expression: @autoclosure () throws -> T, _ message: String = "", file: StaticString = #fileID, line: UInt = #line) throws { do { _ = try expression() } catch { return }; throw CheckFailure("\(file):\(line): expected an error. \(message)") }
func failCheck(_ text: String) throws -> Never { throw CheckFailure(text) }
