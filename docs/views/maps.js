/**
 * Factions view — renders the matrix overview + each faction section.
 */
const FactionsView = (() => {

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
    const factions = c.factions || [];

    // Count NPCs per faction (from npc list, matching by faction tag)
    const npcCount = {};
    const factionTagMap = {
      bci: 'BCI', cotu: 'CotU', truth: 'Truth Division',
      redarchive: 'Red Archive', continuance: 'Continuance',
      greymarket: 'Grey Market', civic: 'Politics'
    };
    for (const npc of (c.npcs || [])) {
      const tag = npc.faction;
      const fid = Object.entries(factionTagMap).find(([_, v]) => v === tag)?.[0];
      if (fid) npcCount[fid] = (npcCount[fid] || 0) + 1;
    }

    return `
      <div class="faction-view wrap">
        <section class="hero">
          <div class="hero-grid">
            <div>
              <div class="kicker">▸ Unified Faction Intelligence Database</div>
              <h1>FACTION <span>MATRIX</span></h1>
              <p class="subtitle">A working dossier on the seven power blocs operating in Meridian Spire's licensed intelligence sector. Click any faction to open the full report.</p>
              <div class="meta-row">
                <span class="pill">${factions.length} factions</span>
                <span class="pill">${c.npcs?.length || 0} NPCs</span>
                <span class="pill">Meridian Spire // 2089</span>
              </div>
            </div>
            <div class="hero-banner">
              <img src="banner.png" alt="">
            </div>
          </div>
        </section>

        <section class="matrix-section">
          <h2>▸ Faction Matrix</h2>
          <div class="matrix-grid">
            ${factions.map(f => `
              <div class="matrix-card" style="--primary:${f.primary};--accent:${f.accent}" data-faction-jump="${f.id}">
                <div class="matrix-emblem"><span class="letter">${escapeHtml(f.letter || '?')}</span></div>
                <div>
                  <span class="matrix-kicker">${escapeHtml(f.kicker || '')}</span>
                  <h3>${escapeHtml(f.title)}</h3>
                  <p class="matrix-sub">${escapeHtml(f.subtitle || '')}</p>
                  <span class="count">${npcCount[f.id] || 0} NPC Dossiers</span>
                </div>
              </div>
            `).join('')}
          </div>
        </section>

        ${factions.map(f => renderFactionSection(f, c.npcs || [])).join('')}
      </div>
    `;
  }

  function renderFactionSection(f, allNpcs) {
    const factionTagMap = {
      bci: 'BCI', cotu: 'CotU', truth: 'Truth Division',
      redarchive: 'Red Archive', continuance: 'Continuance',
      greymarket: 'Grey Market', civic: 'Politics'
    };
    const tag = factionTagMap[f.id];
    const npcs = tag ? allNpcs.filter(n => n.faction === tag) : [];

    return `
      <section class="faction-section" id="faction-${f.id}" style="--primary:${f.primary};--accent:${f.accent}">
        <div class="section-header">
          <div>
            <div class="kicker">▸ ${escapeHtml(f.kicker || '')}</div>
            <h2>${escapeHtml(f.title)}</h2>
            <p class="section-sub">${escapeHtml(f.subtitle || '')}</p>
          </div>
          <div class="section-emblem"><span class="letter">${escapeHtml(f.letter || '?')}</span></div>
        </div>

        ${renderFactionFields(f)}

        ${(f.npcs && f.npcs.length) ? `
          <div class="panel">
            <div class="panel-title-row">
              <h3>Key NPC Dossiers</h3>
              <span class="pill">${f.npcs.length}</span>
            </div>
            <div class="npc-grid">
              ${f.npcs.map((n, i) => renderFactionNpc(f, n, i)).join('')}
            </div>
          </div>
        ` : ''}
      </section>
    `;
  }

  function renderFactionFields(f) {
    const order = ['Faction Overview', 'Internal Structure', 'Session Hooks', 'Faction Secrets'];
    const blocks = [];
    for (const key of order) {
      const field = f.fields?.[key];
      if (!field) continue;
      if (Data.isFieldVisible(field)) {
        blocks.push(`
          <div class="panel">
            <div class="panel-title-row">
              <h3>${escapeHtml(key)}</h3>
              ${Data.canEditCampaign() ? `
                <div>
                  <span class="reveal-toggle ${field.revealed ? 'revealed' : 'hidden'}"
                        data-faction-toggle data-faction-id="${f.id}" data-field-key="${escapeHtml(key)}">
                    ${field.revealed ? '◉ REVEALED' : '◌ HIDDEN'}
                  </span>
                  <button class="edit-btn" data-faction-edit
                          data-faction-id="${f.id}" data-field-key="${escapeHtml(key)}">EDIT</button>
                </div>
              ` : ''}
            </div>
            <div class="field wide">
              <b>Content</b>
              <p>${escapeHtml(field.value)}</p>
            </div>
          </div>
        `);
      } else {
        blocks.push(`
          <div class="panel">
            <div class="panel-title-row">
              <h3>${escapeHtml(key)}</h3>
              ${Data.canEditCampaign() ? `
                <span class="reveal-toggle hidden"
                      data-faction-toggle data-faction-id="${f.id}" data-field-key="${escapeHtml(key)}">
                  ◌ HIDDEN
                </span>
              ` : ''}
            </div>
            <div class="field-redacted">
              <b>${escapeHtml(key)}</b>
              <p>[ REDACTED // CLEARANCE INSUFFICIENT ]</p>
            </div>
          </div>
        `);
      }
    }
    return blocks.join('');
  }

  function renderFactionNpc(f, n, idx) {
    const visible = Data.canEditCampaign() || n.revealed !== false;
    if (!visible) {
      return `
        <div class="npc-card-mini">
          <div class="field-redacted">
            <b>NPC Dossier ${idx + 1}</b>
            <p>[ REDACTED ]</p>
          </div>
        </div>`;
    }
    return `
      <div class="npc-card-mini">
        ${Data.canEditCampaign() ? `
          <div style="display:flex;justify-content:flex-end;gap:6px;margin-bottom:8px">
            <span class="reveal-toggle ${n.revealed === false ? 'hidden' : 'revealed'}"
                  data-faction-npc-toggle data-faction-id="${f.id}" data-npc-idx="${idx}">
              ${n.revealed === false ? '◌ HIDDEN' : '◉ REVEALED'}
            </span>
            <button class="edit-btn" data-faction-npc-edit
                    data-faction-id="${f.id}" data-npc-idx="${idx}">EDIT</button>
          </div>
        ` : ''}
        <h4>${escapeHtml(n.name)}</h4>
        <p>${escapeHtml(n.body)}</p>
      </div>
    `;
  }

  // Click handler wiring
  function wire(container) {
    // Matrix → jump to faction
    container.querySelectorAll('[data-faction-jump]').forEach(el => {
      el.addEventListener('click', () => {
        const id = el.getAttribute('data-faction-jump');
        const target = document.getElementById('faction-' + id);
        if (target) target.scrollIntoView({ behavior: 'smooth', block: 'start' });
      });
    });

    // Toggle reveal flag
    container.querySelectorAll('[data-faction-toggle]').forEach(el => {
      el.addEventListener('click', async () => {
        const fid = el.getAttribute('data-faction-id');
        const key = el.getAttribute('data-field-key');
        const f = Data.getCampaign().factions.find(x => x.id === fid);
        if (!f || !f.fields[key]) return;
        f.fields[key].revealed = !f.fields[key].revealed;
        await pushAndRefresh('Updated field visibility');
      });
    });

    // Edit field
    container.querySelectorAll('[data-faction-edit]').forEach(el => {
      el.addEventListener('click', () => {
        const fid = el.getAttribute('data-faction-id');
        const key = el.getAttribute('data-field-key');
        const f = Data.getCampaign().factions.find(x => x.id === fid);
        if (!f || !f.fields[key]) return;
        AdminPanel.openTextEditor({
          title: `Edit: ${f.title} → ${key}`,
          value: f.fields[key].value,
          onSave: async (v) => {
            f.fields[key].value = v;
            await pushAndRefresh('Updated field content');
          }
        });
      });
    });

    // Toggle NPC reveal
    container.querySelectorAll('[data-faction-npc-toggle]').forEach(el => {
      el.addEventListener('click', async () => {
        const fid = el.getAttribute('data-faction-id');
        const idx = parseInt(el.getAttribute('data-npc-idx'), 10);
        const f = Data.getCampaign().factions.find(x => x.id === fid);
        if (!f || !f.npcs[idx]) return;
        f.npcs[idx].revealed = !(f.npcs[idx].revealed !== false);
        await pushAndRefresh('Updated NPC visibility');
      });
    });

    // Edit NPC body
    container.querySelectorAll('[data-faction-npc-edit]').forEach(el => {
      el.addEventListener('click', () => {
        const fid = el.getAttribute('data-faction-id');
        const idx = parseInt(el.getAttribute('data-npc-idx'), 10);
        const f = Data.getCampaign().factions.find(x => x.id === fid);
        if (!f || !f.npcs[idx]) return;
        AdminPanel.openTextEditor({
          title: `Edit NPC: ${f.npcs[idx].name}`,
          value: f.npcs[idx].body,
          onSave: async (v) => {
            f.npcs[idx].body = v;
            await pushAndRefresh('Updated NPC dossier');
          }
        });
      });
    });
  }

  async function pushAndRefresh(msg) {
    try {
      Toast.show('Saving…');
      await Data.pushCampaign();
      Toast.show(msg);
      App.rerender();
    } catch (e) {
      Toast.show('Save failed: ' + e.message, true);
    }
  }

  return { render, wire };
})();
