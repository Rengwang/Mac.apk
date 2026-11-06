import Foundation
import ServiceManagement

/// Launchd metadata shared by the packaged SMAppService agent and the local
/// `launchctl bootstrap` fallback used by ad-hoc development installs.
enum ProcessBrokerLaunchAgent {
    static let label = "org.macapk.processes"
    static let plistName = label + ".plist"
    static let helperComponent = "Contents/Helpers/macapk-system"

    static func plistData(helperURL: URL) throws -> Data {
        let object: [String: Any] = [
            "Label": label,
            "Program": helperURL.path,
            "ProgramArguments": [helperURL.path, "--app-processes"],
            "MachServices": [label: true],
            "ProcessType": "Background",
            "ThrottleInterval": 1,
        ]
        return try PropertyListSerialization.data(
            fromPropertyList: object, format: .xml, options: 0)
    }

    /// Changes whenever the containing app or helper changes. The fallback
    /// keeps one stable launchd label, so this permits a safe migration while
    /// avoiding a broker restart on every launcher appearance.
    static func fingerprint(bundleURL: URL, helperURL: URL) -> String {
        let attributes = try? FileManager.default.attributesOfItem(atPath: helperURL.path)
        let size = (attributes?[.size] as? NSNumber)?.int64Value ?? -1
        let modified = (attributes?[.modificationDate] as? Date)?.timeIntervalSince1970 ?? -1
        let buildID = (try? String(
            contentsOf: bundleURL.appendingPathComponent("Contents/Resources/build-id.txt"),
            encoding: .utf8))?.trimmingCharacters(in: .whitespacesAndNewlines) ?? "unknown"
        return "v1\n\(bundleURL.path)\n\(helperURL.path)\n\(size)\n\(modified)\n\(buildID)\n"
    }
}

enum ProcessBrokerRegistrationError: LocalizedError {
    case helperMissing(String)
    case requiresApproval
    case launchd(String)

    var errorDescription: String? {
        switch self {
        case .helperMissing(let path):
            return "process broker helper is missing at \(path)"
        case .requiresApproval:
            return "process broker is waiting for approval in System Settings > General > Login Items"
        case .launchd(let detail):
            return "process broker launchd registration failed: \(detail)"
        }
    }
}

struct ProcessBrokerRegistrationResult {
    let detail: String
}

/// Prefer the supported SMAppService agent. A locally built Mac.apk is
/// ad-hoc signed and current macOS releases can reject its agent with security
/// error -67028 even though the helper and plist are valid. In that case load
/// the same helper under a user LaunchAgent so manifest-declared Android
/// sibling processes remain available instead of silently disabling them.
enum ProcessBrokerRegistrar {
    private struct CommandResult {
        let status: Int32
        let output: String
    }

    static func ensure(
        bundle: Bundle = .main,
        home: URL = FileManager.default.homeDirectoryForCurrentUser
    ) throws -> ProcessBrokerRegistrationResult {
        let bundleURL = bundle.bundleURL
        let helperURL = bundleURL.appendingPathComponent(ProcessBrokerLaunchAgent.helperComponent)
        guard FileManager.default.isExecutableFile(atPath: helperURL.path) else {
            throw ProcessBrokerRegistrationError.helperMissing(helperURL.path)
        }

        let stateDirectory = home
            .appendingPathComponent("Library/Application Support/Mac.apk/launchd", isDirectory: true)
        let plistURL = stateDirectory.appendingPathComponent(ProcessBrokerLaunchAgent.plistName)
        let markerURL = stateDirectory.appendingPathComponent("processes.state")
        let fingerprint = ProcessBrokerLaunchAgent.fingerprint(
            bundleURL: bundleURL, helperURL: helperURL)

        if fallbackIsCurrent(
            plistURL: plistURL, markerURL: markerURL, fingerprint: fingerprint) {
            return ProcessBrokerRegistrationResult(
                detail: "process broker already loaded from \(plistURL.path)")
        }

        // Once this installation has chosen the local fallback, keep its
        // lifecycle independent of ServiceManagement approval changes. This
        // branch also handles a changed app/helper fingerprint by replacing
        // the same owned job with the newly built helper.
        if FileManager.default.fileExists(atPath: markerURL.path) {
            try installFallback(
                helperURL: helperURL,
                plistURL: plistURL,
                markerURL: markerURL,
                fingerprint: fingerprint)
            return ProcessBrokerRegistrationResult(
                detail: "process broker refreshed through launchctl at \(plistURL.path)")
        }

        let item = SMAppService.agent(plistName: ProcessBrokerLaunchAgent.plistName)
        if item.status == .enabled && jobIsLoaded {
            return ProcessBrokerRegistrationResult(
                detail: "process broker registered through SMAppService")
        }
        if item.status == .requiresApproval {
            throw ProcessBrokerRegistrationError.requiresApproval
        }

        var serviceError: Error?
        if item.status != .notFound {
            do {
                try item.register()
                if item.status == .enabled && jobIsLoaded {
                    return ProcessBrokerRegistrationResult(
                        detail: "process broker registered through SMAppService")
                }
                if item.status == .requiresApproval {
                    throw ProcessBrokerRegistrationError.requiresApproval
                }
                let reason = item.status == .enabled
                    ? "SMAppService reported enabled but launchd has no \(jobTarget)"
                    : "SMAppService returned status \(item.status.rawValue) after register()"
                serviceError = ProcessBrokerRegistrationError.launchd(reason)
            } catch {
                serviceError = error
            }
        }

        try installFallback(
            helperURL: helperURL,
            plistURL: plistURL,
            markerURL: markerURL,
            fingerprint: fingerprint)

        if let serviceError {
            return ProcessBrokerRegistrationResult(
                detail: "SMAppService was unavailable (\(serviceError)); " +
                    "process broker loaded through launchctl at \(plistURL.path)")
        }
        return ProcessBrokerRegistrationResult(
            detail: "process broker loaded through launchctl at \(plistURL.path)")
    }

    private static func fallbackIsCurrent(
        plistURL: URL, markerURL: URL, fingerprint: String
    ) -> Bool {
        guard let marker = try? String(contentsOf: markerURL, encoding: .utf8),
              marker == fingerprint else { return false }
        let result = launchctl(["print", jobTarget])
        return result.status == 0 && result.output.contains("path = \(plistURL.path)")
    }

    private static func installFallback(
        helperURL: URL,
        plistURL: URL,
        markerURL: URL,
        fingerprint: String
    ) throws {
        try FileManager.default.createDirectory(
            at: plistURL.deletingLastPathComponent(),
            withIntermediateDirectories: true)
        try ProcessBrokerLaunchAgent.plistData(helperURL: helperURL)
            .write(to: plistURL, options: .atomic)

        // The same label may still point at an earlier app path. Stop only the
        // exact job this code owns before loading the current helper.
        if launchctl(["print", jobTarget]).status == 0 {
            _ = launchctl(["bootout", jobTarget])
        }

        var load = launchctl(["bootstrap", domainTarget, plistURL.path])
        if load.status != 0 {
            // A concurrent registration may have won after the first print.
            // Remove that exact label once and retry the same immutable plist.
            _ = launchctl(["bootout", jobTarget])
            load = launchctl(["bootstrap", domainTarget, plistURL.path])
        }
        guard load.status == 0 else {
            throw ProcessBrokerRegistrationError.launchd(load.output)
        }

        let loaded = launchctl(["print", jobTarget])
        guard loaded.status == 0,
              loaded.output.contains("path = \(plistURL.path)") else {
            throw ProcessBrokerRegistrationError.launchd(
                "launchctl did not retain \(jobTarget) at \(plistURL.path)")
        }
        try fingerprint.write(to: markerURL, atomically: true, encoding: .utf8)
    }

    private static var domainTarget: String { "gui/\(getuid())" }
    private static var jobTarget: String {
        "\(domainTarget)/\(ProcessBrokerLaunchAgent.label)"
    }

    /// SMAppService can report a stale `.enabled` background-item record even
    /// though no launchd job exists for it. Treat the job as the source of
    /// truth so the fallback can repair that state instead of silently
    /// launching Android components in one process.
    private static var jobIsLoaded: Bool {
        launchctl(["print", jobTarget]).status == 0
    }

    private static func launchctl(_ arguments: [String]) -> CommandResult {
        let process = Process()
        let pipe = Pipe()
        process.executableURL = URL(fileURLWithPath: "/bin/launchctl")
        process.arguments = arguments
        process.standardOutput = pipe
        process.standardError = pipe
        do {
            try process.run()
            let data = pipe.fileHandleForReading.readDataToEndOfFile()
            process.waitUntilExit()
            return CommandResult(
                status: process.terminationStatus,
                output: String(data: data, encoding: .utf8) ?? "")
        } catch {
            return CommandResult(status: -1, output: error.localizedDescription)
        }
    }
}
