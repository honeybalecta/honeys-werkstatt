/* Honeys Werkstatt – Brücke für Web-Werkzeuge.
 * Stellt window.claude.use('downloads') bereit (wie in Claude-Artefakten) und fängt <a download> mit blob:/data: ab.
 * Gespeichert wird über die App: Bilder in „Bilder/Honeys Werkstatt“, alles andere in „Download/Honeys Werkstatt“. */
(function () {
  if (window.__werkstatt) return;
  window.__werkstatt = true;
  var B = window.WerkstattBruecke;
  if (!B) return;

  function b64(u8) {
    var s = '', CH = 0x8000;
    for (var i = 0; i < u8.length; i += CH) s += String.fromCharCode.apply(null, u8.subarray(i, i + CH));
    return btoa(s);
  }

  async function zuBytes(data) {
    if (data instanceof Blob) return new Uint8Array(await data.arrayBuffer());
    if (data instanceof ArrayBuffer) return new Uint8Array(data);
    if (ArrayBuffer.isView(data)) return new Uint8Array(data.buffer, data.byteOffset, data.byteLength);
    if (typeof data === 'string') {
      if (data.slice(0, 5) === 'data:') { var r = await fetch(data); return new Uint8Array(await r.arrayBuffer()); }
      return new TextEncoder().encode(data);
    }
    if (data && typeof data === 'object') return new TextEncoder().encode(JSON.stringify(data));
    throw new Error('Unbekanntes Datenformat');
  }

  function mimeVon(data, mime) {
    if (mime) return mime;
    if (data instanceof Blob && data.type) return data.type;
    if (typeof data === 'string' && data.slice(0, 5) === 'data:') {
      var ende = data.indexOf(';'); if (ende < 0) ende = data.indexOf(',');
      return data.slice(5, ende);
    }
    return '';
  }

  async function speichern(name, data, mime) {
    var bytes = await zuBytes(data);
    var h = B.beginnen(String(name || 'datei'), mimeVon(data, mime) || '');
    var CH = 3 * 256 * 1024;
    for (var i = 0; i < bytes.length; i += CH) B.teil(h, b64(bytes.subarray(i, i + CH)));
    var r = B.abschliessen(h);
    if (r.slice(0, 7) === 'FEHLER:') { var e = new Error(r.slice(7)); e.code = 'failed'; throw e; }
    return r;
  }

  var downloads = {
    save: async function (o) {
      o = o || {};
      var ort = await speichern(o.filename || o.name, o.data !== undefined ? o.data : (o.blob || o.content || ''), o.mimeType || o.type);
      return { ok: true, saved: true, path: ort };
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

  // Viele Seiten geben Blob-Adressen sofort nach dem Klick wieder frei – kurz aufheben, damit sie noch lesbar sind
  var freigeben = URL.revokeObjectURL.bind(URL);
  URL.revokeObjectURL = function (u) { setTimeout(function () { freigeben(u); }, 60000); };

  async function anker(a) {
    var href = a.href;
    var name = a.getAttribute('download') || decodeURIComponent((href.split('/').pop() || 'datei').split('?')[0]);
    var blob = await (await fetch(href)).blob();
    await speichern(name, blob, blob.type);
  }
  function istDownload(a) { return a && a.hasAttribute && a.hasAttribute('download') && /^(blob|data):/.test(a.href || ''); }

  var klick = HTMLAnchorElement.prototype.click;
  HTMLAnchorElement.prototype.click = function () {
    if (istDownload(this)) { anker(this).catch(function (e) { B.melden(String(e && e.message || e)); }); return; }
    return klick.call(this);
  };
  document.addEventListener('click', function (ev) {
    var a = ev.target && ev.target.closest ? ev.target.closest('a[download]') : null;
    if (istDownload(a)) { ev.preventDefault(); anker(a).catch(function (e) { B.melden(String(e && e.message || e)); }); }
  }, true);
})();
