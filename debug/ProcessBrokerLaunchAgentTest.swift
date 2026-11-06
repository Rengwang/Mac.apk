import Foundation

@main
struct ProcessBrokerLaunchAgentTest {
    static func main() throws {
        let helper = URL(fileURLWithPath: "/Applications/Mac.apk.app/Contents/Helpers/macapk-system")
        let data = try ProcessBrokerLaunchAgent.plistData(helperURL: helper)
        guard let plist = try PropertyListSerialization.propertyList(
            from: data, options: [], format: nil) as? [String: Any] else {
            fatalError("plist is not a dictionary")
        }
        precondition(plist["Label"] as? String == ProcessBrokerLaunchAgent.label)
        precondition(plist["Program"] as? String == helper.path)
        precondition(plist["ProgramArguments"] as? [String] == [helper.path, "--app-processes"])
        let services = plist["MachServices"] as? [String: Bool]
        precondition(services?[ProcessBrokerLaunchAgent.label] == true)
        precondition(plist["ThrottleInterval"] as? Int == 1)

        let first = ProcessBrokerLaunchAgent.fingerprint(
            bundleURL: URL(fileURLWithPath: "/Applications/Mac.apk.app"),
            helperURL: helper)
        let second = ProcessBrokerLaunchAgent.fingerprint(
            bundleURL: URL(fileURLWithPath: "/Applications/Mac.apk.app"),
            helperURL: helper)
        precondition(first == second)
        precondition(first.contains(helper.path))
        print("PASS ProcessBrokerLaunchAgentTest")
    }
}
