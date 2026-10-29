/* Honeys Werkstatt (Windows) – Oberfläche. Spricht über window.pywebview.api mit dem Programm. */
(function () {
  'use strict';
  var $ = function (s) { return document.querySelector(s); };
  var Z = null;                 // letzter Zustand
  var menueOffen = null;        // id der App, deren Menü offen ist
  var gezeichnet = new Map();   // id -> Schlüssel des zuletzt gezeichneten Zustands
  var fertigGeplant = new Set();
  var seite = 'liste';

  function api() { return window.pywebview && window.pywebview.api; }

  function esc(s) {
    return String(s == null ? '' : s).replace(/[&<>"']/g, function (c) {
      return { '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c];
    });
  }
  function mb(b) {
    if (!b || b <= 0) return '';
    if (b >= 10 * 1048576) return Math.round(b / 1048576) + ' MB';
    return (b / 1048576).toLocaleString('de-DE', { maximumFractionDigits: 1, minimumFractionDigits: 1 }) + ' MB';
  }
  function zuletzt(t) {
    if (!t) return '';
    var d = new Date(t * 1000), j = new Date();
    var hm = d.toLocaleTimeString('de-DE', { hour: '2-digit', minute: '2-digit' });
    var heute = d.toDateString() === j.toDateString();
    return ' · geprüft ' + (heute ? hm : d.getDate() + '.' + (d.getMonth() + 1) + '. ' + hm);
  }
  var meldungZeit = 0;
  function meldung(text) {
    var m = $('#meldung');
    m.textContent = text; m.hidden = false;
    var meine = ++meldungZeit;
    setTimeout(function () { if (meine === meldungZeit) m.hidden = true; }, 4500);
  }

  // ------------------------------------------------------------------ Beschriftungen

  function laeuft(v) { return v && ['wartet', 'laedt', 'einspielen', 'geoeffnet', 'schliesst', 'haengt'].indexOf(v.art) >= 0; }

  function grundzeile(a) {
    // [Text, Knopf, betont]
    if (a.typ === 'web') {
      if (!a.bereit && a.fehler) return [a.fehler.meldung, 'Laden', true];
      if (!a.bereit) return ['Web-Werkzeug · noch nicht geladen', 'Laden', true];
      if (a.update) return ['Web-Werkzeug · neue Fassung im Repo', 'Aktualisieren', true];
      return ['Web-Werkzeug · aktuell', 'Öffnen', false];
    }
    if (!a.neu && a.fehler) return [a.fehler.meldung, a.installiert && !a.selbst && a.oeffnen ? 'Öffnen' : null, false];
    if (!a.installiert) return ['Nicht installiert' + (a.neu ? ' · ' + a.neu : '') + (a.groesse ? ' · ' + mb(a.groesse) : ''), 'Installieren', true];
    if (a.update) return [a.installiert + ' → ' + (a.neu || 'neu') + (a.groesse ? ' · ' + mb(a.groesse) : ''), 'Aktualisieren', true];
    if (a.selbst) return ['Aktuell · ' + a.installiert + ' · diese App', null, false];
    return ['Aktuell · ' + a.installiert, a.oeffnen ? 'Öffnen' : null, false];
  }

  function symbol(a) {
    if (a.symbol) return '<div class="symbol"><img alt="" src="' + esc(a.symbol) + '"></div>';
    var web = a.typ === 'web';
    var pfad = 'M24,1.5 L43.5,12.75 L43.5,35.25 L24,46.5 L4.5,35.25 L4.5,12.75 Z';
    return '<div class="symbol' + (web ? ' web' : '') + '" aria-hidden="true"><svg viewBox="0 0 48 48"><path class="' +
      (web ? 'umriss' : 'voll') + '" d="' + pfad + '"/></svg><b>' + esc((a.name || '?').trim().charAt(0).toUpperCase()) + '</b></div>';
  }

  function menuePunkte(a) {
    var p = [];
    if (a.typ === 'web') {
      if (a.bereit) p.push(['oeffnen', 'Öffnen']);
      if (a.bereit) p.push(['desktop', 'Verknüpfung auf dem Desktop']);
      if (a.bereit) p.push(['startmenue', 'Ins Startmenü']);
      p.push(['laden', 'Neu laden']);
      if (a.bereit) p.push(['entfernen', 'Entfernen']);
    } else {
      if (a.installiert && !a.selbst && a.oeffnen) p.push(['oeffnen', 'Öffnen']);
      if (a.installiert && a.neu) p.push(['laden', 'Neueste Fassung neu installieren']);
      if (a.installiert && !a.selbst) p.push(['deinstallieren', 'Deinstallieren']);
    }
    p.push(['github', 'Auf GitHub ansehen']);
    return p;
  }

  function vorgangHtml(a) {
    var v = a.vorgang, n = esc(a.name);
    var abbrechen = '<button class="knopf text" data-a="abbrechen">Abbrechen</button>';
    switch (v.art) {
      case 'wartet':
        return '<div class="zeile"><div class="status">In der Warteschlange</div>' + abbrechen + '</div>';
      case 'laedt': {
        var anteil = v.gesamt > 0 ? v.gelesen / v.gesamt : null;
        var text = anteil != null ? 'Lädt ' + Math.floor(anteil * 100) + ' % · ' + (mb(v.gelesen) || '0 MB') + ' von ' + mb(v.gesamt) : 'Lädt …';
        return '<div class="zeile"><div class="status">' + text + '<div class="balken' + (anteil == null ? ' offen' : '') +
          '"><i style="width:' + (anteil != null ? (anteil * 100).toFixed(1) + '%' : '') + '"></i></div></div>' + abbrechen + '</div>';
      }
      case 'einspielen':
        return '<div class="zeile"><div class="status">' + (v.selbst ? 'Die Werkstatt startet gleich neu …' : 'Wird installiert …') +
          '<div class="balken offen"><i></i></div></div></div>';
      case 'geoeffnet':
        return '<div class="frage"><p>' + n + ' ist geöffnet. Zum Aktualisieren muss es geschlossen werden – speichere vorher, was offen ist.</p>' +
          '<div class="reihe"><button class="knopf haupt" data-a="schliessen">Schließen und aktualisieren</button>' + abbrechen + '</div></div>';
      case 'schliesst':
        return '<div class="zeile"><div class="status">' + n + ' wird geschlossen …<div class="balken offen"><i></i></div></div></div>';
      case 'haengt':
        return '<div class="frage"><p>' + n + ' ist noch offen – vielleicht fragt es nach ungespeicherten Änderungen. ' +
          'Beenden erzwingen verwirft, was nicht gespeichert ist.</p><div class="reihe"><button class="knopf haupt" data-a="erzwingen">Beenden erzwingen</button>' +
          abbrechen + '</div></div>';
      case 'fehler':
        return '<div class="zeile"><div class="status"><div class="fehler">' + esc(v.meldung) + '</div>' +
          (v.hinweis ? '<div class="klein leise">' + esc(v.hinweis) + '</div>' : '') + '</div>' +
          '<button class="knopf" data-a="laden">Noch einmal</button><button class="symbol-knopf" data-a="weg" title="Schließen" aria-label="Meldung schließen">' +
          '<svg viewBox="0 0 24 24"><path d="M6 6l12 12M18 6L6 18"/></svg></button></div>';
      case 'fertig':
        return '<div class="zeile"><div class="status"><span class="punkt"></span>' + esc(v.text) + '</div></div>';
    }
    return '';
  }

  function karteHtml(a) {
    var inhalt;
    if (a.vorgang) {
      inhalt = vorgangHtml(a);
    } else {
      var g = grundzeile(a);
      var aktuell = !a.update && (a.installiert || a.bereit) && !a.fehler;
      var fehler = a.fehler && !a.neu && a.typ === 'app' || (a.typ === 'web' && a.fehler && !a.bereit);
      var knopf = '';
      if (g[1]) knopf = '<button class="knopf' + (g[2] ? ' haupt' : '') + '" data-a="' + (g[1] === 'Öffnen' ? 'oeffnen' : 'laden') + '">' + g[1] + '</button>';
      inhalt = '<div class="zeile"><div class="status">' + (aktuell ? '<span class="punkt"></span>' : '') +
        '<span class="' + (fehler ? 'fehler' : '') + '">' + esc(g[0]) + '</span>' +
        (fehler && a.fehler.hinweis ? '<div class="klein leise">' + esc(a.fehler.hinweis) + '</div>' : '') + '</div>' + knopf + '</div>';
    }
    var punkte = menuePunkte(a).map(function (p) { return '<button data-a="' + p[0] + '">' + esc(p[1]) + '</button>'; }).join('');
    return '<div class="app-kopf">' + symbol(a) + '<div class="app-text"><div class="app-name">' + esc(a.name) + '</div>' +
      (a.beschreibung ? '<div class="klein leise">' + esc(a.beschreibung) + '</div>' : '') + '</div>' +
      '<div class="menue-wurzel"><button class="symbol-knopf" data-a="menue" aria-haspopup="menu" aria-label="Mehr zu ' + esc(a.name) + '">' +
      '<svg viewBox="0 0 24 24"><circle cx="12" cy="5" r="1.3"/><circle cx="12" cy="12" r="1.3"/><circle cx="12" cy="19" r="1.3"/></svg></button>' +
      '<div class="menue" role="menu"' + (menueOffen === a.id ? '' : ' hidden') + '>' + punkte + '</div></div></div>' + inhalt;
  }

  // ------------------------------------------------------------------ Zeichnen

  function zeichnen() {
    if (!Z) return;
    var apps = Z.apps || [];
    var offen = apps.filter(function (a) { return a.update && !laeuft(a.vorgang); });
    var sub = Z.prueft ? 'Prüfe GitHub …' : offen.length === 1 ? '1 Update bereit' : offen.length > 1 ? offen.length + ' Updates bereit'
      : apps.length ? 'Alles aktuell' : 'Katalog wird geladen …';
    $('#untertitel').textContent = sub + (Z.prueft ? '' : zuletzt(Z.zuletzt));
    $('#k-pruefen').classList.toggle('laeuft', !!Z.prueft);

    var alle = $('#alle');
    alle.hidden = offen.length < 2;
    alle.textContent = 'Alle aktualisieren (' + offen.length + ')';
    alle.dataset.ids = offen.map(function (a) { return a.id; }).join(',');

    var hinweise = '';
    if (!Z.token && apps.some(function (a) { return a.typ === 'app' && a.fehler && !a.neu; })) {
      hinweise += '<div class="karte hinweis"><h2>Lesetoken fehlt</h2><p class="leise">Apps aus privaten Repos (z. B. HoneyCut) sieht die Werkstatt erst mit einem GitHub-Lesetoken.</p>' +
        '<button class="knopf haupt" data-seite="einstellungen">Einrichten</button></div>';
    }
    var h = $('#hinweise');
    if (h.dataset.html !== hinweise) { h.innerHTML = hinweise; h.dataset.html = hinweise; }

    var liste = $('#apps');
    var ids = apps.map(function (a) { return a.id; });
    Array.prototype.slice.call(liste.children).forEach(function (el) {
      if (ids.indexOf(el.dataset.id) < 0) { liste.removeChild(el); gezeichnet.delete(el.dataset.id); }
    });
    apps.forEach(function (a, i) {
      var schluessel = JSON.stringify(a) + '|' + (menueOffen === a.id);
      var el = liste.querySelector('[data-id="' + a.id + '"]');
      if (!el) {
        el = document.createElement('section');
        el.className = 'karte app';
        el.dataset.id = a.id;
      }
      if (liste.children[i] !== el) liste.insertBefore(el, liste.children[i] || null);
      el.classList.toggle('update', !!(a.update && !a.vorgang));
      if (gezeichnet.get(a.id) !== schluessel) {
        el.innerHTML = karteHtml(a);
        gezeichnet.set(a.id, schluessel);
      }
      if (a.vorgang && a.vorgang.art === 'fertig') {
        var k = a.id + '|' + a.vorgang.text;
        if (!fertigGeplant.has(k)) {
          fertigGeplant.add(k);
          setTimeout(function () { fertigGeplant.delete(k); api().vorgang_weg(a.id); }, 4000);
        }
      }
    });
    if (seite === 'einstellungen') einstellungenZeigen(false);
  }

  function holen() {
    var a = api();
    if (!a) { setTimeout(holen, 300); return; }
    a.zustand().then(function (z) { Z = z; zeichnen(); }).catch(function () { })
      .then(function () { setTimeout(holen, document.hidden ? 3000 : 700); });
  }

  // ------------------------------------------------------------------ Aktionen

  function app(id) { return (Z && Z.apps || []).find(function (a) { return a.id === id; }); }

  function aktion(id, was) {
    var a = app(id), A = api();
    if (!a || !A) return;
    if (was !== 'menue') menueOffen = null;
    switch (was) {
      case 'menue': menueOffen = menueOffen === id ? null : id; break;
      case 'laden': A.starten([id]); break;
      case 'abbrechen': A.abbrechen(id); break;
      case 'schliessen': A.entscheiden(id, 'schliessen'); break;
      case 'erzwingen': A.entscheiden(id, 'erzwingen'); break;
      case 'weg': A.vorgang_weg(id); break;
      case 'oeffnen': A.oeffnen(id).then(function (ok) { if (!ok) meldung(a.name + ' ließ sich nicht starten.'); }); break;
      case 'deinstallieren': A.deinstallieren(id); break;
      case 'github': A.github(id); break;
      case 'entfernen': A.web_entfernen(id).then(function () { meldung(a.name + ' entfernt.'); }); break;
      case 'desktop': case 'startmenue':
        A.verknuepfung(id, was).then(function (r) { meldung(r.text); }); break;
    }
    gezeichnet.clear();
    zeichnen();
  }

  document.addEventListener('click', function (ev) {
    var b = ev.target.closest('button');
    if (!b) { if (menueOffen) { menueOffen = null; gezeichnet.clear(); zeichnen(); } return; }
    if (b.dataset.seite) { zeigeSeite(b.dataset.seite); return; }
    var karte = b.closest('[data-id]');
    if (karte && b.dataset.a) { aktion(karte.dataset.id, b.dataset.a); return; }
    if (menueOffen && !b.closest('.menue-wurzel')) { menueOffen = null; gezeichnet.clear(); zeichnen(); }
  });
  document.addEventListener('keydown', function (ev) {
    if (ev.key === 'Escape') {
      if (menueOffen) { menueOffen = null; gezeichnet.clear(); zeichnen(); }
      else if (seite !== 'liste') zeigeSeite('liste');
    }
    if (ev.key === 'F5') { ev.preventDefault(); api() && api().pruefen(); }
  });
  $('#k-pruefen').onclick = function () { api() && api().pruefen(); };
  $('#k-einstellungen').onclick = function () { zeigeSeite('einstellungen'); };
  $('#k-zurueck').onclick = function () { zeigeSeite('liste'); };
  $('#alle').onclick = function () { var ids = (this.dataset.ids || '').split(',').filter(Boolean); if (ids.length) api().starten(ids); };

  // ------------------------------------------------------------------ Einstellungen

  function zeigeSeite(s) {
    seite = s;
    $('#liste').hidden = s !== 'liste';
    $('#einstellungen').hidden = s !== 'einstellungen';
    window.scrollTo(0, 0);
    if (s === 'einstellungen') einstellungenZeigen(true);
  }

  function einstellungenZeigen(voll) {
    var e = (Z && Z.einstellungen) || {};
    $('#token-text').textContent = e.token
      ? 'Ein Lesetoken ist gespeichert. Damit sieht die Werkstatt auch Apps aus privaten Repos.'
      : 'Für Apps aus privaten Repos (z. B. HoneyCut) braucht die Werkstatt einen Lesetoken. Er bleibt verschlüsselt auf diesem Rechner.';
    $('#token-label').textContent = e.token ? 'Neuen Token eintragen' : 'Token (beginnt mit github_pat_)';
    $('#token-entfernen').hidden = !e.token;
    ['autostart', 'hintergrund', 'auto'].forEach(function (k) {
      var el = $('#s-' + k);
      if (voll || document.activeElement !== el) el.checked = !!e[k];
    });
    $('#ueber').textContent = 'Honeys Werkstatt für Windows ' + (e.version || '') + (e.frozen === false ? ' (Entwicklungsfassung)' : '');
  }

  $('#token').addEventListener('input', function () { $('#token-speichern').disabled = this.value.trim().length < 20; });
  $('#token-speichern').onclick = function () {
    var t = $('#token').value.trim(), b = this;
    b.disabled = true;
    $('#token-status').textContent = 'Prüfe …';
    api().token_pruefen(t).then(function (r) {
      $('#token-status').textContent = r.text;
      if (r.ok) $('#token').value = '';
      b.disabled = !$('#token').value.trim();
    });
  };
  $('#token-entfernen').onclick = function () { api().token_entfernen().then(function () { $('#token-status').textContent = 'Token entfernt.'; }); };
  $('#token-anlegen').onclick = function () { api().link('https://github.com/settings/personal-access-tokens/new'); };
  ['autostart', 'hintergrund', 'auto'].forEach(function (k) {
    $('#s-' + k).addEventListener('change', function () {
      api().setze(k, this.checked).then(function (e) { if (Z) Z.einstellungen = e; einstellungenZeigen(true); });
    });
  });
  $('#o-ablage').onclick = function () { api().ablage(); };
  $('#o-daten').onclick = function () { api().datenordner(); };
  $('#o-protokoll').onclick = function () { api().protokoll(); };

  if (window.pywebview && window.pywebview.api) holen();
  else window.addEventListener('pywebviewready', holen, { once: true });
})();
