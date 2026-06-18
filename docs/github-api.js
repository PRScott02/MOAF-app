/**
 * GitHub API helper for MOAF web app.
 *
 * Reads use raw.githubusercontent.com (cached aggressively by browsers — we
 * append a timestamp to bypass cache).
 * Writes use the api.github.com Contents endpoint and require a Personal
 * Access Token stored in localStorage on the editor's device.
 */

const GitHub = (() => {
  // Repo location is hard-coded since this site is bound to a specific repo.
  // The site's own URL tells us where it lives, but for clarity we read it
  // from a config block injected by the page (or default to current host).
  const REPO = {
    owner: 'PRScott02',
    repo:  'MOAF-app',
    branch: 'main',
    campaignPath: 'docs/campaign.json',
    notesPath:    'docs/notes.json'
  };

  // ── Token storage (browser-local only) ───────────────────────────────
  const ADMIN_TOKEN_KEY = 'moaf.adminToken';
  const NOTE_TOKEN_KEY  = 'moaf.noteTakerToken';

  function getAdminToken()       { return localStorage.getItem(ADMIN_TOKEN_KEY) || ''; }
  function setAdminToken(t)      { localStorage.setItem(ADMIN_TOKEN_KEY, t || ''); }
  function getNoteTakerToken()   { return localStorage.getItem(NOTE_TOKEN_KEY) || ''; }
  function setNoteTakerToken(t)  { localStorage.setItem(NOTE_TOKEN_KEY, t || ''); }

  // ── Read campaign / notes (public) ───────────────────────────────────
  async function fetchJson(path) {
    const url = `https://raw.githubusercontent.com/${REPO.owner}/${REPO.repo}/${REPO.branch}/${path}?t=${Date.now()}`;
    const res = await fetch(url);
    if (!res.ok) throw new Error(`Fetch ${path} failed: HTTP ${res.status}`);
    return res.json();
  }

  async function fetchCampaign() { return fetchJson(REPO.campaignPath); }
  async function fetchNotes()    { return fetchJson(REPO.notesPath); }

  // ── Write via Contents API ───────────────────────────────────────────
  async function writeFile(path, dataObj, message, token) {
    if (!token) throw new Error('No token set');
    const apiBase = `https://api.github.com/repos/${REPO.owner}/${REPO.repo}/contents/${path}`;

    // Get current sha so the PUT updates instead of creating
    let sha = null;
    const head = await fetch(`${apiBase}?ref=${REPO.branch}`, {
      headers: {
        'Accept': 'application/vnd.github+json',
        'Authorization': `Bearer ${token}`
      }
    });
    if (head.ok) {
      const j = await head.json();
      sha = j.sha;
    }

    const json = JSON.stringify(dataObj, null, 2);
    const contentB64 = btoa(unescape(encodeURIComponent(json)));

    const body = { message, content: contentB64, branch: REPO.branch };
    if (sha) body.sha = sha;

    const put = await fetch(apiBase, {
      method: 'PUT',
      headers: {
        'Accept': 'application/vnd.github+json',
        'Authorization': `Bearer ${token}`,
        'Content-Type': 'application/json'
      },
      body: JSON.stringify(body)
    });
    if (!put.ok) {
      const err = await put.text();
      throw new Error(`PUT ${path} failed: HTTP ${put.status} — ${err}`);
    }
    return put.json();
  }

  async function pushCampaign(data, token) {
    return writeFile(REPO.campaignPath, data, 'Update MOAF campaign data', token);
  }
  async function pushNotes(data, token) {
    return writeFile(REPO.notesPath, data, 'Update MOAF session notes', token);
  }

  async function testToken(token) {
    if (!token) return false;
    const res = await fetch(`https://api.github.com/repos/${REPO.owner}/${REPO.repo}`, {
      headers: {
        'Accept': 'application/vnd.github+json',
        'Authorization': `Bearer ${token}`
      }
    });
    return res.ok;
  }

  return {
    REPO,
    fetchCampaign, fetchNotes,
    pushCampaign, pushNotes,
    testToken,
    getAdminToken, setAdminToken,
    getNoteTakerToken, setNoteTakerToken
  };
})();
