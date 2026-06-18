/**
 * Main app — wires tabs, renders the active view, handles top-level events.
 */
const App = (() => {

  let currentTab = 'factions';

  const TABS = [
    { id: 'factions', label: 'Factions', icon: '◈' },
    { id: 'npcs',     label: 'NPCs',     icon: '◎' },
    { id: 'maps',     label: 'Maps',     icon: '⌖' },
    { id: 'notes',    label: 'Notes',    icon: '▤' }
  ];

  function rerender() {
    renderSidebar();
    renderMain();
    renderAdminBar();
  }

  function renderSidebar() {
    const tabs = document.getElementById('tabs');
    tabs.innerHTML = TABS.map(t => `
      <button class="tab ${t.id === currentTab ? 'active' : ''}" data-tab="${t.id}">
        <span class="tab-icon">${t.icon}</span>
        <span>${t.label}</span>
      </button>
    `).join('');
    tabs.querySelectorAll('.tab').forEach(btn => {
      btn.addEventListener('click', () => {
        currentTab = btn.getAttribute('data-tab');
        rerender();
      });
    });

    const mode = Data.getMode();
    document.getElementById('btn-admin').classList.toggle('active', mode === 'admin');
    document.getElementById('btn-notetaker').classList.toggle('active', mode === 'notetaker');
    document.getElementById('btn-admin').textContent = mode === 'admin' ? '▸ Exit Admin Mode' : '▸ Admin Login';
    document.getElementById('btn-notetaker').textContent = mode === 'notetaker' ? '▸ Exit Note-Taker' : '▸ Note-Taker Login';
  }

  function renderMain() {
    const main = document.getElementById('main');
    let html = '';
    let view = null;
    switch (currentTab) {
      case 'factions': view = FactionsView; break;
      case 'npcs':     view = NpcsView;     break;
      case 'maps':     view = MapsView;     break;
      case 'notes':    view = NotesView;    break;
    }
    if (view) {
      html = view.render();
      main.innerHTML = html;
      view.wire(main);
    }
  }

  function renderAdminBar() {
    let bar = document.querySelector('.admin-bar');
    const mode = Data.getMode();
    if (mode === 'admin' || mode === 'notetaker') {
      if (!bar) {
        bar = document.createElement('div');
        bar.className = 'admin-bar';
        document.body.appendChild(bar);
      }
      const left = mode === 'admin'
        ? '◆ ADMIN MODE // FIELDS CAN BE EDITED // CLEARANCE TOGGLED'
        : '◆ NOTE-TAKER MODE // SESSION NOTES EDITABLE';
      bar.innerHTML = `
        <span class="left">${left}</span>
        <span class="right">
          <button class="btn" id="ab-settings">SETTINGS</button>
          <button class="btn" id="ab-exit">EXIT</button>
        </span>
      `;
      bar.querySelector('#ab-settings').addEventListener('click', AdminPanel.openSettings);
      bar.querySelector('#ab-exit').addEventListener('click', () => {
        Data.setMode('player');
        Toast.show('Returned to player mode');
        rerender();
      });
      // Add top padding to main so admin bar doesn't cover content
      document.getElementById('main').style.paddingTop = '50px';
    } else {
      if (bar) bar.remove();
      document.getElementById('main').style.paddingTop = '0';
    }
  }

  async function init() {
    // Sidebar button handlers
    document.getElementById('btn-admin').addEventListener('click', AdminPanel.openAdminLogin);
    document.getElementById('btn-notetaker').addEventListener('click', AdminPanel.openNoteTakerLogin);
    document.getElementById('btn-refresh').addEventListener('click', async () => {
      Toast.show('Syncing…');
      await Data.refresh();
      Toast.show('Synced');
      rerender();
    });

    try {
      await Data.load();
      rerender();
    } catch (e) {
      console.error(e);
      document.getElementById('main').innerHTML =
        `<div class="loading">Failed to load campaign data: ${e.message}</div>`;
    }

    // Register service worker for offline PWA support
    if ('serviceWorker' in navigator) {
      navigator.serviceWorker.register('sw.js').catch(err => console.warn('SW failed', err));
    }
  }

  return { init, rerender };
})();

/* Tiny toast helper, used everywhere */
const Toast = (() => {
  function show(message, isError = false) {
    const host = document.getElementById('toast-host');
    if (!host) return;
    const el = document.createElement('div');
    el.className = 'toast' + (isError ? ' error' : '');
    el.textContent = message;
    host.appendChild(el);
    setTimeout(() => {
      el.style.transition = 'opacity 0.3s';
      el.style.opacity = '0';
      setTimeout(() => el.remove(), 300);
    }, 2400);
  }
  return { show };
})();

window.addEventListener('DOMContentLoaded', App.init);
