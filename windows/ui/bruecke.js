/* Honeys Werkstatt (Windows) – Brücke für Web-Werkzeuge.
 * Stellt window.claude.use('downloads') bereit (wie in Claude-Artefakten) und fängt <a download> mit blob:/data: ab.
 * Gespeichert wird über die Werkstatt in „Downloads\Honeys Werkstatt“. */
(function () {
  if (window.__werkstattBruecke) return;
  window.__werkstattBruecke = true;
  var GEHEIM = (window.__WERKSTATT || {}).geheim;
  if (!GEHEIM) return;

  async function zuBlob(data, mime) {
    if (data instanceof Blob) return data;
    if (data instanceof ArrayBuffer || ArrayBuffer.isView(data)) return new Blob([data], { type: mime || '' });
    if (typeof data === 'string') {
      if (data.slice(0, 5) === 'data:') return await (await fetch(data)).blob();
      return new Blob([data], { type: mime || 'text/plain;charset=utf-8' });
    }
    if (data && typeof data === 'object') return new Blob([JSON.stringify(data)], { type: 'application/json' });
    throw new Error('Unbekanntes Datenformat');
  }

  var hinweisEl = null, hinweisZeit = 0;
  function hinweis(text, pfad) {
    try {
      if (!hinweisEl) {
        hinweisEl = document.createElement('div');
        hinweisEl.setAttribute('role', 'status');
        hinweisEl.style.cssText = 'position:fixed;left:50%;bottom:24px;transform:translateX(-50%);z-index:2147483647;' +
          'background:#131312;color:#EDE7DA;border:1px solid #383733;border-radius:12px;padding:10px 14px;' +
          'font:14px Inter,"Segoe UI",system-ui,sans-serif;display:flex;gap:12px;align-items:center;' +
          'box-shadow:0 6px 24px rgba(0,0,0,.35);max-width:calc(100vw - 32px)';
        document.body.appendChild(hinweisEl);
      }
      hinweisEl.textContent = '';
      var t = document.createElement('span'); t.textContent = text; hinweisEl.appendChild(t);
      if (pfad) {
        var b = document.createElement('button');
        b.textContent = 'Im Ordner zeigen';
        b.style.cssText = 'background:none;border:1px solid #CFC6B0;color:#EDE7DA;border-radius:8px;padding:4px 10px;font:inherit;cursor:pointer';
        b.onclick = function () {
          fetch('/__werkstatt/ordner', { method: 'POST', headers: { 'X-Werkstatt': GEHEIM, 'Content-Type': 'application/json' }, body: JSON.stringify({ pfad: pfad }) });
        };
        hinweisEl.appendChild(b);
      }
      hinweisEl.style.display = 'flex';
      var meine = ++hinweisZeit;
      setTimeout(function () { if (meine === hinweisZeit && hinweisEl) hinweisEl.style.display = 'none'; }, 6000);
    } catch (e) { /* Seite ohne body */ }
  }

  async function speichern(name, data, mime) {
    var blob = await zuBlob(data, mime);
    var r = await fetch('/__werkstatt/speichern', {
      method: 'POST',
      headers: { 'X-Werkstatt': GEHEIM, 'X-Dateiname': encodeURIComponent(String(name || 'datei')), 'Content-Type': blob.type || 'application/octet-stream' },
      body: blob
    });
    var o = {};
    try { o = await r.json(); } catch (e) { }
    if (!r.ok) { var err = new Error(o.fehler || 'Speichern fehlgeschlagen'); err.code = 'failed'; throw err; }
    hinweis('Gespeichert: ' + o.anzeige, o.pfad);
    return o;
  }

  var downloads = {
    save: async function (o) {
      o = o || {};
      var r = await speichern(o.filename || o.name, o.data !== undefined ? o.data : (o.blob || o.content || ''), o.mimeType || o.type);
      return { ok: true, saved: true, path: r.pfad };
    }
  };

  window.claude = window.claude || {};
  var vorher = window.claude.use;
  window.claude.use = async function (cap) {
    if (cap === 'downloads') return downloads;
    if (typeof vorher === 'function') return vorher.apply(this, arguments);
    return null;
  };
  window.werkstatt = { speichern: speichern };

  // Viele Seiten geben Blob-Adressen sofort nach dem Klick frei – kurz aufheben, damit sie noch lesbar sind
  var freigeben = URL.revokeObjectURL.bind(URL);
  URL.revokeObjectURL = function (u) { setTimeout(function () { freigeben(u); }, 60000); };

  async function anker(a) {
    var href = a.href;
    var name = a.getAttribute('download') || decodeURIComponent((href.split('/').pop() || 'datei').split('?')[0]);
    var blob = await (await fetch(href)).blob();
    await speichern(name, blob, blob.type);
  }
  function istDownload(a) { return a && a.hasAttribute && a.hasAttribute('download') && /^(blob|data):/.test(a.href || ''); }
  function melden(e) { hinweis('Speichern fehlgeschlagen: ' + (e && e.message || e)); }

  var klick = HTMLAnchorElement.prototype.click;
  HTMLAnchorElement.prototype.click = function () {
    if (istDownload(this)) { anker(this).catch(melden); return; }
    return klick.call(this);
  };
  document.addEventListener('click', function (ev) {
    var a = ev.target && ev.target.closest ? ev.target.closest('a[download]') : null;
    if (istDownload(a)) { ev.preventDefault(); anker(a).catch(melden); }
  }, true);
})();
