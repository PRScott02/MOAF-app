/**
 * NPCs view — searchable, filterable grid grouped by section.
 */
const NpcsView = (() => {

  let searchQuery = '';
  let factionFilter = 'all';
  let sectionFilter = 'all';

  function escapeHtml(s) {
    if (s == null) return '';
    return String(s)
      .replaceAll('&', '&amp;')
      .replaceAll('<', '&lt;')
      .replaceAll('>', '&gt;')
      .replaceAll('"', '&quot;')
      .replaceAll("'", '&#39;');
  }

  function factionColor(name) {
    const map = {
      'BCI': '#7fffc5', 'CotU': '#ff4267', 'Truth Division': '#9b76ff',
      'Red Archive': '#ff334e', 'Continuance': '#6ad8ff',
      'Grey Market': '#ffd36b', 'RedWire': '#ff3b3b', 'Politics': '#f5f1d8',
      'Media': '#d8f7ec', 'Licensing': '#7fffc5', 'Player Thread': '#ffd36b',
      'Arasaka': '#d11f2f', 'Session One': '#ffffff'
    };
    return map[name] || '#7fffc5';
  }

  function initials(name) {
    if (!name) return '?';
    const parts = name.split(/\s+/).filter(Boolean);
    if (parts.length === 1) return parts[0][0].toUpperCase();
    return (parts[0][0] + parts[parts.length - 1][0]).toUpperCase();
  }

  function render() {
    const c = Data.getCampaign();
    if (!c) return '<div class="loading">No data.</div>';
    const npcs = c.npcs || [];

    // Group by section
    const sections = new Map();
    for (const n of npcs) {
      const sec = n.section || 'Unsorted';
      if (!sections.has(sec)) sections.set(sec, []);
      sections.get(sec).push(n);
    }

    // Collect unique factions for the filter dropdown
    const factionsSet = new Set();
    for (const n of npcs) if (n.faction) factionsSet.add(n.faction);
    const factionOpts = ['all', ...[...factionsSet].sort()];
    const sectionOpts = ['all', ...[...sections.keys()]];

    return `
      <div class="npc-view wrap">
        <section class="npc-hero">
          <div class="eyebrow">▸ Unified Personnel Index // Meridian Spire</div>
          <h1>NPC INDEX</h1>
          <p>Personnel dossiers for every active, archival, or watched individual in the Meridian Spire investigation. Public records are open; restricted records appear redacted unless GM clearance is provided.</p>
        </section>

        <div class="npc-controls">
          <input type="text" id="npc-search" placeholder="Search by name, role, or vibe…" value="${escapeHtml(searchQuery)}">
          <select id="npc-faction-filter">
            ${factionOpts.map(f => `<option value="${escapeHtml(f)}" ${f === factionFilter ? 'selected' : ''}>${f === 'all' ? 'All factions' : escapeHtml(f)}</option>`).join('')}
          </select>
          <select id="npc-section-filter">
            ${sectionOpts.map(s => `<option value="${escapeHtml(s)}" ${s === sectionFilter ? 'selected' : ''}>${s === 'all' ? 'All sections' : escapeHtml(s)}</option>`).join('')}
          </select>
          <span class="pill" style="text-align:center">${countMatching(npcs)} match</span>
        </div>

        ${[...sections.entries()].map(([sec, list]) => renderSection(sec, list)).join('')}
      </div>
    `;
  }

  function countMatching(npcs) {
    return npcs.filter(matchesFilters).length;
  }

  function matchesFilters(n) {
    if (factionFilter !== 'all' && n.faction !== factionFilter) return false;
    if (sectionFilter !== 'all' && n.section !== sectionFilter) return false;
    if (searchQuery) {
      const q = searchQuery.toLowerCase();
      const hay = [n.name, n.role, n.faction, n.section,
                   n.fields?.Vibe?.value, n.fields?.['Public Face']?.value]
                  .filter(Boolean).join(' ').toLowerCase();
      if (!hay.includes(q)) return false;
    }
    return true;
  }

  function renderSection(sec, list) {
    const visible = list.filter(matchesFilters);
    if (visible.length === 0) return '';
    const desc = Data.getCampaign()?.sectionDescriptions?.[sec] || '';
    return `
      <section class="npc-section-block">
        <h2>▸ ${escapeHtml(sec)}</h2>
        ${desc ? `<p class="section-desc">${escapeHtml(desc)}</p>` : ''}
        <div class="npc-grid-main">
          ${visible.map(renderNpc).join('')}
        </div>
      </section>
    `;
  }

  function renderNpc(n) {
    const color = factionColor(n.faction);
    const locked = Data.isLocked(n);

    if (locked) {
      return `
        <article class="npc-card locked-card" data-npc-id="${escapeHtml(n.id)}" style="--faction-color:${color}">
          <div class="npc-portrait" style="color:var(--red);text-shadow:0 0 20px var(--red)">🔒</div>
          <div class="npc-body">
            <div class="npc-role" style="color:var(--red)">▸ Classified Personnel File</div>
            <h3>[ REDACTED ]</h3>
            <span class="npc-faction-tag">Unknown Affiliation</span>
            <div class="mini">This individual has not yet been identified in your investigation.</div>
            ${Data.canEditCampaign() ? `
              <div style="margin-top:auto;padding-top:12px;display:flex;justify-content:flex-end;gap:6px">
                <span class="reveal-toggle hidden" data-npc-lock data-npc-id="${escapeHtml(n.id)}">🔒 LOCKED</span>
                <button class="edit-btn" data-npc-edit data-npc-id="${escapeHtml(n.id)}">EDIT</button>
              </div>
            ` : ''}
          </div>
        </article>
      `;
    }

    return `
      <article class="npc-card" data-npc-id="${escapeHtml(n.id)}" style="--faction-color:${color}">
        <div class="npc-portrait">${escapeHtml(initials(n.name))}</div>
        <div class="npc-body">
          <div class="npc-role">${escapeHtml(n.role || '')}</div>
          <h3>${escapeHtml(n.name)}</h3>
          ${n.faction ? `<span class="npc-faction-tag">${escapeHtml(n.faction)}</span>` : ''}
          ${n.status?.length ? `<div class="status-pills">${n.status.map(s => `<span class="pill">${escapeHtml(s)}</span>`).join('')}</div>` : ''}
          ${renderNpcField(n, 'Quote', 'quote')}
          ${renderNpcField(n, 'Public Face', 'mini')}
          ${renderNpcField(n, 'Vibe', 'mini')}
          ${Data.canEditCampaign() ? `
            <div style="margin-top:auto;padding-top:12px;display:flex;justify-content:flex-end;gap:6px">
              <span class="reveal-toggle revealed" data-npc-lock data-npc-id="${escapeHtml(n.id)}">🔓 UNLOCKED</span>
              <button class="edit-btn" data-npc-edit data-npc-id="${escapeHtml(n.id)}">EDIT</button>
            </div>
          ` : ''}
        </div>
      </article>
    `;
  }

  function renderNpcField(n, key, style) {
    const f = n.fields?.[key];
    if (!f) return '';
    if (!Data.isFieldVisible(f)) {
      return `<div class="field-redacted" style="margin:8px 0"><b>${escapeHtml(key)}</b><p>[REDACTED]</p></div>`;
    }
    if (style === 'quote') {
      return `<div class="quote">"${escapeHtml(f.value)}"</div>`;
    }
    return `<div class="mini"><strong>${escapeHtml(key)}</strong>${escapeHtml(f.value)}</div>`;
  }

  function wire(container) {
    const search = container.querySelector('#npc-search');
    const ff = container.querySelector('#npc-faction-filter');
    const sf = container.querySelector('#npc-section-filter');
    if (search) {
      search.addEventListener('input', () => {
        searchQuery = search.value;
        App.rerender();
        // Restore focus after rerender
        setTimeout(() => {
          const r = document.querySelector('#npc-search');
          if (r) { r.focus(); r.setSelectionRange(searchQuery.length, searchQuery.length); }
        }, 0);
      });
    }
    if (ff) ff.addEventListener('change', () => { factionFilter = ff.value; App.rerender(); });
    if (sf) sf.addEventListener('change', () => { sectionFilter = sf.value; App.rerender(); });

    // NPC card click → detail
    container.querySelectorAll('.npc-card').forEach(card => {
      card.addEventListener('click', e => {
        if (e.target.closest('.edit-btn')) return;
        if (e.target.closest('[data-npc-lock]')) return;
        const id = card.getAttribute('data-npc-id');
        const n = Data.getCampaign().npcs.find(x => x.id === id);
        if (n && Data.isLocked(n)) return;  // locked NPCs don't open for players
        openNpcDetail(id);
      });
    });

    // NPC lock/unlock toggle (admin)
    container.querySelectorAll('[data-npc-lock]').forEach(el => {
      el.addEventListener('click', async e => {
        e.stopPropagation();
        const id = el.getAttribute('data-npc-id');
        const n = Data.getCampaign().npcs.find(x => x.id === id);
        if (!n) return;
        n.locked = !n.locked;
        try {
          Toast.show('Saving…');
          await Data.pushCampaign();
          Toast.show(n.locked ? 'NPC locked' : 'NPC unlocked');
          App.rerender();
        } catch (err) { Toast.show('Save failed: ' + err.message, true); }
      });
    });

    // Edit NPC
    container.querySelectorAll('[data-npc-edit]').forEach(btn => {
      btn.addEventListener('click', e => {
        e.stopPropagation();
        const id = btn.getAttribute('data-npc-id');
        const n = Data.getCampaign().npcs.find(x => x.id === id);
        if (n) AdminPanel.openNpcEditor(n);
      });
    });
  }

  function openNpcDetail(id) {
    const n = Data.getCampaign().npcs.find(x => x.id === id);
    if (!n) return;
    const color = factionColor(n.faction);
    const isAdmin = Data.canEditCampaign();

    const renderField = (key) => {
      const f = n.fields?.[key];
      if (!f) return '';
      if (Data.isFieldVisible(f)) {
        return `
          <div class="field wide" style="margin:12px 0">
            <b>${escapeHtml(key)}</b>
            <p>${escapeHtml(f.value)}</p>
            ${isAdmin ? `
              <div style="margin-top:8px;display:flex;gap:6px;justify-content:flex-end">
                <span class="reveal-toggle ${f.revealed ? 'revealed' : 'hidden'}"
                      data-npc-detail-toggle data-key="${escapeHtml(key)}">
                  ${f.revealed ? '◉ REVEALED' : '◌ HIDDEN'}
                </span>
              </div>
            ` : ''}
          </div>
        `;
      }
      return `
        <div class="field-redacted" style="margin:12px 0">
          <b>${escapeHtml(key)}</b>
          <p>[ REDACTED // CLEARANCE INSUFFICIENT ]</p>
          ${isAdmin ? `
            <div style="margin-top:8px;display:flex;justify-content:flex-end;position:relative;z-index:1">
              <span class="reveal-toggle hidden" data-npc-detail-toggle data-key="${escapeHtml(key)}">◌ HIDDEN</span>
            </div>
          ` : ''}
        </div>
      `;
    };

    const allFieldKeys = Object.keys(n.fields || {});

    const html = `
      <div class="modal-backdrop" data-close-modal>
        <div class="modal" style="max-width:840px" onclick="event.stopPropagation()">
          <div style="display:flex;justify-content:space-between;align-items:flex-start;gap:14px">
            <div>
              <div class="eyebrow" style="color:${color};font-family:var(--mono);font-size:11px;letter-spacing:0.2em;text-transform:uppercase;margin-bottom:8px">
                ${escapeHtml(n.role || '')}
              </div>
              <h2 style="color:${color}">${escapeHtml(n.name)}</h2>
              ${n.faction ? `<span class="pill">${escapeHtml(n.faction)}</span>` : ''}
            </div>
            ${isAdmin ? `<button class="edit-btn" data-npc-detail-edit>EDIT FIELDS</button>` : ''}
          </div>
          ${allFieldKeys.map(renderField).join('')}
          ${n.connections?.length ? `
            <div class="field wide" style="margin-top:14px">
              <b>Connections</b>
              <p>${n.connections.map(escapeHtml).join(' · ')}</p>
            </div>
          ` : ''}
          <div class="modal-actions">
            <button class="btn" data-close-modal>Close</button>
          </div>
        </div>
      </div>
    `;
    const host = document.createElement('div');
    host.innerHTML = html;
    document.body.appendChild(host.firstElementChild);
    const backdrop = document.querySelector('.modal-backdrop');
    backdrop.querySelectorAll('[data-close-modal]').forEach(el => el.addEventListener('click', () => backdrop.remove()));
    backdrop.querySelectorAll('[data-npc-detail-toggle]').forEach(el => {
      el.addEventListener('click', async () => {
        const key = el.getAttribute('data-key');
        n.fields[key].revealed = !n.fields[key].revealed;
        try {
          await Data.pushCampaign();
          Toast.show('Visibility updated');
          backdrop.remove();
          openNpcDetail(id);
        } catch (e) { Toast.show('Save failed: ' + e.message, true); }
      });
    });
    const editBtn = backdrop.querySelector('[data-npc-detail-edit]');
    if (editBtn) {
      editBtn.addEventListener('click', () => {
        backdrop.remove();
        AdminPanel.openNpcEditor(n);
      });
    }
  }

  return { render, wire };
})();
