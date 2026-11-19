import SwiftUI
import StrandDesign
import StrandImport

/// The body of the "Garmin Connect" card in Data Sources (fork feature): the Personal API key (Keychain,
/// never shown back), auto-import, "Sync now" with its status line, and which device exported FIT files
/// claim to come from. `DataSourcesView` supplies the card chrome, title and explanation.
struct GarminConnectSettingsView: View {
    @EnvironmentObject private var model: AppModel
    @ObservedObject private var sync = GarminSync.shared

    @AppStorage(GarminConnect.Prefs.autoSync) private var autoSync = true
    @AppStorage(GarminConnect.Prefs.exportDevice) private var exportDeviceRaw = GarminFitExporter.DeviceIdentity.default.rawValue

    @State private var keyDraft = ""
    @State private var keySaveFailed = false

    var body: some View {
        VStack(alignment: .leading, spacing: NoopMetrics.cardInnerSpacing) {
            keySection
            if sync.hasKey {
                Toggle(isOn: $autoSync) {
                    Text("Import automatically")
                        .font(StrandFont.subhead)
                        .foregroundStyle(StrandPalette.textPrimary)
                }
                .toggleStyle(.switch)
                .tint(StrandPalette.zone1)
                syncSection
            }
            Divider().overlay(StrandPalette.hairline)
            exportSection
        }
    }

    // MARK: Key

    @ViewBuilder private var keySection: some View {
        if sync.hasKey {
            HStack(spacing: NoopMetrics.space3) {
                Label("Key saved", systemImage: "key.fill")
                    .font(StrandFont.subhead)
                    .foregroundStyle(StrandPalette.statusPositive)
                Spacer(minLength: 0)
                Button(role: .destructive) {
                    sync.removeKey()
                } label: {
                    Text("Remove")
                }
                .buttonStyle(NoopButtonStyle(.secondary))
                .disabled(sync.isSyncing)
            }
        } else {
            VStack(alignment: .leading, spacing: 6) {
                Text("Personal API key").strandOverline()
                SecureField("Paste your Garmin Personal API key", text: $keyDraft)
                    .textFieldStyle(.plain)
                    .font(StrandFont.body)
                    .foregroundStyle(StrandPalette.textPrimary)
                    .padding(.horizontal, 12)
                    .padding(.vertical, 9)
                    .background(StrandPalette.surfaceInset, in: RoundedRectangle(cornerRadius: 10, style: .continuous))
                    .overlay(RoundedRectangle(cornerRadius: 10, style: .continuous)
                        .strokeBorder(StrandPalette.hairline, lineWidth: 1))
                    .onSubmit(saveKey)
                    .accessibilityLabel("Personal API key")
            }
            HStack(spacing: NoopMetrics.space3) {
                NoopButton("Save", systemImage: "key.fill", kind: .primary, action: saveKey)
                    .disabled(keyDraft.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty)
                Spacer(minLength: 0)
            }
            if keySaveFailed {
                Text("Couldn't save the key to the Keychain.")
                    .font(StrandFont.footnote)
                    .foregroundStyle(StrandPalette.statusWarning)
            }
            if let keyPage = URL(string: GarminConnect.keyPageURL) {
                Link(destination: keyPage) {
                    Label("Create a Personal API key", systemImage: "arrow.up.right.square")
                        .font(StrandFont.subhead)
                }
                .foregroundStyle(StrandPalette.accent)
            }
        }
    }

    private func saveKey() {
        let key = keyDraft.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !key.isEmpty else { return }
        keySaveFailed = !sync.saveKey(key)
        if !keySaveFailed {
            keyDraft = ""
            Task { await sync.syncNow(model: model) }
        }
    }

    // MARK: Sync

    private var syncSection: some View {
        VStack(alignment: .leading, spacing: NoopMetrics.space2) {
            HStack(spacing: NoopMetrics.space3) {
                Button {
                    Task { await sync.syncNow(model: model) }
                } label: {
                    Label(sync.isSyncing ? "Syncing…" : "Sync now", systemImage: "arrow.triangle.2.circlepath")
                }
                .buttonStyle(NoopButtonStyle(.primary))
                .disabled(sync.isSyncing)
                if sync.isSyncing { ProgressView().controlSize(.small) }
            }
            if let ts = sync.lastAttemptTs, let summary = sync.lastSummary {
                let when = Date(timeIntervalSince1970: TimeInterval(ts)).formatted(.relative(presentation: .named))
                Text("Last sync \(when): \(summary)")
                    .font(StrandFont.footnote)
                    .foregroundStyle(StrandPalette.textSecondary)
                    .fixedSize(horizontal: false, vertical: true)
            }
        }
    }

    // MARK: Export identity

    private var exportSection: some View {
        VStack(alignment: .leading, spacing: 6) {
            Picker(selection: $exportDeviceRaw) {
                Text("Forerunner 945 (recommended: Garmin counts badges)")
                    .tag(GarminFitExporter.DeviceIdentity.forerunner945.rawValue)
                Text("Tacx Training App")
                    .tag(GarminFitExporter.DeviceIdentity.tacxTrainingApp.rawValue)
                Text("NOOP (no Garmin device)")
                    .tag(GarminFitExporter.DeviceIdentity.noop.rawValue)
            } label: {
                Text("Exported files identify as")
                    .font(StrandFont.subhead)
                    .foregroundStyle(StrandPalette.textPrimary)
            }
            .pickerStyle(.menu)
            .tint(StrandPalette.zone1)
            Text("Garmin Connect only credits badges for files from a Garmin device.")
                .font(StrandFont.footnote)
                .foregroundStyle(StrandPalette.textTertiary)
                .fixedSize(horizontal: false, vertical: true)
        }
    }
}
