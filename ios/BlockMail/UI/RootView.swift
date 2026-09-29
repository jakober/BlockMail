import SwiftUI
import Combine

/// Wurzelansicht (Port von `MainActivity.setContent` + NavHost-Routen und
/// `TwoPaneScreen`): Posteingang, Navigation, Verfassen-Fenster als Sheet,
/// „PDF erstellen“, Willkommen beim ersten Start, Anforderungen von außen
/// (AppRouter) und Lebenszyklus (Echtzeit-Push, Hintergrundaufgaben).
struct RootView: View {
    @Environment(Prefs.self) private var prefs
    @Environment(MailRepository.self) private var repo
    @Environment(\.scenePhase) private var scenePhase

    private var nav: AppNav { AppNav.shared }
    private var router: AppRouter { AppRouter.shared }

    @State private var didStart = false
    @State private var isWide = false

    var body: some View {
        GeometryReader { geo in
            let wide = geo.size.width >= 600
            Group {
                if wide {
                    InboxTwoPaneView(totalWidth: geo.size.width)
                } else {
                    NavigationStack(path: Bindable(nav).path) {
                        InboxScreen(onOpen: { route in nav.push(route) })
                            .navigationDestination(for: Route.self) { route in
                                RouteDestination(route: route)
                            }
                    }
                }
            }
            .onAppear { isWide = wide }
            .onChange(of: wide) { _, w in isWide = w }
        }
        .environment(AppNav.shared)
        .blockMailTheme()
        .overlay {
            if InboxTour.shared.active {
                InboxTourOverlay().blockMailTheme()
            }
        }
        .sheet(item: Bindable(nav).compose) { req in
            ComposeScreen(request: req)
                .modifier(RootEnvironment())
        }
        .sheet(isPresented: Bindable(nav).showNewPdf) {
            NewPdfDialog()
                .modifier(RootEnvironment())
        }
        .onAppear { startOnce() }
        .onChange(of: scenePhase) { _, phase in handleScenePhase(phase) }
        .onChange(of: prefs.isConfigured) { old, new in
            // Nach der ersten Einrichtung einmalig die Live-Tour starten
            if !old && new && !prefs.tourShown {
                InboxTour.start()
            }
        }
        // Anforderungen von außen (Benachrichtigung, Widget, Links, Quick Actions)
        .onChange(of: router.openMail?.uid) { _, _ in handleRouterOpenMail() }
        .onChange(of: router.compose) { _, _ in handleRouterCompose() }
        .onChange(of: router.openDocument) { _, _ in handleRouterDocument() }
        .onChange(of: router.newPdf) { _, _ in handleRouterNewPdf() }
        // Einstellungen → „Einführung ansehen“
        .onReceive(NotificationCenter.default.publisher(for: .settingsRequestTour)) { _ in
            InboxTour.start()
        }
    }

    // MARK: Start & Lebenszyklus

    private func startOnce() {
        guard !didStart else { return }
        didStart = true
        // Erster Start: Willkommens-Bildschirm statt leerem Posteingang
        if !prefs.isConfigured && !prefs.welcomeShown {
            nav.push(.welcome)
        }
        handleRouterOpenMail()
        handleRouterCompose()
        handleRouterDocument()
        handleRouterNewPdf()
        if scenePhase == .active { handleScenePhase(.active) }
    }

    private func handleScenePhase(_ phase: ScenePhase) {
        switch phase {
        case .active:
            PushService.shared.start()
            Task { await MailRepository.shared.refresh() }
            BackgroundScheduler.scheduleRefresh()
            BackgroundScheduler.scheduleMaintenance()
        case .background:
            PushService.shared.stop()
            Task { await MailSessionPool.shared.closeAll() }
        default:
            break
        }
    }

    // MARK: AppRouter

    private func handleRouterOpenMail() {
        guard let req = router.openMail else { return }
        router.openMail = nil
        let route = Route.detail(uid: req.uid, account: req.account, folder: nil, fallback: nil)
        if isWide {
            nav.path.removeAll()
            nav.selected = route
        } else {
            nav.push(route)
        }
    }

    private func handleRouterCompose() {
        guard let prefill = router.compose else { return }
        router.compose = nil
        nav.compose = ComposeRequest(prefill: prefill)
    }

    private func handleRouterDocument() {
        guard let url = router.openDocument else { return }
        router.openDocument = nil
        if DocumentEditing.openExternal(url) {
            if isWide { nav.selected = nil }
            nav.push(.editor)
        }
    }

    private func handleRouterNewPdf() {
        guard router.newPdf else { return }
        router.newPdf = false
        nav.showNewPdf = true
    }
}

/// Umgebung für Sheets (Theme + Kern-Objekte explizit weiterreichen).
struct RootEnvironment: ViewModifier {
    func body(content: Content) -> some View {
        content
            .environment(Prefs.shared)
            .environment(MailRepository.shared)
            .environment(AppRouter.shared)
            .environment(AppNav.shared)
            .blockMailTheme()
    }
}

/// Ziel-Bildschirm einer Route (Port der NavHost-`composable`-Einträge).
struct RouteDestination: View {
    let route: Route

    var body: some View {
        switch route {
        case let .detail(uid, account, folder, fallback):
            DetailScreen(uid: uid, account: account, folder: folder, fallback: fallback)
        case .settings:
            SettingsScreen()
        case .setup:
            SetupWizardScreen()
        case .welcome:
            WelcomeScreen()
        case .stats:
            StatsScreen()
        case .attachments:
            AttachmentsScreen()
        case .editor:
            DocumentEditorScreen()
        }
    }
}

/// Zweispaltige Ansicht (Port von `TwoPaneScreen`): links die Mail-Liste,
/// rechts die Detailansicht bzw. weitere Bildschirme. Die Trennlinie lässt
/// sich verschieben; die Kachelspalten links passen sich automatisch an.
struct InboxTwoPaneView: View {
    let totalWidth: CGFloat

    @Environment(\.palette) private var palette
    @SceneStorage("inbox_split") private var split: Double = 0.42
    @State private var dragStart: Double? = nil

    private var nav: AppNav { AppNav.shared }

    var body: some View {
        let leftWidth = max(260, CGFloat(split) * totalWidth)
        HStack(spacing: 0) {
            NavigationStack {
                InboxScreen(onOpen: { route in select(route) })
            }
            .frame(width: leftWidth)
            divider
            NavigationStack(path: Bindable(nav).path) {
                rightRoot
                    .navigationDestination(for: Route.self) { route in
                        RouteDestination(route: route)
                    }
            }
            .frame(maxWidth: .infinity)
        }
        .background(palette.background.ignoresSafeArea())
    }

    private func select(_ route: Route) {
        if case .detail = route {
            nav.path.removeAll()
            nav.selected = route
        } else {
            nav.push(route)
        }
    }

    /// Verschiebbarer Griff mit dünner Linie + Anfasser.
    private var divider: some View {
        ZStack {
            palette.background
            Rectangle()
                .fill(palette.onSurfaceVariant.opacity(0.45))
                .frame(width: 1)
            RoundedRectangle(cornerRadius: 2)
                .fill(palette.onSurfaceVariant.opacity(0.7))
                .frame(width: 4, height: 48)
        }
        .frame(width: 14)
        .contentShape(Rectangle())
        .gesture(
            DragGesture(minimumDistance: 1, coordinateSpace: .global)
                .onChanged { v in
                    let start = dragStart ?? split
                    if dragStart == nil { dragStart = split }
                    let next = start + Double(v.translation.width / max(totalWidth, 1))
                    split = min(0.72, max(0.22, next))
                }
                .onEnded { _ in dragStart = nil }
        )
        .ignoresSafeArea(edges: .vertical)
    }

    @ViewBuilder
    private var rightRoot: some View {
        if let route = nav.selected, case let .detail(uid, account, folder, fallback) = route {
            // id: Beim Mail-Wechsel die Detailansicht komplett neu aufbauen
            DetailScreen(uid: uid, account: account, folder: folder, fallback: fallback)
                .id(route)
        } else {
            VStack(spacing: 12) {
                Image(systemName: "envelope")
                    .font(.system(size: 52))
                    .foregroundStyle(palette.onSurfaceVariant)
                Text(L("pane_select_mail"))
                    .font(.body)
                    .foregroundStyle(palette.onSurfaceVariant)
            }
            .frame(maxWidth: .infinity, maxHeight: .infinity)
            .background(palette.background.ignoresSafeArea())
        }
    }
}
