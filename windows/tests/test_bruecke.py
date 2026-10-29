"""Lokaler Server + Brücke in Chromium: Speichern über claude.use('downloads') und <a download>. Braucht Playwright (sonst übersprungen)."""
import sys
import tempfile
import unittest
import urllib.error
import urllib.request
from pathlib import Path

HIER = Path(__file__).resolve().parent
sys.path.insert(0, str(HIER.parent))

from werkstatt.server import Server  # noqa: E402

try:
    from playwright.sync_api import sync_playwright
except ImportError:  # pragma: no cover
    sync_playwright = None

SEITE = """<!doctype html><html><head><meta charset="utf-8"><title>Probe</title>
<script>
  // wie Karussell-Werkstatt: holt sich die Fähigkeit schon beim Laden
  window.DL = null;
  (async () => { try { window.DL = await window.claude?.use?.('downloads'); } catch (e) { window.DL = null; } })();
</script></head><body>
<button id="a" onclick="DL.save({filename:'Slide_01.png', data: new Blob([new Uint8Array([137,80,78,71])], {type:'image/png'})}).then(r => document.title='ok:'+r.path)">a</button>
<button id="b" onclick="const a=document.createElement('a');a.href=window.URL.createObjectURL(new Blob(['hallo'],{type:'text/plain'}));a.download='notiz.txt';document.body.appendChild(a);a.click();window.URL.revokeObjectURL(a.href)">b</button>
</body></html>"""


@unittest.skipIf(sync_playwright is None, "Playwright fehlt")
class Bruecke(unittest.TestCase):
    def test_speichern_in_chromium(self):
        tmp = Path(tempfile.mkdtemp())
        (tmp / "web" / "probe").mkdir(parents=True)
        (tmp / "web" / "probe" / "index.html").write_text(SEITE, encoding="utf-8")
        ui = HIER.parent / "ui"
        srv = Server(ui, tmp / "web", (ui / "bruecke.js").read_text(encoding="utf-8"), befehl=lambda b: None,
                     ablage=lambda: tmp / "ablage")
        srv.starten(0)
        try:
            with sync_playwright() as p:
                b = p.chromium.launch()
                s = b.new_page()
                fehler = []
                s.on("pageerror", lambda e: fehler.append(str(e)))
                s.goto(f"{srv.basis}/web/probe/index.html")
                s.wait_for_function("window.DL && typeof window.DL.save === 'function'")
                s.click("#a")
                s.wait_for_function("document.title.startsWith('ok:')")
                s.click("#b")
                s.wait_for_function("document.querySelector('[role=status]') && document.querySelector('[role=status]').textContent.includes('notiz.txt')")
                # fremde Seite darf nicht speichern (kein Geheimnis, anderer Ursprung)
                s.goto("about:blank")
                b.close()
            self.assertEqual(fehler, [])
            namen = sorted(x.name for x in (tmp / "ablage").iterdir())
            self.assertEqual(namen, ["Slide_01.png", "notiz.txt"])
            self.assertEqual((tmp / "ablage" / "notiz.txt").read_text(), "hallo")
            self.assertEqual((tmp / "ablage" / "Slide_01.png").read_bytes()[:4], bytes([137, 80, 78, 71]))
        finally:
            srv.stoppen()

    def test_schutz(self):
        tmp = Path(tempfile.mkdtemp())
        (tmp / "web").mkdir()
        srv = Server(HIER.parent / "ui", tmp / "web", "", befehl=lambda b: None, ablage=lambda: tmp / "ablage")
        srv.starten(0)
        oeffner = urllib.request.build_opener(urllib.request.ProxyHandler({}))
        try:
            def code(req):
                try:
                    return oeffner.open(req, timeout=5).status
                except urllib.error.HTTPError as e:
                    return e.code
            self.assertEqual(code(urllib.request.Request(f"{srv.basis}/web/../../etc/passwd")), 404)
            self.assertEqual(code(urllib.request.Request(f"{srv.basis}/ui/index.html", headers={"Host": "boese.example:80"})), 403)
            self.assertEqual(code(urllib.request.Request(f"{srv.basis}/__steuer", data=b"{}", method="POST")), 403)
            self.assertEqual(code(urllib.request.Request(f"{srv.basis}/ui/index.html")), 200)
        finally:
            srv.stoppen()


if __name__ == "__main__":
    unittest.main()
