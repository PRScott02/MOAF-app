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
            ${factions.map(f => {
              const isAdmin = Data.canEditCampaign();
              const actuallyLocked = f.locked === true;
              const showRedacted = actuallyLocked && !isAdmin;
              return `
              <div class="matrix-card ${showRedacted ? 'locked-card' : ''}${actuallyLocked && isAdmin ? ' admin-locked' : ''}" style="--primary:${f.primary};--accent:${f.accent}" data-faction-jump="${f.id}">
                <div class="matrix-emblem"><span class="letter">${showRedacted ? '🔒' : escapeHtml(f.letter || '?')}</span></div>
                <div>
                  ${showRedacted ? `
                    <span class="matrix-kicker" style="color:var(--red)">▸ CLASSIFIED // NOT YET DISCOVERED</span>
                    <h3>[ REDACTED ]</h3>
                    <p class="matrix-sub">This faction's records are sealed. Intelligence will unlock as your investigation progresses.</p>
                  ` : `
                    <span class="matrix-kicker">${escapeHtml(f.kicker || '')}</span>
                    <h3>${escapeHtml(f.title)}</h3>
                    <p class="matrix-sub">${escapeHtml(f.subtitle || '')}</p>
                    <span class="count">${npcCount[f.id] || 0} NPC Dossiers</span>
                  `}
                  ${isAdmin ? `
                    <div style="margin-top:10px">
                      <span class="reveal-toggle ${actuallyLocked ? 'hidden' : 'revealed'}"
                            data-faction-lock-toggle data-faction-id="${f.id}"
                            onclick="event.stopPropagation()">
                        ${actuallyLocked ? '🔒 LOCKED' : '🔓 UNLOCKED'}
                      </span>
                    </div>
                  ` : ''}
                </div>
              </div>
            `;}).join('')}
          </div>
        </section>

        ${factions.map(f => renderFactionSection(f, c.npcs || [])).join('')}
      </div>
    `;
  }

  function renderFactionSection(f, allNpcs) {
    const locked = Data.isLocked(f);

    if (locked) {
      // Locked placeholder — players see the faction exists but no details
      return `
        <section class="faction-section" id="faction-${f.id}" style="--primary:${f.primary};--accent:${f.accent}">
          <div class="section-header">
            <div>
              <div class="kicker" style="color:var(--red)">▸ CLASSIFIED // ACCESS RESTRICTED</div>
              <h2>[ REDACTED FACTION ]</h2>
              <p class="section-sub">This dossier is sealed. Records will become available as your investigation uncovers this faction.</p>
            </div>
            <div class="section-emblem"><span class="letter">🔒</span></div>
          </div>
          <div class="panel">
            <div class="field-redacted">
              <b>Classified Intelligence</b>
              <p>[ CLEARANCE INSUFFICIENT // FACTION NOT YET DISCOVERED ]</p>
            </div>
          </div>
        </section>
      `;
    }

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

        ${renderOverviewPanel(f)}
        ${renderStructurePanel(f)}

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

  function renderOverviewPanel(f) {
    const keys = Object.keys(f.fields || {});
    if (keys.length === 0) return '';
    return `
      <div class="panel">
        <div class="panel-title-row"><h3>Faction Overview</h3></div>
        <div class="twocol">
          ${keys.map(key => renderFactionField(f, key)).join('')}
        </div>
      </div>
    `;
  }

  function renderFactionField(f, key) {
    const field = f.fields[key];
    const wide = (key === 'Territory' || key === 'Player-Safe Read');
    const cls = 'field' + (wide ? ' wide' : '');
    if (Data.isFieldVisible(field)) {
      return `
        <div class="${cls}">
          <b>${escapeHtml(key)}</b>
          <p>${escapeHtml(field.value)}</p>
          ${Data.canEditCampaign() ? `
            <div style="margin-top:10px;display:flex;gap:6px;justify-content:flex-end">
              <span class="reveal-toggle ${field.revealed ? 'revealed' : 'hidden'}"
                    data-faction-toggle data-faction-id="${f.id}" data-field-key="${escapeHtml(key)}">
                ${field.revealed ? '◉ REVEALED' : '◌ HIDDEN'}
              </span>
              <button class="edit-btn" data-faction-edit
                      data-faction-id="${f.id}" data-field-key="${escapeHtml(key)}">EDIT</button>
            </div>
          ` : ''}
        </div>
      `;
    }
    return `
      <div class="field-redacted ${wide ? 'wide' : ''}" style="${wide ? 'grid-column:1/-1' : ''}">
        <b>${escapeHtml(key)}</b>
        <p>[ REDACTED // CLEARANCE INSUFFICIENT ]</p>
        ${Data.canEditCampaign() ? `
          <div style="margin-top:10px;display:flex;justify-content:flex-end;position:relative;z-index:1">
            <span class="reveal-toggle hidden"
                  data-faction-toggle data-faction-id="${f.id}" data-field-key="${escapeHtml(key)}">◌ HIDDEN</span>
          </div>
        ` : ''}
      </div>
    `;
  }

  function renderStructurePanel(f) {
    if (!f.structure || f.structure.length === 0) return '';
    const visibleNodes = f.structure.filter(n => Data.canEditCampaign() || n.revealed !== false);
    if (visibleNodes.length === 0) return '';
    return `
      <div class="panel">
        <div class="panel-title-row"><h3>Internal Structure</h3></div>
        <ul class="list">
          ${f.structure.map((node, idx) => {
            const visible = Data.canEditCampaign() || node.revealed !== false;
            if (!visible) return '';
            return `
              <li>
                <strong style="color:var(--primary);font-family:var(--mono);font-size:12px;text-transform:uppercase;letter-spacing:0.1em;display:block;margin-bottom:4px">${escapeHtml(node.label)}</strong>
                ${escapeHtml(node.value)}
                ${Data.canEditCampaign() ? `
                  <span class="reveal-toggle ${node.revealed !== false ? 'revealed' : 'hidden'}"
                        style="margin-left:8px;font-size:9px"
                        data-faction-struct-toggle data-faction-id="${f.id}" data-node-idx="${idx}">
                    ${node.revealed !== false ? '◉' : '◌'}
                  </span>
                ` : ''}
              </li>
            `;
          }).join('')}
        </ul>
      </div>
    `;
  }

  function renderFactionNpc(f, n, idx) {
    const isAdmin = Data.canEditCampaign();
    const visible = isAdmin || n.revealed !== false;
    if (!visible) {
      return `
        <div class="npc-card-mini locked-card">
          <h4 style="color:var(--red)">🔒 [ SEALED DOSSIER ]</h4>
          <div class="dossier-role" style="color:var(--red)">Identity Not Yet Discovered</div>
          <div class="dossier-field redacted">
            <b>Classified Personnel</b>
            <span>This individual has not yet been identified in your investigation.</span>
          </div>
        </div>`;
    }

    // Build clean structured content from parsedFields (falls back to body blob).
    let inner = '';
    if (n.parsedFields && Object.keys(n.parsedFields).length) {
      const blocks = [];
      for (const [label, field] of Object.entries(n.parsedFields)) {
        const showField = isAdmin || field.gm !== true;
        if (showField) {
          blocks.push(`
            <div class="dossier-field">
              <b>${escapeHtml(label)}</b>
              <span>${escapeHtml(field.value)}</span>
            </div>
          `);
        } else {
          blocks.push(`
            <div class="dossier-field redacted">
              <b>${escapeHtml(label)}</b>
              <span>[ REDACTED // CLEARANCE INSUFFICIENT ]</span>
            </div>
          `);
        }
      }
      inner = blocks.join('');
    } else if (n.body) {
      // Fallback: show the raw body but strip the stray "Open full dossier" line.
      const cleaned = n.body.split('\n').filter(l => l.trim() && l.trim() !== 'Open full dossier').join('\n');
      inner = `<p>${escapeHtml(cleaned)}</p>`;
    }

    return `
      <div class="npc-card-mini">
        ${isAdmin ? `
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
        ${n.role ? `<div class="dossier-role">${escapeHtml(n.role)}</div>` : ''}
        ${n.quote ? `<div class="dossier-quote">"${escapeHtml(n.quote)}"</div>` : ''}
        ${inner}
      </div>
    `;
  }

  // Click handler wiring
  function wire(container) {
    // Faction lock/unlock toggle
    container.querySelectorAll('[data-faction-lock-toggle]').forEach(el => {
      el.addEventListener('click', async (ev) => {
        ev.stopPropagation();
        const fid = el.getAttribute('data-faction-id');
        const f = Data.getCampaign().factions.find(x => x.id === fid);
        if (!f) return;
        f.locked = !f.locked;
        await pushAndRefresh(f.locked ? 'Faction locked' : 'Faction unlocked');
      });
    });

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

    // Toggle structure node reveal
    container.querySelectorAll('[data-faction-struct-toggle]').forEach(el => {
      el.addEventListener('click', async () => {
        const fid = el.getAttribute('data-faction-id');
        const idx = parseInt(el.getAttribute('data-node-idx'), 10);
        const f = Data.getCampaign().factions.find(x => x.id === fid);
        if (!f || !f.structure[idx]) return;
        f.structure[idx].revealed = !(f.structure[idx].revealed !== false);
        await pushAndRefresh('Updated structure visibility');
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
