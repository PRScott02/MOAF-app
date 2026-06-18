/**
 * Admin & note-taker PIN dialogs, token management, generic editors.
 */
const AdminPanel = (() => {

  function escapeHtml(s) {
    if (s == null) return '';
    return String(s)
      .replaceAll('&', '&amp;')
      .replaceAll('<', '&lt;')
      .replaceAll('>', '&gt;')
      .replaceAll('"', '&quot;')
      .replaceAll("'", '&#39;');
  }

  function showModal(htmlString) {
    const host = document.createElement('div');
    host.innerHTML = htmlString;
    document.body.appendChild(host.firstElementChild);
    const backdrop = document.querySelector('.modal-backdrop:last-of-type');
    backdrop.querySelectorAll('[data-close-modal]').forEach(el =>
      el.addEventListener('click', () => backdrop.remove()));
    return backdrop;
  }

  /** Open the admin login dialog. */
  function openAdminLogin() {
    if (Data.getMode() === 'admin') {
      // Already in admin mode → log out
      Data.setMode('player');
      Toast.show('Admin mode closed');
      App.rerender();
      return;
    }
    const backdrop = showModal(`
      <div class="modal-backdrop" data-close-modal>
        <div class="modal" style="max-width:480px" onclick="event.stopPropagation()">
          <h2>Admin Authorization</h2>
          <div class="modal-row">
            <label>Admin PIN</label>
            <input type="password" id="admin-pin" autofocus>
          </div>
          <div class="modal-actions">
            <button class="btn" data-close-modal>Cancel</button>
            <button class="btn primary" id="admin-pin-ok">UNLOCK</button>
          </div>
        </div>
      </div>
    `);
    const pinInput = backdrop.querySelector('#admin-pin');
    const submit = () => {
      const v = pinInput.value;
      if (v === Data.getAdminPin()) {
        Data.setMode('admin');
        Toast.show('Admin mode enabled');
        backdrop.remove();
        App.rerender();
        if (!GitHub.getAdminToken()) {
          setTimeout(openAdminTokenSetup, 200);
        }
      } else {
        Toast.show('Incorrect PIN', true);
      }
    };
    backdrop.querySelector('#admin-pin-ok').addEventListener('click', submit);
    pinInput.addEventListener('keydown', e => { if (e.key === 'Enter') submit(); });
  }

  /** Prompt for the admin's GitHub Personal Access Token. */
  function openAdminTokenSetup() {
    const current = GitHub.getAdminToken();
    const backdrop = showModal(`
      <div class="modal-backdrop" data-close-modal>
        <div class="modal" onclick="event.stopPropagation()">
          <h2>GitHub Token Setup</h2>
          <p style="font-size:13px;color:var(--muted);line-height:1.6">
            To save changes back to GitHub, this device needs a Personal Access Token.<br><br>
            Generate one at <code>https://github.com/settings/tokens?type=beta</code>
            with <strong>Contents: Read & write</strong> access to repo
            <strong>${GitHub.REPO.owner}/${GitHub.REPO.repo}</strong>.<br><br>
            The token is stored only in this browser's local storage. It never leaves this device.
          </p>
          <div class="modal-row">
            <label>Token (starts with github_pat_…)</label>
            <input type="password" id="admin-token" value="${escapeHtml(current)}">
          </div>
          <div class="modal-actions">
            <button class="btn" data-close-modal>Skip</button>
            <button class="btn" id="admin-token-test">TEST</button>
            <button class="btn primary" id="admin-token-save">SAVE</button>
          </div>
        </div>
      </div>
    `);
    backdrop.querySelector('#admin-token-test').addEventListener('click', async () => {
      const t = backdrop.querySelector('#admin-token').value.trim();
      const ok = await GitHub.testToken(t);
      Toast.show(ok ? 'Token works' : 'Token failed', !ok);
    });
    backdrop.querySelector('#admin-token-save').addEventListener('click', () => {
      GitHub.setAdminToken(backdrop.querySelector('#admin-token').value.trim());
      Toast.show('Token saved');
      backdrop.remove();
    });
  }

  /** Open the note-taker login dialog. */
  function openNoteTakerLogin() {
    if (Data.getMode() === 'notetaker') {
      Data.setMode('player');
      Toast.show('Note-taker mode closed');
      App.rerender();
      return;
    }
    const backdrop = showModal(`
      <div class="modal-backdrop" data-close-modal>
        <div class="modal" style="max-width:480px" onclick="event.stopPropagation()">
          <h2>Note-Taker Authorization</h2>
          <div class="modal-row">
            <label>Note-Taker PIN</label>
            <input type="password" id="nt-pin" autofocus>
          </div>
          <div class="modal-actions">
            <button class="btn" data-close-modal>Cancel</button>
            <button class="btn primary" id="nt-pin-ok">UNLOCK</button>
          </div>
        </div>
      </div>
    `);
    const pinInput = backdrop.querySelector('#nt-pin');
    const submit = () => {
      if (pinInput.value === Data.getNotePin()) {
        Data.setMode('notetaker');
        Toast.show('Note-taker mode enabled');
        backdrop.remove();
        App.rerender();
        if (!GitHub.getNoteTakerToken()) {
          setTimeout(openNoteTakerTokenSetup, 200);
        }
      } else {
        Toast.show('Incorrect PIN', true);
      }
    };
    backdrop.querySelector('#nt-pin-ok').addEventListener('click', submit);
    pinInput.addEventListener('keydown', e => { if (e.key === 'Enter') submit(); });
  }

  function openNoteTakerTokenSetup() {
    const current = GitHub.getNoteTakerToken();
    const backdrop = showModal(`
      <div class="modal-backdrop" data-close-modal>
        <div class="modal" onclick="event.stopPropagation()">
          <h2>Note-Taker GitHub Token</h2>
          <p style="font-size:13px;color:var(--muted);line-height:1.6">
            To push session notes to GitHub, this device needs a Personal Access Token.<br><br>
            Generate one at <code>https://github.com/settings/tokens?type=beta</code>
            with <strong>Contents: Read & write</strong> on repo
            <strong>${GitHub.REPO.owner}/${GitHub.REPO.repo}</strong>.<br><br>
            (You must be added as a collaborator on the repo first.)
          </p>
          <div class="modal-row">
            <label>Token</label>
            <input type="password" id="nt-token" value="${escapeHtml(current)}">
          </div>
          <div class="modal-actions">
            <button class="btn" data-close-modal>Skip</button>
            <button class="btn" id="nt-token-test">TEST</button>
            <button class="btn primary" id="nt-token-save">SAVE</button>
          </div>
        </div>
      </div>
    `);
    backdrop.querySelector('#nt-token-test').addEventListener('click', async () => {
      const t = backdrop.querySelector('#nt-token').value.trim();
      const ok = await GitHub.testToken(t);
      Toast.show(ok ? 'Token works' : 'Token failed', !ok);
    });
    backdrop.querySelector('#nt-token-save').addEventListener('click', () => {
      GitHub.setNoteTakerToken(backdrop.querySelector('#nt-token').value.trim());
      Toast.show('Token saved');
      backdrop.remove();
    });
  }

  /** Generic single-text-area editor. */
  function openTextEditor({ title, value, onSave }) {
    const backdrop = showModal(`
      <div class="modal-backdrop" data-close-modal>
        <div class="modal" onclick="event.stopPropagation()">
          <h2>${escapeHtml(title)}</h2>
          <div class="modal-row">
            <label>Content</label>
            <textarea id="te-body" rows="14">${escapeHtml(value || '')}</textarea>
          </div>
          <div class="modal-actions">
            <button class="btn" data-close-modal>Cancel</button>
            <button class="btn primary" id="te-save">SAVE</button>
          </div>
        </div>
      </div>
    `);
    backdrop.querySelector('#te-save').addEventListener('click', async () => {
      const v = backdrop.querySelector('#te-body').value;
      backdrop.remove();
      await onSave(v);
    });
  }

  /** Open the full NPC editor with all fields + reveal toggles. */
  function openNpcEditor(n) {
    const fields = Object.keys(n.fields || {});
    const backdrop = showModal(`
      <div class="modal-backdrop" data-close-modal>
        <div class="modal" style="max-width:820px" onclick="event.stopPropagation()">
          <h2>Edit NPC: ${escapeHtml(n.name)}</h2>

          <div class="modal-row">
            <label>Name</label>
            <input type="text" id="npc-name" value="${escapeHtml(n.name)}">
          </div>
          <div class="modal-row">
            <label>Role</label>
            <input type="text" id="npc-role" value="${escapeHtml(n.role || '')}">
          </div>
          <div class="modal-row">
            <label>Faction</label>
            <input type="text" id="npc-faction" value="${escapeHtml(n.faction || '')}">
          </div>
          <div class="modal-row">
            <label>Section</label>
            <input type="text" id="npc-section" value="${escapeHtml(n.section || '')}">
          </div>

          <div class="modal-row" style="flex-direction:row;align-items:center;gap:10px">
            <label style="display:flex;align-items:center;gap:8px;cursor:pointer">
              <input type="checkbox" id="npc-locked" ${n.locked ? 'checked' : ''}>
              <span>LOCKED (hidden from players as a redacted card)</span>
            </label>
          </div>

          <h2 style="margin-top:20px">Fields</h2>
          ${fields.map(key => `
            <div class="modal-row" data-field-block data-key="${escapeHtml(key)}">
              <label style="display:flex;align-items:center;gap:10px">
                <span>${escapeHtml(key)}</span>
                <label style="display:inline-flex;align-items:center;gap:4px;font-size:10px;color:${n.fields[key].revealed ? 'var(--green)' : 'var(--red)'}">
                  <input type="checkbox" data-reveal-cb data-key="${escapeHtml(key)}" ${n.fields[key].revealed ? 'checked' : ''}>
                  REVEAL TO PLAYERS
                </label>
              </label>
              <textarea data-field-value data-key="${escapeHtml(key)}" rows="3">${escapeHtml(n.fields[key].value || '')}</textarea>
            </div>
          `).join('')}

          <div class="modal-actions">
            <button class="btn" data-close-modal>Cancel</button>
            <button class="btn primary" id="npc-save">SAVE & PUSH</button>
          </div>
        </div>
      </div>
    `);

    backdrop.querySelector('#npc-save').addEventListener('click', async () => {
      n.name    = backdrop.querySelector('#npc-name').value.trim() || n.name;
      n.role    = backdrop.querySelector('#npc-role').value.trim();
      n.faction = backdrop.querySelector('#npc-faction').value.trim();
      n.section = backdrop.querySelector('#npc-section').value.trim();
      n.locked  = backdrop.querySelector('#npc-locked').checked;
      backdrop.querySelectorAll('[data-field-value]').forEach(ta => {
        const key = ta.getAttribute('data-key');
        if (n.fields[key]) n.fields[key].value = ta.value;
      });
      backdrop.querySelectorAll('[data-reveal-cb]').forEach(cb => {
        const key = cb.getAttribute('data-key');
        if (n.fields[key]) n.fields[key].revealed = cb.checked;
      });
      try {
        Toast.show('Saving…');
        await Data.pushCampaign();
        Toast.show('NPC saved');
        backdrop.remove();
        App.rerender();
      } catch (e) { Toast.show('Save failed: ' + e.message, true); }
    });
  }

  /** Settings dialog — change PINs, manage token. */
  function openSettings() {
    const backdrop = showModal(`
      <div class="modal-backdrop" data-close-modal>
        <div class="modal" onclick="event.stopPropagation()">
          <h2>Settings</h2>

          <div class="modal-row">
            <label>Admin PIN (this device)</label>
            <input type="text" id="set-admin-pin" value="${escapeHtml(Data.getAdminPin())}">
          </div>
          <div class="modal-row">
            <label>Note-Taker PIN (this device)</label>
            <input type="text" id="set-note-pin" value="${escapeHtml(Data.getNotePin())}">
          </div>
          <p style="font-size:12px;color:var(--muted)">
            PINs are stored per-device. Share these PINs with players who need access.
          </p>

          <h2 style="margin-top:20px">GitHub Token</h2>
          <div class="modal-row">
            <label>Your token</label>
            <input type="password" id="set-token" value="${escapeHtml(GitHub.getAdminToken())}">
          </div>

          <div class="modal-actions">
            <button class="btn" data-close-modal>Close</button>
            <button class="btn primary" id="set-save">SAVE</button>
          </div>
        </div>
      </div>
    `);
    backdrop.querySelector('#set-save').addEventListener('click', () => {
      Data.setAdminPin(backdrop.querySelector('#set-admin-pin').value || '2089');
      Data.setNotePin(backdrop.querySelector('#set-note-pin').value || '0451');
      GitHub.setAdminToken(backdrop.querySelector('#set-token').value.trim());
      Toast.show('Settings saved');
      backdrop.remove();
    });
  }

  return {
    openAdminLogin, openNoteTakerLogin,
    openAdminTokenSetup, openNoteTakerTokenSetup,
    openTextEditor, openNpcEditor, openSettings
  };
})();
