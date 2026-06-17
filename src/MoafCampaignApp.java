import javafx.application.Application;
import javafx.application.Platform;
import javafx.beans.property.SimpleStringProperty;
import javafx.beans.property.StringProperty;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.input.MouseButton;
import javafx.scene.layout.*;
import javafx.scene.paint.Color;
import javafx.stage.Modality;
import javafx.stage.Stage;

import java.io.*;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Instant;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.CompletableFuture;

/**
 * Memories of a Few — Campaign Index (v2)
 *
 * Architecture summary:
 *   • Pure JavaFX desktop app (no WebView, no embedded HTTP server).
 *   • Bundled JSON resources seed the campaign on first run.
 *   • A locally-stored campaign-master.json is the GM's working file.
 *   • Each Entry has a per-field reveal map so the GM controls exactly which
 *     fields players see; unrevealed fields render as [REDACTED] panels.
 *   • A separate notes.json in the GitHub repo holds shared session notes,
 *     edited by an authorized note-taker (separate PIN), read by everyone else.
 *   • Faction / NPC / Map detail views are styled to evoke the original HTML
 *     dossier aesthetic: rounded panels, per-faction accent colors, quote
 *     callouts with colored side bars, field grids with monospace eyebrow
 *     labels.
 */
public final class MoafCampaignApp extends Application {

    // ── Locations ────────────────────────────────────────────────────────────
    private static final Path INSTALL_DIR = locateInstallDir();
    private static final Path DATA_DIR    = locateDataDir();
    private static final Path MASTER_FILE = DATA_DIR.resolve("campaign-master.json");
    private static final Path CONFIG_FILE = DATA_DIR.resolve("github-config.json");
    private static final Path NOTES_FILE  = DATA_DIR.resolve("notes-cache.json");

    // ── Theme palette ────────────────────────────────────────────────────────
    private static final String BG       = "#040707";
    private static final String BG_DEEP  = "#020403";
    private static final String PANEL    = "#0a1310";
    private static final String PANEL2   = "#0e1a16";
    private static final String INK      = "#d8f7ec";
    private static final String INK_DIM  = "#a9c9bd";
    private static final String MUTED    = "#88a99f";
    private static final String DIM      = "#58756c";
    private static final String LINE     = "rgba(135,255,213,0.18)";
    private static final String LINE_S   = "#234a3d";
    private static final String GREEN    = "#7fffc5";
    private static final String GREEN2   = "#21f39b";
    private static final String GOLD     = "#ffd36b";
    private static final String RED      = "#ff4267";
    private static final String FONT     = "Consolas, 'Courier New', monospace";

    // Per-faction accent colors taken from the original HTML
    private static final Map<String, String[]> FACTION_PALETTE = Map.of(
            "bci",         new String[]{"#8bffb1", "#ff4f6d"},
            "cotu",        new String[]{"#ffcc66", "#ff4f6d"},
            "truth",       new String[]{"#b982ff", "#59e3ff"},
            "redarchive",  new String[]{"#ff4c5f", "#ffd27d"},
            "continuance", new String[]{"#74e8ff", "#d9f7ff"},
            "greymarket",  new String[]{"#ff5a5f", "#ffd166"},
            "civic",       new String[]{"#8fc7ff", "#ffd27d"});

    private static final Map<String, String> NPC_FACTION_COLOR = Map.ofEntries(
            Map.entry("BCI",            "#7fffc5"),
            Map.entry("CotU",           "#ff4267"),
            Map.entry("Truth Division", "#9b76ff"),
            Map.entry("Red Archive",    "#ff334e"),
            Map.entry("Continuance",    "#6ad8ff"),
            Map.entry("Grey Market",    "#ffd36b"),
            Map.entry("RedWire",        "#ff3b3b"),
            Map.entry("Politics",       "#f5f1d8"),
            Map.entry("Media",          "#d8f7ec"),
            Map.entry("Licensing",      "#7fffc5"),
            Map.entry("Player Thread",  "#ffd36b"),
            Map.entry("Arasaka",        "#d11f2f"),
            Map.entry("Session One",    "#ffffff"));

    // Which fields are GM-only by default when seeding new entries
    private static final Set<String> DEFAULT_GM_ONLY = Set.of(
            "Truth", "The Truth", "Secret", "Faction Secrets", "DM Notes", "First Encounter");

    // ── State ────────────────────────────────────────────────────────────────
    private final CampaignData data = new CampaignData();
    private final GitHubConfig ghConfig = new GitHubConfig();
    private final SessionNotes notes = new SessionNotes();
    private final StringProperty currentTab = new SimpleStringProperty("factions");
    private boolean adminMode = false;
    private boolean noteTakerMode = false;

    private VBox    sidebarTabs;
    private Label   pageTitleLabel;
    private Label   pageSubtitleLabel;
    private Label   statusLabel;
    private FlowPane cardGrid;
    private VBox    notesView;
    private Button  adminButton;
    private Button  noteTakerButton;
    private HBox    adminBar;
    private Button  newEntryButton;
    private Button  manageButton;
    private Button  newSessionButton;
    private Stage   primaryStage;
    private ScrollPane mainScroll;

    // ── Entry point ──────────────────────────────────────────────────────────
    public static void main(String[] args) { Launcher.main(args); }

    public static final class Launcher {
        public static void main(String[] args) { Application.launch(MoafCampaignApp.class, args); }
    }

    @Override
    public void start(Stage stage) {
        this.primaryStage = stage;
        try {
            Files.createDirectories(DATA_DIR);
            loadOrSeedMaster();
            loadConfig();
            loadNotesCache();
        } catch (Exception e) {
            logError(e);
        }

        BorderPane root = new BorderPane();
        root.setStyle("-fx-background-color: " + BG_DEEP + ";");

        root.setLeft(buildSidebar());

        VBox mainColumn = new VBox(buildAdminBar(), buildHeader());
        BorderPane mainArea = new BorderPane();
        mainArea.setTop(mainColumn);
        mainArea.setCenter(buildContentArea());
        mainArea.setStyle("-fx-background-color: " + BG + ";");
        root.setCenter(mainArea);

        Scene scene = new Scene(root, 1320, 860);
        stage.setScene(scene);
        stage.setTitle("MOAF Campaign Index");
        stage.setMinWidth(960);
        stage.setMinHeight(640);

        currentTab.addListener((obs, oldVal, newVal) -> refresh());
        refresh();

        stage.setOnCloseRequest(e -> shutdown());
        stage.show();

        if (!ghConfig.owner.isBlank() && !ghConfig.repo.isBlank()) {
            CompletableFuture.runAsync(() -> { syncRemote(true); syncNotesPull(true); });
        }
    }

    @Override public void stop() { shutdown(); }
    private static void shutdown() { Platform.exit(); System.exit(0); }

    // ────────────────────────────────────────────────────────────────────────
    //  SIDEBAR
    // ────────────────────────────────────────────────────────────────────────
    private VBox buildSidebar() {
        VBox side = new VBox();
        side.setPrefWidth(280);
        side.setStyle("-fx-background-color: " + PANEL + ";" +
                      "-fx-border-color: transparent " + LINE_S + " transparent transparent;" +
                      "-fx-border-width: 0 1 0 0;");

        Label title = new Label(data.campaignTitle);
        title.setStyle(textStyle(18, GREEN, true));
        title.setWrapText(true);
        Label sub = new Label(data.subtitle);
        sub.setStyle(textStyle(10, MUTED, false) + " -fx-letter-spacing: 0.12em;");
        sub.setWrapText(true);

        Label signal = new Label("● SIGNAL LIVE");
        signal.setStyle(textStyle(9, GREEN2, false) + " -fx-letter-spacing: 0.16em;");

        VBox brand = new VBox(8, title, sub, signal);
        brand.setPadding(new Insets(22, 18, 20, 20));
        brand.setStyle("-fx-border-color: transparent transparent " + LINE_S + " transparent;" +
                       "-fx-border-width: 0 0 1 0;");

        sidebarTabs = new VBox(2);
        sidebarTabs.setPadding(new Insets(12, 0, 12, 0));
        ScrollPane tabsScroll = new ScrollPane(sidebarTabs);
        tabsScroll.setFitToWidth(true);
        tabsScroll.setStyle("-fx-background-color: transparent; -fx-background: transparent;" +
                            " -fx-border-color: transparent;");
        VBox.setVgrow(tabsScroll, Priority.ALWAYS);

        VBox foot = new VBox(6);
        foot.setPadding(new Insets(10, 12, 14, 12));
        foot.setStyle("-fx-border-color: " + LINE_S + " transparent transparent transparent;" +
                      "-fx-border-width: 1 0 0 0;");

        manageButton = pillBtn("▸ MANAGE CAMPAIGN");
        manageButton.setOnAction(e -> openManager());

        newSessionButton = pillBtn("▸ NEW SESSION TAB");
        newSessionButton.setOnAction(e -> openNewSessionDialog());

        Button updates = pillBtn("▸ CHECK FOR UPDATES");
        updates.setOnAction(e -> CompletableFuture.runAsync(() -> { syncRemote(false); syncNotesPull(false); }));

        adminButton    = pillBtn("▸ ADMIN LOGIN");
        adminButton.setOnAction(e -> adminAction());

        noteTakerButton = pillBtn("▸ NOTE-TAKER LOGIN");
        noteTakerButton.setOnAction(e -> noteTakerAction());

        foot.getChildren().addAll(manageButton, newSessionButton, updates, adminButton, noteTakerButton);
        manageButton.setVisible(false);    manageButton.setManaged(false);
        newSessionButton.setVisible(false); newSessionButton.setManaged(false);

        side.getChildren().addAll(brand, tabsScroll, foot);
        return side;
    }

    private void renderSidebarTabs() {
        sidebarTabs.getChildren().clear();
        List<Tab> visible = new ArrayList<>();
        for (Tab t : data.tabs) if (adminMode || t.visible) visible.add(t);
        visible.sort(Comparator.comparingInt(t -> t.order));
        for (Tab t : visible) {
            boolean active = t.id.equals(currentTab.get());
            Button btn = new Button((t.icon == null ? "□" : t.icon) + "   " + t.title.toUpperCase());
            btn.setMaxWidth(Double.MAX_VALUE);
            btn.setAlignment(Pos.CENTER_LEFT);
            btn.setStyle(tabButtonStyle(active, false));
            btn.setOnMouseEntered(e -> { if (!active) btn.setStyle(tabButtonStyle(false, true)); });
            btn.setOnMouseExited(e -> { if (!active) btn.setStyle(tabButtonStyle(false, false)); });
            btn.setOnAction(e -> currentTab.set(t.id));
            sidebarTabs.getChildren().add(btn);
        }
    }

    // ────────────────────────────────────────────────────────────────────────
    //  TOP BARS
    // ────────────────────────────────────────────────────────────────────────
    private HBox buildAdminBar() {
        Label l = new Label("◆ ADMIN MODE // MASTER DATA IS LOCAL // PUBLISH SENDS ONLY REVEALED CONTENT");
        l.setStyle(textStyle(10, "#ffb6b0", false) + " -fx-letter-spacing: 0.16em;");
        adminBar = new HBox(l);
        adminBar.setPadding(new Insets(7, 24, 7, 24));
        adminBar.setStyle("-fx-background-color: #2a0c0f;" +
                          "-fx-border-color: transparent transparent #7f2731 transparent;" +
                          "-fx-border-width: 0 0 1 0;");
        adminBar.setVisible(false); adminBar.setManaged(false);
        return adminBar;
    }

    private HBox buildHeader() {
        Label eyebrow = new Label("UNIFIED DOSSIER ACCESS // MERIDIAN SPIRE");
        eyebrow.setStyle(textStyle(10, GREEN, false) + " -fx-letter-spacing: 0.22em;");
        pageTitleLabel = new Label("INDEX");
        pageTitleLabel.setStyle(textStyle(28, INK, true) + " -fx-letter-spacing: -0.04em;");
        pageSubtitleLabel = new Label("PLAYER ACCESS // AUTHORIZED RECORDS ONLY");
        pageSubtitleLabel.setStyle(textStyle(10, MUTED, false) + " -fx-letter-spacing: 0.14em;");

        VBox titles = new VBox(4, eyebrow, pageTitleLabel, pageSubtitleLabel);

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        statusLabel = new Label("LOCAL");
        statusLabel.setStyle(textStyle(10, DIM, false) + " -fx-letter-spacing: 0.14em;");
        newEntryButton = pillBtn("+ NEW ENTRY");
        newEntryButton.setOnAction(e -> openEntryEditor(null));
        newEntryButton.setVisible(false); newEntryButton.setManaged(false);

        HBox tools = new HBox(8, statusLabel, newEntryButton);
        tools.setAlignment(Pos.CENTER_RIGHT);

        HBox header = new HBox(20, titles, spacer, tools);
        header.setAlignment(Pos.CENTER_LEFT);
        header.setPadding(new Insets(20, 28, 20, 28));
        header.setStyle("-fx-background-color: " + PANEL + ";" +
                        "-fx-border-color: transparent transparent " + LINE_S + " transparent;" +
                        "-fx-border-width: 0 0 1 0;");
        return header;
    }

    private ScrollPane buildContentArea() {
        cardGrid = new FlowPane(16, 16);
        cardGrid.setPadding(new Insets(26, 28, 26, 28));
        cardGrid.setStyle("-fx-background-color: " + BG + ";");
        notesView = new VBox(16);
        notesView.setPadding(new Insets(26, 28, 26, 28));
        notesView.setStyle("-fx-background-color: " + BG + ";");

        mainScroll = new ScrollPane(cardGrid);
        mainScroll.setFitToWidth(true);
        mainScroll.setStyle("-fx-background-color: " + BG + "; -fx-background: " + BG + ";" +
                            " -fx-border-color: transparent;");
        return mainScroll;
    }

    // ────────────────────────────────────────────────────────────────────────
    //  REFRESH
    // ────────────────────────────────────────────────────────────────────────
    private void refresh() {
        renderSidebarTabs();

        adminBar.setVisible(adminMode);            adminBar.setManaged(adminMode);
        manageButton.setVisible(adminMode);        manageButton.setManaged(adminMode);
        newEntryButton.setVisible(adminMode);      newEntryButton.setManaged(adminMode);
        newSessionButton.setVisible(adminMode && "notes".equals(currentTab.get()));
        newSessionButton.setManaged(adminMode && "notes".equals(currentTab.get()));

        adminButton.setText(adminMode ? "▸ EXIT ADMIN MODE" : "▸ ADMIN LOGIN");
        noteTakerButton.setText(noteTakerMode ? "▸ EXIT NOTE-TAKER" : "▸ NOTE-TAKER LOGIN");

        Tab t = findTab(currentTab.get());
        pageTitleLabel.setText(t == null ? "INDEX" : t.title.toUpperCase());
        pageSubtitleLabel.setText(adminMode
                ? "ADMIN ACCESS // CLASSIFIED RECORDS VISIBLE"
                : noteTakerMode
                  ? "AUTHORIZED NOTE-TAKER // PUBLIC RECORDS"
                  : "PLAYER ACCESS // AUTHORIZED RECORDS ONLY");

        if ("notes".equals(currentTab.get())) {
            renderNotesView();
            mainScroll.setContent(notesView);
        } else {
            renderCardsView();
            mainScroll.setContent(cardGrid);
        }
    }

    private void renderCardsView() {
        cardGrid.getChildren().clear();
        List<Entry> entries = entriesForCurrentTab();
        if (entries.isEmpty()) {
            Label empty = new Label("NO AUTHORIZED RECORDS IN THIS SECTION");
            empty.setPadding(new Insets(60));
            empty.setStyle(textStyle(11, DIM, false));
            cardGrid.getChildren().add(empty);
        } else {
            for (Entry e : entries) cardGrid.getChildren().add(buildCard(e));
        }
    }

    private List<Entry> entriesForCurrentTab() {
        List<Entry> out = new ArrayList<>();
        for (Entry e : data.entries) {
            if (!e.tabId.equals(currentTab.get())) continue;
            if (!adminMode && e.fullyHiddenFromPlayers()) continue;
            out.add(e);
        }
        out.sort(Comparator.comparingInt(x -> x.order));
        return out;
    }

    private Tab findTab(String id) {
        for (Tab t : data.tabs) if (t.id.equals(id)) return t;
        return null;
    }

    // ────────────────────────────────────────────────────────────────────────
    //  CARD
    // ────────────────────────────────────────────────────────────────────────
    private VBox buildCard(Entry e) {
        String accent = accentFor(e);

        VBox card = new VBox(8);
        card.setPrefWidth(296);
        card.setMinWidth(296); card.setMaxWidth(296);
        card.setPadding(new Insets(16, 16, 16, 16));
        card.setStyle(cardStyle(accent, false, e.fullyHiddenFromPlayers() && !adminMode));

        Label eyebrow = new Label(eyebrowFor(e).toUpperCase());
        eyebrow.setStyle(textStyle(9, accent, false) + " -fx-letter-spacing: 0.18em;");

        Label title = new Label(e.title);
        title.setStyle(textStyle(15, INK, true) + " -fx-letter-spacing: -0.02em;");
        title.setWrapText(true);

        VBox top = new VBox(6, eyebrow, title);

        if (e.subtitle != null && !e.subtitle.isBlank()) {
            Label sub = new Label(e.subtitle);
            sub.setStyle(textStyle(11, INK_DIM, false));
            sub.setWrapText(true);
            top.getChildren().add(sub);
        }

        card.getChildren().add(top);

        String preview = cardPreviewText(e);
        if (!preview.isBlank()) {
            Label body = new Label(preview);
            body.setStyle(textStyle(11, "#b7d6c9", false));
            body.setWrapText(true);
            card.getChildren().add(body);
        }

        if (adminMode) {
            int revealed = e.countRevealed();
            int total = e.sections == null ? 0 : e.sections.size();
            Label status = new Label("◇ " + revealed + " of " + total + " fields revealed");
            status.setStyle(textStyle(9, MUTED, false) + " -fx-letter-spacing: 0.12em;");
            card.getChildren().add(status);

            Region spacer = new Region();
            VBox.setVgrow(spacer, Priority.ALWAYS);
            Button editBtn = miniBtn("EDIT");
            editBtn.setOnAction(ev -> { ev.consume(); openEntryEditor(e); });
            HBox row = new HBox(editBtn);
            row.setAlignment(Pos.CENTER_RIGHT);
            card.getChildren().addAll(spacer, row);
        }

        card.setOnMouseClicked(ev -> { if (ev.getButton() == MouseButton.PRIMARY) openEntry(e); });
        card.setOnMouseEntered(ev -> card.setStyle(cardStyle(accent, true, e.fullyHiddenFromPlayers() && !adminMode)));
        card.setOnMouseExited(ev  -> card.setStyle(cardStyle(accent, false, e.fullyHiddenFromPlayers() && !adminMode)));
        return card;
    }

    private String accentFor(Entry e) {
        if (e.id.startsWith("faction-")) {
            String fid = e.id.substring("faction-".length());
            String[] pal = FACTION_PALETTE.get(fid);
            if (pal != null) return pal[0];
        }
        if (e.id.startsWith("npc-")) {
            // Try to find the faction in the subtitle (e.g. "BCI Patrol Captain // BCI")
            if (e.subtitle != null) {
                int slash = e.subtitle.lastIndexOf("//");
                if (slash > 0) {
                    String fac = e.subtitle.substring(slash + 2).trim();
                    String c = NPC_FACTION_COLOR.get(fac);
                    if (c != null) return c;
                }
            }
        }
        if (e.id.startsWith("map-")) return "#9bd4ff";
        return GREEN;
    }

    private String eyebrowFor(Entry e) {
        if (e.id.startsWith("faction-")) return "FACTION DOSSIER";
        if (e.id.startsWith("npc-"))     return "PERSONNEL FILE";
        if (e.id.startsWith("map-"))     return "TACTICAL CARTOGRAPHY";
        return "RECORD";
    }

    private String cardPreviewText(Entry e) {
        // Show the first revealed field's body, or generic if all hidden
        if (e.sections != null) {
            for (Map.Entry<String, String> s : e.sections.entrySet()) {
                if (adminMode || e.isRevealed(s.getKey())) {
                    return truncate(s.getValue(), 180);
                }
            }
        }
        if (e.body != null && !e.body.isBlank()) return truncate(e.body, 180);
        if (!adminMode) return "[REDACTED // CLEARANCE INSUFFICIENT]";
        return "";
    }

    // ────────────────────────────────────────────────────────────────────────
    //  DETAIL VIEW (the dossier)
    // ────────────────────────────────────────────────────────────────────────
    private void openEntry(Entry e) {
        String accent = accentFor(e);
        String[] accentPair = (e.id.startsWith("faction-")
                ? FACTION_PALETTE.getOrDefault(e.id.substring("faction-".length()),
                        new String[]{accent, RED})
                : new String[]{accent, RED});
        String primary = accentPair[0];
        String secondary = accentPair[1];

        Stage dialog = new Stage();
        dialog.initOwner(primaryStage);
        dialog.initModality(Modality.WINDOW_MODAL);
        dialog.setTitle(e.title);

        VBox content = new VBox(18);
        content.setPadding(new Insets(28, 32, 28, 32));
        content.setStyle("-fx-background-color: " + BG + ";");

        // ── Hero block ──
        VBox hero = new VBox(10);
        hero.setPadding(new Insets(28, 28, 28, 28));
        hero.setStyle(panelStyle(primary, true));

        Label kicker = new Label(eyebrowFor(e).toUpperCase() + "   //   CLASSIFICATION " +
                (adminMode ? "CLASSIFIED-OMEGA" : "PUBLIC"));
        kicker.setStyle(textStyle(10, secondary, false) + " -fx-letter-spacing: 0.22em;");

        Label title = new Label(e.title);
        title.setStyle(textStyle(36, INK, true) + " -fx-letter-spacing: -0.04em;");
        title.setWrapText(true);

        hero.getChildren().addAll(kicker, title);

        if (e.subtitle != null && !e.subtitle.isBlank()) {
            Label sub = new Label(e.subtitle);
            sub.setStyle(textStyle(13, INK_DIM, false));
            sub.setWrapText(true);
            hero.getChildren().add(sub);
        }

        // Pills row (info chips for maps, faction tag for NPCs, etc.)
        HBox pills = buildHeroPills(e, primary, secondary);
        if (!pills.getChildren().isEmpty()) hero.getChildren().add(pills);

        content.getChildren().add(hero);

        // ── Image (for maps) ──
        if (e.image != null && !e.image.isBlank()) {
            try {
                Path imgPath = INSTALL_DIR.resolve(e.image);
                if (Files.exists(imgPath)) {
                    ImageView iv = new ImageView(new Image(imgPath.toUri().toString()));
                    iv.setPreserveRatio(true);
                    iv.setFitWidth(960);
                    iv.setSmooth(true);
                    VBox imgWrap = new VBox(iv);
                    imgWrap.setPadding(new Insets(14));
                    imgWrap.setStyle(panelStyle(primary, false));
                    imgWrap.setAlignment(Pos.CENTER);
                    content.getChildren().add(imgWrap);
                }
            } catch (Exception ignored) {}
        }

        // ── Quote callout (NPCs) ──
        if (e.sections != null) {
            String quote = e.sections.get("Quote");
            if (quote != null && !quote.isBlank()) {
                boolean visible = adminMode || e.isRevealed("Quote");
                Node q = visible ? quoteBlock(quote, secondary) : redactedBlock("Quote", secondary);
                content.getChildren().add(q);
            }
        }

        // ── Field grid ──
        if (e.sections != null) {
            GridPane grid = new GridPane();
            grid.setHgap(14); grid.setVgap(14);
            ColumnConstraints c1 = new ColumnConstraints(); c1.setHgrow(Priority.ALWAYS); c1.setPercentWidth(50);
            ColumnConstraints c2 = new ColumnConstraints(); c2.setHgrow(Priority.ALWAYS); c2.setPercentWidth(50);
            grid.getColumnConstraints().addAll(c1, c2);

            int row = 0, col = 0;
            for (Map.Entry<String, String> field : e.sections.entrySet()) {
                String name = field.getKey();
                if (name.equals("Quote")) continue;
                boolean revealed = adminMode || e.isRevealed(name);
                boolean wide = isWideField(name);
                Node block = revealed
                        ? fieldBlock(name, field.getValue(), primary)
                        : redactedBlock(name, secondary);
                if (wide) {
                    if (col != 0) { row++; col = 0; }
                    grid.add(block, 0, row, 2, 1);
                    row++;
                } else {
                    grid.add(block, col, row);
                    col++;
                    if (col >= 2) { col = 0; row++; }
                }
            }
            content.getChildren().add(grid);
        }

        // ── Free body ──
        if (e.body != null && !e.body.isBlank()) {
            VBox body = new VBox(8);
            body.setPadding(new Insets(20));
            body.setStyle(panelStyle(primary, false));
            Label h = new Label("▸ ENTRY NOTES");
            h.setStyle(textStyle(11, primary, true) + " -fx-letter-spacing: 0.16em;");
            Label v = new Label(e.body);
            v.setStyle(textStyle(13, INK, false));
            v.setWrapText(true);
            body.getChildren().addAll(h, v);
            content.getChildren().add(body);
        }

        // ── Connections ──
        if (e.connections != null && !e.connections.isEmpty()) {
            boolean revealed = adminMode || e.isRevealed("Connections");
            if (revealed) {
                VBox conn = new VBox(8);
                conn.setPadding(new Insets(20));
                conn.setStyle(panelStyle(primary, false));
                Label h = new Label("▸ CONNECTIONS");
                h.setStyle(textStyle(11, primary, true) + " -fx-letter-spacing: 0.16em;");
                FlowPane chips = new FlowPane(8, 8);
                for (String c : e.connections) chips.getChildren().add(chip(c, primary));
                conn.getChildren().addAll(h, chips);
                content.getChildren().add(conn);
            } else {
                content.getChildren().add(redactedBlock("Connections", secondary));
            }
        }

        Button close = pillBtn("CLOSE DOSSIER");
        close.setOnAction(ev -> dialog.close());
        HBox btnRow = new HBox(close);
        btnRow.setAlignment(Pos.CENTER_RIGHT);
        btnRow.setPadding(new Insets(8, 0, 0, 0));
        content.getChildren().add(btnRow);

        ScrollPane scroll = new ScrollPane(content);
        scroll.setFitToWidth(true);
        scroll.setStyle("-fx-background-color: " + BG + "; -fx-background: " + BG + ";" +
                        " -fx-border-color: transparent;");
        Scene s = new Scene(scroll, 1080, 800);
        dialog.setScene(s);
        dialog.show();
    }

    private boolean isWideField(String name) {
        if (name == null) return false;
        String n = name.toLowerCase();
        return n.equals("public face") || n.equals("the truth") || n.equals("truth")
            || n.equals("faction overview") || n.equals("internal structure")
            || n.equals("session hooks") || n.equals("faction secrets")
            || n.equals("dm notes") || n.equals("description")
            || n.equals("first encounter") || n.equals("why they matter");
    }

    private HBox buildHeroPills(Entry e, String primary, String secondary) {
        HBox pills = new HBox(8);
        pills.setPadding(new Insets(8, 0, 0, 0));
        if (e.id.startsWith("map-") && e.sections != null) {
            for (String key : new String[]{"Population", "Scale", "Elevation", "Connections"}) {
                String v = e.sections.get(key);
                if (v != null && !v.isBlank() && (adminMode || e.isRevealed(key))) {
                    pills.getChildren().add(infoPill(key, v, primary));
                }
            }
        }
        if (e.id.startsWith("npc-") && e.sections != null) {
            String section = e.sections.get("Section");
            if (section != null && (adminMode || e.isRevealed("Section"))) {
                pills.getChildren().add(infoPill("Section", section, primary));
            }
            String status = e.sections.get("Status");
            if (status != null && (adminMode || e.isRevealed("Status"))) {
                pills.getChildren().add(infoPill("Status", status, secondary));
            }
        }
        return pills;
    }

    // ────────────────────────────────────────────────────────────────────────
    //  BLOCKS
    // ────────────────────────────────────────────────────────────────────────
    private VBox fieldBlock(String name, String value, String accent) {
        VBox box = new VBox(8);
        box.setPadding(new Insets(16));
        box.setStyle(panelStyle(accent, false));
        Label h = new Label("▸ " + name.toUpperCase());
        h.setStyle(textStyle(10, accent, true) + " -fx-letter-spacing: 0.18em;");
        Label v = new Label(value);
        v.setStyle(textStyle(12, INK, false));
        v.setWrapText(true);
        box.getChildren().addAll(h, v);
        return box;
    }

    private VBox redactedBlock(String name, String accentSecondary) {
        VBox box = new VBox(6);
        box.setPadding(new Insets(16));
        box.setStyle("-fx-background-color: rgba(40,12,16,0.55);" +
                     "-fx-border-color: " + accentSecondary + ";" +
                     "-fx-border-width: 1;" +
                     "-fx-background-radius: 14; -fx-border-radius: 14;");
        Label h = new Label("▸ " + name.toUpperCase());
        h.setStyle(textStyle(10, accentSecondary, true) + " -fx-letter-spacing: 0.18em;");
        Label v = new Label("[ REDACTED  //  CLEARANCE INSUFFICIENT ]");
        v.setStyle(textStyle(12, "#ff8895", false) + " -fx-letter-spacing: 0.14em;");
        v.setWrapText(true);
        box.getChildren().addAll(h, v);
        return box;
    }

    private HBox quoteBlock(String text, String accent) {
        Label q = new Label("\u201C" + text + "\u201D");
        q.setStyle(textStyle(14, INK, false) + " -fx-font-style: italic;");
        q.setWrapText(true);
        HBox.setHgrow(q, Priority.ALWAYS);

        HBox row = new HBox(q);
        row.setPadding(new Insets(16, 18, 16, 22));
        row.setStyle("-fx-background-color: rgba(255,255,255,0.035);" +
                     "-fx-background-radius: 14;" +
                     "-fx-border-radius: 14;" +
                     "-fx-border-color: " + LINE + " " + LINE + " " + LINE + " " + accent + ";" +
                     "-fx-border-width: 1 1 1 3;");
        return row;
    }

    private HBox infoPill(String label, String value, String accent) {
        Label l = new Label(label.toUpperCase() + " ");
        l.setStyle(textStyle(9, accent, true) + " -fx-letter-spacing: 0.14em;");
        Label v = new Label(value);
        v.setStyle(textStyle(11, INK, false));
        HBox pill = new HBox(4, l, v);
        pill.setPadding(new Insets(6, 12, 6, 12));
        pill.setAlignment(Pos.CENTER_LEFT);
        pill.setStyle("-fx-background-color: rgba(0,0,0,0.32);" +
                      "-fx-background-radius: 999;" +
                      "-fx-border-color: " + LINE + ";" +
                      "-fx-border-width: 1;" +
                      "-fx-border-radius: 999;");
        return pill;
    }

    private Label chip(String text, String accent) {
        Label c = new Label(text);
        c.setStyle(textStyle(11, INK, false) +
                   "-fx-background-color: rgba(0,0,0,0.32);" +
                   "-fx-padding: 5 10 5 10;" +
                   "-fx-background-radius: 999;" +
                   "-fx-border-color: " + accent + ";" +
                   "-fx-border-width: 1;" +
                   "-fx-border-radius: 999;");
        return c;
    }

    // ────────────────────────────────────────────────────────────────────────
    //  NOTES VIEW
    // ────────────────────────────────────────────────────────────────────────
    private void renderNotesView() {
        notesView.getChildren().clear();
        if (notes.sessions.isEmpty()) {
            Label empty = new Label("NO SESSION NOTES YET. " +
                    (adminMode ? "USE NEW SESSION TAB TO CREATE ONE." : "AWAITING NOTE-TAKER."));
            empty.setPadding(new Insets(40));
            empty.setStyle(textStyle(11, DIM, false) + " -fx-letter-spacing: 0.14em;");
            notesView.getChildren().add(empty);
            return;
        }
        List<SessionNote> ordered = new ArrayList<>(notes.sessions.values());
        ordered.sort(Comparator.comparingInt(s -> s.order));
        for (SessionNote s : ordered) notesView.getChildren().add(buildSessionBlock(s));
    }

    private VBox buildSessionBlock(SessionNote s) {
        VBox box = new VBox(10);
        box.setPadding(new Insets(20));
        box.setStyle(panelStyle(GREEN, false));

        Label eyebrow = new Label("SESSION NOTE   //   " +
                (s.date == null || s.date.isBlank() ? "UNDATED" : s.date.toUpperCase()));
        eyebrow.setStyle(textStyle(10, GOLD, false) + " -fx-letter-spacing: 0.22em;");
        Label title = new Label(s.title);
        title.setStyle(textStyle(22, INK, true) + " -fx-letter-spacing: -0.02em;");
        title.setWrapText(true);

        box.getChildren().addAll(eyebrow, title);

        Label body = new Label(s.body == null || s.body.isBlank()
                ? "(No notes recorded yet.)" : s.body);
        body.setStyle(textStyle(13, INK, false));
        body.setWrapText(true);
        box.getChildren().add(body);

        HBox btnRow = new HBox(8);
        btnRow.setAlignment(Pos.CENTER_RIGHT);
        if (noteTakerMode || adminMode) {
            Button edit = miniBtn("EDIT");
            edit.setOnAction(e -> openNoteEditor(s));
            btnRow.getChildren().add(edit);
        }
        if (adminMode) {
            Button del = miniBtn("DELETE");
            del.setStyle(miniBtnStyle("#ff8891", "#6b252d"));
            del.setOnAction(e -> {
                Alert a = new Alert(Alert.AlertType.CONFIRMATION, "Delete session note?", ButtonType.YES, ButtonType.NO);
                a.initOwner(primaryStage);
                a.showAndWait().ifPresent(bt -> {
                    if (bt == ButtonType.YES) {
                        notes.sessions.remove(s.id);
                        saveNotesCache();
                        if (noteTakerMode || adminMode) CompletableFuture.runAsync(this::syncNotesPush);
                        refresh();
                    }
                });
            });
            btnRow.getChildren().add(del);
        }
        if (!btnRow.getChildren().isEmpty()) box.getChildren().add(btnRow);

        return box;
    }

    private void openNewSessionDialog() {
        TextInputDialog dlg = new TextInputDialog("Session " + (notes.sessions.size() + 1));
        dlg.initOwner(primaryStage);
        dlg.setTitle("New Session");
        dlg.setHeaderText("New Session Tab");
        dlg.setContentText("Session title:");
        dlg.showAndWait().ifPresent(title -> {
            if (title.isBlank()) return;
            SessionNote s = new SessionNote();
            s.id = "session-" + System.currentTimeMillis();
            s.title = title.trim();
            s.body = "";
            s.order = notes.sessions.size() + 1;
            notes.sessions.put(s.id, s);
            saveNotesCache();
            CompletableFuture.runAsync(this::syncNotesPush);
            refresh();
        });
    }

    private void openNoteEditor(SessionNote s) {
        Stage dlg = new Stage();
        dlg.initOwner(primaryStage);
        dlg.initModality(Modality.WINDOW_MODAL);
        dlg.setTitle("Edit Session Note");

        TextField tTitle = new TextField(s.title);
        TextField tDate = new TextField(s.date == null ? "" : s.date);
        TextArea tBody = new TextArea(s.body == null ? "" : s.body);
        tBody.setWrapText(true); tBody.setPrefRowCount(20);

        GridPane g = new GridPane();
        g.setHgap(10); g.setVgap(10); g.setPadding(new Insets(20));
        int r = 0;
        g.add(label("Title"), 0, r); g.add(tTitle, 1, r++);
        g.add(label("Date"), 0, r);  g.add(tDate, 1, r++);
        g.add(label("Body"), 0, r);  g.add(tBody, 1, r++);

        Button save = pillBtn("SAVE & SYNC");
        Button cancel = pillBtn("CANCEL");
        save.setOnAction(e -> {
            s.title = tTitle.getText().isBlank() ? s.title : tTitle.getText().trim();
            s.date = tDate.getText().trim();
            s.body = tBody.getText();
            saveNotesCache();
            CompletableFuture.runAsync(this::syncNotesPush);
            refresh();
            dlg.close();
        });
        cancel.setOnAction(e -> dlg.close());

        HBox btns = new HBox(8, save, cancel);
        btns.setAlignment(Pos.CENTER_RIGHT);
        btns.setPadding(new Insets(8, 20, 20, 20));

        VBox layout = new VBox(g, btns);
        layout.setStyle("-fx-background-color: " + PANEL + ";");
        Scene scene = new Scene(layout, 760, 640);
        dlg.setScene(scene);
        dlg.show();
    }

    // ────────────────────────────────────────────────────────────────────────
    //  ADMIN / NOTE-TAKER LOGIN
    // ────────────────────────────────────────────────────────────────────────
    private void adminAction() {
        if (adminMode) { adminMode = false; toast("Admin mode closed"); refresh(); return; }
        TextInputDialog dlg = new TextInputDialog();
        dlg.initOwner(primaryStage);
        dlg.setTitle("Admin Authorization"); dlg.setHeaderText("Enter admin PIN"); dlg.setContentText("PIN:");
        dlg.showAndWait().ifPresent(pin -> {
            if (pin.equals(data.adminPin)) {
                adminMode = true; toast("Admin mode enabled"); refresh();
            } else toast("Incorrect PIN");
        });
    }

    private void noteTakerAction() {
        if (noteTakerMode) { noteTakerMode = false; toast("Note-taker mode closed"); refresh(); return; }
        TextInputDialog dlg = new TextInputDialog();
        dlg.initOwner(primaryStage);
        dlg.setTitle("Note-Taker Authorization"); dlg.setHeaderText("Enter note-taker PIN"); dlg.setContentText("PIN:");
        dlg.showAndWait().ifPresent(pin -> {
            if (pin.equals(data.noteTakerPin)) {
                noteTakerMode = true; toast("Note-taker mode enabled"); refresh();
            } else toast("Incorrect PIN");
        });
    }

    // ────────────────────────────────────────────────────────────────────────
    //  ENTRY EDITOR (admin) — with per-field reveal toggles
    // ────────────────────────────────────────────────────────────────────────
    private void openEntryEditor(Entry existing) {
        Stage dlg = new Stage();
        dlg.initOwner(primaryStage);
        dlg.initModality(Modality.WINDOW_MODAL);
        dlg.setTitle(existing == null ? "New Entry" : "Edit Entry");

        TextField tTitle = new TextField(existing == null ? "" : existing.title);
        TextField tSub   = new TextField(existing == null ? "" : nz(existing.subtitle));
        TextArea  tBody  = new TextArea(existing == null ? "" : nz(existing.body));
        tBody.setPrefRowCount(6); tBody.setWrapText(true);

        ComboBox<String> tTab = new ComboBox<>();
        for (Tab t : data.tabs) tTab.getItems().add(t.id);
        tTab.setValue(existing == null ? currentTab.get() : existing.tabId);

        TextField tImage = new TextField(existing == null ? "" : nz(existing.image));
        Spinner<Integer> tOrder = new Spinner<>(1, 9999, existing == null ? 1 : existing.order);
        tOrder.setEditable(true);

        GridPane g = new GridPane();
        g.setHgap(10); g.setVgap(8); g.setPadding(new Insets(20));
        int r = 0;
        g.add(headerLabel("BASIC INFO"), 0, r++, 2, 1);
        g.add(label("Title"), 0, r);    g.add(tTitle, 1, r++);
        g.add(label("Subtitle"), 0, r); g.add(tSub, 1, r++);
        g.add(label("Tab"), 0, r);      g.add(tTab, 1, r++);
        g.add(label("Order"), 0, r);    g.add(tOrder, 1, r++);
        g.add(label("Image"), 0, r);    g.add(tImage, 1, r++);
        g.add(label("Body"), 0, r);     g.add(tBody, 1, r++);

        // Per-field reveal section
        g.add(headerLabel("FIELD VISIBILITY"), 0, r++, 2, 1);
        Label hint = new Label("Check a field to reveal it to players. Unchecked fields show as [REDACTED].");
        hint.setStyle(textStyle(10, MUTED, false));
        g.add(hint, 0, r++, 2, 1);

        Map<String, CheckBox> revealBoxes = new LinkedHashMap<>();
        Map<String, TextArea> bodyBoxes = new LinkedHashMap<>();
        if (existing != null && existing.sections != null) {
            for (Map.Entry<String, String> field : existing.sections.entrySet()) {
                String name = field.getKey();
                CheckBox cb = new CheckBox("REVEAL");
                cb.setSelected(existing.isRevealed(name));
                cb.setStyle(textStyle(10, GREEN, false));
                Label fl = new Label(name.toUpperCase());
                fl.setStyle(textStyle(10, GREEN, true) + " -fx-letter-spacing: 0.16em;");
                TextArea ta = new TextArea(field.getValue());
                ta.setWrapText(true);
                ta.setPrefRowCount(3);
                HBox head = new HBox(12, fl, cb);
                head.setAlignment(Pos.CENTER_LEFT);
                g.add(head, 0, r, 2, 1); r++;
                g.add(ta, 0, r, 2, 1); r++;
                revealBoxes.put(name, cb);
                bodyBoxes.put(name, ta);
            }
        }

        if (existing != null && existing.connections != null && !existing.connections.isEmpty()) {
            CheckBox cbConn = new CheckBox("REVEAL CONNECTIONS LIST");
            cbConn.setSelected(existing.isRevealed("Connections"));
            cbConn.setStyle(textStyle(10, GREEN, false));
            g.add(cbConn, 0, r++, 2, 1);
            revealBoxes.put("Connections", cbConn);
        }

        Button save = pillBtn("SAVE");
        Button cancel = pillBtn("CANCEL");
        Button del = pillBtn("DELETE");
        del.setStyle(pillBtnStyle("#ff8891", "#6b252d"));
        del.setVisible(existing != null);

        save.setOnAction(e -> {
            Entry target = existing;
            if (target == null) {
                target = new Entry();
                target.id = slug(tTitle.getText());
                data.entries.add(target);
            }
            target.title    = tTitle.getText().isBlank() ? "Untitled" : tTitle.getText().trim();
            target.subtitle = tSub.getText().trim();
            target.body     = tBody.getText();
            target.tabId    = tTab.getValue();
            target.order    = tOrder.getValue();
            target.image    = tImage.getText().trim();
            for (Map.Entry<String, CheckBox> kv : revealBoxes.entrySet()) {
                target.setRevealed(kv.getKey(), kv.getValue().isSelected());
            }
            for (Map.Entry<String, TextArea> kv : bodyBoxes.entrySet()) {
                if (target.sections != null && target.sections.containsKey(kv.getKey())) {
                    target.sections.put(kv.getKey(), kv.getValue().getText());
                }
            }
            saveLocal();
            currentTab.set(target.tabId);
            refresh();
            dlg.close();
            toast("Entry saved");
        });
        cancel.setOnAction(e -> dlg.close());
        del.setOnAction(e -> {
            Alert a = new Alert(Alert.AlertType.CONFIRMATION, "Delete this entry?", ButtonType.YES, ButtonType.NO);
            a.initOwner(dlg);
            a.showAndWait().ifPresent(bt -> {
                if (bt == ButtonType.YES && existing != null) {
                    data.entries.remove(existing); saveLocal(); refresh(); dlg.close();
                    toast("Entry deleted");
                }
            });
        });

        HBox btns = new HBox(8, save, cancel, del);
        btns.setAlignment(Pos.CENTER_RIGHT);
        btns.setPadding(new Insets(14, 20, 20, 20));

        VBox layout = new VBox(g, btns);
        layout.setStyle("-fx-background-color: " + PANEL + ";");
        ScrollPane scroll = new ScrollPane(layout);
        scroll.setFitToWidth(true);
        scroll.setStyle("-fx-background-color: " + PANEL + "; -fx-background: " + PANEL + ";" +
                        " -fx-border-color: transparent;");
        Scene scene = new Scene(scroll, 880, 800);
        dlg.setScene(scene);
        dlg.show();
    }

    // ────────────────────────────────────────────────────────────────────────
    //  CAMPAIGN MANAGER
    // ────────────────────────────────────────────────────────────────────────
    private void openManager() {
        Stage dlg = new Stage();
        dlg.initOwner(primaryStage);
        dlg.initModality(Modality.WINDOW_MODAL);
        dlg.setTitle("Campaign Manager");

        TextField mTitle = new TextField(data.campaignTitle);
        TextField mSub   = new TextField(data.subtitle);
        PasswordField mPin = new PasswordField(); mPin.setText(data.adminPin);
        PasswordField mNotePin = new PasswordField(); mNotePin.setText(data.noteTakerPin);

        TextField gOwner = new TextField(ghConfig.owner);
        TextField gRepo  = new TextField(ghConfig.repo);
        TextField gBranch = new TextField(ghConfig.branch);
        TextField gPath  = new TextField(ghConfig.path);
        TextField gNotes = new TextField(ghConfig.notesPath);
        PasswordField gToken = new PasswordField(); gToken.setText(ghConfig.token);

        GridPane g = new GridPane();
        g.setHgap(10); g.setVgap(8); g.setPadding(new Insets(20));
        int r = 0;
        g.add(headerLabel("CAMPAIGN SETTINGS"), 0, r++, 2, 1);
        g.add(label("Title"), 0, r);     g.add(mTitle, 1, r++);
        g.add(label("Subtitle"), 0, r);  g.add(mSub,   1, r++);
        g.add(label("Admin PIN"), 0, r); g.add(mPin,  1, r++);
        g.add(label("Note-Taker PIN"), 0, r); g.add(mNotePin, 1, r++);

        Region sp = new Region(); sp.setMinHeight(14);
        g.add(sp, 0, r++);
        g.add(headerLabel("GITHUB PUBLISHING"), 0, r++, 2, 1);
        Label notice = new Label("Publish only reveals fields you've checked. Locked entries skipped.\n" +
                "Notes sync via a separate file in the same repo.");
        notice.setStyle(textStyle(10, GOLD, false));
        notice.setWrapText(true);
        g.add(notice, 0, r++, 2, 1);

        g.add(label("Owner"), 0, r);     g.add(gOwner, 1, r++);
        g.add(label("Repo"), 0, r);      g.add(gRepo,  1, r++);
        g.add(label("Branch"), 0, r);    g.add(gBranch, 1, r++);
        g.add(label("Campaign path"), 0, r); g.add(gPath, 1, r++);
        g.add(label("Notes path"), 0, r); g.add(gNotes, 1, r++);
        g.add(label("Token (PAT)"), 0, r); g.add(gToken, 1, r++);

        Button save = pillBtn("SAVE");
        Button publish = pillBtn("PUBLISH PLAYER VIEW");
        Button test = pillBtn("TEST CONNECTION");
        Button export = pillBtn("EXPORT MASTER");
        Button close = pillBtn("CLOSE");

        save.setOnAction(e -> {
            data.campaignTitle = mTitle.getText().isBlank() ? "MEMORIES OF A FEW" : mTitle.getText().trim();
            data.subtitle = mSub.getText().trim();
            if (!mPin.getText().isBlank()) data.adminPin = mPin.getText();
            if (!mNotePin.getText().isBlank()) data.noteTakerPin = mNotePin.getText();
            ghConfig.owner = gOwner.getText().trim();
            ghConfig.repo  = gRepo.getText().trim();
            ghConfig.branch = gBranch.getText().isBlank() ? "main" : gBranch.getText().trim();
            ghConfig.path = gPath.getText().isBlank() ? "campaign.json" : gPath.getText().trim();
            ghConfig.notesPath = gNotes.getText().isBlank() ? "notes.json" : gNotes.getText().trim();
            ghConfig.token = gToken.getText().trim();
            saveLocal(); saveConfig(); refresh();
            toast("Settings saved");
        });
        publish.setOnAction(e -> { save.fire(); CompletableFuture.runAsync(this::publishGithub); });
        test.setOnAction(e -> { save.fire(); CompletableFuture.runAsync(this::testGithub); });
        export.setOnAction(e -> exportMaster());
        close.setOnAction(e -> dlg.close());

        HBox btns = new HBox(8, save, publish, test, export, close);
        btns.setAlignment(Pos.CENTER_LEFT);
        btns.setPadding(new Insets(8, 20, 20, 20));

        VBox layout = new VBox(g, btns);
        layout.setStyle("-fx-background-color: " + PANEL + ";");
        ScrollPane scroll = new ScrollPane(layout);
        scroll.setFitToWidth(true);
        scroll.setStyle("-fx-background-color: " + PANEL + "; -fx-background: " + PANEL + ";" +
                        " -fx-border-color: transparent;");
        Scene scene = new Scene(scroll, 760, 800);
        dlg.setScene(scene);
        dlg.show();
    }

    // ────────────────────────────────────────────────────────────────────────
    //  TOAST
    // ────────────────────────────────────────────────────────────────────────
    private void toast(String message) {
        Platform.runLater(() -> {
            Stage toast = new Stage();
            toast.initOwner(primaryStage);
            toast.initStyle(javafx.stage.StageStyle.UNDECORATED);
            Label l = new Label(message);
            l.setStyle(textStyle(11, INK, false) + " -fx-padding: 14 18 14 18;" +
                       " -fx-background-color: " + PANEL2 + ";" +
                       " -fx-border-color: " + GREEN + "; -fx-border-width: 1;");
            Scene s = new Scene(l);
            s.setFill(Color.TRANSPARENT);
            toast.setScene(s);
            toast.show();
            toast.setX(primaryStage.getX() + primaryStage.getWidth() - 320);
            toast.setY(primaryStage.getY() + primaryStage.getHeight() - 90);
            new Timer(true).schedule(new TimerTask() {
                @Override public void run() { Platform.runLater(toast::close); }
            }, 2800);
        });
    }

    // ────────────────────────────────────────────────────────────────────────
    //  GITHUB SYNC
    // ────────────────────────────────────────────────────────────────────────
    private void publishGithub() {
        if (ghConfig.owner.isBlank() || ghConfig.repo.isBlank() || ghConfig.token.isBlank()) {
            toast("Complete GitHub settings first"); return;
        }
        try {
            putToGitHub(ghConfig.path, JsonWriter.toJson(data.playerSnapshot()), "Publish MOAF player view");
            Platform.runLater(() -> statusLabel.setText("PUBLISHED " +
                    LocalTime.now().format(DateTimeFormatter.ofPattern("HH:mm:ss"))));
            toast("Player view published");
        } catch (Exception ex) {
            toast("Publish failed: " + ex.getMessage());
        }
    }

    private void syncNotesPush() {
        if (!noteTakerMode && !adminMode) return;
        if (ghConfig.owner.isBlank() || ghConfig.repo.isBlank() || ghConfig.token.isBlank()) {
            toast("Note sync needs a GitHub token"); return;
        }
        try {
            putToGitHub(ghConfig.notesPath, JsonWriter.toJson(notes.toMap()), "Update session notes");
            Platform.runLater(() -> statusLabel.setText("NOTES PUSHED " +
                    LocalTime.now().format(DateTimeFormatter.ofPattern("HH:mm:ss"))));
        } catch (Exception ex) { toast("Notes sync failed: " + ex.getMessage()); }
    }

    private void putToGitHub(String path, String body, String message) throws Exception {
        String urlBase = "https://api.github.com/repos/" + ghConfig.owner + "/" + ghConfig.repo + "/contents/" + path;
        HttpClient c = HttpClient.newHttpClient();
        HttpResponse<String> get = c.send(
                HttpRequest.newBuilder(URI.create(urlBase + "?ref=" + ghConfig.branch))
                        .header("Accept", "application/vnd.github+json")
                        .header("Authorization", "Bearer " + ghConfig.token)
                        .GET().build(),
                HttpResponse.BodyHandlers.ofString());
        String sha = null;
        if (get.statusCode() == 200) {
            int idx = get.body().indexOf("\"sha\":\"");
            if (idx >= 0) { int s = idx + 7; int e = get.body().indexOf("\"", s); sha = get.body().substring(s, e); }
        }
        String b64 = Base64.getEncoder().encodeToString(body.getBytes(StandardCharsets.UTF_8));
        StringBuilder json = new StringBuilder();
        json.append("{\"message\":").append(JsonWriter.quote(message))
            .append(",\"branch\":").append(JsonWriter.quote(ghConfig.branch))
            .append(",\"content\":\"").append(b64).append("\"");
        if (sha != null) json.append(",\"sha\":\"").append(sha).append("\"");
        json.append("}");
        HttpResponse<String> put = c.send(
                HttpRequest.newBuilder(URI.create(urlBase))
                        .header("Accept", "application/vnd.github+json")
                        .header("Authorization", "Bearer " + ghConfig.token)
                        .header("Content-Type", "application/json")
                        .PUT(HttpRequest.BodyPublishers.ofString(json.toString())).build(),
                HttpResponse.BodyHandlers.ofString());
        if (put.statusCode() < 200 || put.statusCode() >= 300) {
            throw new RuntimeException("HTTP " + put.statusCode() + ": " + put.body());
        }
    }

    private void testGithub() {
        try {
            HttpResponse<String> r = HttpClient.newHttpClient().send(
                    HttpRequest.newBuilder(URI.create("https://api.github.com/repos/" + ghConfig.owner + "/" + ghConfig.repo))
                            .header("Accept", "application/vnd.github+json")
                            .header("Authorization", "Bearer " + ghConfig.token).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            if (r.statusCode() == 200) toast("Connection OK");
            else toast("Connection failed: HTTP " + r.statusCode());
        } catch (Exception ex) { toast("Connection failed: " + ex.getMessage()); }
    }

    private void syncRemote(boolean silent) {
        if (ghConfig.owner.isBlank() || ghConfig.repo.isBlank()) {
            if (!silent) toast("Online updates not configured"); return;
        }
        try {
            String url = "https://raw.githubusercontent.com/" + ghConfig.owner + "/" + ghConfig.repo +
                         "/" + ghConfig.branch + "/" + ghConfig.path + "?t=" + System.currentTimeMillis();
            HttpResponse<String> r = HttpClient.newHttpClient().send(
                    HttpRequest.newBuilder(URI.create(url)).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            if (r.statusCode() != 200) { if (!silent) toast("Player data not found"); return; }
            if (!adminMode) data.loadFromJson(r.body());
            Platform.runLater(() -> {
                statusLabel.setText("UPDATED " + LocalTime.now().format(DateTimeFormatter.ofPattern("HH:mm:ss")));
                refresh();
            });
            if (!silent) toast("Campaign updated");
        } catch (Exception ex) { if (!silent) toast("Update failed: " + ex.getMessage()); }
    }

    private void syncNotesPull(boolean silent) {
        if (ghConfig.owner.isBlank() || ghConfig.repo.isBlank()) return;
        try {
            String url = "https://raw.githubusercontent.com/" + ghConfig.owner + "/" + ghConfig.repo +
                         "/" + ghConfig.branch + "/" + ghConfig.notesPath + "?t=" + System.currentTimeMillis();
            HttpResponse<String> r = HttpClient.newHttpClient().send(
                    HttpRequest.newBuilder(URI.create(url)).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            if (r.statusCode() != 200) return;
            notes.loadFromJson(r.body());
            saveNotesCache();
            Platform.runLater(this::refresh);
            if (!silent) toast("Notes updated");
        } catch (Exception ignored) {}
    }

    private void exportMaster() {
        try {
            Path p = Path.of(System.getProperty("user.home"), "moaf-campaign-master.json");
            Files.writeString(p, JsonWriter.toJson(data.fullExport()), StandardCharsets.UTF_8);
            toast("Exported to home folder");
        } catch (Exception e) { toast("Export failed: " + e.getMessage()); }
    }

    // ────────────────────────────────────────────────────────────────────────
    //  STYLES
    // ────────────────────────────────────────────────────────────────────────
    private String textStyle(int size, String color, boolean bold) {
        return "-fx-text-fill: " + color + ";" +
               "-fx-font-family: " + FONT + ";" +
               "-fx-font-size: " + size + "px;" +
               (bold ? "-fx-font-weight: bold;" : "");
    }
    private String tabButtonStyle(boolean active, boolean hover) {
        String fg = active ? GREEN : (hover ? "#9ed7c1" : MUTED);
        String bg = active ? "rgba(127,255,197,0.07)" : (hover ? "rgba(127,255,197,0.03)" : "transparent");
        String border = active ? GREEN : "transparent";
        return "-fx-background-color: " + bg + ";" +
               "-fx-text-fill: " + fg + ";" +
               "-fx-font-family: " + FONT + ";" +
               "-fx-font-size: 12px;" +
               "-fx-padding: 11 18 11 18;" +
               "-fx-border-color: transparent transparent transparent " + border + ";" +
               "-fx-border-width: 0 0 0 3;" +
               "-fx-background-radius: 0;" +
               "-fx-cursor: hand;";
    }
    private String cardStyle(String accent, boolean hover, boolean dim) {
        String border = hover ? accent : LINE_S;
        return "-fx-background-color: " + PANEL2 + ";" +
               "-fx-background-radius: 18;" +
               "-fx-border-color: " + border + ";" +
               "-fx-border-width: 1;" +
               "-fx-border-radius: 18;" +
               "-fx-cursor: hand;" +
               (dim ? "-fx-opacity: 0.55;" : "");
    }
    private String panelStyle(String accent, boolean hero) {
        return "-fx-background-color: " + (hero ? "#0a1612" : PANEL2) + ";" +
               "-fx-background-radius: 18;" +
               "-fx-border-color: " + LINE + ";" +
               "-fx-border-width: 1;" +
               "-fx-border-radius: 18;";
    }
    private String pillBtnStyle() { return pillBtnStyle(GREEN, LINE_S); }
    private String pillBtnStyle(String fg, String border) {
        return "-fx-background-color: rgba(0,0,0,0.32);" +
               "-fx-text-fill: " + fg + ";" +
               "-fx-font-family: " + FONT + ";" +
               "-fx-font-size: 11px;" +
               "-fx-border-color: " + border + ";" +
               "-fx-border-width: 1;" +
               "-fx-background-radius: 999;" +
               "-fx-border-radius: 999;" +
               "-fx-padding: 8 14 8 14;" +
               "-fx-cursor: hand;";
    }
    private String miniBtnStyle() { return miniBtnStyle(GREEN, LINE_S); }
    private String miniBtnStyle(String fg, String border) {
        return pillBtnStyle(fg, border) + " -fx-font-size: 10px; -fx-padding: 5 10 5 10;";
    }
    private Button pillBtn(String text) {
        Button b = new Button(text);
        b.setStyle(pillBtnStyle());
        b.setOnMouseEntered(e -> b.setStyle(pillBtnStyle().replace("rgba(0,0,0,0.32)", "rgba(127,255,197,0.06)")));
        b.setOnMouseExited(e -> b.setStyle(pillBtnStyle()));
        return b;
    }
    private Button miniBtn(String text) {
        Button b = new Button(text);
        b.setStyle(miniBtnStyle());
        b.setOnMouseEntered(e -> b.setStyle(miniBtnStyle().replace("rgba(0,0,0,0.32)", "rgba(127,255,197,0.06)")));
        b.setOnMouseExited(e -> b.setStyle(miniBtnStyle()));
        return b;
    }
    private Label label(String s) { Label l = new Label(s); l.setStyle(textStyle(10, MUTED, false)); return l; }
    private Label headerLabel(String s) {
        Label l = new Label("▸ " + s);
        l.setStyle(textStyle(11, GREEN, true) + " -fx-letter-spacing: 0.18em;");
        return l;
    }

    // ── Utilities ────────────────────────────────────────────────────────────
    private static String nz(String s) { return s == null ? "" : s; }
    private static String truncate(String s, int max) {
        if (s == null) return "";
        s = s.trim();
        return s.length() <= max ? s : s.substring(0, max).trim() + "…";
    }
    private static String slug(String s) {
        if (s == null || s.isBlank()) s = "entry";
        s = s.toLowerCase().replaceAll("[^a-z0-9]+", "-").replaceAll("^-|-$", "");
        return s + "-" + Long.toString(System.currentTimeMillis(), 36);
    }
    private static Path locateInstallDir() {
        try {
            Path code = Path.of(MoafCampaignApp.class.getProtectionDomain()
                    .getCodeSource().getLocation().toURI());
            return Files.isRegularFile(code) ? code.getParent().toAbsolutePath() : Path.of("").toAbsolutePath();
        } catch (Exception ignored) { return Path.of("").toAbsolutePath(); }
    }
    private static Path locateDataDir() {
        String localAppData = System.getenv("LOCALAPPDATA");
        Path base = (localAppData != null && !localAppData.isBlank())
                ? Path.of(localAppData)
                : Path.of(System.getProperty("user.home"), ".local", "share");
        return base.resolve("MOAF Campaign Index").toAbsolutePath();
    }

    private void loadOrSeedMaster() {
        if (Files.exists(MASTER_FILE)) {
            try { data.loadFromJson(Files.readString(MASTER_FILE, StandardCharsets.UTF_8)); return; }
            catch (Exception ex) { logError(ex); }
        }
        data.seedFromBundledResources();
        saveLocal();
    }
    private void loadConfig() {
        if (Files.exists(CONFIG_FILE)) {
            try { ghConfig.loadFromJson(Files.readString(CONFIG_FILE, StandardCharsets.UTF_8)); } catch (Exception ignored) {}
        }
    }
    private void loadNotesCache() {
        if (Files.exists(NOTES_FILE)) {
            try { notes.loadFromJson(Files.readString(NOTES_FILE, StandardCharsets.UTF_8)); } catch (Exception ignored) {}
        }
    }
    private void saveLocal() {
        try { Files.writeString(MASTER_FILE, JsonWriter.toJson(data.fullExport()), StandardCharsets.UTF_8); }
        catch (Exception ex) { logError(ex); }
    }
    private void saveConfig() {
        try { Files.writeString(CONFIG_FILE, JsonWriter.toJson(ghConfig.toMap()), StandardCharsets.UTF_8); }
        catch (Exception ex) { logError(ex); }
    }
    private void saveNotesCache() {
        try { Files.writeString(NOTES_FILE, JsonWriter.toJson(notes.toMap()), StandardCharsets.UTF_8); }
        catch (Exception ex) { logError(ex); }
    }
    private static void logError(Exception e) {
        try {
            Files.createDirectories(DATA_DIR);
            Files.writeString(DATA_DIR.resolve("startup-error.txt"),
                    e + System.lineSeparator(), StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (Exception ignored) {}
        e.printStackTrace();
    }

    // ────────────────────────────────────────────────────────────────────────
    //  DATA CLASSES
    // ────────────────────────────────────────────────────────────────────────
    static final class Tab {
        String id, title, icon;
        boolean visible = true;
        int order;
        Map<String, Object> toMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", id); m.put("title", title); m.put("icon", icon);
            m.put("visible", visible); m.put("order", order);
            return m;
        }
    }

    static final class Entry {
        String id, tabId, title, subtitle, body, image;
        int order = 1;
        LinkedHashMap<String, String> sections;
        List<String> connections;
        LinkedHashMap<String, Boolean> revealed = new LinkedHashMap<>();

        boolean isRevealed(String field) {
            Boolean b = revealed.get(field);
            return b != null && b;
        }
        void setRevealed(String field, boolean v) { revealed.put(field, v); }

        boolean fullyHiddenFromPlayers() {
            // Hidden if no body, no image, and no revealed section
            if (image != null && !image.isBlank()) return false;
            if (body != null && !body.isBlank()) return false;
            if (sections != null) {
                for (String k : sections.keySet()) if (isRevealed(k)) return false;
            }
            return true;
        }

        int countRevealed() {
            int n = 0;
            if (sections != null) for (String k : sections.keySet()) if (isRevealed(k)) n++;
            return n;
        }

        Map<String, Object> toMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", id); m.put("tabId", tabId); m.put("title", title);
            m.put("subtitle", subtitle); m.put("body", body); m.put("image", image);
            m.put("order", order);
            if (sections != null && !sections.isEmpty()) m.put("sections", sections);
            if (connections != null && !connections.isEmpty()) m.put("connections", connections);
            if (!revealed.isEmpty()) m.put("revealed", revealed);
            return m;
        }
        Map<String, Object> toPlayerMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", id); m.put("tabId", tabId); m.put("title", title);
            m.put("subtitle", subtitle); m.put("body", body); m.put("image", image);
            m.put("order", order);
            LinkedHashMap<String, Boolean> publicReveal = new LinkedHashMap<>();
            if (sections != null && !sections.isEmpty()) {
                // Include every section name. Hidden ones get an empty value; the player
                // app shows them as [REDACTED] based on the revealed flags below.
                LinkedHashMap<String, String> playerSections = new LinkedHashMap<>();
                for (Map.Entry<String, String> kv : sections.entrySet()) {
                    boolean rev = isRevealed(kv.getKey());
                    playerSections.put(kv.getKey(), rev ? kv.getValue() : "");
                    publicReveal.put(kv.getKey(), rev);
                }
                m.put("sections", playerSections);
            }
            if (connections != null && !connections.isEmpty()) {
                boolean rev = isRevealed("Connections");
                publicReveal.put("Connections", rev);
                if (rev) m.put("connections", connections);
            }
            if (!publicReveal.isEmpty()) m.put("revealed", publicReveal);
            return m;
        }
    }

    static final class CampaignData {
        String campaignTitle = "MEMORIES OF A FEW";
        String subtitle = "MERIDIAN SPIRE // LICENSED PI INTELLIGENCE INDEX";
        String adminPin = "2089";
        String noteTakerPin = "0451";
        List<Tab> tabs = new ArrayList<>();
        List<Entry> entries = new ArrayList<>();

        Map<String, Object> fullExport() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("campaignTitle", campaignTitle);
            m.put("subtitle", subtitle);
            m.put("adminPin", adminPin);
            m.put("noteTakerPin", noteTakerPin);
            List<Map<String,Object>> ts = new ArrayList<>();
            for (Tab t : tabs) ts.add(t.toMap());
            m.put("tabs", ts);
            List<Map<String,Object>> es = new ArrayList<>();
            for (Entry e : entries) es.add(e.toMap());
            m.put("entries", es);
            return m;
        }
        Map<String, Object> playerSnapshot() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("campaignTitle", campaignTitle);
            m.put("subtitle", subtitle);
            m.put("publishedAt", Instant.now().toString());
            List<Map<String,Object>> ts = new ArrayList<>();
            Set<String> visibleTabIds = new HashSet<>();
            for (Tab t : tabs) if (t.visible) { ts.add(t.toMap()); visibleTabIds.add(t.id); }
            m.put("tabs", ts);
            List<Map<String,Object>> es = new ArrayList<>();
            for (Entry e : entries) {
                if (e.fullyHiddenFromPlayers()) continue;
                if (!visibleTabIds.contains(e.tabId)) continue;
                es.add(e.toPlayerMap());
            }
            m.put("entries", es);
            return m;
        }

        @SuppressWarnings("unchecked")
        void loadFromJson(String json) {
            Map<String, Object> m = (Map<String, Object>) JsonReader.parse(json);
            if (m.get("campaignTitle") instanceof String s) campaignTitle = s;
            if (m.get("subtitle") instanceof String s) subtitle = s;
            if (m.get("adminPin") instanceof String s) adminPin = s;
            if (m.get("noteTakerPin") instanceof String s) noteTakerPin = s;
            tabs.clear();
            if (m.get("tabs") instanceof List<?> list) {
                for (Object o : list) {
                    if (!(o instanceof Map)) continue;
                    Map<String,Object> t = (Map<String,Object>) o;
                    Tab x = new Tab();
                    x.id = strOr(t.get("id"), "");
                    x.title = strOr(t.get("title"), x.id);
                    x.icon = strOr(t.get("icon"), "□");
                    x.visible = boolOr(t.get("visible"), true);
                    x.order = intOr(t.get("order"), 1);
                    tabs.add(x);
                }
            }
            entries.clear();
            if (m.get("entries") instanceof List<?> list) {
                for (Object o : list) {
                    if (!(o instanceof Map)) continue;
                    entries.add(entryFromMap((Map<String,Object>) o));
                }
            }
        }
        @SuppressWarnings("unchecked")
        Entry entryFromMap(Map<String,Object> e) {
            Entry x = new Entry();
            x.id       = strOr(e.get("id"), slug("entry"));
            x.tabId    = strOr(e.get("tabId"), "");
            x.title    = strOr(e.get("title"), "Untitled");
            x.subtitle = strOr(e.get("subtitle"), "");
            x.body     = strOr(e.get("body"), "");
            x.image    = strOr(e.get("image"), "");
            x.order    = intOr(e.get("order"), 1);
            if (e.get("sections") instanceof Map<?,?> sm) {
                x.sections = new LinkedHashMap<>();
                for (Map.Entry<?,?> kv : sm.entrySet())
                    x.sections.put(String.valueOf(kv.getKey()), String.valueOf(kv.getValue()));
            }
            if (e.get("connections") instanceof List<?> cl) {
                x.connections = new ArrayList<>();
                for (Object o : cl) x.connections.add(String.valueOf(o));
            }
            if (e.get("revealed") instanceof Map<?,?> rm) {
                for (Map.Entry<?,?> kv : rm.entrySet())
                    x.revealed.put(String.valueOf(kv.getKey()), Boolean.parseBoolean(String.valueOf(kv.getValue())));
            } else if (e.get("unlocked") instanceof Boolean unlocked && unlocked) {
                // Migrate from old data format — if entry was "unlocked", reveal everything not in DEFAULT_GM_ONLY
                if (x.sections != null) {
                    for (String k : x.sections.keySet()) x.revealed.put(k, !DEFAULT_GM_ONLY.contains(k));
                }
                if (x.connections != null) x.revealed.put("Connections", true);
            }
            return x;
        }

        @SuppressWarnings("unchecked")
        void seedFromBundledResources() {
            tabs.add(makeTab("factions", "Factions", "◈", 1));
            tabs.add(makeTab("npcs",     "NPCs",     "◎", 2));
            tabs.add(makeTab("maps",     "Maps",     "⌖", 3));
            tabs.add(makeTab("notes",    "Notes",    "▤", 4));

            try {
                Map<String, Object> f = (Map<String,Object>) JsonReader.parse(readResource("/data/factions.json"));
                int order = 1;
                for (Map.Entry<String,Object> kv : f.entrySet()) {
                    Map<String,Object> v = (Map<String,Object>) kv.getValue();
                    Entry e = new Entry();
                    e.id = "faction-" + kv.getKey();
                    e.tabId = "factions";
                    e.title = strOr(v.get("title"), kv.getKey());
                    e.subtitle = "MERIDIAN SPIRE FACTION DOSSIER";
                    e.order = order++;
                    e.sections = new LinkedHashMap<>();
                    if (v.get("sections") instanceof Map<?,?> sm) {
                        for (Map.Entry<?,?> s : sm.entrySet()) {
                            String key = String.valueOf(s.getKey());
                            String val = String.valueOf(s.getValue());
                            // Skip empty NPC dossier marker
                            if (key.equals("Key NPC Dossiers") && val.length() < 30) continue;
                            e.sections.put(key, val);
                        }
                    }
                    // Reveal Overview and Internal Structure by default for factions; keep Secrets hidden
                    for (String k : e.sections.keySet()) {
                        e.revealed.put(k, !DEFAULT_GM_ONLY.contains(k) && !k.contains("Secret"));
                    }
                    entries.add(e);
                }
            } catch (Exception ex) { logErrorStatic(ex); }

            try {
                List<Object> arr = (List<Object>) JsonReader.parse(readResource("/data/npcs.json"));
                int order = 1;
                for (Object o : arr) {
                    Map<String,Object> n = (Map<String,Object>) o;
                    Entry e = new Entry();
                    e.id = "npc-" + slug(strOr(n.get("name"), "npc"));
                    e.tabId = "npcs";
                    e.title = strOr(n.get("name"), "Unknown");
                    e.subtitle = strOr(n.get("role"), "") + " // " + strOr(n.get("faction"), "");
                    e.order = order++;
                    e.sections = new LinkedHashMap<>();
                    putIfPresent(e.sections, "Section",         n.get("section"));
                    putIfPresent(e.sections, "Quote",           n.get("quote"));
                    putIfPresent(e.sections, "Public Face",     n.get("public"));
                    putIfPresent(e.sections, "The Truth",       n.get("truth"));
                    putIfPresent(e.sections, "Vibe",            n.get("vibe"));
                    if (n.get("personality") instanceof List<?> pl) e.sections.put("Personality", joinList(pl));
                    putIfPresent(e.sections, "Why They Matter", n.get("why"));
                    putIfPresent(e.sections, "Secret",          n.get("secret"));
                    putIfPresent(e.sections, "First Encounter", n.get("first"));
                    putIfPresent(e.sections, "DM Notes",        n.get("dm"));
                    if (n.get("status") instanceof List<?> sl)   e.sections.put("Status", joinList(sl));
                    if (n.get("connections") instanceof List<?> cl) {
                        e.connections = new ArrayList<>();
                        for (Object x : cl) e.connections.add(String.valueOf(x));
                    }
                    // Default reveal: name, role, faction (in subtitle), quote, public face, section, status. Hide truth, secret, first, dm.
                    for (String k : e.sections.keySet()) {
                        e.revealed.put(k, !DEFAULT_GM_ONLY.contains(k));
                    }
                    e.revealed.put("Connections", false);
                    entries.add(e);
                }
            } catch (Exception ex) { logErrorStatic(ex); }

            try {
                List<Object> arr = (List<Object>) JsonReader.parse(readResource("/data/maps.json"));
                int order = 1;
                for (Object o : arr) {
                    Map<String,Object> mp = (Map<String,Object>) o;
                    Entry e = new Entry();
                    e.id = "map-" + strOr(mp.get("id"), "map");
                    e.tabId = "maps";
                    e.title = strOr(mp.get("title"), "Map");
                    e.subtitle = strOr(mp.get("subtitle"), "");
                    e.order = order++;
                    e.image = "content/maps/" + strOr(mp.get("file"), "");
                    e.sections = new LinkedHashMap<>();
                    putIfPresent(e.sections, "Description", mp.get("desc"));
                    putIfPresent(e.sections, "Population",  mp.get("population"));
                    putIfPresent(e.sections, "Scale",       mp.get("scale"));
                    putIfPresent(e.sections, "Elevation",   mp.get("elevation"));
                    putIfPresent(e.sections, "Connections", mp.get("connections"));
                    // Maps are mostly public by default
                    for (String k : e.sections.keySet()) e.revealed.put(k, true);
                    entries.add(e);
                }
            } catch (Exception ex) { logErrorStatic(ex); }
        }

        Tab makeTab(String id, String title, String icon, int order) {
            Tab t = new Tab(); t.id = id; t.title = title; t.icon = icon; t.order = order; t.visible = true; return t;
        }
        void putIfPresent(Map<String,String> m, String k, Object v) {
            if (v == null) return;
            String s = String.valueOf(v).trim();
            if (!s.isEmpty()) m.put(k, s);
        }
        String joinList(List<?> l) {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < l.size(); i++) { if (i > 0) sb.append(" · "); sb.append(l.get(i)); }
            return sb.toString();
        }
        String readResource(String path) throws IOException {
            try (InputStream is = MoafCampaignApp.class.getResourceAsStream(path)) {
                if (is == null) throw new FileNotFoundException("Resource " + path);
                return new String(is.readAllBytes(), StandardCharsets.UTF_8);
            }
        }
        static void logErrorStatic(Exception e) { e.printStackTrace(); }
        static String strOr(Object o, String d) { return o instanceof String s ? s : d; }
        static int intOr(Object o, int d) { return o instanceof Number n ? n.intValue() : d; }
        static boolean boolOr(Object o, boolean d) { return o instanceof Boolean b ? b : d; }
    }

    static final class GitHubConfig {
        String owner = "", repo = "", branch = "main", path = "campaign.json", notesPath = "notes.json", token = "";
        @SuppressWarnings("unchecked")
        void loadFromJson(String json) {
            Map<String, Object> m = (Map<String, Object>) JsonReader.parse(json);
            owner = CampaignData.strOr(m.get("owner"), "");
            repo = CampaignData.strOr(m.get("repo"), "");
            branch = CampaignData.strOr(m.get("branch"), "main");
            path = CampaignData.strOr(m.get("path"), "campaign.json");
            notesPath = CampaignData.strOr(m.get("notesPath"), "notes.json");
            token = CampaignData.strOr(m.get("token"), "");
        }
        Map<String,Object> toMap() {
            Map<String,Object> m = new LinkedHashMap<>();
            m.put("owner", owner); m.put("repo", repo); m.put("branch", branch);
            m.put("path", path); m.put("notesPath", notesPath); m.put("token", token);
            return m;
        }
    }

    static final class SessionNote {
        String id, title, body, date;
        int order;
        Map<String,Object> toMap() {
            Map<String,Object> m = new LinkedHashMap<>();
            m.put("id", id); m.put("title", title); m.put("body", body);
            m.put("date", date); m.put("order", order);
            return m;
        }
    }
    static final class SessionNotes {
        LinkedHashMap<String, SessionNote> sessions = new LinkedHashMap<>();
        Map<String,Object> toMap() {
            Map<String,Object> m = new LinkedHashMap<>();
            List<Map<String,Object>> list = new ArrayList<>();
            for (SessionNote s : sessions.values()) list.add(s.toMap());
            m.put("sessions", list);
            return m;
        }
        @SuppressWarnings("unchecked")
        void loadFromJson(String json) {
            sessions.clear();
            Map<String, Object> m = (Map<String, Object>) JsonReader.parse(json);
            if (m.get("sessions") instanceof List<?> list) {
                for (Object o : list) {
                    if (!(o instanceof Map)) continue;
                    Map<String,Object> n = (Map<String,Object>) o;
                    SessionNote s = new SessionNote();
                    s.id = CampaignData.strOr(n.get("id"), "session-" + System.currentTimeMillis());
                    s.title = CampaignData.strOr(n.get("title"), "Session");
                    s.body = CampaignData.strOr(n.get("body"), "");
                    s.date = CampaignData.strOr(n.get("date"), "");
                    s.order = CampaignData.intOr(n.get("order"), 1);
                    sessions.put(s.id, s);
                }
            }
        }
    }

    // ────────────────────────────────────────────────────────────────────────
    //  Minimal JSON read/write (no dependencies)
    // ────────────────────────────────────────────────────────────────────────
    static final class JsonReader {
        private final String s; private int i;
        JsonReader(String s) { this.s = s; this.i = 0; }
        static Object parse(String s) { return new JsonReader(s).val(); }
        Object val() {
            ws();
            if (i >= s.length()) return null;
            char c = s.charAt(i);
            if (c == '{') return obj();
            if (c == '[') return arr();
            if (c == '"') return str();
            if (c == 't' || c == 'f') return bool();
            if (c == 'n') return nul();
            return num();
        }
        void ws() { while (i < s.length() && Character.isWhitespace(s.charAt(i))) i++; }
        Map<String, Object> obj() {
            Map<String, Object> m = new LinkedHashMap<>();
            i++; ws();
            if (i < s.length() && s.charAt(i) == '}') { i++; return m; }
            while (i < s.length()) {
                ws(); String k = str(); ws();
                if (s.charAt(i) != ':') throw new RuntimeException("Expected ':' at " + i);
                i++; ws();
                m.put(k, val());
                ws();
                if (i < s.length() && s.charAt(i) == ',') { i++; continue; }
                if (i < s.length() && s.charAt(i) == '}') { i++; return m; }
                throw new RuntimeException("Bad object at " + i);
            }
            return m;
        }
        List<Object> arr() {
            List<Object> l = new ArrayList<>();
            i++; ws();
            if (i < s.length() && s.charAt(i) == ']') { i++; return l; }
            while (i < s.length()) {
                ws(); l.add(val()); ws();
                if (i < s.length() && s.charAt(i) == ',') { i++; continue; }
                if (i < s.length() && s.charAt(i) == ']') { i++; return l; }
                throw new RuntimeException("Bad array at " + i);
            }
            return l;
        }
        String str() {
            if (s.charAt(i) != '"') throw new RuntimeException("Expected '\"' at " + i);
            i++;
            StringBuilder sb = new StringBuilder();
            while (i < s.length()) {
                char c = s.charAt(i++);
                if (c == '"') return sb.toString();
                if (c == '\\') {
                    char e = s.charAt(i++);
                    switch (e) {
                        case '"': sb.append('"'); break;
                        case '\\': sb.append('\\'); break;
                        case '/': sb.append('/'); break;
                        case 'n': sb.append('\n'); break;
                        case 't': sb.append('\t'); break;
                        case 'r': sb.append('\r'); break;
                        case 'b': sb.append('\b'); break;
                        case 'f': sb.append('\f'); break;
                        case 'u':
                            String hex = s.substring(i, i+4); i += 4;
                            sb.append((char) Integer.parseInt(hex, 16));
                            break;
                        default: sb.append(e);
                    }
                } else sb.append(c);
            }
            throw new RuntimeException("Unterminated string");
        }
        Boolean bool() {
            if (s.startsWith("true", i)) { i += 4; return true; }
            if (s.startsWith("false", i)) { i += 5; return false; }
            throw new RuntimeException("Bad bool at " + i);
        }
        Object nul() {
            if (s.startsWith("null", i)) { i += 4; return null; }
            throw new RuntimeException("Bad null at " + i);
        }
        Number num() {
            int st = i;
            if (s.charAt(i) == '-') i++;
            while (i < s.length() && "0123456789.eE+-".indexOf(s.charAt(i)) >= 0) i++;
            String t = s.substring(st, i);
            if (t.contains(".") || t.contains("e") || t.contains("E")) return Double.parseDouble(t);
            return Long.parseLong(t);
        }
    }

    static final class JsonWriter {
        static String toJson(Object o) { StringBuilder sb = new StringBuilder(); write(sb, o); return sb.toString(); }
        @SuppressWarnings("unchecked")
        static void write(StringBuilder sb, Object o) {
            if (o == null) { sb.append("null"); return; }
            if (o instanceof String s) { sb.append(quote(s)); return; }
            if (o instanceof Boolean || o instanceof Number) { sb.append(o); return; }
            if (o instanceof Map<?,?> m) {
                sb.append('{'); boolean first = true;
                for (Map.Entry<?,?> e : m.entrySet()) {
                    if (!first) sb.append(','); first = false;
                    sb.append(quote(String.valueOf(e.getKey()))).append(':');
                    write(sb, e.getValue());
                }
                sb.append('}'); return;
            }
            if (o instanceof List<?> l) {
                sb.append('['); boolean first = true;
                for (Object v : l) { if (!first) sb.append(','); first = false; write(sb, v); }
                sb.append(']'); return;
            }
            sb.append(quote(o.toString()));
        }
        static String quote(String s) {
            StringBuilder sb = new StringBuilder("\"");
            for (int i = 0; i < s.length(); i++) {
                char c = s.charAt(i);
                switch (c) {
                    case '"': sb.append("\\\""); break;
                    case '\\': sb.append("\\\\"); break;
                    case '\n': sb.append("\\n"); break;
                    case '\r': sb.append("\\r"); break;
                    case '\t': sb.append("\\t"); break;
                    default:
                        if (c < 0x20) sb.append(String.format("\\u%04x", (int) c));
                        else sb.append(c);
                }
            }
            sb.append('"'); return sb.toString();
        }
    }
}
