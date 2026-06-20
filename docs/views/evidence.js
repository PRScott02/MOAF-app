/**
 * Files / Evidence view — interactive in-world terminal screens.
 * Each card opens a self-contained HTML "device" full-screen in a new tab.
 * Locked items show a redacted placeholder until the GM unlocks them.
 */
const EvidenceView = (() => {

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
    const c = Data.getCampaign();
    if (!c) return '<div class="loading">No data.</div>';
    const items = c.evidence || [];

    return `
      <div class="evidence-view wrap">
        <section class="npc-hero">
          <div class="eyebrow">▸ Recovered Files // Intercepted Transmissions</div>
          <h1>FILES / EVIDENCE</h1>
          <p>Interactive records, intercepted messages, and live device interfaces recovered over the course of the investigation. Open a file to access its terminal.</p>
        </section>

        <div class="evidence-grid">
          ${items.length === 0
            ? '<div class="notes-empty">No files recovered yet</div>'
            : items.map(renderCard).join('')}
        </div>
      </div>
    `;
  }

  function renderCard(e) {
    const accent = e.accent || '#7fffc5';
    const isAdmin = Data.canEditCampaign();
    const actuallyLocked = e.locked === true;
    // Players see the sealed placeholder when locked; admins always see the full card.
    const showSealed = actuallyLocked && !isAdmin;

    if (showSealed) {
      return `
        <article class="evidence-card locked-card" data-ev-id="${escapeHtml(e.id)}" style="--accent:${accent}">
          <div class="evidence-icon" style="color:var(--red);text-shadow:0 0 20px var(--red)">🔒</div>
          <div class="evidence-body">
            <div class="evidence-kicker" style="color:var(--red)">▸ ENCRYPTED // NOT YET RECOVERED</div>
            <h3>[ SEALED FILE ]</h3>
            <p class="evidence-desc">This record has not yet been recovered in your investigation.</p>
          </div>
        </article>
      `;
    }

    return `
      <article class="evidence-card${actuallyLocked && isAdmin ? ' admin-locked' : ''}" data-ev-id="${escapeHtml(e.id)}" style="--accent:${accent}">
        <div class="evidence-icon">▦</div>
        <div class="evidence-body">
          <div class="evidence-kicker">▸ ${escapeHtml(e.subtitle || 'RECOVERED FILE')}</div>
          <h3>${escapeHtml(e.title)}</h3>
          <p class="evidence-desc">${escapeHtml(e.desc || '')}</p>
          <div class="evidence-actions">
            <button class="btn primary" data-ev-open data-ev-id="${escapeHtml(e.id)}">▸ ACCESS TERMINAL</button>
          </div>
          ${isAdmin ? `
            <div class="evidence-admin">
              <span class="reveal-toggle ${actuallyLocked ? 'hidden' : 'revealed'}" data-ev-lock data-ev-id="${escapeHtml(e.id)}">
                ${actuallyLocked ? '🔒 LOCKED (hidden from players)' : '🔓 UNLOCKED (players can see)'}
              </span>
            </div>
          ` : ''}
        </div>
      </article>
    `;
  }

  function wire(container) {
    // Open terminal full-screen in a new tab
    container.querySelectorAll('[data-ev-open]').forEach(btn => {
      btn.addEventListener('click', e => {
        e.stopPropagation();
        const id = btn.getAttribute('data-ev-id');
        const item = Data.getCampaign().evidence.find(x => x.id === id);
        if (!item) return;
        window.open('evidence/' + item.file, '_blank');
      });
    });

    // Also allow clicking the whole card (if unlocked) to open
    container.querySelectorAll('.evidence-card').forEach(card => {
      card.addEventListener('click', e => {
        if (e.target.closest('[data-ev-lock]')) return;
        if (e.target.closest('[data-ev-open]')) return;
        const id = card.getAttribute('data-ev-id');
        const item = Data.getCampaign().evidence.find(x => x.id === id);
        if (!item || Data.isLocked(item)) return;
        window.open('evidence/' + item.file, '_blank');
      });
    });

    // Lock/unlock toggle (admin)
    container.querySelectorAll('[data-ev-lock]').forEach(el => {
      el.addEventListener('click', async e => {
        e.stopPropagation();
        const id = el.getAttribute('data-ev-id');
        const item = Data.getCampaign().evidence.find(x => x.id === id);
        if (!item) return;
        item.locked = !item.locked;
        try {
          Toast.show('Saving…');
          await Data.pushCampaign();
          Toast.show(item.locked ? 'File sealed' : 'File recovered');
          App.rerender();
        } catch (err) { Toast.show('Save failed: ' + err.message, true); }
      });
    });
  }

  return { render, wire };
})();
