/**
 * Notes view — session notes with edit access for admin & note-taker.
 */
const NotesView = (() => {

  function escapeHtml(s) {
    if (s == null) return '';
    return String(s)
      .replaceAll('&', '&amp;')
      .replaceAll('<', '&lt;')
      .replaceAll('>', '&gt;')
      .replaceAll('"', '&quot;')
      .replaceAll("'", '&#39;');
  }

  function render() {
    const notes = Data.getNotes() || { sessions: [] };
    const canEdit = Data.canEditNotes();
    const sessions = (notes.sessions || []).slice().sort((a, b) => (a.order || 0) - (b.order || 0));

    return `
      <div class="notes-view wrap">
        <div class="notes-header">
          <div>
            <div class="eyebrow">▸ Session Records // Shared Investigation Log</div>
            <h1>Case Notes</h1>
          </div>
          ${canEdit ? `<button class="btn primary" id="new-session">+ NEW SESSION</button>` : ''}
        </div>

        ${sessions.length === 0
          ? `<div class="notes-empty">No session records yet</div>`
          : sessions.map(renderSession).join('')}
      </div>
    `;
  }

  function renderSession(s) {
    const canEdit = Data.canEditNotes();
    const isAdmin = Data.canEditCampaign();
    return `
      <section class="session-block" data-session-id="${escapeHtml(s.id)}">
        <div class="session-eyebrow">▸ Session Note</div>
        <h3>${escapeHtml(s.title)}</h3>
        <div class="session-meta">${s.date ? escapeHtml(s.date) : 'Undated'}</div>
        <div class="session-body">${escapeHtml(s.body || '(No notes recorded yet.)')}</div>
        ${canEdit ? `
          <div class="session-actions">
            <button class="btn" data-session-edit data-session-id="${escapeHtml(s.id)}">EDIT</button>
            ${isAdmin ? `<button class="btn danger" data-session-delete data-session-id="${escapeHtml(s.id)}">DELETE</button>` : ''}
          </div>
        ` : ''}
      </section>
    `;
  }

  function wire(container) {
    const newBtn = container.querySelector('#new-session');
    if (newBtn) newBtn.addEventListener('click', () => openSessionEditor(null));

    container.querySelectorAll('[data-session-edit]').forEach(btn => {
      btn.addEventListener('click', () => {
        const id = btn.getAttribute('data-session-id');
        const s = (Data.getNotes()?.sessions || []).find(x => x.id === id);
        if (s) openSessionEditor(s);
      });
    });

    container.querySelectorAll('[data-session-delete]').forEach(btn => {
      btn.addEventListener('click', async () => {
        if (!confirm('Delete this session note?')) return;
        const id = btn.getAttribute('data-session-id');
        const notes = Data.getNotes();
        notes.sessions = notes.sessions.filter(x => x.id !== id);
        try {
          Toast.show('Saving…');
          await Data.pushNotes();
          Toast.show('Session deleted');
          App.rerender();
        } catch (e) { Toast.show('Save failed: ' + e.message, true); }
      });
    });
  }

  function openSessionEditor(existing) {
    const isNew = !existing;
    const s = existing || { id: 'session-' + Date.now(), title: '', date: '', body: '', order: 0 };

    const html = `
      <div class="modal-backdrop" data-close-modal>
        <div class="modal" onclick="event.stopPropagation()">
          <h2>${isNew ? 'New Session' : 'Edit Session'}</h2>
          <div class="modal-row">
            <label>Title</label>
            <input type="text" id="sess-title" value="${escapeHtml(s.title)}" placeholder="Session 1: The Ruiz Apartment">
          </div>
          <div class="modal-row">
            <label>Date (optional)</label>
            <input type="text" id="sess-date" value="${escapeHtml(s.date)}" placeholder="2089-04-12">
          </div>
          <div class="modal-row">
            <label>Notes</label>
            <textarea id="sess-body" rows="14">${escapeHtml(s.body)}</textarea>
          </div>
          <div class="modal-actions">
            <button class="btn" data-close-modal>Cancel</button>
            <button class="btn primary" id="sess-save">SAVE & SYNC</button>
          </div>
        </div>
      </div>
    `;
    const host = document.createElement('div');
    host.innerHTML = html;
    document.body.appendChild(host.firstElementChild);
    const backdrop = document.querySelector('.modal-backdrop');
    backdrop.querySelectorAll('[data-close-modal]').forEach(el => el.addEventListener('click', () => backdrop.remove()));

    backdrop.querySelector('#sess-save').addEventListener('click', async () => {
      s.title = (backdrop.querySelector('#sess-title').value || '').trim() || 'Untitled Session';
      s.date  = (backdrop.querySelector('#sess-date').value || '').trim();
      s.body  = backdrop.querySelector('#sess-body').value;

      const notes = Data.getNotes() || { sessions: [] };
      if (!notes.sessions) notes.sessions = [];
      if (isNew) {
        s.order = notes.sessions.length + 1;
        notes.sessions.push(s);
      } else {
        const idx = notes.sessions.findIndex(x => x.id === s.id);
        if (idx >= 0) notes.sessions[idx] = s;
      }
      try {
        Toast.show('Saving…');
        await Data.pushNotes();
        Toast.show('Session saved');
        backdrop.remove();
        App.rerender();
      } catch (e) { Toast.show('Save failed: ' + e.message, true); }
    });
  }

  return { render, wire };
})();
