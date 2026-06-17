import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.awt.Desktop;
import java.io.*;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.concurrent.Executors;

/**
 * Memories of a Few Campaign Index
 * Single-file Java desktop server with a browser-based player/admin interface.
 * Requires Java 21+.
 */
public final class MoafCampaignApp {
    private static final int PORT = 38921;
    private static final Path APP_DIR = locateAppDir();
    private static final Path MASTER_FILE = APP_DIR.resolve("campaign-master.json");
    private static final Path CONFIG_FILE = APP_DIR.resolve("github-config.json");
    private static final Path LEGACY_DIR = APP_DIR.resolve("content").resolve("legacy");

    public static void main(String[] args) throws Exception {
        ensureInitialFiles();
        HttpServer server;
        try {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", PORT), 0);
        } catch (IOException busy) {
            openBrowser("http://127.0.0.1:" + PORT + "/");
            return;
        }
        server.createContext("/", MoafCampaignApp::handleRoot);
        server.createContext("/api/master", ex -> handleJsonFile(ex, MASTER_FILE));
        server.createContext("/api/config", ex -> handleJsonFile(ex, CONFIG_FILE));
        server.createContext("/legacy/", MoafCampaignApp::handleLegacy);
        server.setExecutor(Executors.newCachedThreadPool());
        server.start();
        System.out.println("MOAF Campaign Index is running at http://127.0.0.1:" + PORT);
        openBrowser("http://127.0.0.1:" + PORT + "/");
    }

    private static Path locateAppDir() {
        try {
            Path code = Paths.get(MoafCampaignApp.class.getProtectionDomain().getCodeSource().getLocation().toURI());
            return Files.isRegularFile(code) ? code.getParent().toAbsolutePath() : Paths.get("").toAbsolutePath();
        } catch (Exception ignored) {
            return Paths.get("").toAbsolutePath();
        }
    }

    private static void ensureInitialFiles() throws IOException {
        Files.createDirectories(LEGACY_DIR);
        if (!Files.exists(MASTER_FILE)) Files.writeString(MASTER_FILE, INITIAL_DATA, StandardCharsets.UTF_8);
        if (!Files.exists(CONFIG_FILE)) Files.writeString(CONFIG_FILE, INITIAL_CONFIG, StandardCharsets.UTF_8);
    }

    private static void openBrowser(String url) {
        try {
            if (Desktop.isDesktopSupported()) Desktop.getDesktop().browse(URI.create(url));
        } catch (Exception e) {
            System.err.println("Open this address in your browser: " + url);
        }
    }

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
        if ("OPTIONS".equalsIgnoreCase(ex.getRequestMethod())) {
            ex.sendResponseHeaders(204, -1); return;
        }
        if ("GET".equalsIgnoreCase(ex.getRequestMethod())) {
            send(ex, 200, "application/json; charset=utf-8", Files.readString(file));
            return;
        }
        if ("PUT".equalsIgnoreCase(ex.getRequestMethod()) || "POST".equalsIgnoreCase(ex.getRequestMethod())) {
            byte[] body = ex.getRequestBody().readAllBytes();
            if (body.length > 60_000_000) { send(ex, 413, "text/plain", "File too large"); return; }
            Path temp = file.resolveSibling(file.getFileName() + ".tmp");
            Files.write(temp, body);
            Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            send(ex, 200, "application/json", "{\"ok\":true}");
            return;
        }
        send(ex, 405, "text/plain", "Method not allowed");
    }

    private static void handleLegacy(HttpExchange ex) throws IOException {
        String raw = ex.getRequestURI().getPath().substring("/legacy/".length());
        String decoded = URLDecoder.decode(raw, StandardCharsets.UTF_8);
        Path file = LEGACY_DIR.resolve(decoded).normalize();
        if (!file.startsWith(LEGACY_DIR) || !Files.isRegularFile(file)) {
            send(ex, 404, "text/plain", "Legacy page not found"); return;
        }
        String type = decoded.toLowerCase().endsWith(".html") ? "text/html; charset=utf-8" : "application/octet-stream";
        byte[] bytes = Files.readAllBytes(file);
        ex.getResponseHeaders().set("Content-Type", type);
        ex.sendResponseHeaders(200, bytes.length);
        try (OutputStream out = ex.getResponseBody()) { out.write(bytes); }
    }

    private static void addCors(Headers h) {
        h.set("Access-Control-Allow-Origin", "*");
        h.set("Access-Control-Allow-Methods", "GET,PUT,POST,OPTIONS");
        h.set("Access-Control-Allow-Headers", "Content-Type,Authorization");
    }

    private static void send(HttpExchange ex, int status, String type, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", type);
        ex.getResponseHeaders().set("Cache-Control", "no-store");
        ex.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = ex.getResponseBody()) { out.write(bytes); }
    }

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
            {"id":"npcs","title":"NPCs","icon":"◎","visible":true,"order":2},
            {"id":"maps","title":"Maps","icon":"⌖","visible":true,"order":3},
            {"id":"notes","title":"Notes","icon":"▤","visible":true,"order":4}
          ],
          "entries": [
            {"id":"faction-index","tabId":"factions","title":"Unified Faction Intelligence Database","subtitle":"BCI / CotU / Truth Division / Red Archive / Continuance / Grey Market","body":"Original campaign faction index.","image":"","legacyUrl":"/legacy/MOAF%20Faction%20Index.html","unlocked":true,"order":1},
            {"id":"npc-index","tabId":"npcs","title":"NPC Intelligence Index","subtitle":"Known persons of interest","body":"Original campaign NPC index.","image":"","legacyUrl":"/legacy/MoaF%20NPC%20Index.html","unlocked":true,"order":1},
            {"id":"map-index","tabId":"maps","title":"Meridian Spire District Atlas","subtitle":"District maps and operational locations","body":"Original campaign map atlas.","image":"","legacyUrl":"/legacy/MOAF%20Map%20Index.html","unlocked":true,"order":1},
            {"id":"session-notes","tabId":"notes","title":"Case Notes","subtitle":"Shared investigation record","body":"No notes have been entered yet.","image":"","legacyUrl":"","unlocked":true,"order":1}
          ]
        }
        """;

    private static final String APP_HTML = """
<!doctype html>
<html lang="en"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>MOAF Campaign Index</title>
<style>
:root{--bg:#040706;--panel:#08100d;--panel2:#0d1713;--line:#1e4b39;--green:#77ffb0;--dim:#6ba184;--red:#ff4e5c;--amber:#ffc766;--shadow:0 0 18px rgba(80,255,160,.14)}
*{box-sizing:border-box}body{margin:0;background:radial-gradient(circle at 50% -10%,#123224 0,#050907 38%,#020403 100%);color:#cdfce0;font-family:Consolas,"Courier New",monospace;min-height:100vh;overflow:hidden}
body:before{content:"";position:fixed;inset:0;pointer-events:none;background:repeating-linear-gradient(0deg,rgba(255,255,255,.018) 0,rgba(255,255,255,.018) 1px,transparent 1px,transparent 4px);z-index:99}
button,input,textarea,select{font:inherit}.shell{display:grid;grid-template-columns:270px 1fr;height:100vh}.side{border-right:1px solid var(--line);background:rgba(3,8,6,.94);padding:22px 16px;display:flex;flex-direction:column;gap:18px}.brand{padding:8px 10px 19px;border-bottom:1px solid var(--line)}.brand h1{font-size:21px;letter-spacing:3px;color:var(--green);margin:0 0 5px;text-shadow:0 0 12px #35ff8f88}.brand small{font-size:10px;color:var(--dim);line-height:1.4}.tabs{display:flex;flex-direction:column;gap:7px;overflow:auto}.tab{border:1px solid transparent;background:transparent;color:#8ab79c;padding:11px 12px;text-align:left;cursor:pointer;display:flex;gap:10px;align-items:center}.tab:hover,.tab.active{color:var(--green);border-color:var(--line);background:#0a1711;box-shadow:inset 3px 0 var(--green)}.side-foot{margin-top:auto;display:grid;gap:8px}.btn{border:1px solid var(--line);background:#09140f;color:var(--green);padding:9px 11px;cursor:pointer}.btn:hover{background:#10271c;box-shadow:var(--shadow)}.btn.danger{color:#ff8891;border-color:#6b252d}.btn.primary{background:#113322}.btn.small{padding:6px 8px;font-size:12px}.main{min-width:0;display:flex;flex-direction:column}.top{height:72px;border-bottom:1px solid var(--line);display:flex;align-items:center;justify-content:space-between;padding:0 26px;background:rgba(4,10,7,.86)}.top-title h2{margin:0;color:var(--green);font-size:18px;letter-spacing:2px}.top-title div{font-size:10px;color:var(--dim);margin-top:5px}.tools{display:flex;gap:8px;align-items:center}.status{font-size:11px;color:var(--dim);padding-right:8px}.content{padding:22px 26px;overflow:auto;height:calc(100vh - 72px)}.grid{display:grid;grid-template-columns:repeat(auto-fill,minmax(280px,1fr));gap:15px}.card{border:1px solid var(--line);background:linear-gradient(135deg,rgba(12,27,20,.94),rgba(5,12,9,.94));min-height:190px;padding:16px;position:relative;box-shadow:var(--shadow);cursor:pointer}.card:hover{border-color:#55b47d;transform:translateY(-1px)}.card.locked{filter:saturate(.4);opacity:.62}.card .tag{font-size:9px;letter-spacing:2px;color:var(--dim)}.card h3{color:var(--green);margin:11px 0 5px;font-size:17px}.card .sub{color:#8eb89e;font-size:11px;min-height:30px}.card .body{color:#b5d7c2;font-size:12px;line-height:1.5;margin-top:14px;max-height:70px;overflow:hidden}.thumb{width:100%;height:130px;object-fit:cover;border:1px solid var(--line);margin-bottom:12px}.lock{position:absolute;right:12px;top:12px;color:var(--red)}.empty{border:1px dashed var(--line);padding:40px;text-align:center;color:var(--dim)}
.modal{display:none;position:fixed;inset:0;background:#000c;z-index:200;align-items:center;justify-content:center;padding:22px}.modal.show{display:flex}.dialog{width:min(760px,96vw);max-height:92vh;overflow:auto;background:#07100c;border:1px solid #347052;box-shadow:0 0 60px #000;padding:20px}.dialog.wide{width:min(1200px,96vw);height:92vh}.dialog h3{margin-top:0;color:var(--green);letter-spacing:2px}.form{display:grid;gap:11px}.form.two{grid-template-columns:1fr 1fr}.form label{font-size:10px;color:var(--dim);display:grid;gap:5px}.form input,.form textarea,.form select{background:#030705;border:1px solid var(--line);color:#d5ffe5;padding:9px}.form textarea{min-height:130px;resize:vertical}.row{display:flex;gap:8px;flex-wrap:wrap}.right{margin-left:auto}.viewer{height:calc(92vh - 80px);width:100%;border:1px solid var(--line);background:white}.entry-view img{max-width:100%;border:1px solid var(--line)}.entry-view .text{line-height:1.65;white-space:pre-wrap;color:#c8ead4}.adminbar{display:none;background:#23090c;border-bottom:1px solid #7f2731;padding:8px 26px;color:#ff818c;font-size:11px}.admin .adminbar{display:flex}.admin-only{display:none!important}.admin .admin-only{display:inline-flex!important}.admin .card.locked{display:block}.toast{position:fixed;right:20px;bottom:20px;background:#0d2118;border:1px solid var(--green);padding:12px 15px;z-index:400;display:none}.toast.show{display:block}.split{display:grid;grid-template-columns:1fr 1fr;gap:14px}.listbox{border:1px solid var(--line);padding:10px;max-height:260px;overflow:auto}.listitem{display:flex;align-items:center;gap:7px;border-bottom:1px solid #173326;padding:7px 3px}.muted{color:var(--dim);font-size:11px}.notice{border-left:3px solid var(--amber);background:#2b210d;padding:10px 12px;color:#ffe0a0;font-size:11px;line-height:1.5}@media(max-width:760px){.shell{grid-template-columns:1fr}.side{position:fixed;z-index:80;width:250px;transform:translateX(-100%);transition:.2s;height:100vh}.side.open{transform:none}.top{padding:0 14px}.content{padding:14px}.form.two,.split{grid-template-columns:1fr}.mobile{display:inline-flex!important}}
</style></head>
<body><div class="shell"><aside class="side" id="side"><div class="brand"><h1 id="brandTitle">MEMORIES OF A FEW</h1><small id="brandSub">MERIDIAN SPIRE // INTELLIGENCE INDEX</small></div><nav class="tabs" id="tabs"></nav><div class="side-foot"><button class="btn admin-only" onclick="openManager()">MANAGE CAMPAIGN</button><button class="btn" onclick="syncRemote()">CHECK FOR UPDATES</button><button class="btn" id="adminBtn" onclick="adminAction()">ADMIN LOGIN</button></div></aside><main class="main"><div class="adminbar">ADMIN MODE // MASTER DATA IS LOCAL // PUBLISH SENDS ONLY UNLOCKED CONTENT TO GITHUB</div><header class="top"><div class="top-title"><h2 id="pageTitle">INDEX</h2><div id="pageSub">PLAYER ACCESS</div></div><div class="tools"><span class="status" id="status">LOCAL</span><button class="btn small mobile" style="display:none" onclick="side.classList.toggle('open')">MENU</button><button class="btn small admin-only" onclick="newEntry()">+ ENTRY</button></div></header><section class="content"><div class="grid" id="grid"></div></section></main></div>
<div class="modal" id="loginModal"><div class="dialog"><h3>ADMIN AUTHORIZATION</h3><div class="form"><label>PIN<input id="pinInput" type="password" autocomplete="off"></label><div class="row"><button class="btn primary" onclick="login()">UNLOCK EDITOR</button><button class="btn" onclick="closeModal('loginModal')">CANCEL</button></div><div class="muted">Initial PIN: 2089. Change it in Campaign Settings.</div></div></div></div>
<div class="modal" id="entryModal"><div class="dialog wide" id="entryDialog"></div></div>
<div class="modal" id="editModal"><div class="dialog"><h3 id="editHeading">EDIT ENTRY</h3><div class="form two"><label>Title<input id="eTitle"></label><label>Subtitle<input id="eSub"></label><label>Tab<select id="eTab"></select></label><label>Order<input id="eOrder" type="number"></label><label style="grid-column:1/-1">Image URL or data image<input id="eImage"></label><label style="grid-column:1/-1">Legacy page URL<input id="eLegacy" placeholder="/legacy/example.html"></label><label style="grid-column:1/-1">Information<textarea id="eBody"></textarea></label><label><span>Player visibility</span><select id="eUnlocked"><option value="true">Unlocked</option><option value="false">Locked / GM only</option></select></label><label>Upload image<input id="eFile" type="file" accept="image/*" onchange="readImage(this)"></label></div><div class="row" style="margin-top:14px"><button class="btn primary" onclick="saveEntry()">SAVE</button><button class="btn danger" id="deleteEntryBtn" onclick="deleteEntry()">DELETE</button><button class="btn" onclick="closeModal('editModal')">CANCEL</button></div></div></div>
<div class="modal" id="manageModal"><div class="dialog wide"><h3>CAMPAIGN MANAGER</h3><div class="split"><section><h4>TABS</h4><div class="listbox" id="tabManager"></div><div class="row" style="margin-top:9px"><button class="btn" onclick="addTab()">+ ADD TAB</button></div><h4>CAMPAIGN SETTINGS</h4><div class="form two"><label>Campaign title<input id="mTitle"></label><label>Subtitle<input id="mSubtitle"></label><label>Admin PIN<input id="mPin" type="password"></label></div></section><section><h4>GITHUB PUBLISHING</h4><div class="notice">Use a public repository for the player snapshot. Locked entries are never uploaded. Your GitHub token is saved only in the local app configuration file.</div><div class="form two" style="margin-top:10px"><label>Owner / username<input id="gOwner"></label><label>Repository<input id="gRepo"></label><label>Branch<input id="gBranch" value="main"></label><label>Player data path<input id="gPath" value="campaign.json"></label><label style="grid-column:1/-1">Fine-grained personal access token<input id="gToken" type="password"></label></div><div class="row" style="margin-top:10px"><button class="btn primary" onclick="publishGithub()">PUBLISH PLAYER VIEW</button><button class="btn" onclick="testGithub()">TEST CONNECTION</button></div><h4>BACKUP</h4><div class="row"><button class="btn" onclick="exportMaster()">EXPORT MASTER</button><label class="btn">IMPORT MASTER<input type="file" accept="application/json" hidden onchange="importMaster(this)"></label></div></section></div><div class="row" style="margin-top:16px"><button class="btn primary" onclick="saveManager()">SAVE SETTINGS</button><button class="btn" onclick="closeModal('manageModal')">CLOSE</button></div></div></div>
<div class="toast" id="toast"></div>
<script>
let data=null, config=null, currentTab='factions', admin=false, editingId=null;
const $=id=>document.getElementById(id); const esc=s=>(s??'').toString().replace(/[&<>\"']/g,c=>({'&':'&amp;','<':'&lt;','>':'&gt;','\"':'&quot;',"'":'&#39;'}[c]));
async function boot(){data=await (await fetch('/api/master')).json();config=await (await fetch('/api/config')).json();render();if(config.autoSync&&config.owner&&config.repo)syncRemote(true)}
function visibleTabs(){return data.tabs.filter(t=>admin||t.visible).sort((a,b)=>(a.order||0)-(b.order||0))}
function entries(){return data.entries.filter(e=>e.tabId===currentTab&&(admin||e.unlocked)).sort((a,b)=>(a.order||0)-(b.order||0))}
function render(){document.body.classList.toggle('admin',admin);$('brandTitle').textContent=data.campaignTitle;$('brandSub').textContent=data.subtitle;let ts=visibleTabs();if(!ts.some(t=>t.id===currentTab)&&ts[0])currentTab=ts[0].id;$('tabs').innerHTML=ts.map(t=>`<button class="tab ${t.id===currentTab?'active':''}" onclick="selectTab('${esc(t.id)}')"><span>${esc(t.icon||'□')}</span><span>${esc(t.title)}</span></button>`).join('');let tab=ts.find(t=>t.id===currentTab);$('pageTitle').textContent=tab?.title?.toUpperCase()||'INDEX';$('pageSub').textContent=admin?'ADMIN ACCESS // LOCKED RECORDS VISIBLE':'PLAYER ACCESS // AUTHORIZED RECORDS ONLY';let list=entries();$('grid').innerHTML=list.length?list.map(card).join(''):`<div class="empty">NO AUTHORIZED RECORDS IN THIS SECTION</div>`;$('adminBtn').textContent=admin?'EXIT ADMIN MODE':'ADMIN LOGIN'}
function card(e){return `<article class="card ${e.unlocked?'':'locked'}" onclick="openEntry('${esc(e.id)}')">${!e.unlocked?'<span class="lock">LOCKED</span>':''}${e.image?`<img class="thumb" src="${esc(e.image)}" onerror="this.style.display='none'">`:''}<div class="tag">${e.unlocked?'AUTHORIZED RECORD':'GM-ONLY RECORD'}</div><h3>${esc(e.title)}</h3><div class="sub">${esc(e.subtitle)}</div><div class="body">${esc(e.body)}</div>${admin?`<button class="btn small admin-only" style="position:absolute;right:10px;bottom:10px" onclick="event.stopPropagation();editEntry('${esc(e.id)}')">EDIT</button>`:''}</article>`}
function selectTab(id){currentTab=id;render();$('side').classList.remove('open')}
function adminAction(){if(admin){admin=false;render();toast('Admin mode closed')}else{$('loginModal').classList.add('show');$('pinInput').focus()}}
async function login(){let master=await (await fetch('/api/master')).json();if($('pinInput').value===master.adminPin){data=master;admin=true;$('pinInput').value='';closeModal('loginModal');render();toast('Admin mode enabled')}else toast('Incorrect PIN')}
function openEntry(id){let e=data.entries.find(x=>x.id===id);if(!e||(!admin&&!e.unlocked))return;let d=$('entryDialog');if(e.legacyUrl){d.innerHTML=`<div class="row"><h3>${esc(e.title)}</h3><button class="btn right" onclick="closeModal('entryModal')">CLOSE</button></div><iframe class="viewer" src="${esc(e.legacyUrl)}"></iframe>`}else{d.innerHTML=`<div class="row"><h3>${esc(e.title)}</h3><button class="btn right" onclick="closeModal('entryModal')">CLOSE</button></div><div class="entry-view">${e.image?`<img src="${esc(e.image)}">`:''}<p class="muted">${esc(e.subtitle)}</p><div class="text">${esc(e.body)}</div></div>`}$('entryModal').classList.add('show')}
function newEntry(){editingId=null;fillEntry({tabId:currentTab,title:'',subtitle:'',body:'',image:'',legacyUrl:'',unlocked:false,order:entries().length+1});$('deleteEntryBtn').style.display='none';$('editHeading').textContent='NEW ENTRY';$('editModal').classList.add('show')}
function editEntry(id){editingId=id;fillEntry(data.entries.find(x=>x.id===id));$('deleteEntryBtn').style.display='inline-flex';$('editHeading').textContent='EDIT ENTRY';$('editModal').classList.add('show')}
function fillEntry(e){$('eTab').innerHTML=data.tabs.map(t=>`<option value="${esc(t.id)}">${esc(t.title)}</option>`).join('');$('eTitle').value=e.title||'';$('eSub').value=e.subtitle||'';$('eTab').value=e.tabId||currentTab;$('eOrder').value=e.order||1;$('eImage').value=e.image||'';$('eLegacy').value=e.legacyUrl||'';$('eBody').value=e.body||'';$('eUnlocked').value=String(!!e.unlocked)}
function slug(s){return(s||'entry').toLowerCase().replace(/[^a-z0-9]+/g,'-').replace(/^-|-$/g,'')+'-'+Date.now().toString(36)}
async function saveEntry(){let e={id:editingId||slug($('eTitle').value),title:$('eTitle').value.trim()||'Untitled',subtitle:$('eSub').value.trim(),tabId:$('eTab').value,order:Number($('eOrder').value)||1,image:$('eImage').value.trim(),legacyUrl:$('eLegacy').value.trim(),body:$('eBody').value,unlocked:$('eUnlocked').value==='true'};if(editingId){let i=data.entries.findIndex(x=>x.id===editingId);data.entries[i]=e}else data.entries.push(e);currentTab=e.tabId;await saveLocal();closeModal('editModal');render();toast('Entry saved')}
async function deleteEntry(){if(!editingId||!confirm('Delete this entry?'))return;data.entries=data.entries.filter(x=>x.id!==editingId);await saveLocal();closeModal('editModal');render();toast('Entry deleted')}
function readImage(input){let f=input.files[0];if(!f)return;if(f.size>8_000_000)return toast('Use an image smaller than 8 MB');let r=new FileReader();r.onload=()=>$('eImage').value=r.result;r.readAsDataURL(f)}
function openManager(){$('mTitle').value=data.campaignTitle;$('mSubtitle').value=data.subtitle;$('mPin').value=data.adminPin;$('gOwner').value=config.owner||'';$('gRepo').value=config.repo||'';$('gBranch').value=config.branch||'main';$('gPath').value=config.path||'campaign.json';$('gToken').value=config.token||'';renderTabManager();$('manageModal').classList.add('show')}
function renderTabManager(){$('tabManager').innerHTML=data.tabs.sort((a,b)=>(a.order||0)-(b.order||0)).map((t,i)=>`<div class="listitem"><input style="width:36px" value="${esc(t.icon||'□')}" onchange="tabField('${t.id}','icon',this.value)"><input style="flex:1" value="${esc(t.title)}" onchange="tabField('${t.id}','title',this.value)"><input style="width:55px" type="number" value="${t.order||i+1}" onchange="tabField('${t.id}','order',Number(this.value))"><label class="muted"><input type="checkbox" ${t.visible?'checked':''} onchange="tabField('${t.id}','visible',this.checked)"> visible</label><button class="btn small danger" onclick="removeTab('${t.id}')">X</button></div>`).join('')}
function tabField(id,key,val){let t=data.tabs.find(x=>x.id===id);if(t)t[key]=val}
function addTab(){let title=prompt('New tab name:');if(!title)return;data.tabs.push({id:slug(title),title,icon:'□',visible:true,order:data.tabs.length+1});renderTabManager()}
function removeTab(id){if(data.entries.some(e=>e.tabId===id))return toast('Move or delete this tab’s entries first');if(confirm('Delete this empty tab?')){data.tabs=data.tabs.filter(t=>t.id!==id);renderTabManager()}}
async function saveManager(){data.campaignTitle=$('mTitle').value.trim()||'MEMORIES OF A FEW';data.subtitle=$('mSubtitle').value.trim();data.adminPin=$('mPin').value||data.adminPin;config={...config,owner:$('gOwner').value.trim(),repo:$('gRepo').value.trim(),branch:$('gBranch').value.trim()||'main',path:$('gPath').value.trim()||'campaign.json',token:$('gToken').value.trim()};await Promise.all([saveLocal(),fetch('/api/config',{method:'PUT',headers:{'Content-Type':'application/json'},body:JSON.stringify(config,null,2)})]);render();toast('Settings saved')}
async function saveLocal(){await fetch('/api/master',{method:'PUT',headers:{'Content-Type':'application/json'},body:JSON.stringify(data,null,2)})}
function playerSnapshot(){let tabs=data.tabs.filter(t=>t.visible);let ids=new Set(tabs.map(t=>t.id));return{campaignTitle:data.campaignTitle,subtitle:data.subtitle,publishedAt:new Date().toISOString(),tabs,entries:data.entries.filter(e=>e.unlocked&&ids.has(e.tabId)).map(({id,tabId,title,subtitle,body,image,legacyUrl,order,unlocked})=>({id,tabId,title,subtitle,body,image,legacyUrl,order,unlocked}))}}
function githubHeaders(){return{'Accept':'application/vnd.github+json','Authorization':'Bearer '+config.token,'X-GitHub-Api-Version':'2022-11-28','Content-Type':'application/json'}}
async function publishGithub(){await saveManager();if(!config.owner||!config.repo||!config.token)return toast('Complete the GitHub settings first');let url=`https://api.github.com/repos/${encodeURIComponent(config.owner)}/${encodeURIComponent(config.repo)}/contents/${config.path.split('/').map(encodeURIComponent).join('/')}?ref=${encodeURIComponent(config.branch)}`;try{let sha=null;let old=await fetch(url,{headers:githubHeaders()});if(old.ok)sha=(await old.json()).sha;let content=btoa(unescape(encodeURIComponent(JSON.stringify(playerSnapshot(),null,2))));let body={message:'Publish MOAF player campaign data',content,branch:config.branch};if(sha)body.sha=sha;let res=await fetch(url,{method:'PUT',headers:githubHeaders(),body:JSON.stringify(body)});if(!res.ok)throw new Error((await res.json()).message||res.statusText);$('status').textContent='PUBLISHED '+new Date().toLocaleTimeString();toast('Unlocked player content published')}catch(e){toast('Publish failed: '+e.message)}}
async function testGithub(){await saveManager();try{let r=await fetch(`https://api.github.com/repos/${encodeURIComponent(config.owner)}/${encodeURIComponent(config.repo)}`,{headers:githubHeaders()});if(!r.ok)throw new Error((await r.json()).message||r.statusText);toast('GitHub connection successful')}catch(e){toast('Connection failed: '+e.message)}}
async function syncRemote(silent=false){if(!config.owner||!config.repo){if(!silent)toast('Online updates are not configured yet');return}let raw=`https://raw.githubusercontent.com/${encodeURIComponent(config.owner)}/${encodeURIComponent(config.repo)}/${encodeURIComponent(config.branch)}/${config.path.split('/').map(encodeURIComponent).join('/')}?t=${Date.now()}`;try{let r=await fetch(raw,{cache:'no-store'});if(!r.ok)throw new Error('Player data not found');let remote=await r.json();if(!admin)data=remote;$('status').textContent='UPDATED '+new Date().toLocaleTimeString();render();if(!silent)toast('Campaign updated')}catch(e){if(!silent)toast('Update check failed: '+e.message)}}
function exportMaster(){let a=document.createElement('a');a.href=URL.createObjectURL(new Blob([JSON.stringify(data,null,2)],{type:'application/json'}));a.download='moaf-campaign-master.json';a.click();URL.revokeObjectURL(a.href)}
function importMaster(input){let f=input.files[0];if(!f)return;let r=new FileReader();r.onload=async()=>{try{data=JSON.parse(r.result);await saveLocal();render();toast('Master backup imported')}catch{toast('Invalid campaign backup')}};r.readAsText(f)}
function closeModal(id){$(id).classList.remove('show')}function toast(msg){$('toast').textContent=msg;$('toast').classList.add('show');setTimeout(()=>$('toast').classList.remove('show'),3200)}
document.addEventListener('keydown',e=>{if(e.key==='Escape')document.querySelectorAll('.modal.show').forEach(m=>m.classList.remove('show'))});boot();
</script></body></html>
""";
}
