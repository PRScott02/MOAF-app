/**
 * Loads campaign and notes data, manages local cache, and tracks mode.
 */

const Data = (() => {
  let campaign = null;   // current loaded campaign
  let notes    = null;   // current loaded notes
  let mode     = 'player';  // 'player' | 'admin' | 'notetaker'

  const CACHE_CAMPAIGN = 'moaf.cache.campaign';
  const CACHE_NOTES    = 'moaf.cache.notes';
  const ADMIN_PIN_KEY    = 'moaf.adminPin';
  const NOTE_PIN_KEY     = 'moaf.notePin';

  // Default PINs (change these in admin settings)
  const DEFAULT_ADMIN_PIN = '2089';
  const DEFAULT_NOTE_PIN  = '0451';

  function getCampaign() { return campaign; }
  function getNotes()    { return notes; }
  function getMode()     { return mode; }
  function setMode(m)    { mode = m; }

  function getAdminPin()   { return localStorage.getItem(ADMIN_PIN_KEY) || DEFAULT_ADMIN_PIN; }
  function setAdminPin(p)  { localStorage.setItem(ADMIN_PIN_KEY, p); }
  function getNotePin()    { return localStorage.getItem(NOTE_PIN_KEY) || DEFAULT_NOTE_PIN; }
  function setNotePin(p)   { localStorage.setItem(NOTE_PIN_KEY, p); }

  async function load() {
    // Try cache first for instant render, then refresh from network
    const cachedCampaign = localStorage.getItem(CACHE_CAMPAIGN);
    const cachedNotes    = localStorage.getItem(CACHE_NOTES);
    if (cachedCampaign) { try { campaign = JSON.parse(cachedCampaign); } catch (_) {} }
    if (cachedNotes)    { try { notes    = JSON.parse(cachedNotes);    } catch (_) {} }

    // Network refresh — non-blocking if cache present
    try {
      const fresh = await GitHub.fetchCampaign();
      campaign = fresh;
      localStorage.setItem(CACHE_CAMPAIGN, JSON.stringify(fresh));
    } catch (e) {
      if (!campaign) throw e;
      console.warn('Campaign refresh failed, using cache:', e);
    }
    try {
      const freshN = await GitHub.fetchNotes();
      notes = freshN;
      localStorage.setItem(CACHE_NOTES, JSON.stringify(freshN));
    } catch (e) {
      if (!notes) notes = { sessions: [] };
      console.warn('Notes refresh failed:', e);
    }
  }

  async function refresh() {
    try {
      campaign = await GitHub.fetchCampaign();
      localStorage.setItem(CACHE_CAMPAIGN, JSON.stringify(campaign));
    } catch (e) { console.warn(e); }
    try {
      notes = await GitHub.fetchNotes();
      localStorage.setItem(CACHE_NOTES, JSON.stringify(notes));
    } catch (e) { console.warn(e); }
  }

  function isFieldVisible(fieldObj) {
    if (mode === 'admin') return true;
    return fieldObj && fieldObj.revealed === true;
  }

  function canEditCampaign() { return mode === 'admin'; }
  function canEditNotes()    { return mode === 'admin' || mode === 'notetaker'; }

  async function pushCampaign() {
    if (!canEditCampaign()) throw new Error('Not authorized');
    const token = GitHub.getAdminToken();
    if (!token) throw new Error('Set your admin token first');
    await GitHub.pushCampaign(campaign, token);
    localStorage.setItem(CACHE_CAMPAIGN, JSON.stringify(campaign));
  }
  async function pushNotes() {
    if (!canEditNotes()) throw new Error('Not authorized');
    const token = mode === 'admin' ? GitHub.getAdminToken() : GitHub.getNoteTakerToken();
    if (!token) throw new Error('Set your token first');
    await GitHub.pushNotes(notes, token);
    localStorage.setItem(CACHE_NOTES, JSON.stringify(notes));
  }

  return {
    load, refresh,
    getCampaign, getNotes,
    getMode, setMode,
    getAdminPin, setAdminPin, getNotePin, setNotePin,
    isFieldVisible, canEditCampaign, canEditNotes,
    pushCampaign, pushNotes
  };
})();
