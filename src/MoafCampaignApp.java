import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import javafx.application.Application;
import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.scene.image.Image;
import javafx.scene.web.WebEngine;
import javafx.scene.web.WebView;
import javafx.stage.Stage;

import java.io.*;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.concurrent.Executors;

/**
 * Memories of a Few – Campaign Index
 * JavaFX desktop app. Starts a local HTTP server and displays the UI in an
 * embedded WebView — no external browser required.
 * Requires Java 21+ with JavaFX.
 */
public final class MoafCampaignApp extends Application {

    private static final int    PORT         = 38921;
    private static final Path   INSTALL_DIR  = locateInstallDir();
    private static final Path   DATA_DIR     = locateDataDir();
    private static final Path   MASTER_FILE  = DATA_DIR.resolve("campaign-master.json");
    private static final Path   CONFIG_FILE  = DATA_DIR.resolve("github-config.json");
    private static final Path   LEGACY_DIR   = INSTALL_DIR.resolve("content").resolve("legacy");

    // ── Entry point ──────────────────────────────────────────────────────────
    //
    // A separate launcher class (that does NOT extend Application) is used as the
    // packaged main class. Launching JavaFX from a non-Application class avoids the
    // "JavaFX runtime components are missing" error that occurs when an
    // Application subclass is invoked directly as the main class of a packaged app.

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
        try {
            ensureInitialFiles();
            startServer();
        } catch (Exception e) {
            logError(e);
        }

        WebView webView = new WebView();
        WebEngine engine = webView.getEngine();

        // GRAY font smoothing composites faster than the default LCD subpixel
        // mode in JavaFX WebView, reducing per-frame work while scrolling.
        webView.setFontSmoothingType(javafx.scene.text.FontSmoothingType.GRAY);

        // Allow the JS inside the page to call fetch() against localhost
        engine.setUserAgent("MOAFCampaignIndex/1.0");

        // Intercept JS alert/confirm/prompt so they display natively if needed
        engine.setOnAlert(ev -> {
            javafx.scene.control.Alert alert = new javafx.scene.control.Alert(
                    javafx.scene.control.Alert.AlertType.INFORMATION, ev.getData());
            alert.setHeaderText(null);
            alert.showAndWait();
        });

        Scene scene = new Scene(webView, 1280, 820);
        scene.getStylesheets().add("data:text/css,");          // placeholder
        stage.setScene(scene);
        stage.setTitle("MOAF Campaign Index");
        stage.setMinWidth(860);
        stage.setMinHeight(560);

        // Try to set the window icon (ignored silently if the resource is absent)
        try {
            stage.getIcons().add(new Image(
                    MoafCampaignApp.class.getResourceAsStream("/moaf-icon.png")));
        } catch (Exception ignored) {}

        stage.show();

        // Load the app — small delay so the HTTP server is guaranteed to be up
        Platform.runLater(() -> {
            try { Thread.sleep(300); } catch (InterruptedException ignored) {}
            engine.load("http://127.0.0.1:" + PORT + "/");
        });

        stage.setOnCloseRequest(ev -> shutdown());
    }

    @Override
    public void stop() {
        // Called by JavaFX during shutdown — backstop in case the window is
        // closed by a route that bypasses setOnCloseRequest.
        shutdown();
    }

    private static void shutdown() {
        try { if (SERVER != null) SERVER.stop(0); } catch (Exception ignored) {}
        Platform.exit();
        System.exit(0);
    }

    // ── Local HTTP server ─────────────────────────────────────────────────────

    private static volatile HttpServer SERVER;

    private static void startServer() throws IOException {
        HttpServer server;
        try {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", PORT), 0);
        } catch (IOException busy) {
            // Another instance already running — the WebView will still load it
            return;
        }
        server.createContext("/",            MoafCampaignApp::handleRoot);
        server.createContext("/api/master",  ex -> handleJsonFile(ex, MASTER_FILE));
        server.createContext("/api/config",  ex -> handleJsonFile(ex, CONFIG_FILE));
        server.createContext("/legacy/",     MoafCampaignApp::handleLegacy);
        // Daemon threads: these never keep the JVM alive on their own, so the
        // process can always exit cleanly even if shutdown is reached unusually.
        server.setExecutor(Executors.newCachedThreadPool(r -> {
            Thread t = new Thread(r, "moaf-http");
            t.setDaemon(true);
            return t;
        }));
        server.start();
        SERVER = server;
    }

    // ── Request handlers ──────────────────────────────────────────────────────

    private static void handleRoot(HttpExchange ex) throws IOException {
        String path = ex.getRequestURI().getPath();
        if (!"/".equals(path) && !"/index.html".equals(path)) {
            send(ex, 404, "text/plain; charset=utf-8", "Not found");
            return;
        }
        send(ex, 200, "text/html; charset=utf-8", APP_HTML);
    }

    private static void handleJsonFile(HttpExchange ex, Path file) throws IOException {
        addCors(ex.getResponseHeaders());
        String method = ex.getRequestMethod().toUpperCase();
        if ("OPTIONS".equals(method)) { ex.sendResponseHeaders(204, -1); return; }
        if ("GET".equals(method)) {
            send(ex, 200, "application/json; charset=utf-8", Files.readString(file));
            return;
        }
        if ("PUT".equals(method) || "POST".equals(method)) {
            byte[] body = ex.getRequestBody().readAllBytes();
            if (body.length > 60_000_000) { send(ex, 413, "text/plain", "File too large"); return; }
            Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
            Files.write(tmp, body);
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            send(ex, 200, "application/json", "{\"ok\":true}");
            return;
        }
        send(ex, 405, "text/plain", "Method not allowed");
    }

    private static void handleLegacy(HttpExchange ex) throws IOException {
        String raw     = ex.getRequestURI().getPath().substring("/legacy/".length());
        String decoded = URLDecoder.decode(raw, StandardCharsets.UTF_8);
        Path   file    = LEGACY_DIR.resolve(decoded).normalize();
        if (!file.startsWith(LEGACY_DIR) || !Files.isRegularFile(file)) {
            send(ex, 404, "text/plain", "Legacy page not found"); return;
        }
        String type  = decoded.toLowerCase().endsWith(".html")
                ? "text/html; charset=utf-8" : "application/octet-stream";
        byte[] bytes = Files.readAllBytes(file);
        ex.getResponseHeaders().set("Content-Type", type);
        ex.sendResponseHeaders(200, bytes.length);
        try (OutputStream out = ex.getResponseBody()) { out.write(bytes); }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private static void addCors(Headers h) {
        h.set("Access-Control-Allow-Origin",  "*");
        h.set("Access-Control-Allow-Methods", "GET,PUT,POST,OPTIONS");
        h.set("Access-Control-Allow-Headers", "Content-Type,Authorization");
    }

    private static void send(HttpExchange ex, int status, String type, String body)
            throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type",  type);
        ex.getResponseHeaders().set("Cache-Control", "no-store");
        ex.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = ex.getResponseBody()) { out.write(bytes); }
    }

    private static Path locateInstallDir() {
        try {
            Path code = Path.of(
                    MoafCampaignApp.class.getProtectionDomain()
                                        .getCodeSource().getLocation().toURI());
            return Files.isRegularFile(code)
                    ? code.getParent().toAbsolutePath()
                    : Path.of("").toAbsolutePath();
        } catch (Exception ignored) {
            return Path.of("").toAbsolutePath();
        }
    }

    private static Path locateDataDir() {
        String localAppData = System.getenv("LOCALAPPDATA");
        Path base = (localAppData != null && !localAppData.isBlank())
                ? Path.of(localAppData)
                : Path.of(System.getProperty("user.home"), ".local", "share");
        return base.resolve("MOAF Campaign Index").toAbsolutePath();
    }

    private static void ensureInitialFiles() throws IOException {
        Files.createDirectories(DATA_DIR);
        if (!Files.exists(MASTER_FILE)) Files.writeString(MASTER_FILE, INITIAL_DATA,  StandardCharsets.UTF_8);
        if (!Files.exists(CONFIG_FILE)) Files.writeString(CONFIG_FILE, INITIAL_CONFIG, StandardCharsets.UTF_8);
    }

    private static void logError(Exception e) {
        try {
            Files.createDirectories(DATA_DIR);
            Files.writeString(DATA_DIR.resolve("startup-error.txt"),
                    e + System.lineSeparator(),
                    StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (Exception ignored) {}
        e.printStackTrace();
    }

    // ── Seed data ─────────────────────────────────────────────────────────────

    private static final String INITIAL_CONFIG = """
        {"owner":"","repo":"","branch":"main","path":"campaign.json","token":"","autoSync":true}
        """;

    private static final String INITIAL_DATA = """
        {
          "campaignTitle": "MEMORIES OF A FEW",
          "subtitle": "MERIDIAN SPIRE // LICENSED PI INTELLIGENCE INDEX",
          "adminPin": "2089",
          "tabs": [
            {"id":"factions","title":"Factions","icon":"◈","visible":true,"order":1},
            {"id":"npcs",    "title":"NPCs",    "icon":"◎","visible":true,"order":2},
            {"id":"maps",    "title":"Maps",    "icon":"⌖","visible":true,"order":3},
            {"id":"notes",   "title":"Notes",   "icon":"▤","visible":true,"order":4}
          ],
          "entries": [
            {"id":"faction-index","tabId":"factions","title":"Unified Faction Intelligence Database","subtitle":"BCI / CotU / Truth Division / Red Archive / Continuance / Grey Market","body":"Original campaign faction index.","image":"","legacyUrl":"/legacy/MOAF%20Faction%20Index.html","unlocked":true,"order":1},
            {"id":"npc-index",    "tabId":"npcs",    "title":"NPC Intelligence Index",              "subtitle":"Known persons of interest",                                          "body":"Original campaign NPC index.",     "image":"","legacyUrl":"/legacy/MoaF%20NPC%20Index.html",      "unlocked":true,"order":1},
            {"id":"map-index",    "tabId":"maps",    "title":"Meridian Spire District Atlas",       "subtitle":"District maps and operational locations",                            "body":"Original campaign map atlas.",     "image":"","legacyUrl":"/legacy/MOAF%20Map%20Index.html",      "unlocked":true,"order":1},
            {"id":"session-notes","tabId":"notes",   "title":"Case Notes",                          "subtitle":"Shared investigation record",                                        "body":"No notes have been entered yet.",  "image":"","legacyUrl":"",                                    "unlocked":true,"order":1}
          ]
        }
        """;

    // ── Embedded UI (HTML + CSS + JS) ─────────────────────────────────────────

    private static final String APP_HTML = """
<!doctype html>
<html lang="en"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>MOAF Campaign Index</title>
<link rel="preconnect" href="https://fonts.gstatic.com" crossorigin>
<link rel="stylesheet" media="print" onload="this.media='all'"
      href="https://fonts.googleapis.com/css2?family=Share+Tech+Mono&display=swap">
<style>
:root{
  --bg:#020403;--panel:#060d09;--panel2:#0a1410;--line:#1a4230;
  --green:#39ff8f;--green-dim:#1a7a45;--dim:#4e8060;--red:#ff3d4e;
  --amber:#ffb833;--glow:0 0 14px rgba(57,255,143,.25);
  --font:'Share Tech Mono',Consolas,'Courier New',monospace;
}
*{box-sizing:border-box;margin:0;padding:0}
html,body{height:100%;overflow:hidden;background:var(--bg)}
body{font-family:var(--font);color:#b8dfc8;
  background:radial-gradient(ellipse at 50% 0%,#0a2018 0%,#030806 55%,#010302 100%)}

/* scanlines overlay — promoted to its own GPU layer so it does not
   force a full-window repaint while the content area scrolls */
body::before{content:'';position:fixed;inset:0;pointer-events:none;z-index:9999;
  background:repeating-linear-gradient(0deg,
    rgba(0,0,0,.13) 0px,rgba(0,0,0,.13) 1px,
    transparent 1px,transparent 3px);
  transform:translateZ(0);will-change:transform}

/* phosphor flicker — applied to the fixed overlay only, never the whole
   body. Animating <body> opacity repaints every descendant each frame,
   which is the main cause of scroll lag in the WebView. */
@keyframes flicker{0%,100%{opacity:.34}92%{opacity:.30}94%{opacity:.33}}
body::before{opacity:.34;animation:flicker 8s infinite}

button,input,textarea,select{font:inherit;outline:none}
button{cursor:pointer}

/* ── Layout ── */
.shell{display:grid;grid-template-columns:260px 1fr;height:100vh}

/* ── Sidebar ── */
.side{
  border-right:1px solid var(--line);
  background:rgba(2,6,4,.97);
  display:flex;flex-direction:column;gap:0;
  overflow:hidden;
}
.brand{
  padding:20px 16px 16px;
  border-bottom:1px solid var(--line);
  flex-shrink:0;
}
.brand-top{
  font-size:10px;letter-spacing:3px;color:var(--dim);margin-bottom:6px;
}
.brand h1{
  font-size:17px;letter-spacing:4px;color:var(--green);
  text-shadow:0 0 18px rgba(57,255,143,.6);
  line-height:1.2;margin-bottom:4px;
}
.brand small{font-size:9px;color:var(--dim);letter-spacing:2px;line-height:1.5;display:block}

/* blinking cursor after title */
.brand h1::after{content:'_';animation:blink 1.1s step-end infinite}
@keyframes blink{0%,100%{opacity:1}50%{opacity:0}}

.tabs{flex:1;overflow-y:auto;padding:10px 0}
.tabs::-webkit-scrollbar{width:3px}
.tabs::-webkit-scrollbar-thumb{background:var(--green-dim)}

.tab{
  width:100%;border:none;background:transparent;
  color:var(--dim);padding:11px 18px;
  text-align:left;display:flex;gap:12px;align-items:center;
  font-size:12px;letter-spacing:2px;
  border-left:3px solid transparent;
  transition:all .15s;
}
.tab:hover{color:#7dffb8;background:rgba(57,255,143,.04);border-left-color:var(--green-dim)}
.tab.active{color:var(--green);background:rgba(57,255,143,.07);border-left-color:var(--green)}
.tab-icon{font-size:14px;width:18px;text-align:center;flex-shrink:0}

.side-foot{
  border-top:1px solid var(--line);
  padding:12px 12px;
  display:flex;flex-direction:column;gap:7px;
  flex-shrink:0;
}

/* ── Buttons ── */
.btn{
  border:1px solid var(--line);background:#060e09;color:var(--green);
  padding:9px 12px;font-size:11px;letter-spacing:2px;
  transition:all .15s;text-align:left;
}
.btn:hover{background:#0d2018;border-color:var(--green-dim);box-shadow:var(--glow)}
.btn.primary{border-color:var(--green-dim);background:#091a10}
.btn.danger{color:var(--red);border-color:#5a1a22}
.btn.danger:hover{background:#1a060a;border-color:var(--red)}
.btn.small{padding:5px 9px;font-size:10px}
.btn.full{width:100%}

/* ── Admin bar ── */
.adminbar{
  display:none;
  background:#160507;border-bottom:1px solid #6b1a24;
  padding:6px 24px;color:#ff6b7a;font-size:10px;letter-spacing:2px;
  flex-shrink:0;
}
.admin .adminbar{display:block}

/* ── Top bar ── */
.main{min-width:0;display:flex;flex-direction:column;overflow:hidden}
.topbar{
  height:64px;border-bottom:1px solid var(--line);
  display:flex;align-items:center;justify-content:space-between;
  padding:0 24px;background:rgba(2,5,3,.9);
  flex-shrink:0;
}
.top-title h2{font-size:16px;letter-spacing:4px;color:var(--green);
  text-shadow:0 0 10px rgba(57,255,143,.4)}
.top-title .sub{font-size:9px;color:var(--dim);letter-spacing:2px;margin-top:4px}
.top-tools{display:flex;gap:8px;align-items:center}
.status-pill{
  font-size:9px;letter-spacing:2px;color:var(--dim);
  border:1px solid var(--line);padding:3px 8px;
}

/* ── Content area ── */
.content{flex:1;overflow-y:auto;padding:22px 24px;transform:translateZ(0);will-change:scroll-position}
.content::-webkit-scrollbar{width:4px}
.content::-webkit-scrollbar-thumb{background:var(--green-dim)}

/* ── Cards grid ── */
.grid{display:grid;grid-template-columns:repeat(auto-fill,minmax(270px,1fr));gap:14px}

.card{
  border:1px solid var(--line);
  background:linear-gradient(135deg,rgba(8,20,13,.95),rgba(3,8,5,.95));
  padding:16px;position:relative;cursor:pointer;
  transition:all .2s;min-height:160px;
}
.card:hover{border-color:#2d7a52;transform:translateY(-2px);box-shadow:var(--glow)}
.card.locked{filter:saturate(.3) brightness(.7)}

.card-tag{font-size:8px;letter-spacing:3px;color:var(--dim);margin-bottom:10px}
.card h3{font-size:15px;color:var(--green);letter-spacing:2px;margin-bottom:5px;line-height:1.3}
.card .card-sub{font-size:10px;color:#5e9e78;letter-spacing:1px;min-height:28px;line-height:1.5}
.card .card-body{
  font-size:11px;color:#8abda0;line-height:1.6;margin-top:12px;
  max-height:60px;overflow:hidden;
}
.card-thumb{width:100%;height:120px;object-fit:cover;border:1px solid var(--line);margin-bottom:12px}
.lock-badge{
  position:absolute;right:12px;top:12px;
  font-size:9px;letter-spacing:2px;color:var(--red);
  border:1px solid #5a1a22;padding:2px 6px;background:#0e0204;
}
.card-edit{
  position:absolute;right:10px;bottom:10px;
  display:none;
}
.admin .card-edit{display:inline-flex}

.empty{
  border:1px dashed var(--line);padding:48px;text-align:center;
  color:var(--dim);font-size:11px;letter-spacing:2px;
}

/* hide admin-only elements unless in admin mode */
.admin-only{display:none!important}
.admin .admin-only{display:inline-flex!important}
.admin .card.locked{display:block}

/* ── Modals ── */
.modal{
  display:none;position:fixed;inset:0;
  background:rgba(0,0,0,.82);z-index:200;
  align-items:center;justify-content:center;padding:20px;
}
.modal.show{display:flex}
.dialog{
  width:min(740px,96vw);max-height:90vh;overflow-y:auto;
  background:#050e08;border:1px solid #2d6644;
  box-shadow:0 0 80px rgba(0,0,0,.9),0 0 30px rgba(57,255,143,.08);
  padding:22px;
}
.dialog.wide{width:min(1160px,96vw);height:90vh}
.dialog h3{color:var(--green);letter-spacing:3px;font-size:14px;margin-bottom:18px;
  border-bottom:1px solid var(--line);padding-bottom:10px}

/* form */
.form{display:grid;gap:10px}
.form.two{grid-template-columns:1fr 1fr}
.form label{font-size:9px;letter-spacing:2px;color:var(--dim);display:grid;gap:5px}
.form input,.form textarea,.form select{
  background:#020704;border:1px solid var(--line);color:#c8f0d8;
  padding:8px 10px;font-size:12px;
}
.form input:focus,.form textarea:focus,.form select:focus{
  border-color:var(--green-dim);box-shadow:0 0 8px rgba(57,255,143,.15);
}
.form textarea{min-height:120px;resize:vertical}
.form select option{background:#050e08}

.row{display:flex;gap:8px;flex-wrap:wrap;align-items:center}
.right{margin-left:auto}
.muted{color:var(--dim);font-size:10px;letter-spacing:1px}
.notice{
  border-left:3px solid var(--amber);background:rgba(255,184,51,.06);
  padding:10px 14px;color:#ffe0a0;font-size:10px;
  line-height:1.6;letter-spacing:1px;
}

/* legacy iframe viewer */
.viewer{height:calc(90vh - 90px);width:100%;border:1px solid var(--line);background:#fff}

/* entry detail */
.entry-view img{max-width:100%;border:1px solid var(--line);margin-bottom:14px}
.entry-view .text{line-height:1.7;white-space:pre-wrap;color:#b0d8be;font-size:13px}

/* split layout */
.split{display:grid;grid-template-columns:1fr 1fr;gap:16px}

/* tab manager list */
.listbox{border:1px solid var(--line);padding:8px;max-height:230px;overflow-y:auto}
.listitem{
  display:flex;align-items:center;gap:7px;
  border-bottom:1px solid #0f2d1e;padding:7px 2px;
}
.listitem input[type=text],.listitem input[type=number]{
  background:#020704;border:1px solid var(--line);color:#c8f0d8;padding:5px 7px
}

/* toast */
.toast{
  position:fixed;right:18px;bottom:18px;
  background:#061209;border:1px solid var(--green-dim);
  padding:10px 16px;z-index:500;display:none;
  font-size:11px;letter-spacing:2px;color:var(--green);
  box-shadow:var(--glow);
}
.toast.show{display:block}

/* section headers */
h4{font-size:10px;letter-spacing:3px;color:var(--dim);
  border-bottom:1px solid var(--line);padding-bottom:6px;margin:14px 0 10px}
</style></head>
<body>
<div class="shell">
  <aside class="side">
    <div class="brand">
      <div class="brand-top">BCI TERMINAL // SECURE ACCESS</div>
      <h1 id="brandTitle">MEMORIES OF A FEW</h1>
      <small id="brandSub">MERIDIAN SPIRE // INTELLIGENCE INDEX</small>
    </div>
    <nav class="tabs" id="tabs"></nav>
    <div class="side-foot">
      <button class="btn full admin-only" onclick="openManager()">▸ MANAGE CAMPAIGN</button>
      <button class="btn full" onclick="syncRemote()">▸ CHECK FOR UPDATES</button>
      <button class="btn full" id="adminBtn" onclick="adminAction()">▸ ADMIN LOGIN</button>
    </div>
  </aside>

  <main class="main">
    <div class="adminbar">
      ⚠ ADMIN MODE ACTIVE // MASTER DATA LOCAL // PUBLISH SENDS ONLY UNLOCKED RECORDS
    </div>
    <header class="topbar">
      <div class="top-title">
        <h2 id="pageTitle">INDEX</h2>
        <div class="sub" id="pageSub">PLAYER ACCESS // AUTHORIZED RECORDS ONLY</div>
      </div>
      <div class="top-tools">
        <span class="status-pill" id="status">LOCAL</span>
        <button class="btn small admin-only" onclick="newEntry()">+ ENTRY</button>
      </div>
    </header>
    <section class="content">
      <div class="grid" id="grid"></div>
    </section>
  </main>
</div>

<!-- Admin Login -->
<div class="modal" id="loginModal">
  <div class="dialog">
    <h3>ADMIN AUTHORIZATION</h3>
    <div class="form">
      <label>ACCESS CODE
        <input id="pinInput" type="password" autocomplete="off"
               onkeydown="if(event.key==='Enter')login()">
      </label>
      <div class="row">
        <button class="btn primary" onclick="login()">UNLOCK</button>
        <button class="btn" onclick="closeModal('loginModal')">CANCEL</button>
        <span class="muted" style="margin-left:8px">Default: 2089</span>
      </div>
    </div>
  </div>
</div>

<!-- Entry viewer -->
<div class="modal" id="entryModal">
  <div class="dialog wide" id="entryDialog"></div>
</div>

<!-- Entry editor -->
<div class="modal" id="editModal">
  <div class="dialog">
    <h3 id="editHeading">EDIT ENTRY</h3>
    <div class="form two">
      <label>TITLE<input id="eTitle"></label>
      <label>SUBTITLE<input id="eSub"></label>
      <label>TAB<select id="eTab"></select></label>
      <label>ORDER<input id="eOrder" type="number"></label>
      <label style="grid-column:1/-1">IMAGE URL OR DATA URI<input id="eImage"></label>
      <label style="grid-column:1/-1">LEGACY PAGE PATH<input id="eLegacy" placeholder="/legacy/example.html"></label>
      <label style="grid-column:1/-1">INFORMATION<textarea id="eBody"></textarea></label>
      <label>PLAYER VISIBILITY
        <select id="eUnlocked">
          <option value="true">UNLOCKED — VISIBLE TO PLAYERS</option>
          <option value="false">LOCKED — GM ONLY</option>
        </select>
      </label>
      <label>UPLOAD IMAGE<input id="eFile" type="file" accept="image/*" onchange="readImage(this)"></label>
    </div>
    <div class="row" style="margin-top:16px">
      <button class="btn primary" onclick="saveEntry()">SAVE</button>
      <button class="btn danger" id="deleteEntryBtn" onclick="deleteEntry()">DELETE</button>
      <button class="btn right" onclick="closeModal('editModal')">CANCEL</button>
    </div>
  </div>
</div>

<!-- Campaign manager -->
<div class="modal" id="manageModal">
  <div class="dialog wide">
    <h3>CAMPAIGN MANAGER</h3>
    <div class="split">
      <section>
        <h4>TABS</h4>
        <div class="listbox" id="tabManager"></div>
        <div class="row" style="margin-top:9px">
          <button class="btn" onclick="addTab()">+ ADD TAB</button>
        </div>
        <h4>CAMPAIGN SETTINGS</h4>
        <div class="form two">
          <label>CAMPAIGN TITLE<input id="mTitle"></label>
          <label>SUBTITLE<input id="mSubtitle"></label>
          <label>ADMIN PIN<input id="mPin" type="password"></label>
        </div>
      </section>
      <section>
        <h4>GITHUB PUBLISHING</h4>
        <div class="notice">
          Use a PUBLIC repository for the player snapshot.<br>
          Locked entries are NEVER uploaded.<br>
          Your token is stored only in the local config file.
        </div>
        <div class="form two" style="margin-top:12px">
          <label>OWNER / USERNAME<input id="gOwner"></label>
          <label>REPOSITORY<input id="gRepo"></label>
          <label>BRANCH<input id="gBranch" value="main"></label>
          <label>PLAYER DATA PATH<input id="gPath" value="campaign.json"></label>
          <label style="grid-column:1/-1">FINE-GRAINED PERSONAL ACCESS TOKEN
            <input id="gToken" type="password">
          </label>
        </div>
        <div class="row" style="margin-top:10px">
          <button class="btn primary" onclick="publishGithub()">PUBLISH PLAYER VIEW</button>
          <button class="btn" onclick="testGithub()">TEST CONNECTION</button>
        </div>
        <h4>BACKUP</h4>
        <div class="row">
          <button class="btn" onclick="exportMaster()">EXPORT MASTER</button>
          <label class="btn">IMPORT MASTER
            <input type="file" accept="application/json" hidden onchange="importMaster(this)">
          </label>
        </div>
      </section>
    </div>
    <div class="row" style="margin-top:18px">
      <button class="btn primary" onclick="saveManager()">SAVE SETTINGS</button>
      <button class="btn right" onclick="closeModal('manageModal')">CLOSE</button>
    </div>
  </div>
</div>

<div class="toast" id="toast"></div>

<script>
let data=null,config=null,currentTab='factions',admin=false,editingId=null;
const $=id=>document.getElementById(id);
const esc=s=>(s??'').toString().replace(/[&<>"']/g,c=>({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[c]));

async function boot(){
  data=await(await fetch('/api/master')).json();
  config=await(await fetch('/api/config')).json();
  render();
  if(config.autoSync&&config.owner&&config.repo)syncRemote(true);
}

function visibleTabs(){return data.tabs.filter(t=>admin||t.visible).sort((a,b)=>(a.order||0)-(b.order||0))}
function entries(){return data.entries.filter(e=>e.tabId===currentTab&&(admin||e.unlocked)).sort((a,b)=>(a.order||0)-(b.order||0))}

function render(){
  document.body.classList.toggle('admin',admin);
  $('brandTitle').textContent=data.campaignTitle;
  $('brandSub').textContent=data.subtitle;
  let ts=visibleTabs();
  if(!ts.some(t=>t.id===currentTab)&&ts[0])currentTab=ts[0].id;
  $('tabs').innerHTML=ts.map(t=>`<button class="tab ${t.id===currentTab?'active':''}" onclick="selectTab('${esc(t.id)}')"><span class="tab-icon">${esc(t.icon||'□')}</span><span>${esc(t.title)}</span></button>`).join('');
  let tab=ts.find(t=>t.id===currentTab);
  $('pageTitle').textContent=tab?.title?.toUpperCase()||'INDEX';
  $('pageSub').textContent=admin?'ADMIN ACCESS // LOCKED RECORDS VISIBLE':'PLAYER ACCESS // AUTHORIZED RECORDS ONLY';
  let list=entries();
  $('grid').innerHTML=list.length?list.map(card).join(''):`<div class="empty">NO AUTHORIZED RECORDS IN THIS SECTION</div>`;
  $('adminBtn').textContent=admin?'▸ EXIT ADMIN MODE':'▸ ADMIN LOGIN';
}

function card(e){
  return`<article class="card ${e.unlocked?'':'locked'}" onclick="openEntry('${esc(e.id)}')">
    ${!e.unlocked?'<span class="lock-badge">LOCKED</span>':''}
    ${e.image?`<img class="card-thumb" src="${esc(e.image)}" onerror="this.style.display='none'">` :''}
    <div class="card-tag">${e.unlocked?'AUTHORIZED RECORD':'GM-ONLY RECORD'}</div>
    <h3>${esc(e.title)}</h3>
    <div class="card-sub">${esc(e.subtitle)}</div>
    <div class="card-body">${esc(e.body)}</div>
    <button class="btn small card-edit" onclick="event.stopPropagation();editEntry('${esc(e.id)}')">EDIT</button>
  </article>`;
}

function selectTab(id){currentTab=id;render()}
function adminAction(){if(admin){admin=false;render();toast('Admin mode closed')}else{$('loginModal').classList.add('show');setTimeout(()=>$('pinInput').focus(),80)}}

async function login(){
  let master=await(await fetch('/api/master')).json();
  if($('pinInput').value===master.adminPin){
    data=master;admin=true;$('pinInput').value='';
    closeModal('loginModal');render();toast('Admin mode enabled');
  }else toast('Incorrect PIN');
}

function openEntry(id){
  let e=data.entries.find(x=>x.id===id);
  if(!e||(!admin&&!e.unlocked))return;
  let d=$('entryDialog');
  if(e.legacyUrl){
    d.innerHTML=`<div class="row" style="margin-bottom:12px"><h3 style="margin:0">${esc(e.title)}</h3><button class="btn right" onclick="closeModal('entryModal')">CLOSE</button></div><iframe class="viewer" src="${esc(e.legacyUrl)}"></iframe>`;
  }else{
    d.innerHTML=`<div class="row" style="margin-bottom:14px"><h3 style="margin:0">${esc(e.title)}</h3><button class="btn right" onclick="closeModal('entryModal')">CLOSE</button></div><div class="entry-view">${e.image?`<img src="${esc(e.image)}">`:''}<p class="muted" style="margin-bottom:10px">${esc(e.subtitle)}</p><div class="text">${esc(e.body)}</div></div>`;
  }
  $('entryModal').classList.add('show');
}

function newEntry(){editingId=null;fillEntry({tabId:currentTab,title:'',subtitle:'',body:'',image:'',legacyUrl:'',unlocked:false,order:entries().length+1});$('deleteEntryBtn').style.display='none';$('editHeading').textContent='NEW ENTRY';$('editModal').classList.add('show')}
function editEntry(id){editingId=id;fillEntry(data.entries.find(x=>x.id===id));$('deleteEntryBtn').style.display='inline-flex';$('editHeading').textContent='EDIT ENTRY';$('editModal').classList.add('show')}
function fillEntry(e){$('eTab').innerHTML=data.tabs.map(t=>`<option value="${esc(t.id)}">${esc(t.title)}</option>`).join('');$('eTitle').value=e.title||'';$('eSub').value=e.subtitle||'';$('eTab').value=e.tabId||currentTab;$('eOrder').value=e.order||1;$('eImage').value=e.image||'';$('eLegacy').value=e.legacyUrl||'';$('eBody').value=e.body||'';$('eUnlocked').value=String(!!e.unlocked)}
function slug(s){return(s||'entry').toLowerCase().replace(/[^a-z0-9]+/g,'-').replace(/^-|-$/g,'')+'-'+Date.now().toString(36)}

async function saveEntry(){
  let e={id:editingId||slug($('eTitle').value),title:$('eTitle').value.trim()||'Untitled',subtitle:$('eSub').value.trim(),tabId:$('eTab').value,order:Number($('eOrder').value)||1,image:$('eImage').value.trim(),legacyUrl:$('eLegacy').value.trim(),body:$('eBody').value,unlocked:$('eUnlocked').value==='true'};
  if(editingId){let i=data.entries.findIndex(x=>x.id===editingId);data.entries[i]=e}else data.entries.push(e);
  currentTab=e.tabId;await saveLocal();closeModal('editModal');render();toast('Entry saved');
}
async function deleteEntry(){if(!editingId||!confirm('Delete this entry?'))return;data.entries=data.entries.filter(x=>x.id!==editingId);await saveLocal();closeModal('editModal');render();toast('Entry deleted')}
function readImage(input){let f=input.files[0];if(!f)return;if(f.size>8_000_000)return toast('Use an image smaller than 8 MB');let r=new FileReader();r.onload=()=>$('eImage').value=r.result;r.readAsDataURL(f)}

function openManager(){$('mTitle').value=data.campaignTitle;$('mSubtitle').value=data.subtitle;$('mPin').value=data.adminPin;$('gOwner').value=config.owner||'';$('gRepo').value=config.repo||'';$('gBranch').value=config.branch||'main';$('gPath').value=config.path||'campaign.json';$('gToken').value=config.token||'';renderTabManager();$('manageModal').classList.add('show')}
function renderTabManager(){$('tabManager').innerHTML=data.tabs.sort((a,b)=>(a.order||0)-(b.order||0)).map((t,i)=>`<div class="listitem"><input type="text" style="width:32px" value="${esc(t.icon||'□')}" onchange="tabField('${t.id}','icon',this.value)"><input type="text" style="flex:1" value="${esc(t.title)}" onchange="tabField('${t.id}','title',this.value)"><input type="number" style="width:48px" value="${t.order||i+1}" onchange="tabField('${t.id}','order',Number(this.value))"><label class="muted" style="display:flex;gap:4px;align-items:center"><input type="checkbox" ${t.visible?'checked':''} onchange="tabField('${t.id}','visible',this.checked)">VISIBLE</label><button class="btn small danger" onclick="removeTab('${t.id}')">X</button></div>`).join('')}
function tabField(id,key,val){let t=data.tabs.find(x=>x.id===id);if(t)t[key]=val}
function addTab(){let title=prompt('New tab name:');if(!title)return;data.tabs.push({id:slug(title),title,icon:'□',visible:true,order:data.tabs.length+1});renderTabManager()}
function removeTab(id){if(data.entries.some(e=>e.tabId===id))return toast('Move or delete entries in this tab first');if(confirm('Delete this empty tab?')){data.tabs=data.tabs.filter(t=>t.id!==id);renderTabManager()}}

async function saveManager(){
  data.campaignTitle=$('mTitle').value.trim()||'MEMORIES OF A FEW';
  data.subtitle=$('mSubtitle').value.trim();
  data.adminPin=$('mPin').value||data.adminPin;
  config={...config,owner:$('gOwner').value.trim(),repo:$('gRepo').value.trim(),branch:$('gBranch').value.trim()||'main',path:$('gPath').value.trim()||'campaign.json',token:$('gToken').value.trim()};
  await Promise.all([saveLocal(),fetch('/api/config',{method:'PUT',headers:{'Content-Type':'application/json'},body:JSON.stringify(config,null,2)})]);
  render();toast('Settings saved');
}
async function saveLocal(){await fetch('/api/master',{method:'PUT',headers:{'Content-Type':'application/json'},body:JSON.stringify(data,null,2)})}

function playerSnapshot(){
  let tabs=data.tabs.filter(t=>t.visible);
  let ids=new Set(tabs.map(t=>t.id));
  return{campaignTitle:data.campaignTitle,subtitle:data.subtitle,publishedAt:new Date().toISOString(),tabs,
    entries:data.entries.filter(e=>e.unlocked&&ids.has(e.tabId))
      .map(({id,tabId,title,subtitle,body,image,legacyUrl,order,unlocked})=>({id,tabId,title,subtitle,body,image,legacyUrl,order,unlocked}))};
}
function githubHeaders(){return{'Accept':'application/vnd.github+json','Authorization':'Bearer '+config.token,'X-GitHub-Api-Version':'2022-11-28','Content-Type':'application/json'}}

async function publishGithub(){
  await saveManager();
  if(!config.owner||!config.repo||!config.token)return toast('Complete the GitHub settings first');
  let url=`https://api.github.com/repos/${encodeURIComponent(config.owner)}/${encodeURIComponent(config.repo)}/contents/${config.path.split('/').map(encodeURIComponent).join('/')}?ref=${encodeURIComponent(config.branch)}`;
  try{
    let sha=null;
    let old=await fetch(url,{headers:githubHeaders()});
    if(old.ok)sha=(await old.json()).sha;
    let content=btoa(unescape(encodeURIComponent(JSON.stringify(playerSnapshot(),null,2))));
    let body={message:'Publish MOAF player campaign data',content,branch:config.branch};
    if(sha)body.sha=sha;
    let res=await fetch(url,{method:'PUT',headers:githubHeaders(),body:JSON.stringify(body)});
    if(!res.ok)throw new Error((await res.json()).message||res.statusText);
    $('status').textContent='PUBLISHED '+new Date().toLocaleTimeString();
    toast('Unlocked player content published');
  }catch(e){toast('Publish failed: '+e.message)}
}

async function testGithub(){
  await saveManager();
  try{
    let r=await fetch(`https://api.github.com/repos/${encodeURIComponent(config.owner)}/${encodeURIComponent(config.repo)}`,{headers:githubHeaders()});
    if(!r.ok)throw new Error((await r.json()).message||r.statusText);
    toast('GitHub connection successful');
  }catch(e){toast('Connection failed: '+e.message)}
}

async function syncRemote(silent=false){
  if(!config.owner||!config.repo){if(!silent)toast('Online updates are not configured yet');return}
  let raw=`https://raw.githubusercontent.com/${encodeURIComponent(config.owner)}/${encodeURIComponent(config.repo)}/${encodeURIComponent(config.branch)}/${config.path.split('/').map(encodeURIComponent).join('/')}?t=${Date.now()}`;
  try{
    let r=await fetch(raw,{cache:'no-store'});
    if(!r.ok)throw new Error('Player data not found');
    let remote=await r.json();
    if(!admin)data=remote;
    $('status').textContent='UPDATED '+new Date().toLocaleTimeString();
    render();
    if(!silent)toast('Campaign data updated');
  }catch(e){if(!silent)toast('Update failed: '+e.message)}
}

function exportMaster(){let a=document.createElement('a');a.href=URL.createObjectURL(new Blob([JSON.stringify(data,null,2)],{type:'application/json'}));a.download='moaf-campaign-master.json';a.click();URL.revokeObjectURL(a.href)}
function importMaster(input){let f=input.files[0];if(!f)return;let r=new FileReader();r.onload=async()=>{try{data=JSON.parse(r.result);await saveLocal();render();toast('Master backup imported')}catch{toast('Invalid campaign backup')}};r.readAsText(f)}
function closeModal(id){$(id).classList.remove('show')}
function toast(msg){$('toast').textContent=msg;$('toast').classList.add('show');setTimeout(()=>$('toast').classList.remove('show'),3200)}
document.addEventListener('keydown',e=>{if(e.key==='Escape')document.querySelectorAll('.modal.show').forEach(m=>m.classList.remove('show'))});
boot();
</script>
</body></html>
""";
}
