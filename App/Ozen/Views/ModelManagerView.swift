import SwiftUI
import OzenKit
import OzenPlatform

/// Pick, download, and delete Whisper models. Selecting one restarts the
/// pipeline; the download's progress shows both here and in the main
/// screen's status control, so leaving this screen doesn't hide it.
struct ModelManagerView: View {
    @Bindable var viewModel: LiveCaptionViewModel
    @Environment(\.dynamicTypeSize) private var dynamicTypeSize
    @State private var installed: Set<String> = []
    /// Downloads that were cut off: files on disk, not yet a model.
    @State private var partial: Set<String> = []
    @State private var sizesOnDisk: [String: Int64] = [:]
    @State private var totalOnDisk: Int64 = 0
    /// Room left on the phone, so a model that won't fit says so before
    /// it's picked.
    @State private var freeBytes: Int64?
    @State private var pendingDelete: WhisperModelOption?
    /// A model still to download, picked while captions run: they stop
    /// until it has arrived, which can be minutes, so that is asked first.
    @State private var pendingSwitch: WhisperModelOption?
    @State private var deleteError: String?
    /// Downloads going on with no captions running, by variant.
    @State private var backgroundProgress: [String: Double] = [:]

    private let store = WhisperModelStore()

    var body: some View {
        List {
            phoneSection
            Section {
                ForEach(DeviceModelAdvisor.ordered(for: viewModel.settings.spokenLanguage, on: viewModel.settings.deviceModelCheck)) { option in
                    row(for: option)
                }
            } header: {
                Text(tr("מודלים", "Models"))
            } footer: {
                Text(tr("הורדה נעשית פעם אחת ונשמרת בטלפון (לא מגובה ל‑iCloud). סה\"כ שטח: %1.", "Downloaded once and saved on the phone (not backed up to iCloud). Total space: %1.", args: ["\(Self.format(bytes: totalOnDisk))"]) + (freeBytes.map { " " + tr("פנוי בטלפון: %1.", "Free on the phone: %1.", args: ["\(Self.format(bytes: $0))"]) } ?? ""))
            }
        }
        .accessibilityIdentifier("modelManagerScreen")
        .navigationTitle(tr("מודל Whisper", "Whisper model"))
        .navigationBarTitleDisplayMode(.inline)
        .onAppear(perform: refresh)
        .task { await viewModel.testThisPhone() }
        .task { await followBackgroundDownloads() }
        .onChange(of: viewModel.phase.step) { _, _ in refresh() }
        .onChange(of: viewModel.backupModelProgress == nil) { _, _ in refresh() }
        .confirmationDialog(
            tr("למחוק את %1 מהטלפון?", "Delete %1 from the phone?", args: ["\(pendingDelete?.displayName ?? "")"]),
            isPresented: Binding(get: { pendingDelete != nil }, set: { if !$0 { pendingDelete = nil } }),
            titleVisibility: .visible
        ) {
            Button(tr("מחיקה", "Delete"), role: .destructive) {
                if let option = pendingDelete { delete(option) }
            }
            Button(tr("ביטול", "Cancel"), role: .cancel) {}
        } message: {
            if let option = pendingDelete, option.variant == viewModel.settings.whisperModelVariant {
                // The picked one: the next time it has to load (the next
                // launch, say) it downloads all over again first, which
                // with no Wi-Fi nearby is a long wait nobody expects.
                Text(tr("זה המודל שנבחר. בפעם הבאה שהוא ייטען, למשל כשהאפליקציה תיפתח מחדש, הוא יירד שוב (%1) לפני שיהיו כתוביות.", "This is the selected model. Next time it needs to load, for example when the app reopens, it will download again (%1) before there are captions.", args: ["\(option.sizeLabel)"]))
            } else {
                Text(tr("אפשר להוריד אותו שוב בכל עת.", "It can be downloaded again anytime."))
            }
        }
        .confirmationDialog(
            tr("להוריד את %1 ולעבור אליו?", "Download %1 and switch to it?", args: ["\(pendingSwitch?.displayName ?? "")"]),
            isPresented: Binding(get: { pendingSwitch != nil }, set: { if !$0 { pendingSwitch = nil } }),
            titleVisibility: .visible,
            presenting: pendingSwitch
        ) { option in
            Button(tr("להוריד ולעבור", "Download and switch")) {
                Task { await viewModel.chooseModel(option.variant) }
            }
            Button(tr("ביטול", "Cancel"), role: .cancel) {}
        } message: { option in
            Text(tr("הכתוביות ייעצרו עד שההורדה (%1) תסתיים והמודל ייטען. בלי Wi-Fi ההורדה עשויה לחכות לו.", "Captions will stop until the download (%1) finishes and the model loads. Without Wi‑Fi, the download may wait for it.", args: ["\(option.sizeLabel)"]))
        }
        .alert(tr("המחיקה נכשלה", "Delete failed"), isPresented: Binding(get: { deleteError != nil }, set: { if !$0 { deleteError = nil } })) {
            Button(tr("סגירה", "Close"), role: .cancel) {}
        } message: {
            Text(deleteError ?? "")
        }
    }

    private var phoneSection: some View {
        Section {
            Button {
                Task { await viewModel.testThisPhone() }
            } label: {
                Label(tr("בדיקת הטלפון הזה", "Test this phone"), systemImage: "speedometer")
            }
            .accessibilityIdentifier("modelManagerTestPhone")
            if let check = viewModel.settings.deviceModelCheck {
                if let measured = check.measurement, measured.passes >= DeviceModelAdvisor.minimumMeasuredPasses {
                    Text(tr("נמדד בטלפון הזה: כ-%1 שניות לעדכון עם %2.", "Timed on this phone: about %1 seconds per update with %2.", args: ["\(String(format: "%.1f", measured.averageSeconds))", "\(WhisperModelCatalog.option(for: measured.variant)?.displayName ?? measured.variant)"]))
                        .font(.footnote)
                        .foregroundStyle(.secondary)
                }
                if let chosen = WhisperModelCatalog.option(for: viewModel.settings.whisperModelVariant) {
                    FitLabel(fit: DeviceModelAdvisor.fit(of: chosen, on: check))
                }
                let recommended = viewModel.settings.recommendedWhisperVariant
                if recommended != viewModel.settings.whisperModelVariant,
                   let chosen = WhisperModelCatalog.option(for: viewModel.settings.whisperModelVariant),
                   DeviceModelAdvisor.warns(about: DeviceModelAdvisor.fit(of: chosen, on: check)),
                   let option = WhisperModelCatalog.option(for: recommended) {
                    Button {
                        Task {
                            if await viewModel.modelRowTapped(recommended, isInstalled: installed.contains(recommended)) { pendingSwitch = option }
                        }
                    } label: {
                        Label(tr("המודל הזה מפגר אחרי הדיבור בטלפון הזה. הקישו כדי לעבור ל-%1, %2", "This model falls behind the speech on this phone. Tap to switch to %1, %2", args: ["\(option.displayName)", "\(option.sizeLabel)"]), systemImage: "arrow.triangle.swap")
                    }
                    .accessibilityIdentifier("modelManagerSwitchToLighter")
                }
            }
        } header: {
            Text(tr("בחירת מודל הדיבור", "Choose the speech model"))
        } footer: {
            Text(tr("הרשימה מבוססת על המודלים שידוע שרצים על הדגם הזה של הטלפון. אחרי שהכתוביות רצו כדקה, אפשר לבדוק שוב כדי להוסיף מדידת זמנים אמיתית.", "The list is based on which models are known to run on this model of phone. After captions have run for about a minute, test again to add real timings."))
        }
    }

    // The Hebrew rating's own label wraps onto two lines at the largest
    // accessibility text size, and a plain HStack let the speed rating's
    // dots and label render into that same vertical space, overlapping
    // the wrapped word instead of sitting below it.
    private var ratingsLayout: AnyLayout {
        dynamicTypeSize.isAccessibilitySize
            ? AnyLayout(VStackLayout(alignment: .leading, spacing: 6))
            : AnyLayout(HStackLayout(spacing: 16))
    }

    // Same shape of overlap as the ratings above: at the largest
    // accessibility text size, the size label, icon and status text each
    // need more width than a plain HStack has to give them, so their
    // wrapped lines interleaved instead of stacking cleanly.
    private var statusLayout: AnyLayout {
        dynamicTypeSize.isAccessibilitySize
            ? AnyLayout(VStackLayout(alignment: .leading, spacing: 4))
            : AnyLayout(HStackLayout(spacing: 6))
    }

    // At the largest text sizes the name, the "recommended" badge and the
    // checkmark shared one line, and both texts broke mid-word ("Tur-bo
    // He-bre w"); the badge goes under the name instead.
    private var nameLayout: AnyLayout {
        dynamicTypeSize.isAccessibilitySize
            ? AnyLayout(VStackLayout(alignment: .leading, spacing: 4))
            : AnyLayout(HStackLayout(spacing: 8))
    }

    private func row(for option: WhisperModelOption) -> some View {
        let isSelected = option.variant == viewModel.settings.whisperModelVariant
        let isInstalled = installed.contains(option.variant)
        let isPartial = partial.contains(option.variant)
        let downloadProgress = downloadProgress(for: option)
        // Picking it would stop captions that work for a download with no
        // room to land; the row says why instead.
        let wontFit = !isInstalled && !isPartial && !isSelected
            && StorageSpaceGate.shortfallMegabytes(downloadMegabytes: option.installMegabytes, availableBytes: freeBytes) != nil

        return Button {
            Task {
                if await viewModel.modelRowTapped(option.variant, isInstalled: isInstalled) { pendingSwitch = option }
            }
        } label: {
            VStack(alignment: .leading, spacing: 6) {
                HStack(spacing: 8) {
                    nameLayout {
                        Text(option.displayName)
                            .font(.body.weight(isSelected ? .semibold : .regular))
                        if option.variant == viewModel.settings.recommendedWhisperVariant {
                            RecommendedBadge(forThisPhone: viewModel.settings.deviceModelCheck != nil, language: viewModel.settings.spokenLanguage)
                        }
                    }
                    Spacer()
                    if isSelected {
                        Image(systemName: "checkmark.circle.fill")
                            .accessibilityHidden(true)
                            .foregroundStyle(.readable(.green))
                    }
                }

                if let check = viewModel.settings.deviceModelCheck {
                    FitLabel(fit: DeviceModelAdvisor.fit(of: option, on: check))
                }

                ratingsLayout {
                    // Rated for Hebrew only: no rating says how well any
                    // of them does another language.
                    if viewModel.settings.spokenLanguage == .hebrew {
                        RatingDots(label: tr("עברית", "Hebrew"), value: option.hebrewQuality)
                    }
                    RatingDots(label: tr("מהירות", "Speed"), value: option.speed)
                }

                Text(localizedNote(for: option))
                    .font(.caption)
                    .foregroundStyle(.secondary)
                    .fixedSize(horizontal: false, vertical: true)

                statusLayout {
                    Text(option.sizeLabel)
                    if let progress = downloadProgress {
                        Text(tr("· מוריד %1%", "· downloading %1%", args: ["\(Int((progress * 100).rounded()))"]))
                    } else if isInstalled {
                        Image(systemName: "checkmark")
                            .accessibilityHidden(true)
                        Text(tr("מותקן", "Installed"))
                        if let size = sizesOnDisk[option.variant] {
                            Text(tr("· %1 בפועל", "· %1 actual", args: ["\(Self.format(bytes: size))"]))
                        }
                    } else if isPartial {
                        Image(systemName: "exclamationmark.arrow.circlepath")
                            .accessibilityHidden(true)
                        Text(tr("ההורדה נקטעה · תימשך מאיפה שנעצרה בבחירה", "Download interrupted · will resume from where it stopped when selected"))
                        if let size = sizesOnDisk[option.variant] {
                            Text(tr("· %1 כבר ירדו", "· %1 already downloaded", args: ["\(Self.format(bytes: size))"]))
                        }
                    } else if StorageSpaceGate.shortfallMegabytes(downloadMegabytes: option.installMegabytes, availableBytes: freeBytes) != nil {
                        Image(systemName: "externaldrive.badge.exclamationmark")
                            .accessibilityHidden(true)
                        Text(tr("אין מספיק מקום בטלפון", "Not enough room on the phone"))
                    } else {
                        Image(systemName: "icloud.and.arrow.down")
                            .accessibilityHidden(true)
                        Text(tr("יורד בבחירה", "Downloads when selected"))
                    }
                }
                .font(.caption)
                .foregroundStyle(.secondary)

                if let progress = downloadProgress {
                    ProgressView(value: progress)
                    if viewModel.phase.preparationProgress?.detail == option.variant, let seconds = viewModel.pipeline.downloadSecondsRemaining {
                        Text(PhasePresentation.remainingText(seconds: seconds))
                            .font(.caption)
                            .foregroundStyle(.secondary)
                    }
                }
            }
            .contentShape(Rectangle())
        }
        .foregroundStyle(.primary)
        .disabled(wontFit)
        .swipeActions(edge: .trailing, allowsFullSwipe: false) {
            if (isInstalled || isPartial) && downloadProgress == nil && !(isSelected && (viewModel.isListening || viewModel.phase == .paused || viewModel.phase.isTransitioning)) {
                Button(role: .destructive) {
                    pendingDelete = option
                } label: {
                    Label(tr("מחיקה", "Delete"), systemImage: "trash")
                }
            }
        }
        .accessibilityElement(children: .combine)
        .accessibilityAddTraits(isSelected ? .isSelected : [])
    }

    private func downloadProgress(for option: WhisperModelOption) -> Double? {
        // The phone's backup for the home computer downloads outside the
        // captions: without this its row said "Download interrupted" and
        // offered Delete while the download was still writing into it.
        if option.variant == viewModel.backupModelVariant, let backup = viewModel.backupModelProgress {
            return backup
        }
        guard let progress = viewModel.phase.preparationProgress,
              progress.stage == .downloadingModel,
              progress.detail == option.variant
        else { return backgroundProgress[option.variant] }
        return progress.fraction ?? 0
    }

    private func localizedNote(for option: WhisperModelOption) -> String {
        option.localizedNote
    }

    private func refresh() {
        installed = Set(store.installedVariants())
        // Skip the variant being downloaded right now: mid-download is
        // expected to be incomplete and already shows its progress.
        let downloading = viewModel.phase.preparationProgress.flatMap { $0.stage == .downloadingModel ? $0.detail : nil }
        partial = Set(WhisperModelCatalog.options.map(\.variant).filter { variant in
            variant != downloading && backgroundProgress[variant] == nil && store.state(of: variant) == .partial
        })
        sizesOnDisk = Dictionary(uniqueKeysWithValues: installed.union(partial).map { ($0, store.sizeOnDisk(of: $0)) })
        totalOnDisk = store.totalSizeOnDisk()
        freeBytes = DeviceStorage.availableBytes()
    }

    /// A download goes on after the captions that started it stop, so
    /// starting them again can join it. Its row said "Download
    /// interrupted" all the while; it shows its progress instead.
    private func followBackgroundDownloads() async {
        while !Task.isCancelled {
            var running: [String: Double] = [:]
            for option in WhisperModelCatalog.options {
                if let fraction = await store.downloadProgress(variant: option.variant) {
                    running[option.variant] = fraction
                }
            }
            if running != backgroundProgress {
                let changed = Set(running.keys) != Set(backgroundProgress.keys)
                backgroundProgress = running
                if changed { refresh() }
            }
            try? await Task.sleep(for: .seconds(1))
        }
    }

    private func delete(_ option: WhisperModelOption) {
        Task {
            do {
                try await store.delete(variant: option.variant)
            } catch {
                deleteError = error.localizedDescription
            }
            refresh()
        }
    }

    static func format(bytes: Int64) -> String {
        ByteSize.text(bytes)
    }
}

private struct RatingDots: View {
    let label: String
    let value: Int

    var body: some View {
        HStack(spacing: 4) {
            Text(label)
                .font(.caption)
                .foregroundStyle(.secondary)
            HStack(spacing: 2) {
                ForEach(1...5, id: \.self) { index in
                    Circle()
                        .fill(index <= value ? Color.accentColor : Color.secondary.opacity(0.25))
                        .frame(width: 7, height: 7)
                }
            }
        }
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(tr("%1 %2 מתוך 5", "%1 %2 out of 5", args: ["\(label)", "\(value)"]))
    }
}
