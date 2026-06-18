/**
 * Maps view — grid of map cards with full-image detail.
 */
const MapsView = (() => {

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
    const maps = c.maps || [];

    return `
      <div class="maps-view wrap">
        <section class="npc-hero">
          <div class="eyebrow">▸ Tactical Cartography // Meridian Spire</div>
          <h1>MAP INDEX</h1>
          <p>Ten sectors and the citywide overlay. Tap a sector for the full tactical map and intelligence brief.</p>
        </section>

        <div class="maps-grid">
          ${maps.map(renderMapCard).join('')}
        </div>
      </div>
    `;
  }

  function renderMapCard(m) {
    const accent = m.accent || '#9bd4ff';
    const desc = m.fields?.Description?.value || '';
    return `
      <article class="map-card" data-map-id="${escapeHtml(m.id)}" style="--accent:${accent}">
        <div class="map-thumb" style="background-image:url('maps/${encodeURIComponent(m.file)}')"></div>
        <div class="map-body">
          <span class="map-num">${escapeHtml(m.id)}</span>
          <h3>${escapeHtml(m.title)}</h3>
          <div class="map-sub">${escapeHtml(m.subtitle || '')}</div>
          <p class="map-desc">${escapeHtml(desc.substring(0, 140))}${desc.length > 140 ? '…' : ''}</p>
          <div class="map-stats">
            ${['Population', 'Scale', 'Elevation', 'Connections'].map(key => {
              const f = m.fields?.[key];
              if (!f) return '';
              return `<div class="map-stat"><b>${key}</b>${escapeHtml(f.value)}</div>`;
            }).join('')}
          </div>
        </div>
      </article>
    `;
  }

  function openMapDetail(id) {
    const m = Data.getCampaign().maps.find(x => x.id === id);
    if (!m) return;
    const accent = m.accent || '#9bd4ff';

    const html = `
      <div class="modal-backdrop" data-close-modal>
        <div class="modal" style="max-width:1100px" onclick="event.stopPropagation()">
          <div class="eyebrow" style="color:${accent};font-family:var(--mono);font-size:11px;letter-spacing:0.2em;text-transform:uppercase;margin-bottom:8px">
            ▸ ${escapeHtml(m.id).toUpperCase()}
          </div>
          <h2 style="color:${accent}">${escapeHtml(m.title)}</h2>
          <div style="font-family:var(--mono);color:var(--muted);font-size:12px;letter-spacing:0.14em;margin-bottom:18px">
            ${escapeHtml(m.subtitle || '')}
          </div>
          <div class="map-detail">
            <img src="maps/${encodeURIComponent(m.file)}" alt="${escapeHtml(m.title)}">
          </div>
          <div class="map-stats" style="margin-top:18px;grid-template-columns:repeat(2,1fr)">
            ${['Population', 'Scale', 'Elevation', 'Connections'].map(key => {
              const f = m.fields?.[key];
              if (!f) return '';
              return `<div class="map-stat"><b>${key}</b>${escapeHtml(f.value)}</div>`;
            }).join('')}
          </div>
          ${m.fields?.Description ? `
            <div class="field wide" style="margin-top:18px">
              <b>Description</b>
              <p>${escapeHtml(m.fields.Description.value)}</p>
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
  }

  function wire(container) {
    container.querySelectorAll('.map-card').forEach(card => {
      card.addEventListener('click', () => {
        const id = card.getAttribute('data-map-id');
        openMapDetail(id);
      });
    });
  }

  return { render, wire };
})();
