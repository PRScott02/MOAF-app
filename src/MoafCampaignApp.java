import javafx.application.Application;
import javafx.application.Platform;
import javafx.beans.property.SimpleStringProperty;
import javafx.beans.property.StringProperty;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
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
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.*;

/**
 * Memories of a Few — Campaign Index
 * Pure JavaFX desktop app. No WebView, no embedded HTTP server, no HTML.
 *
 * Data sources:
 *   • Bundled JSON resources for the seed Factions, NPCs, and Maps content.
 *   • A user-editable campaign-master.json in the OS application data folder
 *     for everything the GM adds or modifies after install.
 *   • An optional github-config.json controlling player-snapshot publishing
 *     and the player-side update sync.
 *
 * Requires Java 21 + JavaFX 21 (controls).
 */
public final class MoafCampaignApp extends Application {

    // ── Locations ────────────────────────────────────────────────────────────
    private static final Path INSTALL_DIR = locateInstallDir();
    private static final Path DATA_DIR    = locateDataDir();
    private static final Path MASTER_FILE = DATA_DIR.resolve("campaign-master.json");
    private static final Path CONFIG_FILE = DATA_DIR.resolve("github-config.json");
    private static final Path MAPS_DIR    = INSTALL_DIR.resolve("content").resolve("maps");

    // ── Theme (BCI terminal palette) ─────────────────────────────────────────
    private static final String BG       = "#040908";
    private static final String PANEL    = "#070f0c";
    private static final String PANEL2   = "#0b1612";
    private static final String LINE     = "#1a4230";
    private static final String GREEN    = "#39ff8f";
    private static final String GREEN_D  = "#1a7a45";
    private static final String DIM      = "#6c9080";
    private static final String TEXT     = "#b8dfc8";
    private static final String RED      = "#ff3d4e";
    private static final String AMBER    = "#ffb833";
    private static final String FONT     = "Consolas, 'Courier New', monospace";

    // ── State ────────────────────────────────────────────────────────────────
    private final CampaignData data = new CampaignData();
    private final GitHubConfig ghConfig = new GitHubConfig();
    private final StringProperty currentTab = new SimpleStringProperty("factions");
    private boolean adminMode = false;

    // UI elements that need to refresh
    private VBox    sidebarTabs;
    private Label   pageTitleLabel;
    private Label   pageSubtitleLabel;
    private Label   statusLabel;
    private FlowPane cardGrid;
    private Button  adminButton;
    private HBox    adminBar;
    private Button  newEntryButton;
    private Button  manageButton;
    private Stage   primaryStage;

    // ── Entry point ──────────────────────────────────────────────────────────
    public static void main(String[] args) {
        Launcher.main(args);
    }

    public static final class Launcher {
        public static void main(String[] args) {
            Application.launch(MoafCampaignApp.class, args);
        }
    }

    // ── JavaFX lifecycle ─────────────────────────────────────────────────────
    @Override
    public void start(Stage stage) {
        this.primaryStage = stage;
        try {
            ensureDataDir();
            loadOrSeedMaster();
            loadConfig();
        } catch (Exception e) {
            logError(e);
        }

        BorderPane root = new BorderPane();
        root.setStyle("-fx-background-color: " + BG + ";");

        root.setLeft(buildSidebar());
        VBox mainColumn = new VBox(buildAdminBar(), buildHeader());
        BorderPane mainArea = new BorderPane();
        mainArea.setTop(mainColumn);
        mainArea.setCenter(buildContentArea());
        root.setCenter(mainArea);

        Scene scene = new Scene(root, 1280, 820);
        stage.setScene(scene);
        stage.setTitle("MOAF Campaign Index");
        stage.setMinWidth(900);
        stage.setMinHeight(600);

        currentTab.addListener((obs, oldVal, newVal) -> refresh());
        refresh();

        stage.setOnCloseRequest(e -> shutdown());
        stage.show();

        // Auto-sync after the window is up if configured
        if (ghConfig.autoSync && !ghConfig.owner.isBlank() && !ghConfig.repo.isBlank()) {
            CompletableFuture.runAsync(() -> syncRemote(true));
        }
    }

    @Override
    public void stop() { shutdown(); }

    private static void shutdown() {
        Platform.exit();
        System.exit(0);
    }

    // ── Sidebar ──────────────────────────────────────────────────────────────
    private VBox buildSidebar() {
        VBox side = new VBox();
        side.setPrefWidth(270);
        side.setStyle("-fx-background-color: " + PANEL + ";" +
                      "-fx-border-color: transparent " + LINE + " transparent transparent;" +
                      "-fx-border-width: 0 1 0 0;");

        // Brand block
        Label title = new Label(data.campaignTitle);
        title.setStyle(headerStyle(18, true));
        Label sub = new Label(data.subtitle);
        sub.setStyle(labelStyle(10, DIM));
        sub.setWrapText(true);

        VBox brand = new VBox(6, title, sub);
        brand.setPadding(new Insets(20, 16, 18, 18));
        brand.setStyle("-fx-border-color: transparent transparent " + LINE + " transparent;" +
                       "-fx-border-width: 0 0 1 0;");

        // Tab list
        sidebarTabs = new VBox(2);
        sidebarTabs.setPadding(new Insets(10, 0, 10, 0));
        ScrollPane tabsScroll = new ScrollPane(sidebarTabs);
        tabsScroll.setFitToWidth(true);
        tabsScroll.setStyle("-fx-background-color: transparent; -fx-background: transparent;" +
                            " -fx-border-color: transparent;");
        VBox.setVgrow(tabsScroll, Priority.ALWAYS);

        // Bottom actions
        VBox foot = new VBox(6);
        foot.setPadding(new Insets(10, 12, 12, 12));
        foot.setStyle("-fx-border-color: " + LINE + " transparent transparent transparent;" +
                      "-fx-border-width: 1 0 0 0;");

        manageButton = makeBtn("▸ MANAGE CAMPAIGN");
        manageButton.setOnAction(e -> openManager());
        manageButton.setVisible(false);
        manageButton.setManaged(false);

        Button updates = makeBtn("▸ CHECK FOR UPDATES");
        updates.setOnAction(e -> CompletableFuture.runAsync(() -> syncRemote(false)));

        adminButton = makeBtn("▸ ADMIN LOGIN");
        adminButton.setOnAction(e -> adminAction());

        foot.getChildren().addAll(manageButton, updates, adminButton);

        side.getChildren().addAll(brand, tabsScroll, foot);
        return side;
    }

    private void renderSidebarTabs() {
        sidebarTabs.getChildren().clear();
        List<Tab> visible = new ArrayList<>();
        for (Tab t : data.tabs) {
            if (adminMode || t.visible) visible.add(t);
        }
        visible.sort(Comparator.comparingInt(t -> t.order));

        for (Tab t : visible) {
            boolean active = t.id.equals(currentTab.get());
            Button btn = new Button((t.icon == null ? "□" : t.icon) + "    " + t.title.toUpperCase());
            btn.setMaxWidth(Double.MAX_VALUE);
            btn.setAlignment(Pos.CENTER_LEFT);
            btn.setPadding(new Insets(10, 16, 10, 16));
            btn.setStyle(tabButtonStyle(active));
            btn.setOnMouseEntered(e -> { if (!active) btn.setStyle(tabButtonStyle(false, true)); });
            btn.setOnMouseExited(e -> { if (!active) btn.setStyle(tabButtonStyle(false, false)); });
            btn.setOnAction(e -> currentTab.set(t.id));
            sidebarTabs.getChildren().add(btn);
        }
    }

    // ── Top admin bar ────────────────────────────────────────────────────────
    private HBox buildAdminBar() {
        Label l = new Label("◆ ADMIN MODE // MASTER DATA IS LOCAL // PUBLISH SENDS ONLY UNLOCKED CONTENT");
        l.setStyle(labelStyle(10, "#ffaaa0"));
        adminBar = new HBox(l);
        adminBar.setPadding(new Insets(7, 24, 7, 24));
        adminBar.setStyle("-fx-background-color: #2a0c0f;" +
                          "-fx-border-color: transparent transparent #7f2731 transparent;" +
                          "-fx-border-width: 0 0 1 0;");
        adminBar.setVisible(false);
        adminBar.setManaged(false);
        return adminBar;
    }

    // ── Header ───────────────────────────────────────────────────────────────
    private HBox buildHeader() {
        pageTitleLabel = new Label("INDEX");
        pageTitleLabel.setStyle(headerStyle(16, false));
        pageSubtitleLabel = new Label("PLAYER ACCESS // AUTHORIZED RECORDS ONLY");
        pageSubtitleLabel.setStyle(labelStyle(10, DIM));

        VBox titles = new VBox(4, pageTitleLabel, pageSubtitleLabel);

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        statusLabel = new Label("LOCAL");
        statusLabel.setStyle(labelStyle(10, DIM));

        newEntryButton = makeBtn("+ ENTRY");
        newEntryButton.setOnAction(e -> openEntryEditor(null));
        newEntryButton.setVisible(false);
        newEntryButton.setManaged(false);

        HBox tools = new HBox(8, statusLabel, newEntryButton);
        tools.setAlignment(Pos.CENTER_RIGHT);

        HBox header = new HBox(20, titles, spacer, tools);
        header.setAlignment(Pos.CENTER_LEFT);
        header.setPadding(new Insets(16, 24, 16, 24));
        header.setStyle("-fx-background-color: " + PANEL + ";" +
                        "-fx-border-color: transparent transparent " + LINE + " transparent;" +
                        "-fx-border-width: 0 0 1 0;");
        return header;
    }

    // ── Card grid area ───────────────────────────────────────────────────────
    private ScrollPane buildContentArea() {
        cardGrid = new FlowPane(14, 14);
        cardGrid.setPadding(new Insets(22, 24, 22, 24));
        cardGrid.setStyle("-fx-background-color: " + BG + ";");

        ScrollPane scroll = new ScrollPane(cardGrid);
        scroll.setFitToWidth(true);
        scroll.setStyle("-fx-background-color: " + BG + "; -fx-background: " + BG + ";" +
                        " -fx-border-color: transparent;");
        return scroll;
    }

    // ── Refresh entire view ──────────────────────────────────────────────────
    private void refresh() {
        renderSidebarTabs();

        adminBar.setVisible(adminMode);
        adminBar.setManaged(adminMode);
        manageButton.setVisible(adminMode);
        manageButton.setManaged(adminMode);
        newEntryButton.setVisible(adminMode);
        newEntryButton.setManaged(adminMode);
        adminButton.setText(adminMode ? "▸ EXIT ADMIN MODE" : "▸ ADMIN LOGIN");

        Tab t = findTab(currentTab.get());
        pageTitleLabel.setText(t == null ? "INDEX" : t.title.toUpperCase());
        pageSubtitleLabel.setText(adminMode
                ? "ADMIN ACCESS // LOCKED RECORDS VISIBLE"
                : "PLAYER ACCESS // AUTHORIZED RECORDS ONLY");

        cardGrid.getChildren().clear();
        List<Entry> entries = entriesForCurrentTab();
        if (entries.isEmpty()) {
            Label empty = new Label("NO AUTHORIZED RECORDS IN THIS SECTION");
            empty.setPadding(new Insets(40));
            empty.setStyle(labelStyle(11, DIM));
            cardGrid.getChildren().add(empty);
        } else {
            for (Entry e : entries) cardGrid.getChildren().add(buildCard(e));
        }
    }

    private List<Entry> entriesForCurrentTab() {
        List<Entry> out = new ArrayList<>();
        for (Entry e : data.entries) {
            if (!e.tabId.equals(currentTab.get())) continue;
            if (!adminMode && !e.unlocked) continue;
            out.add(e);
        }
        out.sort(Comparator.comparingInt(x -> x.order));
        return out;
    }

    private Tab findTab(String id) {
        for (Tab t : data.tabs) if (t.id.equals(id)) return t;
        return null;
    }

    // ── A single card ────────────────────────────────────────────────────────
    private VBox buildCard(Entry e) {
        VBox card = new VBox(8);
        card.setPrefWidth(280);
        card.setMinWidth(280);
        card.setMaxWidth(280);
        card.setPadding(new Insets(14, 14, 14, 14));
        card.setStyle(cardStyle(e.unlocked));

        if (!e.unlocked) {
            Label lock = new Label("LOCKED");
            lock.setStyle(labelStyle(9, RED) + "-fx-padding: 0 0 0 0;");
            card.getChildren().add(lock);
        }

        Label tag = new Label(e.unlocked ? "▸ AUTHORIZED RECORD" : "▸ GM-ONLY RECORD");
        tag.setStyle(labelStyle(9, DIM));

        Label title = new Label(e.title);
        title.setStyle(headerStyle(14, false));
        title.setWrapText(true);

        Label sub = new Label(e.subtitle == null ? "" : e.subtitle);
        sub.setStyle(labelStyle(10, "#8db8a0"));
        sub.setWrapText(true);

        Label body = new Label(truncate(e.body, 180));
        body.setStyle(labelStyle(11, TEXT));
        body.setWrapText(true);

        card.getChildren().addAll(tag, title, sub, body);

        if (adminMode) {
            Region spacer = new Region();
            VBox.setVgrow(spacer, Priority.ALWAYS);
            Button editBtn = makeBtn("EDIT");
            editBtn.setStyle(makeBtnSmallStyle());
            editBtn.setOnAction(ev -> {
                ev.consume();
                openEntryEditor(e);
            });
            HBox row = new HBox(editBtn);
            row.setAlignment(Pos.CENTER_RIGHT);
            card.getChildren().addAll(spacer, row);
        }

        card.setOnMouseClicked(ev -> {
            if (ev.getButton() == MouseButton.PRIMARY) openEntry(e);
        });
        card.setOnMouseEntered(ev -> card.setStyle(cardStyle(e.unlocked, true)));
        card.setOnMouseExited(ev -> card.setStyle(cardStyle(e.unlocked, false)));

        return card;
    }

    // ── Entry detail view ────────────────────────────────────────────────────
    private void openEntry(Entry e) {
        if (!adminMode && !e.unlocked) return;

        Stage dialog = new Stage();
        dialog.initOwner(primaryStage);
        dialog.initModality(Modality.WINDOW_MODAL);
        dialog.setTitle(e.title);

        VBox content = new VBox(12);
        content.setPadding(new Insets(24, 28, 24, 28));
        content.setStyle("-fx-background-color: " + PANEL + ";");

        Label title = new Label(e.title);
        title.setStyle(headerStyle(20, true));
        title.setWrapText(true);

        if (e.subtitle != null && !e.subtitle.isBlank()) {
            Label sub = new Label(e.subtitle);
            sub.setStyle(labelStyle(11, DIM));
            sub.setWrapText(true);
            content.getChildren().addAll(title, sub);
        } else {
            content.getChildren().add(title);
        }

        // Image: either a bundled file (relative path under content/) or absent
        if (e.image != null && !e.image.isBlank()) {
            try {
                Path imgPath = INSTALL_DIR.resolve(e.image);
                if (Files.exists(imgPath)) {
                    ImageView iv = new ImageView(new Image(imgPath.toUri().toString()));
                    iv.setPreserveRatio(true);
                    iv.setFitWidth(900);
                    iv.setSmooth(true);
                    content.getChildren().add(iv);
                }
            } catch (Exception ignored) {}
        }

        // Structured sections (Map of section header → text), if present
        if (e.sections != null && !e.sections.isEmpty()) {
            for (Map.Entry<String, String> s : e.sections.entrySet()) {
                Label h = new Label("▸ " + s.getKey().toUpperCase());
                h.setStyle(labelStyle(12, GREEN) + " -fx-font-weight: bold;");
                Label v = new Label(s.getValue());
                v.setStyle(labelStyle(12, TEXT));
                v.setWrapText(true);
                VBox block = new VBox(6, h, v);
                content.getChildren().add(block);
            }
        }

        // Free-form body
        if (e.body != null && !e.body.isBlank()) {
            Label body = new Label(e.body);
            body.setStyle(labelStyle(12, TEXT));
            body.setWrapText(true);
            content.getChildren().add(body);
        }

        // Connections list, if any
        if (e.connections != null && !e.connections.isEmpty()) {
            Label h = new Label("▸ CONNECTIONS");
            h.setStyle(labelStyle(12, GREEN) + " -fx-font-weight: bold;");
            Label v = new Label(String.join(" · ", e.connections));
            v.setStyle(labelStyle(11, "#9bc9b0"));
            v.setWrapText(true);
            content.getChildren().addAll(h, v);
        }

        // Close button
        Button close = makeBtn("CLOSE");
        close.setOnAction(ev -> dialog.close());
        HBox btnRow = new HBox(close);
        btnRow.setAlignment(Pos.CENTER_RIGHT);
        btnRow.setPadding(new Insets(8, 0, 0, 0));
        content.getChildren().add(btnRow);

        ScrollPane scroll = new ScrollPane(content);
        scroll.setFitToWidth(true);
        scroll.setStyle("-fx-background-color: " + PANEL + "; -fx-background: " + PANEL + ";" +
                        " -fx-border-color: transparent;");
        Scene s = new Scene(scroll, 1000, 720);
        dialog.setScene(s);
        dialog.show();
    }

    // ── Admin login / mode toggle ────────────────────────────────────────────
    private void adminAction() {
        if (adminMode) {
            adminMode = false;
            toast("Admin mode closed");
            refresh();
            return;
        }
        TextInputDialog dlg = new TextInputDialog();
        dlg.initOwner(primaryStage);
        dlg.setTitle("Admin Authorization");
        dlg.setHeaderText("Enter admin PIN");
        dlg.setContentText("PIN:");
        Optional<String> r = dlg.showAndWait();
        r.ifPresent(pin -> {
            if (pin.equals(data.adminPin)) {
                adminMode = true;
                toast("Admin mode enabled");
                refresh();
            } else {
                toast("Incorrect PIN");
            }
        });
    }

    // ── Entry editor (admin) ─────────────────────────────────────────────────
    private void openEntryEditor(Entry existing) {
        Stage dlg = new Stage();
        dlg.initOwner(primaryStage);
        dlg.initModality(Modality.WINDOW_MODAL);
        dlg.setTitle(existing == null ? "New Entry" : "Edit Entry");

        TextField tTitle = new TextField(existing == null ? "" : existing.title);
        TextField tSub   = new TextField(existing == null ? "" : existing.subtitle);
        TextArea  tBody  = new TextArea(existing == null ? "" : existing.body);
        tBody.setPrefRowCount(8);
        tBody.setWrapText(true);

        ComboBox<String> tTab = new ComboBox<>();
        for (Tab t : data.tabs) tTab.getItems().add(t.id);
        tTab.setValue(existing == null ? currentTab.get() : existing.tabId);

        TextField tImage = new TextField(existing == null ? "" : existing.image);

        Spinner<Integer> tOrder = new Spinner<>(1, 9999, existing == null ? 1 : existing.order);
        tOrder.setEditable(true);

        ComboBox<String> tUnlocked = new ComboBox<>();
        tUnlocked.getItems().addAll("Unlocked (player visible)", "Locked (GM only)");
        tUnlocked.setValue(existing != null && existing.unlocked
                ? "Unlocked (player visible)" : "Locked (GM only)");

        GridPane g = new GridPane();
        g.setHgap(10); g.setVgap(10);
        g.setPadding(new Insets(20));
        g.setStyle("-fx-background-color: " + PANEL + ";");
        int row = 0;
        g.add(label("Title"), 0, row);      g.add(tTitle, 1, row++);
        g.add(label("Subtitle"), 0, row);   g.add(tSub, 1, row++);
        g.add(label("Tab"), 0, row);        g.add(tTab, 1, row++);
        g.add(label("Order"), 0, row);      g.add(tOrder, 1, row++);
        g.add(label("Image path"), 0, row); g.add(tImage, 1, row++);
        g.add(label("Visibility"), 0, row); g.add(tUnlocked, 1, row++);
        g.add(label("Body"), 0, row);       g.add(tBody, 1, row++);

        tTitle.setPrefColumnCount(40);

        Button save = makeBtn("SAVE");
        Button cancel = makeBtn("CANCEL");
        Button del = makeBtn("DELETE");
        del.setStyle(makeBtnStyle("#ff8891", "#6b252d"));
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
            target.unlocked = tUnlocked.getValue().startsWith("Unlocked");
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
                    data.entries.remove(existing);
                    saveLocal();
                    refresh();
                    dlg.close();
                    toast("Entry deleted");
                }
            });
        });

        HBox btns = new HBox(8, save, cancel, del);
        btns.setAlignment(Pos.CENTER_RIGHT);
        btns.setPadding(new Insets(14, 20, 20, 20));
        btns.setStyle("-fx-background-color: " + PANEL + ";");

        VBox layout = new VBox(g, btns);
        layout.setStyle("-fx-background-color: " + PANEL + ";");
        Scene scene = new Scene(layout, 720, 640);
        dlg.setScene(scene);
        dlg.show();
    }

    // ── Campaign manager (admin) ─────────────────────────────────────────────
    private void openManager() {
        Stage dlg = new Stage();
        dlg.initOwner(primaryStage);
        dlg.initModality(Modality.WINDOW_MODAL);
        dlg.setTitle("Campaign Manager");

        TextField mTitle = new TextField(data.campaignTitle);
        TextField mSub   = new TextField(data.subtitle);
        PasswordField mPin = new PasswordField(); mPin.setText(data.adminPin);

        TextField gOwner = new TextField(ghConfig.owner);
        TextField gRepo  = new TextField(ghConfig.repo);
        TextField gBranch = new TextField(ghConfig.branch);
        TextField gPath  = new TextField(ghConfig.path);
        PasswordField gToken = new PasswordField(); gToken.setText(ghConfig.token);

        GridPane g = new GridPane();
        g.setHgap(10); g.setVgap(8);
        g.setPadding(new Insets(20));
        int r = 0;
        g.add(headerLabel("CAMPAIGN SETTINGS"), 0, r++, 2, 1);
        g.add(label("Title"), 0, r);    g.add(mTitle, 1, r++);
        g.add(label("Subtitle"), 0, r); g.add(mSub,   1, r++);
        g.add(label("Admin PIN"), 0, r); g.add(mPin,  1, r++);

        Region sp = new Region(); sp.setMinHeight(14);
        g.add(sp, 0, r++);
        g.add(headerLabel("GITHUB PUBLISHING"), 0, r++, 2, 1);
        Label notice = new Label("Use a public repository for the player snapshot.\n" +
                "Locked entries are never uploaded.\n" +
                "Your token is stored only on this machine.");
        notice.setStyle(labelStyle(10, AMBER));
        notice.setWrapText(true);
        g.add(notice, 0, r++, 2, 1);

        g.add(label("Owner / username"), 0, r); g.add(gOwner, 1, r++);
        g.add(label("Repository"), 0, r);       g.add(gRepo,  1, r++);
        g.add(label("Branch"), 0, r);           g.add(gBranch, 1, r++);
        g.add(label("Player data path"), 0, r); g.add(gPath, 1, r++);
        g.add(label("Token (PAT)"), 0, r);      g.add(gToken, 1, r++);

        Button save = makeBtn("SAVE SETTINGS");
        Button publish = makeBtn("PUBLISH PLAYER VIEW");
        Button test = makeBtn("TEST CONNECTION");
        Button export = makeBtn("EXPORT MASTER");
        Button close = makeBtn("CLOSE");

        save.setOnAction(e -> {
            data.campaignTitle = mTitle.getText().isBlank() ? "MEMORIES OF A FEW" : mTitle.getText().trim();
            data.subtitle = mSub.getText().trim();
            if (!mPin.getText().isBlank()) data.adminPin = mPin.getText();
            ghConfig.owner = gOwner.getText().trim();
            ghConfig.repo  = gRepo.getText().trim();
            ghConfig.branch = gBranch.getText().isBlank() ? "main" : gBranch.getText().trim();
            ghConfig.path = gPath.getText().isBlank() ? "campaign.json" : gPath.getText().trim();
            ghConfig.token = gToken.getText().trim();
            saveLocal();
            saveConfig();
            refresh();
            toast("Settings saved");
        });
        publish.setOnAction(e -> {
            save.fire();
            CompletableFuture.runAsync(this::publishGithub);
        });
        test.setOnAction(e -> {
            save.fire();
            CompletableFuture.runAsync(this::testGithub);
        });
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
        Scene scene = new Scene(scroll, 720, 720);
        dlg.setScene(scene);
        dlg.show();
    }

    // ── Toast ────────────────────────────────────────────────────────────────
    private void toast(String message) {
        Platform.runLater(() -> {
            Stage toast = new Stage();
            toast.initOwner(primaryStage);
            toast.initStyle(javafx.stage.StageStyle.UNDECORATED);
            Label l = new Label(message);
            l.setStyle(labelStyle(11, TEXT) + " -fx-padding: 12 16 12 16;" +
                       " -fx-background-color: " + PANEL2 + ";" +
                       " -fx-border-color: " + GREEN + "; -fx-border-width: 1;");
            Scene s = new Scene(l);
            s.setFill(Color.TRANSPARENT);
            toast.setScene(s);
            toast.show();
            toast.setX(primaryStage.getX() + primaryStage.getWidth() - 280);
            toast.setY(primaryStage.getY() + primaryStage.getHeight() - 80);
            new Timer(true).schedule(new TimerTask() {
                @Override public void run() { Platform.runLater(toast::close); }
            }, 2800);
        });
    }

    // ── GitHub publish/sync/test ─────────────────────────────────────────────
    private void publishGithub() {
        if (ghConfig.owner.isBlank() || ghConfig.repo.isBlank() || ghConfig.token.isBlank()) {
            toast("Complete the GitHub settings first"); return;
        }
        try {
            String urlBase = "https://api.github.com/repos/" + ghConfig.owner + "/" + ghConfig.repo
                    + "/contents/" + ghConfig.path;
            String getUrl = urlBase + "?ref=" + ghConfig.branch;

            HttpClient client = HttpClient.newHttpClient();
            HttpRequest getReq = HttpRequest.newBuilder(URI.create(getUrl))
                    .header("Accept", "application/vnd.github+json")
                    .header("Authorization", "Bearer " + ghConfig.token)
                    .header("X-GitHub-Api-Version", "2022-11-28")
                    .GET().build();
            HttpResponse<String> getResp = client.send(getReq, HttpResponse.BodyHandlers.ofString());
            String sha = null;
            if (getResp.statusCode() == 200) {
                String b = getResp.body();
                int idx = b.indexOf("\"sha\":\"");
                if (idx >= 0) {
                    int s = idx + 7;
                    int e = b.indexOf("\"", s);
                    sha = b.substring(s, e);
                }
            }

            String snapshot = JsonWriter.toJson(data.playerSnapshot());
            String b64 = Base64.getEncoder().encodeToString(snapshot.getBytes(StandardCharsets.UTF_8));
            StringBuilder body = new StringBuilder();
            body.append("{\"message\":\"Publish MOAF player campaign data\",")
                .append("\"branch\":").append(JsonWriter.quote(ghConfig.branch)).append(",")
                .append("\"content\":\"").append(b64).append("\"");
            if (sha != null) body.append(",\"sha\":\"").append(sha).append("\"");
            body.append("}");

            HttpRequest put = HttpRequest.newBuilder(URI.create(urlBase))
                    .header("Accept", "application/vnd.github+json")
                    .header("Authorization", "Bearer " + ghConfig.token)
                    .header("X-GitHub-Api-Version", "2022-11-28")
                    .header("Content-Type", "application/json")
                    .PUT(HttpRequest.BodyPublishers.ofString(body.toString())).build();
            HttpResponse<String> putResp = client.send(put, HttpResponse.BodyHandlers.ofString());
            if (putResp.statusCode() >= 200 && putResp.statusCode() < 300) {
                Platform.runLater(() -> {
                    statusLabel.setText("PUBLISHED " + LocalTime.now().format(DateTimeFormatter.ofPattern("HH:mm:ss")));
                });
                toast("Unlocked player content published");
            } else {
                toast("Publish failed: HTTP " + putResp.statusCode());
            }
        } catch (Exception ex) {
            toast("Publish failed: " + ex.getMessage());
        }
    }

    private void testGithub() {
        try {
            String url = "https://api.github.com/repos/" + ghConfig.owner + "/" + ghConfig.repo;
            HttpClient c = HttpClient.newHttpClient();
            HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                    .header("Accept", "application/vnd.github+json")
                    .header("Authorization", "Bearer " + ghConfig.token)
                    .GET().build();
            HttpResponse<String> resp = c.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() == 200) toast("GitHub connection successful");
            else toast("Connection failed: HTTP " + resp.statusCode());
        } catch (Exception ex) {
            toast("Connection failed: " + ex.getMessage());
        }
    }

    private void syncRemote(boolean silent) {
        if (ghConfig.owner.isBlank() || ghConfig.repo.isBlank()) {
            if (!silent) toast("Online updates are not configured yet");
            return;
        }
        try {
            String url = "https://raw.githubusercontent.com/" + ghConfig.owner + "/" + ghConfig.repo
                       + "/" + ghConfig.branch + "/" + ghConfig.path + "?t=" + System.currentTimeMillis();
            HttpClient c = HttpClient.newHttpClient();
            HttpResponse<String> r = c.send(
                    HttpRequest.newBuilder(URI.create(url)).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            if (r.statusCode() != 200) {
                if (!silent) toast("Player data not found"); return;
            }
            if (!adminMode) data.applyFromSnapshotJson(r.body());
            Platform.runLater(() -> {
                statusLabel.setText("UPDATED " + LocalTime.now().format(DateTimeFormatter.ofPattern("HH:mm:ss")));
                refresh();
            });
            if (!silent) toast("Campaign updated");
        } catch (Exception ex) {
            if (!silent) toast("Update failed: " + ex.getMessage());
        }
    }

    private void exportMaster() {
        try {
            String path = System.getProperty("user.home") + "/moaf-campaign-master.json";
            Files.writeString(Path.of(path), JsonWriter.toJson(data.fullExport()), StandardCharsets.UTF_8);
            toast("Exported to home folder");
        } catch (Exception e) {
            toast("Export failed: " + e.getMessage());
        }
    }

    // ── Styles ───────────────────────────────────────────────────────────────
    private String headerStyle(int size, boolean bright) {
        return "-fx-text-fill: " + (bright ? GREEN : "#9cffc7") + ";" +
               "-fx-font-family: " + FONT + ";" +
               "-fx-font-size: " + size + "px;" +
               "-fx-font-weight: bold;" +
               "-fx-padding: 0;";
    }
    private String labelStyle(int size, String color) {
        return "-fx-text-fill: " + color + ";" +
               "-fx-font-family: " + FONT + ";" +
               "-fx-font-size: " + size + "px;";
    }
    private String tabButtonStyle(boolean active) { return tabButtonStyle(active, false); }
    private String tabButtonStyle(boolean active, boolean hover) {
        String fg = active ? GREEN : (hover ? "#a8d6bd" : DIM);
        String bg = active ? "rgba(57,255,143,0.06)" : (hover ? "rgba(57,255,143,0.03)" : "transparent");
        String border = active ? GREEN : "transparent";
        return "-fx-background-color: " + bg + ";" +
               "-fx-text-fill: " + fg + ";" +
               "-fx-font-family: " + FONT + ";" +
               "-fx-font-size: 12px;" +
               "-fx-letter-spacing: 2px;" +
               "-fx-padding: 10 16 10 16;" +
               "-fx-border-color: transparent transparent transparent " + border + ";" +
               "-fx-border-width: 0 0 0 3;" +
               "-fx-background-radius: 0;" +
               "-fx-cursor: hand;";
    }
    private String cardStyle(boolean unlocked) { return cardStyle(unlocked, false); }
    private String cardStyle(boolean unlocked, boolean hover) {
        String border = hover ? "#2d7a52" : LINE;
        return "-fx-background-color: " + PANEL2 + ";" +
               "-fx-border-color: " + border + ";" +
               "-fx-border-width: 1;" +
               "-fx-background-radius: 0;" +
               "-fx-cursor: hand;" +
               (unlocked ? "" : "-fx-opacity: 0.55;");
    }
    private String makeBtnStyle() { return makeBtnStyle(GREEN, LINE); }
    private String makeBtnStyle(String fg, String border) {
        return "-fx-background-color: " + PANEL2 + ";" +
               "-fx-text-fill: " + fg + ";" +
               "-fx-font-family: " + FONT + ";" +
               "-fx-font-size: 11px;" +
               "-fx-border-color: " + border + ";" +
               "-fx-border-width: 1;" +
               "-fx-background-radius: 0;" +
               "-fx-padding: 8 14 8 14;" +
               "-fx-cursor: hand;";
    }
    private String makeBtnSmallStyle() {
        return makeBtnStyle() + "-fx-font-size: 10px; -fx-padding: 5 9 5 9;";
    }
    private Button makeBtn(String text) {
        Button b = new Button(text);
        b.setStyle(makeBtnStyle());
        b.setOnMouseEntered(e -> b.setStyle(makeBtnStyle().replace(PANEL2, "#0e211a")));
        b.setOnMouseExited(e -> b.setStyle(makeBtnStyle()));
        return b;
    }
    private Label label(String s) {
        Label l = new Label(s);
        l.setStyle(labelStyle(10, DIM));
        return l;
    }
    private Label headerLabel(String s) {
        Label l = new Label("▸ " + s);
        l.setStyle(labelStyle(11, GREEN) + " -fx-font-weight: bold;");
        return l;
    }

    // ── Utilities ────────────────────────────────────────────────────────────
    private static String truncate(String s, int max) {
        if (s == null) return "";
        s = s.trim();
        if (s.length() <= max) return s;
        return s.substring(0, max).trim() + "…";
    }
    private static String slug(String s) {
        if (s == null || s.isBlank()) s = "entry";
        s = s.toLowerCase().replaceAll("[^a-z0-9]+", "-").replaceAll("^-|-$", "");
        return s + "-" + Long.toString(System.currentTimeMillis(), 36);
    }

    // ── Locations ────────────────────────────────────────────────────────────
    private static Path locateInstallDir() {
        try {
            Path code = Path.of(MoafCampaignApp.class.getProtectionDomain()
                                                     .getCodeSource().getLocation().toURI());
            return Files.isRegularFile(code) ? code.getParent().toAbsolutePath()
                                             : Path.of("").toAbsolutePath();
        } catch (Exception ignored) { return Path.of("").toAbsolutePath(); }
    }
    private static Path locateDataDir() {
        String localAppData = System.getenv("LOCALAPPDATA");
        Path base = (localAppData != null && !localAppData.isBlank())
                ? Path.of(localAppData)
                : Path.of(System.getProperty("user.home"), ".local", "share");
        return base.resolve("MOAF Campaign Index").toAbsolutePath();
    }

    private void ensureDataDir() throws IOException {
        Files.createDirectories(DATA_DIR);
    }

    // ── Load / save ──────────────────────────────────────────────────────────
    private void loadOrSeedMaster() {
        if (Files.exists(MASTER_FILE)) {
            try {
                data.loadFromJson(Files.readString(MASTER_FILE, StandardCharsets.UTF_8));
                return;
            } catch (Exception ex) {
                logError(ex);
            }
        }
        data.seedFromBundledResources();
        saveLocal();
    }

    private void loadConfig() {
        if (Files.exists(CONFIG_FILE)) {
            try {
                ghConfig.loadFromJson(Files.readString(CONFIG_FILE, StandardCharsets.UTF_8));
            } catch (Exception ignored) {}
        }
    }

    private void saveLocal() {
        try {
            Files.writeString(MASTER_FILE, JsonWriter.toJson(data.fullExport()), StandardCharsets.UTF_8);
        } catch (Exception ex) { logError(ex); }
    }
    private void saveConfig() {
        try {
            Files.writeString(CONFIG_FILE, JsonWriter.toJson(ghConfig.toMap()), StandardCharsets.UTF_8);
        } catch (Exception ex) { logError(ex); }
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

    // ── Data classes ─────────────────────────────────────────────────────────
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
        boolean unlocked = true;
        // Optional structured content (used for converted faction/NPC/map entries)
        LinkedHashMap<String, String> sections;
        List<String> connections;
        Map<String, Object> toMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", id); m.put("tabId", tabId); m.put("title", title);
            m.put("subtitle", subtitle); m.put("body", body); m.put("image", image);
            m.put("order", order); m.put("unlocked", unlocked);
            if (sections != null && !sections.isEmpty()) m.put("sections", sections);
            if (connections != null && !connections.isEmpty()) m.put("connections", connections);
            return m;
        }
    }

    static final class CampaignData {
        String campaignTitle = "MEMORIES OF A FEW";
        String subtitle = "MERIDIAN SPIRE // LICENSED PI INTELLIGENCE INDEX";
        String adminPin = "2089";
        List<Tab> tabs = new ArrayList<>();
        List<Entry> entries = new ArrayList<>();

        Map<String, Object> fullExport() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("campaignTitle", campaignTitle);
            m.put("subtitle", subtitle);
            m.put("adminPin", adminPin);
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
            m.put("publishedAt", java.time.Instant.now().toString());
            List<Map<String,Object>> ts = new ArrayList<>();
            Set<String> visibleTabIds = new HashSet<>();
            for (Tab t : tabs) {
                if (t.visible) { ts.add(t.toMap()); visibleTabIds.add(t.id); }
            }
            m.put("tabs", ts);
            List<Map<String,Object>> es = new ArrayList<>();
            for (Entry e : entries) {
                if (!e.unlocked || !visibleTabIds.contains(e.tabId)) continue;
                es.add(e.toMap());
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
            tabs.clear();
            if (m.get("tabs") instanceof List<?> list) {
                for (Object o : list) {
                    if (!(o instanceof Map)) continue;
                    Map<String,Object> t = (Map<String,Object>) o;
                    Tab x = new Tab();
                    x.id      = strOr(t.get("id"), "");
                    x.title   = strOr(t.get("title"), x.id);
                    x.icon    = strOr(t.get("icon"), "□");
                    x.visible = boolOr(t.get("visible"), true);
                    x.order   = intOr(t.get("order"), 1);
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
        void applyFromSnapshotJson(String json) {
            Map<String, Object> m = (Map<String, Object>) JsonReader.parse(json);
            if (m.get("campaignTitle") instanceof String s) campaignTitle = s;
            if (m.get("subtitle") instanceof String s) subtitle = s;
            tabs.clear();
            if (m.get("tabs") instanceof List<?> list) {
                for (Object o : list) {
                    if (!(o instanceof Map)) continue;
                    Map<String,Object> t = (Map<String,Object>) o;
                    Tab x = new Tab();
                    x.id = strOr(t.get("id"), "");
                    x.title = strOr(t.get("title"), x.id);
                    x.icon  = strOr(t.get("icon"), "□");
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
            x.unlocked = boolOr(e.get("unlocked"), true);
            if (e.get("sections") instanceof Map<?,?> sm) {
                x.sections = new LinkedHashMap<>();
                for (Map.Entry<?,?> kv : sm.entrySet()) {
                    x.sections.put(String.valueOf(kv.getKey()), String.valueOf(kv.getValue()));
                }
            }
            if (e.get("connections") instanceof List<?> cl) {
                x.connections = new ArrayList<>();
                for (Object o : cl) x.connections.add(String.valueOf(o));
            }
            return x;
        }

        /** Build the initial campaign from bundled JSON resources + default tabs. */
        void seedFromBundledResources() {
            // Default tabs
            tabs.add(makeTab("factions", "Factions", "◈", 1));
            tabs.add(makeTab("npcs",     "NPCs",     "◎", 2));
            tabs.add(makeTab("maps",     "Maps",     "⌖", 3));
            tabs.add(makeTab("notes",    "Notes",    "▤", 4));

            // Factions
            try {
                String raw = readResource("/data/factions.json");
                Map<String, Object> f = (Map<String,Object>) JsonReader.parse(raw);
                int order = 1;
                for (Map.Entry<String,Object> kv : f.entrySet()) {
                    Map<String,Object> v = (Map<String,Object>) kv.getValue();
                    Entry e = new Entry();
                    e.id = "faction-" + kv.getKey();
                    e.tabId = "factions";
                    e.title = strOr(v.get("title"), kv.getKey());
                    e.subtitle = "MERIDIAN SPIRE FACTION DOSSIER";
                    e.order = order++;
                    e.unlocked = true;
                    e.sections = new LinkedHashMap<>();
                    if (v.get("sections") instanceof Map<?,?> sm) {
                        for (Map.Entry<?,?> s : sm.entrySet()) {
                            e.sections.put(String.valueOf(s.getKey()), String.valueOf(s.getValue()));
                        }
                    }
                    entries.add(e);
                }
            } catch (Exception ex) { logErrorStatic(ex); }

            // NPCs
            try {
                String raw = readResource("/data/npcs.json");
                List<Object> arr = (List<Object>) JsonReader.parse(raw);
                int order = 1;
                for (Object o : arr) {
                    Map<String,Object> n = (Map<String,Object>) o;
                    Entry e = new Entry();
                    e.id = "npc-" + slug(strOr(n.get("name"), "npc"));
                    e.tabId = "npcs";
                    e.title = strOr(n.get("name"), "Unknown");
                    e.subtitle = strOr(n.get("role"), "") + " // " + strOr(n.get("faction"), "");
                    e.order = order++;
                    e.unlocked = true;
                    e.sections = new LinkedHashMap<>();
                    putIfPresent(e.sections, "Section", n.get("section"));
                    putIfPresent(e.sections, "Quote",         n.get("quote"));
                    putIfPresent(e.sections, "Public Face",   n.get("public"));
                    putIfPresent(e.sections, "The Truth",     n.get("truth"));
                    putIfPresent(e.sections, "Vibe",          n.get("vibe"));
                    if (n.get("personality") instanceof List<?> pl) {
                        e.sections.put("Personality", joinList(pl));
                    }
                    putIfPresent(e.sections, "Why They Matter", n.get("why"));
                    putIfPresent(e.sections, "Secret",          n.get("secret"));
                    putIfPresent(e.sections, "First Encounter", n.get("first"));
                    putIfPresent(e.sections, "DM Notes",        n.get("dm"));
                    if (n.get("status") instanceof List<?> sl) {
                        e.sections.put("Status", joinList(sl));
                    }
                    if (n.get("connections") instanceof List<?> cl) {
                        e.connections = new ArrayList<>();
                        for (Object x : cl) e.connections.add(String.valueOf(x));
                    }
                    entries.add(e);
                }
            } catch (Exception ex) { logErrorStatic(ex); }

            // Maps
            try {
                String raw = readResource("/data/maps.json");
                List<Object> arr = (List<Object>) JsonReader.parse(raw);
                int order = 1;
                for (Object o : arr) {
                    Map<String,Object> mp = (Map<String,Object>) o;
                    Entry e = new Entry();
                    e.id = "map-" + strOr(mp.get("id"), "map");
                    e.tabId = "maps";
                    e.title = strOr(mp.get("title"), "Map");
                    e.subtitle = strOr(mp.get("subtitle"), "");
                    e.order = order++;
                    e.unlocked = true;
                    e.image = "content/maps/" + strOr(mp.get("file"), "");
                    e.sections = new LinkedHashMap<>();
                    putIfPresent(e.sections, "Description", mp.get("desc"));
                    putIfPresent(e.sections, "Population",  mp.get("population"));
                    putIfPresent(e.sections, "Scale",       mp.get("scale"));
                    putIfPresent(e.sections, "Elevation",   mp.get("elevation"));
                    putIfPresent(e.sections, "Connections", mp.get("connections"));
                    entries.add(e);
                }
            } catch (Exception ex) { logErrorStatic(ex); }

            // Default note
            Entry note = new Entry();
            note.id = "session-notes";
            note.tabId = "notes";
            note.title = "Case Notes";
            note.subtitle = "Shared investigation record";
            note.body = "No notes have been entered yet.";
            note.unlocked = true;
            note.order = 1;
            entries.add(note);
        }

        Tab makeTab(String id, String title, String icon, int order) {
            Tab t = new Tab(); t.id = id; t.title = title; t.icon = icon; t.order = order; t.visible = true;
            return t;
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

        static String strOr(Object o, String d)  { return o instanceof String s ? s : d; }
        static int intOr(Object o, int d)        { return o instanceof Number n ? n.intValue() : d; }
        static boolean boolOr(Object o, boolean d){ return o instanceof Boolean b ? b : d; }
    }

    static final class GitHubConfig {
        String owner = "", repo = "", branch = "main", path = "campaign.json", token = "";
        boolean autoSync = true;

        @SuppressWarnings("unchecked")
        void loadFromJson(String json) {
            Map<String, Object> m = (Map<String, Object>) JsonReader.parse(json);
            owner    = CampaignData.strOr(m.get("owner"), "");
            repo     = CampaignData.strOr(m.get("repo"), "");
            branch   = CampaignData.strOr(m.get("branch"), "main");
            path     = CampaignData.strOr(m.get("path"), "campaign.json");
            token    = CampaignData.strOr(m.get("token"), "");
            autoSync = CampaignData.boolOr(m.get("autoSync"), true);
        }
        Map<String,Object> toMap() {
            Map<String,Object> m = new LinkedHashMap<>();
            m.put("owner", owner); m.put("repo", repo); m.put("branch", branch);
            m.put("path", path); m.put("token", token); m.put("autoSync", autoSync);
            return m;
        }
    }

    // ── Minimal JSON reader/writer (no external dependencies) ────────────────
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
                ws();
                String k = str();
                ws();
                if (s.charAt(i) != ':') throw new RuntimeException("Expected ':' at " + i);
                i++;
                ws();
                Object v = val();
                m.put(k, v);
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
                ws();
                l.add(val());
                ws();
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
        static String toJson(Object o) {
            StringBuilder sb = new StringBuilder();
            write(sb, o);
            return sb.toString();
        }
        @SuppressWarnings("unchecked")
        static void write(StringBuilder sb, Object o) {
            if (o == null) { sb.append("null"); return; }
            if (o instanceof String s) { sb.append(quote(s)); return; }
            if (o instanceof Boolean || o instanceof Number) { sb.append(o); return; }
            if (o instanceof Map<?,?> m) {
                sb.append('{');
                boolean first = true;
                for (Map.Entry<?,?> e : m.entrySet()) {
                    if (!first) sb.append(',');
                    first = false;
                    sb.append(quote(String.valueOf(e.getKey()))).append(':');
                    write(sb, e.getValue());
                }
                sb.append('}');
                return;
            }
            if (o instanceof List<?> l) {
                sb.append('[');
                boolean first = true;
                for (Object v : l) {
                    if (!first) sb.append(',');
                    first = false;
                    write(sb, v);
                }
                sb.append(']');
                return;
            }
            sb.append(quote(o.toString()));
        }
        static String quote(String s) {
            StringBuilder sb = new StringBuilder("\"");
            for (int i = 0; i < s.length(); i++) {
                char c = s.charAt(i);
                switch (c) {
                    case '"':  sb.append("\\\""); break;
                    case '\\': sb.append("\\\\"); break;
                    case '\n': sb.append("\\n");  break;
                    case '\r': sb.append("\\r");  break;
                    case '\t': sb.append("\\t");  break;
                    default:
                        if (c < 0x20) sb.append(String.format("\\u%04x", (int) c));
                        else sb.append(c);
                }
            }
            sb.append('"');
            return sb.toString();
        }
    }
}
