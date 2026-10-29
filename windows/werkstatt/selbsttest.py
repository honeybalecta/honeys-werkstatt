"""
Selbsttest im gebauten Programm (läuft auf dem Windows-Build-Rechner von GitHub, ohne Fenster):
Paket vollständig? Katalog, Tresor, lokaler Server samt Brücke, GitHub-Abfragen, und eine echte
Installation von Souffleur – einmal frisch, einmal als Update bei geöffnetem Programm.
"""
from __future__ import annotations

import json
import os
import sys
import tempfile
import time
import traceback
import urllib.error
import urllib.request
from pathlib import Path

from . import kern, system


def ausfuehren(ausgabe: Path, wurzel: Path, ui: Path, appid: str, version: str) -> int:
    bericht: dict = {"version": version, "schritte": {}}
    alles_ok = [True]
    ordner = ausgabe.parent
    ordner.mkdir(parents=True, exist_ok=True)

    def schritt(name, f, pflicht=True):
        t0 = time.time()
        try:
            ergebnis = f()
            bericht["schritte"][name] = {"ok": True, "ergebnis": ergebnis, "sekunden": round(time.time() - t0, 1)}
        except Exception:  # noqa: BLE001
            bericht["schritte"][name] = {"ok": False, "fehler": traceback.format_exc()[-1500:], "sekunden": round(time.time() - t0, 1)}
            if pflicht:
                alles_ok[0] = False
        ausgabe.write_text(json.dumps(bericht, ensure_ascii=False, indent=2), encoding="utf-8")

    # 1. Paket
    def paket():
        fehlt = [p for p in ("index.html", "app.js", "app.css", "bruecke.js", "fonts/Inter-Regular.ttf", "fonts/Inter-Bold.ttf")
                 if not (ui / p).exists()]
        assert not fehlt, f"fehlt: {fehlt}"
        assert (wurzel / "katalog.json").exists(), "katalog.json fehlt"
        import webview  # noqa: F401
        import pystray  # noqa: F401
        from PIL import Image  # noqa: F401
        return "vollständig"
    schritt("paket", paket)

    # 2. Katalog
    def katalog():
        ids = [e.id for e in kern.lies_katalog((wurzel / "katalog.json").read_text(encoding="utf-8"))]
        assert ids == ["souffleur", "honeycut", "karussell", "werkstatt"], ids
        return ids
    schritt("katalog", katalog)

    # 3. Anmeldeinformationsverwaltung
    def tresor():
        ziel = "HoneysWerkstatt/selbsttest"
        system.geheimnis_schreiben(ziel, "geheim-äöü-123")
        assert system.geheimnis_lesen(ziel) == "geheim-äöü-123"
        system.geheimnis_loeschen(ziel)
        assert system.geheimnis_lesen(ziel) is None
        return "schreiben, lesen, löschen"
    schritt("tresor", tresor)

    # 4. Lokaler Server mit Brücke
    def server():
        from .server import Server
        tmp = Path(tempfile.mkdtemp())
        (tmp / "web" / "probe").mkdir(parents=True)
        (tmp / "web" / "probe" / "index.html").write_text("<!doctype html><html><head><title>t</title></head><body>x</body></html>", encoding="utf-8")
        srv = Server(ui, tmp / "web", (ui / "bruecke.js").read_text(encoding="utf-8"), befehl=lambda b: None,
                     ablage=lambda: tmp / "ablage")
        srv.starten(0)
        oeffner = urllib.request.build_opener(urllib.request.ProxyHandler({}))
        html = oeffner.open(f"{srv.basis}/web/probe/index.html", timeout=5).read().decode()
        assert "__WERKSTATT" in html and "claude.use" in html, "Brücke fehlt in der Seite"
        assert b"Honeys Werkstatt" in oeffner.open(f"{srv.basis}/ui/index.html", timeout=5).read()

        def post(geheim):
            req = urllib.request.Request(f"{srv.basis}/__werkstatt/speichern", data=b"PNGDATEN", method="POST",
                                         headers={"X-Werkstatt": geheim, "X-Dateiname": "Slide%2001.png"})
            try:
                return oeffner.open(req, timeout=5).status
            except urllib.error.HTTPError as e:
                return e.code
        assert post("falsch") == 403, "Speichern ohne Geheimnis muss scheitern"
        assert post(srv.geheim) == 200
        assert post(srv.geheim) == 200
        namen = sorted(p.name for p in (tmp / "ablage").iterdir())
        assert namen == ["Slide 01 (2).png", "Slide 01.png"], namen
        srv.stoppen()
        return namen
    schritt("server", server)

    # 5. GitHub
    token = os.environ.get("WERKSTATT_TEST_TOKEN") or None
    if token:
        system.geheimnis_schreiben(system.TOKEN_ZIEL, token)

    from .dienst import Dienst
    d = Dienst(version, appid, (wurzel / "katalog.json").read_text(encoding="utf-8"))

    def github():
        d.pruefen()
        z = {a["id"]: a for a in d.zustand()["apps"]}
        assert z["souffleur"]["neu"], f"Souffleur ohne Freigabe: {z['souffleur'].get('fehler')}"
        assert z["karussell"]["fehler"] is None, z["karussell"]["fehler"]
        return {k: {"neu": v["neu"], "installiert": v["installiert"], "fehler": v["fehler"]} for k, v in z.items()}
    schritt("github", github)

    def warte(id_, arten, sekunden=900):
        ende = time.time() + sekunden
        while time.time() < ende:
            v = d.vorgaenge.get(id_) or {}
            if v.get("art") in arten:
                return v
            time.sleep(0.5)
        raise TimeoutError(f"{id_}: kein {arten}, zuletzt {d.vorgaenge.get(id_)}")

    souffleur = d.eintrag("souffleur")

    # 6. Frische Installation
    def installation():
        vorher = system.installiert(souffleur.appid, souffleur.exe)
        d.starte(["souffleur"])
        v = warte("souffleur", ("fertig", "fehler"))
        assert v["art"] == "fertig", v
        inst = system.installiert(souffleur.appid, souffleur.exe)
        assert inst and inst.exe and Path(inst.exe).exists(), inst
        a = next(x for x in d.zustand()["apps"] if x["id"] == "souffleur")
        assert a["update"] is False, "nach der Installation noch Update gemeldet"
        return {"vorher": vorher.version if vorher else None, "jetzt": inst.version, "ordner": inst.ordner}
    schritt("installation", installation)

    # 7. Update bei geöffnetem Programm: Werkstatt fragt, schließt es und installiert
    def update_offen():
        inst = system.installiert(souffleur.appid, souffleur.exe)
        system.starte(inst.exe)
        ende = time.time() + 40
        while not system.prozesse_in(inst.ordner) and time.time() < ende:
            time.sleep(0.5)
        assert system.prozesse_in(inst.ordner), "Souffleur startet nicht"
        time.sleep(6)  # Fenster aufbauen lassen
        d.setze_vorgang("souffleur", None)
        d.starte(["souffleur"])
        warte("souffleur", ("geoeffnet",), 300)
        d.entscheide("souffleur", "schliessen")
        v = warte("souffleur", ("fertig", "fehler", "haengt"), 300)
        if v["art"] == "haengt":
            d.entscheide("souffleur", "erzwingen")
            v = warte("souffleur", ("fertig", "fehler"), 300)
            v["erzwungen"] = True
        assert v["art"] == "fertig", v
        assert not system.prozesse_in(inst.ordner)
        return v
    schritt("update_bei_geoeffnetem_programm", update_offen)

    # 8. Programmsymbol
    def symbol():
        inst = system.installiert(souffleur.appid, souffleur.exe)
        bild = system.programm_symbol(inst.exe, 96)
        assert bild is not None, "kein Symbol"
        bild.save(ordner / "symbol-souffleur.png")
        return list(bild.size)
    schritt("symbol", symbol, pflicht=False)

    # 9. Web-Werkzeug laden
    def web():
        e = d.eintrag("karussell")
        d.web_aktualisiere(e, d.token())
        datei = d.web_ordner(e) / e.start
        assert datei.stat().st_size > 10_000
        a = next(x for x in d.zustand()["apps"] if x["id"] == "karussell")
        assert a["bereit"] and not a["update"]
        return datei.stat().st_size
    schritt("web_werkzeug", web)

    # 10. Verknüpfung und Ordner
    def verknuepfung():
        from . import symbole
        tmp = Path(tempfile.mkdtemp())
        ico = symbole.als_ico(symbole.web_werkzeug("Karussell-Werkstatt", 256, ui / "fonts" / "Inter-Bold.ttf"), tmp / "k.ico")
        lnk = tmp / "Karussell-Werkstatt.lnk"
        system.verknuepfung(lnk, sys.executable, "--web karussell", str(ico), "Test")
        assert lnk.exists()
        return {"downloads": str(system.bekannter_ordner("downloads")), "programme": str(system.bekannter_ordner("programme"))}
    schritt("verknuepfung", verknuepfung)

    bericht["ok"] = alles_ok[0]
    ausgabe.write_text(json.dumps(bericht, ensure_ascii=False, indent=2), encoding="utf-8")
    return 0 if alles_ok[0] else 1
