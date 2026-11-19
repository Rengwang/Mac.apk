// sidecar_runner.swift -- Headless, non-mouse-hijacking, non-focus-stealing
// Sidecar automation runner for Mac.apk.
//
// Usage:
//   sidecar_runner screens
//   sidecar_runner launch <appBundlePath> <bundleId> [--trace] [--log <logPath>]
//   sidecar_runner wait-ready <bundleId> [timeoutSec]
//   sidecar_runner cmd <bundleId> <jsonString> [timeoutSec]
//   sidecar_runner resize <bundleId> <width> <height>
//   sidecar_runner screenshot <bundleId> <outputPngPath>
//   sidecar_runner quit <bundleId>
//
// Hard guarantees:
//   - NEVER posts CGEvent mouse clicks/moves, NEVER warps the cursor, NEVER uses
//     cliclick or AppleScript System Events, and NEVER sets activates = true.
//   - Only touches AXWindow attributes belonging to the target app PID and
//     places/sizes the window strictly inside Sidecar's quartzVisibleFrame.

import AppKit
import ApplicationServices
import CoreGraphics
import Foundation

// MARK: - Models & JSON helpers

struct RectJSON: Codable {
    let x: Double
    let y: Double
    let width: Double
    let height: Double

    init(_ r: CGRect) {
        self.x = Double(r.origin.x)
        self.y = Double(r.origin.y)
        self.width = Double(r.size.width)
        self.height = Double(r.size.height)
    }

    var cgRect: CGRect {
        CGRect(x: x, y: y, width: width, height: height)
    }
}

struct PointJSON: Codable {
    let x: Double
    let y: Double

    init(_ p: CGPoint) {
        self.x = Double(p.x)
        self.y = Double(p.y)
    }
}

struct SizeJSON: Codable {
    let width: Double
    let height: Double

    init(_ s: CGSize) {
        self.width = Double(s.width)
        self.height = Double(s.height)
    }
}

struct ScreenInfo: Codable {
    let name: String
    let displayID: UInt32
    let scale: Double
    let isPrimary: Bool
    let isSidecar: Bool
    let appkitFrame: RectJSON
    let appkitVisibleFrame: RectJSON
    let quartzFrame: RectJSON
    let quartzVisibleFrame: RectJSON
}

struct ScreensResponse: Codable {
    let ok: Bool
    let displays: [ScreenInfo]
    let sidecarScreen: ScreenInfo?
}

struct WindowSnapshot {
    let pid: pid_t
    let windowID: CGWindowID
    let cgBounds: CGRect
    let axWindow: AXUIElement
    let axPosition: CGPoint
    let axSize: CGSize
}

struct CGWindowCandidate {
    let id: CGWindowID
    let bounds: CGRect
    let isOnscreen: Bool
    let alpha: Double
    let name: String
    let orderIndex: Int

    var area: CGFloat { bounds.width * bounds.height }
}

struct AXWindowCandidate {
    let element: AXUIElement
    let position: CGPoint
    let size: CGSize
    let isMinimized: Bool
    let isStandard: Bool
    let isMain: Bool
    let title: String

    var bounds: CGRect { CGRect(origin: position, size: size) }
    var area: CGFloat { size.width * size.height }
}

func automationDir(for bundleId: String) -> URL {
    let base = FileManager.default.homeDirectoryForCurrentUser
        .appendingPathComponent("Library/Application Support/Mac.apk/Automation", isDirectory: true)
    return base.appendingPathComponent(bundleId, isDirectory: true)
}

func validateBundleId(_ bundleId: String) throws {
    if bundleId.isEmpty || bundleId.contains("/") || bundleId.contains("..") {
        throw RunnerError("Invalid bundleId: '\(bundleId)'")
    }
}

struct RunnerError: Error, LocalizedError {
    let message: String
    init(_ message: String) { self.message = message }
    var errorDescription: String? { message }
}

func printJSONValue(_ obj: Any) throws {
    let data = try JSONSerialization.data(withJSONObject: obj, options: [.sortedKeys])
    if let s = String(data: data, encoding: .utf8) {
        print(s)
    }
}

func printEncodable<T: Encodable>(_ value: T) throws {
    let encoder = JSONEncoder()
    encoder.outputFormatting = [.sortedKeys]
    let data = try encoder.encode(value)
    if let s = String(data: data, encoding: .utf8) {
        print(s)
    }
}

func failWithJSON(_ message: String, code: Int32 = 1) -> Never {
    let payload: [String: Any] = ["ok": false, "error": message]
    if let data = try? JSONSerialization.data(withJSONObject: payload, options: [.sortedKeys]),
       let s = String(data: data, encoding: .utf8) {
        FileHandle.standardError.write(Data((s + "\n").utf8))
    } else {
        FileHandle.standardError.write(Data(("error: " + message + "\n").utf8))
    }
    exit(code)
}

// MARK: - Display Discovery

func discoverScreens() -> ScreensResponse {
    // CRITICAL: Sidecar Display (AirPlay) and its menu-bar-adjusted visibleFrame
    // are only reliably reflected after NSApplication.shared initializes AppKit.
    _ = NSApplication.shared
    let nsScreens = NSScreen.screens
    let primaryHeight = Double(nsScreens.first?.frame.size.height ?? 0.0)

    func toQuartz(_ r: NSRect) -> CGRect {
        let qx = Double(r.origin.x)
        let qy = primaryHeight - Double(r.origin.y + r.size.height)
        return CGRect(x: qx, y: qy, width: Double(r.size.width), height: Double(r.size.height))
    }

    var displays: [ScreenInfo] = []
    var sidecar: ScreenInfo? = nil

    for (idx, scr) in nsScreens.enumerated() {
        let name = scr.localizedName
        let did = (scr.deviceDescription[NSDeviceDescriptionKey("NSScreenNumber")] as? NSNumber)?.uint32Value ?? 0
        let scale = Double(scr.backingScaleFactor)
        let isPrimary = (idx == 0)
        let isSidecar = name.lowercased().contains("sidecar")
        let qFrame = toQuartz(scr.frame)
        let qVis = toQuartz(scr.visibleFrame)
        let info = ScreenInfo(
            name: name,
            displayID: did,
            scale: scale,
            isPrimary: isPrimary,
            isSidecar: isSidecar,
            appkitFrame: RectJSON(scr.frame),
            appkitVisibleFrame: RectJSON(scr.visibleFrame),
            quartzFrame: RectJSON(qFrame),
            quartzVisibleFrame: RectJSON(qVis)
        )
        displays.append(info)
        if isSidecar && sidecar == nil {
            sidecar = info
        }
    }
    return ScreensResponse(ok: true, displays: displays, sidecarScreen: sidecar)
}

func requireSidecarScreen() throws -> ScreenInfo {
    let resp = discoverScreens()
    guard let s = resp.sidecarScreen else {
        let names = resp.displays.map(\.name).joined(separator: ", ")
        throw RunnerError("Sidecar Display (AirPlay) not found. Available displays: [\(names)]")
    }
    return s
}

func isRectOnSidecar(_ rect: CGRect, sidecar: ScreenInfo, tolerance: CGFloat = 4.0) -> Bool {
    guard rect.width > 1 && rect.height > 1 else { return false }
    let sFrame = sidecar.quartzFrame.cgRect
    return rect.minX >= sFrame.minX - tolerance
        && rect.maxX <= sFrame.maxX + tolerance
        && rect.minY >= sFrame.minY - tolerance
        && rect.maxY <= sFrame.maxY + tolerance
}

// MARK: - Process Lookup & Termination

func bundleInfoDict(at bundleURL: URL) -> [String: Any]? {
    let plistURL = bundleURL.appendingPathComponent("Contents/Info.plist")
    guard let data = try? Data(contentsOf: plistURL),
          let dict = try? PropertyListSerialization.propertyList(from: data, format: nil) as? [String: Any] else {
        return nil
    }
    return dict
}

func findRunningApps(bundleId: String, appBundleURL: URL? = nil) -> [NSRunningApplication] {
    let resolvedBundlePath = appBundleURL?.standardizedFileURL.path
    let expectedBundleIds: Set<String> = {
        var ids: Set<String> = [bundleId, "org.macapk.app.\(bundleId)"]
        if let appURL = appBundleURL,
           let cfId = bundleInfoDict(at: appURL)?["CFBundleIdentifier"] as? String {
            ids.insert(cfId)
        }
        return ids
    }()

    var seen = Set<pid_t>()
    var out: [NSRunningApplication] = []
    for app in NSWorkspace.shared.runningApplications {
        if app.isTerminated { continue }
        let pid = app.processIdentifier
        if pid <= 0 || seen.contains(pid) { continue }
        var matches = false
        if let bid = app.bundleIdentifier, expectedBundleIds.contains(bid) {
            matches = true
        }
        if !matches, let targetPath = resolvedBundlePath {
            if let bPath = app.bundleURL?.standardizedFileURL.path, bPath == targetPath {
                matches = true
            } else if let exePath = app.executableURL?.standardizedFileURL.path,
                      exePath.hasPrefix(targetPath + "/") {
                matches = true
            }
        }
        if !matches, let appURL = app.bundleURL,
           let pkg = bundleInfoDict(at: appURL)?["MacapkPackage"] as? String,
           pkg == bundleId {
            matches = true
        }
        if matches {
            seen.insert(pid)
            out.append(app)
        }
    }
    return out
}

func processArgsAndEnv(_ pid: pid_t) -> (execPath: String, argv: [String], env: [String: String])? {
    var mib: [Int32] = [CTL_KERN, KERN_PROCARGS2, pid]
    var size = 0
    guard sysctl(&mib, 3, nil, &size, nil, 0) == 0, size > 4 else { return nil }
    var buf = [UInt8](repeating: 0, count: size)
    guard sysctl(&mib, 3, &buf, &size, nil, 0) == 0, size > 4 else { return nil }
    let argc = buf.withUnsafeBytes { Int($0.load(as: Int32.self)) }
    var i = 4
    var execEnd = i
    while execEnd < size && buf[execEnd] != 0 { execEnd += 1 }
    let execPath = String(decoding: buf[i..<execEnd], as: UTF8.self)
    i = execEnd
    while i < size && buf[i] == 0 { i += 1 }
    var argv: [String] = []
    while argv.count < argc && i < size {
        var j = i
        while j < size && buf[j] != 0 { j += 1 }
        argv.append(String(decoding: buf[i..<j], as: UTF8.self))
        i = j + 1
    }
    var env: [String: String] = [:]
    while i < size {
        var j = i
        while j < size && buf[j] != 0 { j += 1 }
        if j == i { break }
        let kv = String(decoding: buf[i..<j], as: UTF8.self)
        if let eq = kv.firstIndex(of: "=") {
            env[String(kv[..<eq])] = String(kv[kv.index(after: eq)...])
        }
        i = j + 1
    }
    return (execPath, argv, env)
}

func stringContainsBundleToken(_ value: String, bundleId: String, resolvedBundlePath: String?) -> Bool {
    if let bp = resolvedBundlePath, !bp.isEmpty {
        if value == bp || value.hasPrefix(bp + "/") {
            return true
        }
    }
    if value.contains("/\(bundleId).app/") || value.hasSuffix("/\(bundleId).app") {
        return true
    }
    if value.contains("/\(bundleId)/") || value.contains("/\(bundleId)-")
        || value.contains("/\(bundleId)_") || value.hasSuffix("/\(bundleId)") {
        return true
    }
    let dotToken = ".\(bundleId)"
    var searchStart = value.startIndex
    while let range = value.range(of: dotToken, range: searchStart..<value.endIndex) {
        let after = range.upperBound
        if after == value.endIndex {
            return true
        }
        let next = value[after]
        if next == "/" || next == "_" || next == "-" || next == ":" || value[after...].hasPrefix(".app/") || value[after...] == ".app" {
            return true
        }
        searchStart = range.upperBound
    }
    if value.hasPrefix("\(bundleId)_") || value.hasPrefix("\(bundleId):") || value == bundleId {
        return true
    }
    return false
}

func appBundleURLFromExecutablePath(_ procPath: String) -> URL? {
    guard let range = procPath.range(of: ".app/Contents/MacOS/") else { return nil }
    let bundlePath = String(procPath[..<range.lowerBound]) + ".app"
    return URL(fileURLWithPath: bundlePath, isDirectory: true)
}

func appBundleMatches(_ bundleURL: URL, bundleId: String) -> Bool {
    guard let plist = bundleInfoDict(at: bundleURL) else { return false }
    if let pkg = plist["MacapkPackage"] as? String, pkg == bundleId {
        return true
    }
    if let cfId = plist["CFBundleIdentifier"] as? String,
       cfId == bundleId || cfId == "org.macapk.app.\(bundleId)" {
        return true
    }
    return false
}

func findMatchingRuntimeChildPids(bundleId: String,
                                  appBundleURL: URL? = nil,
                                  excluding excludedPids: Set<pid_t> = []) -> [pid_t] {
    let resolvedBundlePath = appBundleURL?.standardizedFileURL.path
    let selfPid = getpid()
    let needed = proc_listallpids(nil, 0)
    let cap = max(4096, Int(needed) * 2)
    var pids = [pid_t](repeating: 0, count: cap)
    let n = proc_listallpids(&pids, Int32(pids.count * MemoryLayout<pid_t>.stride))
    guard n > 0 else { return [] }

    var matches: [pid_t] = []
    var seen = Set<pid_t>()
    for pid in pids.prefix(Int(n)) where pid > 0 && pid != selfPid && !excludedPids.contains(pid) && !seen.contains(pid) {
        var pathBuf = [CChar](repeating: 0, count: 4096)
        guard proc_pidpath(pid, &pathBuf, UInt32(pathBuf.count)) > 0 else { continue }
        let procPath = String(cString: pathBuf)
        let exeName = URL(fileURLWithPath: procPath).lastPathComponent
        let bundleFromPath = appBundleURLFromExecutablePath(procPath)
        let isMacapkBinary = (exeName == "macapk-runtime") || (bundleFromPath != nil)
        guard isMacapkBinary else { continue }
        guard let info = processArgsAndEnv(pid) else { continue }

        // For bundle Contents/MacOS/<exe> processes, verify this is a Mac.apk runtime/stub
        // process (has --apk or MACAPK_* env or MacapkPackage plist) rather than an unrelated app.
        if exeName != "macapk-runtime" {
            let hasMacapkMarker = info.argv.contains("--apk")
                || info.env.keys.contains(where: { $0.hasPrefix("MACAPK_") })
                || (bundleFromPath.flatMap { bundleInfoDict(at: $0)?["MacapkPackage"] as? String } != nil)
            if !hasMacapkMarker { continue }
        }

        var matched = false
        if let procName = info.env["MACAPK_PROCESS_NAME"],
           procName == bundleId || procName.hasPrefix("\(bundleId):") {
            matched = true
        }
        if !matched, let autoDir = info.env["MACAPK_AUTOMATION_DIR"],
           stringContainsBundleToken(autoDir, bundleId: bundleId, resolvedBundlePath: resolvedBundlePath) {
            matched = true
        }
        if !matched, stringContainsBundleToken(procPath, bundleId: bundleId, resolvedBundlePath: resolvedBundlePath) {
            matched = true
        }
        if !matched, stringContainsBundleToken(info.execPath, bundleId: bundleId, resolvedBundlePath: resolvedBundlePath) {
            matched = true
        }
        if !matched, let bURL = bundleFromPath, appBundleMatches(bURL, bundleId: bundleId) {
            matched = true
        }
        if !matched {
            for arg in info.argv {
                if stringContainsBundleToken(arg, bundleId: bundleId, resolvedBundlePath: resolvedBundlePath) {
                    matched = true
                    break
                }
            }
        }
        if matched {
            seen.insert(pid)
            matches.append(pid)
        }
    }
    return matches
}

func killPidGroup(_ initialPids: [pid_t],
                  refetch: () -> [pid_t],
                  record: ([pid_t]) -> Void,
                  termTimeoutSec: TimeInterval = 1.5,
                  killTimeoutSec: TimeInterval = 0.5) {
    guard !initialPids.isEmpty else { return }
    record(initialPids)
    for pid in initialPids {
        _ = Darwin.kill(pid, SIGTERM)
    }
    let termDeadline = Date().addingTimeInterval(termTimeoutSec)
    while Date() < termDeadline {
        if refetch().isEmpty { break }
        Thread.sleep(forTimeInterval: 0.05)
    }
    let stubborn = refetch()
    if !stubborn.isEmpty {
        record(stubborn)
        for pid in stubborn {
            _ = Darwin.kill(pid, SIGKILL)
        }
        let killDeadline = Date().addingTimeInterval(killTimeoutSec)
        while Date() < killDeadline {
            if refetch().isEmpty { break }
            Thread.sleep(forTimeInterval: 0.05)
        }
    }
}

func terminateMatchingApps(bundleId: String, appBundleURL: URL? = nil) -> [pid_t] {
    var pids: [pid_t] = []
    var seenPids = Set<pid_t>()
    func recordPids(_ items: [pid_t]) {
        for p in items where !seenPids.contains(p) {
            seenPids.insert(p)
            pids.append(p)
        }
    }

    // Phase 1: Terminate secondary/child runtime processes FIRST (or concurrently) so they
    // cannot trigger a cold relaunch on SystemServer while the primary app is tearing down.
    let initialApps = findRunningApps(bundleId: bundleId, appBundleURL: appBundleURL)
    let primaryAppPids = Set(initialApps.filter { $0.activationPolicy == .regular }.map(\.processIdentifier))
    let secondaryFirst = findMatchingRuntimeChildPids(
        bundleId: bundleId,
        appBundleURL: appBundleURL,
        excluding: primaryAppPids
    )
    if !secondaryFirst.isEmpty {
        killPidGroup(
            secondaryFirst,
            refetch: {
                findMatchingRuntimeChildPids(
                    bundleId: bundleId,
                    appBundleURL: appBundleURL,
                    excluding: primaryAppPids
                )
            },
            record: recordPids
        )
    }

    // Phase 2: Terminate any NSRunningApplication primary/bundle instances.
    let apps = findRunningApps(bundleId: bundleId, appBundleURL: appBundleURL)
    if !apps.isEmpty {
        recordPids(apps.map(\.processIdentifier))
        for app in apps {
            _ = app.terminate()
        }
        let softDeadline = Date().addingTimeInterval(2.5)
        while Date() < softDeadline {
            if findRunningApps(bundleId: bundleId, appBundleURL: appBundleURL).isEmpty { break }
            Thread.sleep(forTimeInterval: 0.05)
        }
        let remainingApps = findRunningApps(bundleId: bundleId, appBundleURL: appBundleURL)
        if !remainingApps.isEmpty {
            recordPids(remainingApps.map(\.processIdentifier))
            for app in remainingApps {
                _ = app.forceTerminate()
            }
            let hardDeadline = Date().addingTimeInterval(2.0)
            while Date() < hardDeadline {
                if findRunningApps(bundleId: bundleId, appBundleURL: appBundleURL).isEmpty { break }
                Thread.sleep(forTimeInterval: 0.05)
            }
        }
    }

    // Phase 3: Verification sweeps — catch any remaining or newly-spawned runtime/bundle processes
    // (including Contents/MacOS/.<App> stubs or secondary macapk-runtime processes).
    for sweep in 0..<2 {
        if sweep > 0 {
            Thread.sleep(forTimeInterval: 0.15)
        }
        let stragglerApps = findRunningApps(bundleId: bundleId, appBundleURL: appBundleURL)
        if !stragglerApps.isEmpty {
            recordPids(stragglerApps.map(\.processIdentifier))
            for app in stragglerApps {
                _ = app.forceTerminate()
            }
        }
        let stragglerPids = findMatchingRuntimeChildPids(bundleId: bundleId, appBundleURL: appBundleURL)
        if stragglerPids.isEmpty && stragglerApps.isEmpty {
            if sweep == 0 {
                continue
            }
            break
        }
        if !stragglerPids.isEmpty {
            killPidGroup(
                stragglerPids,
                refetch: { findMatchingRuntimeChildPids(bundleId: bundleId, appBundleURL: appBundleURL) },
                record: recordPids,
                termTimeoutSec: 0.8,
                killTimeoutSec: 0.5
            )
        }
    }
    return pids
}

func readJSONDict(at url: URL) -> [String: Any]? {
    guard let data = try? Data(contentsOf: url), !data.isEmpty,
          let obj = try? JSONSerialization.jsonObject(with: data, options: []) as? [String: Any] else {
        return nil
    }
    return obj
}

func writeJSONAtomic(_ obj: [String: Any], to targetURL: URL) throws {
    let fm = FileManager.default
    let dir = targetURL.deletingLastPathComponent()
    try fm.createDirectory(at: dir, withIntermediateDirectories: true)
    let data = try JSONSerialization.data(withJSONObject: obj, options: [.sortedKeys])
    let tmpURL = dir.appendingPathComponent(".\(targetURL.lastPathComponent).tmp.\(UUID().uuidString)")
    try data.write(to: tmpURL, options: .atomic)
    if rename(tmpURL.path, targetURL.path) != 0 {
        let err = errno
        try? fm.removeItem(at: tmpURL)
        throw RunnerError("Failed atomic rename to \(targetURL.path): errno=\(err)")
    }
}

func resolveTargetPID(bundleId: String) throws -> pid_t {
    let readyURL = automationDir(for: bundleId).appendingPathComponent("ready.json")
    if let ready = readJSONDict(at: readyURL),
       let pidNum = ready["pid"] as? NSNumber {
        let pid = pid_t(pidNum.intValue)
        if pid > 0 && kill(pid, 0) == 0 {
            return pid
        }
    }
    if let app = findRunningApps(bundleId: bundleId).first {
        return app.processIdentifier
    }
    throw RunnerError("No running target process found for bundleId '\(bundleId)'")
}

// MARK: - AX & CGWindow Helpers

func ensureAXTrusted() throws {
    if !AXIsProcessTrusted() {
        throw RunnerError("Accessibility trust (AXIsProcessTrusted() == false) is required for headless Sidecar AXWindow placement")
    }
}

func cgWindowsForPID(_ pid: pid_t, sidecar: ScreenInfo? = nil, onscreenOnly: Bool = true) -> [CGWindowCandidate] {
    guard let rawList = CGWindowListCopyWindowInfo([.optionAll], kCGNullWindowID) as? [[String: Any]] else {
        return []
    }
    var result: [CGWindowCandidate] = []
    for (idx, entry) in rawList.enumerated() {
        guard let ownerPID = entry[kCGWindowOwnerPID as String] as? NSNumber,
              pid_t(ownerPID.intValue) == pid,
              let layer = entry[kCGWindowLayer as String] as? NSNumber,
              layer.intValue == 0,
              let wid = entry[kCGWindowNumber as String] as? NSNumber,
              let boundsDict = entry[kCGWindowBounds as String] as? NSDictionary,
              let rect = CGRect(dictionaryRepresentation: boundsDict),
              rect.width >= 50 && rect.height >= 50 else {
            continue
        }
        let isOnscreen = (entry[kCGWindowIsOnscreen as String] as? NSNumber)?.boolValue ?? false
        let alpha = (entry[kCGWindowAlpha as String] as? NSNumber)?.doubleValue ?? 1.0
        if onscreenOnly && !isOnscreen { continue }
        if alpha <= 0.01 { continue }
        let name = (entry[kCGWindowName as String] as? String) ?? ""
        result.append(CGWindowCandidate(
            id: CGWindowID(wid.uint32Value),
            bounds: rect,
            isOnscreen: isOnscreen,
            alpha: alpha,
            name: name,
            orderIndex: idx
        ))
    }
    result.sort { lhs, rhs in
        if lhs.isOnscreen != rhs.isOnscreen { return lhs.isOnscreen && !rhs.isOnscreen }
        if let sc = sidecar {
            let lSidecar = isRectOnSidecar(lhs.bounds, sidecar: sc)
            let rSidecar = isRectOnSidecar(rhs.bounds, sidecar: sc)
            if lSidecar != rSidecar { return lSidecar && !rSidecar }
        }
        if abs(lhs.area - rhs.area) > 1.0 { return lhs.area > rhs.area }
        let lNamed = !lhs.name.isEmpty
        let rNamed = !rhs.name.isEmpty
        if lNamed != rNamed { return lNamed && !rNamed }
        return lhs.orderIndex < rhs.orderIndex
    }
    return result
}

func axString(_ el: AXUIElement, _ attr: String) -> String? {
    var val: CFTypeRef?
    guard AXUIElementCopyAttributeValue(el, attr as CFString, &val) == .success else { return nil }
    return val as? String
}

func axBool(_ el: AXUIElement, _ attr: String) -> Bool? {
    var val: CFTypeRef?
    guard AXUIElementCopyAttributeValue(el, attr as CFString, &val) == .success,
          let num = val as? NSNumber else { return nil }
    return num.boolValue
}

func axPoint(_ el: AXUIElement, _ attr: String) -> CGPoint? {
    var val: CFTypeRef?
    guard AXUIElementCopyAttributeValue(el, attr as CFString, &val) == .success,
          let axVal = val,
          CFGetTypeID(axVal) == AXValueGetTypeID() else { return nil }
    var p = CGPoint.zero
    guard AXValueGetValue(axVal as! AXValue, .cgPoint, &p) else { return nil }
    return p
}

func axSize(_ el: AXUIElement, _ attr: String) -> CGSize? {
    var val: CFTypeRef?
    guard AXUIElementCopyAttributeValue(el, attr as CFString, &val) == .success,
          let axVal = val,
          CFGetTypeID(axVal) == AXValueGetTypeID() else { return nil }
    var s = CGSize.zero
    guard AXValueGetValue(axVal as! AXValue, .cgSize, &s) else { return nil }
    return s
}

func setAXPoint(_ el: AXUIElement, _ pt: CGPoint) {
    var p = pt
    if let val = AXValueCreate(.cgPoint, &p) {
        _ = AXUIElementSetAttributeValue(el, kAXPositionAttribute as CFString, val)
    }
}

func setAXSize(_ el: AXUIElement, _ sz: CGSize) {
    var s = sz
    if let val = AXValueCreate(.cgSize, &s) {
        _ = AXUIElementSetAttributeValue(el, kAXSizeAttribute as CFString, val)
    }
}

func axWindowCandidatesForPID(_ pid: pid_t) -> [AXWindowCandidate] {
    let app = AXUIElementCreateApplication(pid)
    var val: CFTypeRef?
    guard AXUIElementCopyAttributeValue(app, kAXWindowsAttribute as CFString, &val) == .success,
          let list = val as? [AXUIElement] else {
        return []
    }
    var candidates: [AXWindowCandidate] = []
    for win in list {
        let role = axString(win, kAXRoleAttribute as String)
        if role != nil && role != kAXWindowRole as String { continue }
        guard let pos = axPoint(win, kAXPositionAttribute as String),
              let sz = axSize(win, kAXSizeAttribute as String),
              sz.width >= 50 && sz.height >= 50 else {
            continue
        }
        let subrole = axString(win, kAXSubroleAttribute as String)
        let isStandard = (subrole == nil || subrole == kAXStandardWindowSubrole as String)
        let isMinimized = axBool(win, kAXMinimizedAttribute as String) ?? false
        let isMain = axBool(win, kAXMainAttribute as String) ?? false
        let title = axString(win, kAXTitleAttribute as String) ?? ""
        candidates.append(AXWindowCandidate(
            element: win,
            position: pos,
            size: sz,
            isMinimized: isMinimized,
            isStandard: isStandard,
            isMain: isMain,
            title: title
        ))
    }
    candidates.sort { lhs, rhs in
        if lhs.isMinimized != rhs.isMinimized { return !lhs.isMinimized && rhs.isMinimized }
        if lhs.isStandard != rhs.isStandard { return lhs.isStandard && !rhs.isStandard }
        if lhs.isMain != rhs.isMain { return lhs.isMain && !rhs.isMain }
        if abs(lhs.area - rhs.area) > 1.0 { return lhs.area > rhs.area }
        let lTitled = !lhs.title.isEmpty
        let rTitled = !rhs.title.isEmpty
        if lTitled != rTitled { return lTitled && !rTitled }
        return false
    }
    return candidates
}

func axWindowsForPID(_ pid: pid_t) -> [AXUIElement] {
    return axWindowCandidatesForPID(pid).map(\.element)
}

func rectsApproximatelyMatch(_ a: CGRect, _ b: CGRect, tolerance: CGFloat = 4.0) -> Bool {
    return abs(a.origin.x - b.origin.x) <= tolerance
        && abs(a.origin.y - b.origin.y) <= tolerance
        && abs(a.size.width - b.size.width) <= tolerance
        && abs(a.size.height - b.size.height) <= tolerance
}

func findWindowSnapshot(pid: pid_t, sidecar: ScreenInfo? = nil) -> WindowSnapshot? {
    let cgList = cgWindowsForPID(pid, sidecar: sidecar, onscreenOnly: true)
    let axList = axWindowCandidatesForPID(pid)
    guard !cgList.isEmpty && !axList.isEmpty else {
        return nil
    }
    for cg in cgList {
        if let ax = axList.first(where: { rectsApproximatelyMatch(cg.bounds, $0.bounds) }) {
            return WindowSnapshot(pid: pid, windowID: cg.id, cgBounds: cg.bounds, axWindow: ax.element, axPosition: ax.position, axSize: ax.size)
        }
    }
    if let cg = cgList.first,
       let ax = axList.first(where: { abs($0.size.width - cg.bounds.width) <= 40.0 && abs($0.size.height - cg.bounds.height) <= 40.0 }) {
        return WindowSnapshot(pid: pid, windowID: cg.id, cgBounds: cg.bounds, axWindow: ax.element, axPosition: ax.position, axSize: ax.size)
    }
    if cgList.count == 1, axList.count == 1, let cg = cgList.first, let ax = axList.first {
        return WindowSnapshot(pid: pid, windowID: cg.id, cgBounds: cg.bounds, axWindow: ax.element, axPosition: ax.position, axSize: ax.size)
    }
    return nil
}

func fitAndCenterWindowOnSidecar(pid: pid_t,
                                  desiredSize: CGSize? = nil,
                                  sidecar: ScreenInfo,
                                  timeoutSec: TimeInterval = 20.0) throws -> WindowSnapshot {
    try ensureAXTrusted()
    let deadline = Date().addingTimeInterval(timeoutSec)
    var snap: WindowSnapshot? = nil
    while Date() < deadline {
        if let found = findWindowSnapshot(pid: pid, sidecar: sidecar) {
            snap = found
            break
        }
        Thread.sleep(forTimeInterval: 0.1)
    }
    guard var current = snap else {
        throw RunnerError("Timed out waiting for AXWindow and onscreen layer-0 CGWindow for pid=\(pid)")
    }

    let vis = sidecar.quartzVisibleFrame.cgRect
    var targetSize = desiredSize ?? current.axSize
    if targetSize.width > vis.width || targetSize.height > vis.height {
        let scale = min(vis.width / max(1, targetSize.width), vis.height / max(1, targetSize.height))
        targetSize = CGSize(width: floor(targetSize.width * scale), height: floor(targetSize.height * scale))
    }
    targetSize.width = max(200, min(targetSize.width, vis.width))
    targetSize.height = max(200, min(targetSize.height, vis.height))

    let needsResize = desiredSize != nil
        || abs(current.axSize.width - targetSize.width) > 2.0
        || abs(current.axSize.height - targetSize.height) > 2.0
        || !isRectOnSidecar(current.cgBounds, sidecar: sidecar)

    if needsResize {
        if desiredSize != nil || targetSize.width != current.axSize.width || targetSize.height != current.axSize.height {
            setAXSize(current.axWindow, targetSize)
            Thread.sleep(forTimeInterval: 0.08)
        }
        let actualSize = axSize(current.axWindow, kAXSizeAttribute as String) ?? targetSize
        let centeredOrigin = CGPoint(
            x: vis.origin.x + floor((vis.width - actualSize.width) / 2.0),
            y: vis.origin.y + floor((vis.height - actualSize.height) / 2.0)
        )
        let clampedOrigin = CGPoint(
            x: max(vis.minX, min(centeredOrigin.x, vis.maxX - actualSize.width)),
            y: max(vis.minY, min(centeredOrigin.y, vis.maxY - actualSize.height))
        )
        setAXPoint(current.axWindow, clampedOrigin)
        Thread.sleep(forTimeInterval: 0.08)
        if desiredSize != nil {
            setAXSize(current.axWindow, targetSize)
            Thread.sleep(forTimeInterval: 0.08)
            let finalSize = axSize(current.axWindow, kAXSizeAttribute as String) ?? targetSize
            let reOrigin = CGPoint(
                x: max(vis.minX, min(vis.origin.x + floor((vis.width - finalSize.width) / 2.0), vis.maxX - finalSize.width)),
                y: max(vis.minY, min(vis.origin.y + floor((vis.height - finalSize.height) / 2.0), vis.maxY - finalSize.height))
            )
            setAXPoint(current.axWindow, reOrigin)
            Thread.sleep(forTimeInterval: 0.08)
        }
        if let updated = findWindowSnapshot(pid: pid, sidecar: sidecar) {
            current = updated
        }
    }

    guard isRectOnSidecar(current.cgBounds, sidecar: sidecar) else {
        throw RunnerError("Window pid=\(pid) wid=\(current.windowID) bounds \(RectJSON(current.cgBounds)) are NOT strictly inside Sidecar \(sidecar.quartzFrame)")
    }
    return current
}

// MARK: - Subcommands

func cmdScreens() throws {
    let resp = discoverScreens()
    try printEncodable(resp)
}

func cmdLaunch(_ args: [String]) throws {
    guard args.count >= 2 else {
        throw RunnerError("Usage: launch <appBundlePath> <bundleId> [--trace] [--log <logPath>]")
    }
    let appBundlePath = (args[0] as NSString).expandingTildeInPath
    let bundleId = args[1]
    try validateBundleId(bundleId)

    var enableTrace = false
    var logPath: String? = nil
    var idx = 2
    while idx < args.count {
        let flag = args[idx]
        if flag == "--trace" {
            enableTrace = true
            idx += 1
        } else if flag == "--log" {
            guard idx + 1 < args.count else { throw RunnerError("--log requires a path argument") }
            logPath = (args[idx + 1] as NSString).expandingTildeInPath
            idx += 2
        } else {
            throw RunnerError("Unknown launch option: \(flag)")
        }
    }

    let appURL = URL(fileURLWithPath: appBundlePath).standardizedFileURL
    var isDir: ObjCBool = false
    guard FileManager.default.fileExists(atPath: appURL.path, isDirectory: &isDir), isDir.boolValue else {
        throw RunnerError("App bundle not found: \(appURL.path)")
    }

    let sidecar = try requireSidecarScreen()
    try ensureAXTrusted()

    _ = terminateMatchingApps(bundleId: bundleId, appBundleURL: appURL)

    let autoDir = automationDir(for: bundleId)
    let fm = FileManager.default
    try fm.createDirectory(at: autoDir, withIntermediateDirectories: true)
    for file in ["cmd.json", "state.json", "ready.json"] {
        try? fm.removeItem(at: autoDir.appendingPathComponent(file))
    }

    var env = ProcessInfo.processInfo.environment
    env["MACAPK_HOST_DISPLAY"] = "left"
    env["MACAPK_AUTOMATION"] = "1"
    env["MACAPK_AUTOMATION_DIR"] = autoDir.path
    env["MACAPK_NO_ACTIVATE"] = "1"
    env["MACAPK_PAUSE_WHEN_HIDDEN"] = "0"
    if enableTrace {
        env["MACAPK_VIDEO_PRESENT_TRACE"] = "1"
        env["MACAPK_FRAME_TRACE"] = "1"
        env["MACAPK_TRACE_VIDEO_GEOMETRY"] = "1"
        env["MACAPK_GL_WINDOW_READBACK_TRACE"] = "1"
        env["MACAPK_MAIN_STALL_MS"] = "50"
    }
    if let lp = logPath, !lp.isEmpty {
        try fm.createDirectory(at: URL(fileURLWithPath: lp).deletingLastPathComponent(), withIntermediateDirectories: true)
        env["MACAPK_AUTOMATION_LOG"] = lp
    }

    let config = NSWorkspace.OpenConfiguration()
    config.activates = false
    config.addsToRecentItems = false
    config.hides = false
    config.createsNewApplicationInstance = true
    config.environment = env

    let sem = DispatchSemaphore(value: 0)
    var launchedApp: NSRunningApplication? = nil
    var launchErr: Error? = nil

    NSWorkspace.shared.openApplication(at: appURL, configuration: config) { app, err in
        launchedApp = app
        launchErr = err
        sem.signal()
    }
    if sem.wait(timeout: .now() + 20.0) == .timedOut {
        throw RunnerError("NSWorkspace.openApplication timed out for \(appURL.path)")
    }
    if let err = launchErr {
        throw RunnerError("NSWorkspace.openApplication failed: \(err.localizedDescription)")
    }
    guard let running = launchedApp else {
        throw RunnerError("NSWorkspace.openApplication returned nil app without error")
    }

    let pid = running.processIdentifier
    let snap = try fitAndCenterWindowOnSidecar(pid: pid, sidecar: sidecar, timeoutSec: 25.0)
    var result: [String: Any] = [
        "ok": true,
        "bundleId": bundleId,
        "appBundlePath": appURL.path,
        "automationDir": autoDir.path,
        "pid": Int(snap.pid),
        "windowID": Int(snap.windowID),
        "axPosition": ["x": snap.axPosition.x, "y": snap.axPosition.y],
        "axSize": ["width": snap.axSize.width, "height": snap.axSize.height],
        "cgBounds": ["x": snap.cgBounds.origin.x, "y": snap.cgBounds.origin.y,
                     "width": snap.cgBounds.size.width, "height": snap.cgBounds.size.height],
        "onSidecar": true,
        "sidecarName": sidecar.name,
    ]
    if let lp = logPath {
        result["logPath"] = lp
    }
    try printJSONValue(result)
}

func cmdWaitReady(_ args: [String]) throws {
    guard let bundleId = args.first else {
        throw RunnerError("Usage: wait-ready <bundleId> [timeoutSec]")
    }
    try validateBundleId(bundleId)
    let timeoutSec = args.count >= 2 ? (Double(args[1]) ?? 30.0) : 30.0
    let readyURL = automationDir(for: bundleId).appendingPathComponent("ready.json")
    let deadline = Date().addingTimeInterval(max(1.0, timeoutSec))
    while Date() < deadline {
        if let dict = readJSONDict(at: readyURL),
           let w = (dict["surfaceWidth"] as? NSNumber)?.intValue,
           let h = (dict["surfaceHeight"] as? NSNumber)?.intValue,
           w > 0 && h > 0 {
            var out = dict
            out["ok"] = true
            try printJSONValue(out)
            return
        }
        Thread.sleep(forTimeInterval: 0.05)
    }
    throw RunnerError("Timed out after \(timeoutSec)s waiting for \(readyURL.path)")
}

func sendCommandInternal(bundleId: String,
                         cmdDict: [String: Any],
                         timeoutSec: TimeInterval) throws -> [String: Any] {
    var payload = cmdDict
    let dir = automationDir(for: bundleId)
    let cmdURL = dir.appendingPathComponent("cmd.json")
    let stateURL = dir.appendingPathComponent("state.json")

    let prevState = readJSONDict(at: stateURL)
    let prevSeq = (prevState?["seq"] as? NSNumber)?.int64Value ?? 0
    let prevData = try? Data(contentsOf: stateURL)

    let sentSeq: Int64
    if let callerSeq = payload["seq"] as? NSNumber {
        sentSeq = callerSeq.int64Value
    } else {
        sentSeq = max(prevSeq + 1, Int64(Date().timeIntervalSince1970 * 1000.0))
        payload["seq"] = NSNumber(value: sentSeq)
    }

    try writeJSONAtomic(payload, to: cmdURL)

    let deadline = Date().addingTimeInterval(max(1.0, timeoutSec))
    while Date() < deadline {
        if let curData = try? Data(contentsOf: stateURL),
           curData != prevData,
           let curDict = try? JSONSerialization.jsonObject(with: curData, options: []) as? [String: Any] {
            let curSeq = (curDict["seq"] as? NSNumber)?.int64Value
            if curSeq == sentSeq || curSeq != prevSeq {
                return curDict
            }
        }
        Thread.sleep(forTimeInterval: 0.03)
    }
    throw RunnerError("Timed out after \(timeoutSec)s waiting for state.json response (seq=\(sentSeq))")
}

func cmdSend(_ args: [String]) throws {
    guard args.count >= 2 else {
        throw RunnerError("Usage: cmd <bundleId> <jsonString> [timeoutSec]")
    }
    let bundleId = args[0]
    try validateBundleId(bundleId)
    let rawJSON = args[1]
    let timeoutSec = args.count >= 3 ? (Double(args[2]) ?? 15.0) : 15.0

    guard let parsedData = rawJSON.data(using: .utf8),
          let cmdDict = try JSONSerialization.jsonObject(with: parsedData, options: []) as? [String: Any] else {
        throw RunnerError("Command payload must be a valid JSON object")
    }

    let curDict = try sendCommandInternal(bundleId: bundleId, cmdDict: cmdDict, timeoutSec: timeoutSec)
    try printJSONValue(curDict)
    let ok = (curDict["ok"] as? Bool) ?? false
    if !ok { exit(2) }
}

func cmdResize(_ args: [String]) throws {
    guard args.count >= 3,
          let reqW = Double(args[1]),
          let reqH = Double(args[2]),
          reqW > 0, reqH > 0 else {
        throw RunnerError("Usage: resize <bundleId> <width> <height>")
    }
    let bundleId = args[0]
    try validateBundleId(bundleId)
    let sidecar = try requireSidecarScreen()
    let pid = try resolveTargetPID(bundleId: bundleId)
    let snap = try fitAndCenterWindowOnSidecar(
        pid: pid,
        desiredSize: CGSize(width: reqW, height: reqH),
        sidecar: sidecar,
        timeoutSec: 10.0
    )
    let result: [String: Any] = [
        "ok": true,
        "bundleId": bundleId,
        "pid": Int(snap.pid),
        "windowID": Int(snap.windowID),
        "axPosition": ["x": snap.axPosition.x, "y": snap.axPosition.y],
        "axSize": ["width": snap.axSize.width, "height": snap.axSize.height],
        "cgBounds": ["x": snap.cgBounds.origin.x, "y": snap.cgBounds.origin.y,
                     "width": snap.cgBounds.size.width, "height": snap.cgBounds.size.height],
        "onSidecar": true,
    ]
    try printJSONValue(result)
}

func cmdScreenshot(_ args: [String]) throws {
    guard args.count >= 2 else {
        throw RunnerError("Usage: screenshot <bundleId> <outputPngPath>")
    }
    let bundleId = args[0]
    try validateBundleId(bundleId)
    let outPath = (args[1] as NSString).expandingTildeInPath
    let outURL = URL(fileURLWithPath: outPath).standardizedFileURL
    try FileManager.default.createDirectory(at: outURL.deletingLastPathComponent(), withIntermediateDirectories: true)

    let sidecar = try requireSidecarScreen()
    let pid = try resolveTargetPID(bundleId: bundleId)
    guard let win = cgWindowsForPID(pid, sidecar: sidecar, onscreenOnly: true).first else {
        throw RunnerError("No visible onscreen layer-0 CGWindow found for pid=\(pid)")
    }
    guard isRectOnSidecar(win.bounds, sidecar: sidecar) else {
        throw RunnerError("Refusing screenshot: window wid=\(win.id) bounds \(RectJSON(win.bounds)) are not on Sidecar")
    }

    let proc = Process()
    proc.executableURL = URL(fileURLWithPath: "/usr/sbin/screencapture")
    proc.arguments = ["-x", "-o", "-l", String(win.id), outURL.path]
    let stderrPipe = Pipe()
    proc.standardError = stderrPipe
    try proc.run()
    proc.waitUntilExit()

    if proc.terminationStatus != 0 {
        let errText = String(data: stderrPipe.fileHandleForReading.readDataToEndOfFile(), encoding: .utf8) ?? ""
        throw RunnerError("screencapture exited \(proc.terminationStatus): \(errText.trimmingCharacters(in: .whitespacesAndNewlines))")
    }
    let attrs = try FileManager.default.attributesOfItem(atPath: outURL.path)
    let byteSize = (attrs[.size] as? NSNumber)?.int64Value ?? 0
    if byteSize <= 0 {
        throw RunnerError("screencapture produced empty file at \(outURL.path)")
    }

    let result: [String: Any] = [
        "ok": true,
        "bundleId": bundleId,
        "pid": Int(pid),
        "windowID": Int(win.id),
        "path": outURL.path,
        "bytes": byteSize,
        "cgBounds": ["x": win.bounds.origin.x, "y": win.bounds.origin.y,
                     "width": win.bounds.size.width, "height": win.bounds.size.height],
        "onSidecar": true,
    ]
    try printJSONValue(result)
}

func cmdQuit(_ args: [String]) throws {
    guard let bundleId = args.first else {
        throw RunnerError("Usage: quit <bundleId>")
    }
    try validateBundleId(bundleId)
    let dir = automationDir(for: bundleId)
    let initialApp = findRunningApps(bundleId: bundleId).first
    let appBundleURL = initialApp?.bundleURL
    var acked = false
    if let app = initialApp, !app.isTerminated {
        if FileManager.default.fileExists(atPath: dir.appendingPathComponent("ready.json").path) {
            if let resp = try? sendCommandInternal(bundleId: bundleId, cmdDict: ["op": "quit"], timeoutSec: 3.0),
               (resp["ok"] as? Bool) == true {
                acked = true
            }
            let deadline = Date().addingTimeInterval(2.0)
            while Date() < deadline {
                if findRunningApps(bundleId: bundleId).isEmpty { break }
                Thread.sleep(forTimeInterval: 0.05)
            }
        }
    }
    let terminated = terminateMatchingApps(bundleId: bundleId, appBundleURL: appBundleURL)
    let result: [String: Any] = [
        "ok": true,
        "bundleId": bundleId,
        "ackQuit": acked,
        "terminatedPids": terminated.map { Int($0) },
    ]
    try printJSONValue(result)
}

func printUsage() {
    print("""
    Usage: sidecar_runner <screens|launch|wait-ready|cmd|resize|screenshot|quit> [args...]
      screens
      launch <appBundlePath> <bundleId> [--trace] [--log <logPath>]
      wait-ready <bundleId> [timeoutSec]
      cmd <bundleId> <jsonString> [timeoutSec]
      resize <bundleId> <width> <height>
      screenshot <bundleId> <outputPngPath>
      quit <bundleId>
    """)
}

// MARK: - Main Entry

do {
    let args = Array(CommandLine.arguments.dropFirst())
    guard let subcmd = args.first else {
        throw RunnerError("Usage: sidecar_runner <screens|launch|wait-ready|cmd|resize|screenshot|quit> ...")
    }
    let tail = Array(args.dropFirst())
    switch subcmd {
    case "--help", "-h", "help":
        printUsage()
    case "screens":
        try cmdScreens()
    case "launch":
        try cmdLaunch(tail)
    case "wait-ready":
        try cmdWaitReady(tail)
    case "cmd":
        try cmdSend(tail)
    case "resize":
        try cmdResize(tail)
    case "screenshot":
        try cmdScreenshot(tail)
    case "quit":
        try cmdQuit(tail)
    default:
        throw RunnerError("Unknown subcommand '\(subcmd)'")
    }
} catch {
    failWithJSON(error.localizedDescription)
}
