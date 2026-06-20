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
  async function getSha(apiBase, token) {
    // Cache-bust via the URL timestamp only. Do NOT send a Cache-Control header:
    // GitHub's API rejects it in CORS preflight, which blocks the request.
    const res = await fetch(`${apiBase}?ref=${REPO.branch}&t=${Date.now()}`, {
      headers: {
        'Accept': 'application/vnd.github+json',
        'Authorization': `Bearer ${token}`
      },
      cache: 'no-store'
    });
    if (res.ok) {
      const j = await res.json();
      return j.sha;
    }
    return null;
  }

  async function writeFile(path, dataObj, message, token) {
    if (!token) throw new Error('No token set');
    const apiBase = `https://api.github.com/repos/${REPO.owner}/${REPO.repo}/contents/${path}`;

    const json = JSON.stringify(dataObj, null, 2);
    const contentB64 = btoa(unescape(encodeURIComponent(json)));

    const MAX_ATTEMPTS = 5;
    let lastErr = '';
    for (let attempt = 0; attempt < MAX_ATTEMPTS; attempt++) {
      // Always fetch a fresh sha right before each attempt.
      const sha = await getSha(apiBase, token);
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

      if (put.ok) return put.json();

      lastErr = `HTTP ${put.status} — ${await put.text()}`;

      // Conflict (sha mismatch) → wait briefly for GitHub's API to settle, then retry.
      if (put.status === 409 || put.status === 422) {
        await new Promise(r => setTimeout(r, 400 + attempt * 300));
        continue;
      }
      // Any other error is not retryable.
      throw new Error(`PUT ${path} failed: ${lastErr}`);
    }
    throw new Error(`PUT ${path} failed after ${MAX_ATTEMPTS} attempts: ${lastErr}`);
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
