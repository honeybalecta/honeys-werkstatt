// Oberfläche mit Testdaten in Chromium öffnen und fotografieren (hell, dunkel, Menü, Einstellungen, schmal).
// Aufruf: node windows/tests/ui_vorschau.mjs <ausgabeordner>
import { createRequire } from 'module';
import path from 'path';
import fs from 'fs';
import { fileURLToPath } from 'url';

const require = createRequire(import.meta.url);
const { chromium } = require('playwright');
const hier = path.dirname(fileURLToPath(import.meta.url));
const ui = path.resolve(hier, '..', 'ui', 'index.html');
const aus = path.resolve(process.argv[2] || 'vorschau');
fs.mkdirSync(aus, { recursive: true });

const jetzt = Date.now() / 1000;
const zustand = {
  prueft: false, zuletzt: jetzt - 120, version: '1.0.0', token: false,
  einstellungen: { autostart: true, hintergrund: true, auto: false, version: '1.0.0', token: false, frozen: true },
  apps: [
    { id: 'souffleur', name: 'Souffleur', typ: 'app', beschreibung: 'Teleprompter, der mitliest und von selbst weiterschaltet',
      installiert: '1.0.0', neu: '1.0.1', groesse: 63e6, update: true, oeffnen: true, vorgang: null, fehler: null },
    { id: 'honeycut', name: 'HoneyCut', typ: 'app', beschreibung: 'Gesprochene Videos über das Transkript schneiden',
      installiert: '2.8.0', neu: null, update: false, oeffnen: true, vorgang: null,
      fehler: { meldung: 'Repo honeybalecta/textschnitt nicht erreichbar', hinweis: 'Bei privaten Repos braucht die Werkstatt einen Lesetoken mit Zugriff auf dieses Repo (Einstellungen).' } },
    { id: 'karussell', name: 'Karussell-Werkstatt', typ: 'web', beschreibung: 'Instagram-Karussells als durchgehende Strecke gestalten',
      bereit: true, update: false, vorgang: null, fehler: null },
    { id: 'werkstatt', name: 'Honeys Werkstatt', typ: 'app', beschreibung: 'Diese App: alle eigenen Apps an einem Ort', selbst: true,
      installiert: '1.0.0', neu: '1.0.0', update: false, vorgang: null, fehler: null },
  ],
};
const varianten = {
  laedt: (z) => { z.apps[0].vorgang = { art: 'laedt', gelesen: 23.4e6, gesamt: 63e6 }; },
  geoeffnet: (z) => { z.apps[0].vorgang = { art: 'geoeffnet' }; z.apps[2].bereit = false; z.apps[2].update = false; },
  fehler: (z) => { z.apps[0].vorgang = { art: 'fehler', meldung: 'Ein Programm hält noch Dateien offen', hinweis: 'Läuft das Programm noch oder blockiert ein Virenschutz die Datei?' }; z.token = true; z.apps[1].fehler = null; z.apps[1].neu = '2.9.0'; z.apps[1].update = true; z.apps[1].groesse = 554e6; },
};

async function seite(browser, name, { dunkel = false, breite = 1000, variante = null, aktion = null } = {}) {
  const ctx = await browser.newContext({ viewport: { width: breite, height: 780 }, colorScheme: dunkel ? 'dark' : 'light', deviceScaleFactor: 1 });
  const p = await ctx.newPage();
  const z = JSON.parse(JSON.stringify(zustand));
  if (variante) varianten[variante](z);
  const fehler = [];
  p.on('pageerror', (e) => fehler.push(String(e)));
  const ohneSchrift = !fs.existsSync(path.resolve(hier, '..', 'ui', 'fonts', 'Inter-Regular.ttf'));
  p.on('console', (m) => {
    if (m.type() !== 'error') return;
    if (ohneSchrift && m.text().includes('ERR_FILE_NOT_FOUND')) return; // Schrift kommt erst im Build dazu
    fehler.push(m.text());
  });
  await p.addInitScript((z) => {
    const ok = (v) => Promise.resolve(v);
    window.__aufrufe = [];
    const merk = (n) => (...a) => { window.__aufrufe.push([n, ...a]); return ok(true); };
    window.pywebview = { api: {
      zustand: () => ok(JSON.parse(JSON.stringify(z))), einstellungen: () => ok(z.einstellungen),
      setze: (k, v) => { z.einstellungen[k] = v; return ok(z.einstellungen); },
      token_pruefen: () => ok({ ok: false, text: 'GitHub lehnt den Token ab' }),
      verknuepfung: () => ok({ ok: true, text: 'Verknüpfung auf dem Desktop angelegt' }),
      pruefen: merk('pruefen'), starten: merk('starten'), abbrechen: merk('abbrechen'), entscheiden: merk('entscheiden'),
      vorgang_weg: merk('vorgang_weg'), oeffnen: merk('oeffnen'), deinstallieren: merk('deinstallieren'), github: merk('github'),
      link: merk('link'), web_entfernen: merk('web_entfernen'), token_entfernen: merk('token_entfernen'),
      datenordner: merk('datenordner'), protokoll: merk('protokoll'), ablage: merk('ablage'),
    } };
  }, z);
  await p.goto('file://' + ui);
  await p.waitForSelector('[data-id="souffleur"]');
  if (aktion) await aktion(p);
  await p.waitForTimeout(300);
  await p.screenshot({ path: path.join(aus, name + '.png'), fullPage: true });
  const aufrufe = await p.evaluate(() => window.__aufrufe);
  await ctx.close();
  return { name, fehler, aufrufe };
}

const browser = await chromium.launch();
const ergebnisse = [];
ergebnisse.push(await seite(browser, '1-hell'));
ergebnisse.push(await seite(browser, '2-dunkel', { dunkel: true }));
ergebnisse.push(await seite(browser, '3-menue', { dunkel: true, aktion: (p) => p.click('[data-id="karussell"] [data-a="menue"]') }));
ergebnisse.push(await seite(browser, '4-laedt', { variante: 'laedt' }));
ergebnisse.push(await seite(browser, '5-geoeffnet', { dunkel: true, variante: 'geoeffnet' }));
ergebnisse.push(await seite(browser, '6-fehler', { variante: 'fehler', aktion: (p) => p.click('#alle') }));
ergebnisse.push(await seite(browser, '7-einstellungen', { aktion: (p) => p.click('#k-einstellungen') }));
ergebnisse.push(await seite(browser, '8-schmal', { breite: 420, dunkel: true }));
ergebnisse.push(await seite(browser, '9-aktionen', { aktion: async (p) => {
  await p.click('[data-id="souffleur"] .zeile [data-a="laden"]');
  await p.click('[data-id="karussell"] [data-a="menue"]');
  await p.click('[data-id="karussell"] [data-a="desktop"]');
} }));
await browser.close();
let schlecht = 0;
for (const e of ergebnisse) {
  console.log(e.name, e.fehler.length ? 'FEHLER ' + e.fehler.join(' | ') : 'ok', e.aufrufe.length ? JSON.stringify(e.aufrufe) : '');
  if (e.fehler.length) schlecht++;
}
const aufrufe9 = ergebnisse.at(-1).aufrufe.map((a) => a[0]);
if (!aufrufe9.includes('starten')) { console.log('starten wurde nicht aufgerufen'); schlecht++; }
const aufrufe6 = ergebnisse[5].aufrufe;
if (!aufrufe6.some((a) => a[0] === 'starten' && a[1].length === 2)) { console.log('„Alle aktualisieren“ startet nicht beide'); schlecht++; }
process.exit(schlecht ? 1 : 0);
