"""
Honeys Werkstatt für Windows: Programmfenster (Edge WebView2), Symbol im Infobereich, Prüfung im Hintergrund.

Start:  HoneysWerkstatt.exe [--hintergrund | --zeigen | --web <id> | --beenden | --selbsttest bericht.json]
"""
from __future__ import annotations

import json
import logging
import logging.handlers
import os
import sys
import threading
import time
from pathlib import Path
from typing import Optional

from . import kern, symbole, system
from .dienst import Dienst
from .server import Server, an_laufende_senden, instanz_merken

FROZEN = bool(getattr(sys, "frozen", False))
HIER = Path(__file__).resolve().parent
WURZEL = Path(getattr(sys, "_MEIPASS", HIER.parent)) if FROZEN else HIER.parent
UI = WURZEL / "ui"
APPID = "{EDD2D7CA-10E7-4F92-8F62-B5F948950037}"
TITEL = "Honeys Werkstatt"
PRUEF_ABSTAND = 6 * 3600

log = logging.getLogger("werkstatt")


def version() -> str:
    for p in (WURZEL / "app-version.txt", HIER.parent / "version.txt"):
        if p.exists():
            return p.read_text(encoding="utf-8").strip()
    return "dev"


def eingebauter_katalog() -> Optional[str]:
    for p in (WURZEL / "katalog.json", HIER.parent.parent / "katalog.json"):
        if p.exists():
            return p.read_text(encoding="utf-8")
    return None


def protokoll_einrichten() -> Path:
    datei = system.daten_ordner() / "werkstatt.log"
    h = logging.handlers.RotatingFileHandler(datei, maxBytes=512 * 1024, backupCount=2, encoding="utf-8")
    h.setFormatter(logging.Formatter("%(asctime)s %(levelname)s %(message)s"))
    log.addHandler(h)
    log.setLevel(logging.INFO)
    if not FROZEN:
        log.addHandler(logging.StreamHandler())
    return datei


def autostart_befehl() -> str:
    return f'"{sys.executable}" --hintergrund'


class Werkstatt:
    """Hält Fenster, Infobereich und Dienst zusammen."""

    def __init__(self, dienst: Dienst, server: Server, versteckt: bool):
        import webview
        self.webview = webview
        self.dienst = dienst
        self.server = server
        self.beenden_laeuft = False
        self.tray = None
        self.web_fenster: dict = {}
        self.api = Api(self)
        self.fenster = webview.create_window(
            TITEL, f"{server.basis}/ui/index.html", js_api=self.api, width=1000, height=780, min_size=(660, 540),
            hidden=versteckt, background_color="#131312", text_select=False)
        self.fenster.events.closing += self._schliesst
        self.fenster.events.shown += lambda: self._sichtbar(True)
        self.dienst.sichtbar = not versteckt
        self.dienst.melden = self.melden
        self.dienst.selbst_update = self._selbst_update

    # ---------------------------------------------------------------- Fenster

    def _sichtbar(self, an: bool):
        self.dienst.sichtbar = an

    def _schliesst(self):
        # Läuft in einem Hilfs-Thread, während das Fenster wartet: Fensterbefehle daher nur zeitversetzt
        if self.beenden_laeuft:
            return True
        if self.dienst.e["hintergrund"]:
            threading.Timer(0.05, self._verstecken).start()  # ausblenden statt beenden
            return False
        self.beenden_laeuft = True
        threading.Timer(0.1, self._rest_beenden).start()
        return True

    def _verstecken(self):
        try:
            self.fenster.hide()
        except Exception:  # noqa: BLE001
            pass
        self.dienst.sichtbar = False
        if not self.dienst.e["hinweis_infobereich"]:
            self.dienst.e["hinweis_infobereich"] = True
            self.melden("Honeys Werkstatt läuft weiter", "Sie sucht im Hintergrund nach Updates. Öffnen über das Symbol im Infobereich.")

    def zeigen(self):
        try:
            self.fenster.show()
            self.fenster.restore()
        except Exception:  # noqa: BLE001
            log.exception("Fenster zeigen")
        self.dienst.sichtbar = True

    def web_oeffnen(self, id_: str):
        e = self.dienst.eintrag(id_)
        if not e or e.typ != "web":
            return
        offen = self.web_fenster.get(id_)
        if offen is not None and offen in self.webview.windows:
            try:
                offen.show()
                offen.restore()
                return
            except Exception:  # noqa: BLE001
                pass
        token = self.dienst.token()
        if self.dienst.web_bereit(e):
            # kurz nach einer neueren Fassung schauen, aber höchstens vier Sekunden warten
            t = threading.Thread(target=lambda: self._still(lambda: self.dienst.web_aktualisiere(e, token)), daemon=True)
            t.start()
            t.join(4)
        else:
            self.dienst.setze_vorgang(id_, {"art": "laedt", "gelesen": 0, "gesamt": 0})
            try:
                self.dienst.web_aktualisiere(e, token)
                self.dienst.setze_vorgang(id_, None)
            except kern.WerkstattFehler as x:
                self.dienst.setze_vorgang(id_, {"art": "fehler", "meldung": x.meldung, "hinweis": x.hinweis})
                return
        url = f"{self.server.basis}/web/{e.id}/{e.start}"
        try:
            w = self.webview.create_window(e.name, url, width=1360, height=900, min_size=(760, 560),
                                           background_color="#131312", maximized=True)
        except TypeError:
            w = self.webview.create_window(e.name, url, width=1360, height=900, min_size=(760, 560), background_color="#131312")
        self.web_fenster[id_] = w

    @staticmethod
    def _still(f):
        try:
            f()
        except Exception:  # noqa: BLE001
            pass

    # ---------------------------------------------------------------- Infobereich

    def infobereich(self):
        try:
            import pystray
        except Exception:  # noqa: BLE001
            log.exception("pystray fehlt")
            return
        bild = symbole.werkstatt(64)
        menue = pystray.Menu(
            pystray.MenuItem("Honeys Werkstatt öffnen", lambda *_: self.zeigen(), default=True),
            pystray.MenuItem("Nach Updates suchen", lambda *_: threading.Thread(target=self._pruefen_und_melden, daemon=True).start()),
            pystray.Menu.SEPARATOR,
            pystray.MenuItem("Beenden", lambda *_: self.beenden()),
        )
        self.tray = pystray.Icon("HoneysWerkstatt", bild, TITEL, menue)
        try:
            self.tray.run_detached()
        except Exception:  # noqa: BLE001
            threading.Thread(target=self.tray.run, daemon=True).start()

    def melden(self, titel: str, text: str):
        if self.tray is not None:
            try:
                self.tray.notify(text, titel)
            except Exception:  # noqa: BLE001
                log.exception("Benachrichtigung")

    def _pruefen_und_melden(self):
        self.dienst.pruefen()
        offen = self.dienst.updates()
        if not offen:
            self.melden("Alles aktuell", "Für keine deiner Apps gibt es ein Update.")
        else:
            self.melden(f"{len(offen)} Update{'s' if len(offen) > 1 else ''} bereit", ", ".join(a["name"] for a in offen))

    # ---------------------------------------------------------------- Hintergrund

    def hintergrund(self):
        time.sleep(90)
        while not self.beenden_laeuft:
            letzte = float(self.dienst.e["zuletzt"] or 0)
            if self.dienst.e["hintergrund"] and time.time() - letzte >= PRUEF_ABSTAND - 60:
                try:
                    self.dienst.hintergrund_runde()
                except Exception:  # noqa: BLE001
                    log.exception("Hintergrundprüfung")
            for _ in range(60):
                if self.beenden_laeuft:
                    return
                time.sleep(1)

    # ---------------------------------------------------------------- Befehle einer zweiten Instanz

    def befehl(self, b: dict):
        art = b.get("befehl")
        if art == "web" and b.get("id"):
            self.web_oeffnen(str(b["id"]))
        elif art == "beenden":
            self.beenden()
        elif art == "zeigen" or art is None:
            self.zeigen()

    # ---------------------------------------------------------------- Ende

    def _selbst_update(self, setup: Path, protokoll: Path):
        start = "--zeigen" if self.dienst.sichtbar else "--hintergrund"
        try:
            system.selbst_aktualisieren(setup, protokoll, start)
        except OSError as x:
            raise kern.WerkstattFehler("Update ließ sich nicht starten", str(x)) from None
        log.info("Selbst-Update gestartet, Werkstatt beendet sich")
        threading.Timer(0.3, self.beenden).start()

    def beenden(self):
        if self.beenden_laeuft:
            return
        self.beenden_laeuft = True
        self._rest_beenden(alle=True)

    def _rest_beenden(self, alle: bool = False):
        if self.tray is not None:
            try:
                self.tray.stop()
            except Exception:  # noqa: BLE001
                pass
        for w in list(self.webview.windows):
            if not alle and w is self.fenster:
                continue
            try:
                w.destroy()
            except Exception:  # noqa: BLE001
                pass


class Api:
    """Aufrufbar aus der Oberfläche als window.pywebview.api.* – nur Methoden, keine öffentlichen Objekte."""

    def __init__(self, w: Werkstatt):
        self._w = w

    @property
    def _d(self) -> Dienst:
        return self._w.dienst

    def zustand(self):
        z = self._d.zustand()
        z["einstellungen"] = self.einstellungen()
        return z

    def einstellungen(self):
        e = self._d.e
        return {"autostart": system.autostart_eingetragen() if FROZEN else bool(e["autostart"]),
                "hintergrund": bool(e["hintergrund"]), "auto": bool(e["auto"]), "version": self._d.version,
                "ordner": str(self._d.ordner), "token": self._d.token() is not None, "frozen": FROZEN}

    def setze(self, name: str, wert):
        wert = bool(wert)
        if name == "autostart":
            self._d.e["autostart"] = wert
            if FROZEN:
                system.autostart_setzen(wert, autostart_befehl())
        elif name in ("hintergrund", "auto"):
            self._d.e[name] = wert
        return self.einstellungen()

    def pruefen(self):
        threading.Thread(target=self._d.pruefen, daemon=True).start()
        return True

    def starten(self, ids):
        self._d.starte([str(i) for i in (ids or [])])
        return True

    def abbrechen(self, id_):
        self._d.abbrechen(str(id_))
        return True

    def entscheiden(self, id_, wahl):
        self._d.entscheide(str(id_), str(wahl))
        return True

    def vorgang_weg(self, id_):
        v = self._d.vorgaenge.get(str(id_))
        if v and v.get("art") in ("fehler", "fertig"):
            self._d.setze_vorgang(str(id_), None)
        return True

    def oeffnen(self, id_):
        e = self._d.eintrag(str(id_))
        if not e:
            return False
        if e.typ == "web":
            threading.Thread(target=self._w.web_oeffnen, args=(e.id,), daemon=True).start()
            return True
        inst = system.installiert(e.appid, e.exe)
        if inst and inst.exe:
            system.starte(inst.exe)
            return True
        return False

    def deinstallieren(self, id_):
        e = self._d.eintrag(str(id_))
        inst = system.installiert(e.appid, e.exe) if e else None
        if inst and inst.deinstallieren:
            system.deinstallieren(inst.deinstallieren)
            return True
        return False

    def github(self, id_):
        e = self._d.eintrag(str(id_))
        if e:
            system.oeffne_url(e.github_url)
        return True

    def link(self, url):
        if str(url).startswith("https://github.com/"):
            system.oeffne_url(str(url))
        return True

    def web_entfernen(self, id_):
        e = self._d.eintrag(str(id_))
        if e and e.typ == "web":
            self._d.web_entfernen(e)
        return True

    def verknuepfung(self, id_, ort):
        e = self._d.eintrag(str(id_))
        if not e or e.typ != "web":
            return {"ok": False, "text": "Unbekanntes Werkzeug"}
        if not FROZEN:
            return {"ok": False, "text": "Verknüpfungen gehen nur in der installierten Werkstatt"}
        try:
            schrift = UI / "fonts" / "Inter-Bold.ttf"
            ico = symbole.als_ico(symbole.web_werkzeug(e.name, 256, schrift if schrift.exists() else None),
                                  self._d.ordner / "symbole" / f"{e.id}.ico")
            if ort == "desktop":
                lnk = system.bekannter_ordner("desktop") / f"{e.name}.lnk"
                text = "Verknüpfung auf dem Desktop angelegt"
            else:
                lnk = system.bekannter_ordner("programme") / "Honeys Werkstatt" / f"{e.name}.lnk"
                text = "Im Startmenü unter „Honeys Werkstatt“ angelegt"
            system.verknuepfung(lnk, sys.executable, f"--web {e.id}", str(ico), e.beschreibung or e.name)
            return {"ok": True, "text": text}
        except Exception as x:  # noqa: BLE001
            log.exception("Verknüpfung")
            return {"ok": False, "text": f"Verknüpfung ließ sich nicht anlegen: {x}"}

    def token_pruefen(self, token):
        token = str(token or "").strip()
        try:
            name = kern.pruefe_token(token)
        except kern.WerkstattFehler as x:
            return {"ok": False, "text": x.meldung + (f" – {x.hinweis}" if x.hinweis else "")}
        try:
            system.geheimnis_schreiben(system.TOKEN_ZIEL, token)
        except OSError as x:
            return {"ok": False, "text": f"Token ließ sich nicht speichern: {x}"}
        threading.Thread(target=self._d.pruefen, daemon=True).start()
        return {"ok": True, "text": f"Verbunden als {name}. Die Liste wird neu geladen."}

    def token_entfernen(self):
        system.geheimnis_loeschen(system.TOKEN_ZIEL)
        threading.Thread(target=self._d.pruefen, daemon=True).start()
        return True

    def datenordner(self):
        system.oeffne_ordner(str(self._d.ordner))
        return True

    def protokoll(self):
        p = self._d.ordner / "werkstatt.log"
        if p.exists():
            system.oeffne_ordner(str(p))
        return True

    def ablage(self):
        p = system.bekannter_ordner("downloads") / "Honeys Werkstatt"
        p.mkdir(parents=True, exist_ok=True)
        system.oeffne_ordner(str(p))
        return True


def main():
    args = sys.argv[1:]
    if args[:1] == ["--selbsttest"]:
        protokoll_einrichten()
        from .selbsttest import ausfuehren
        sys.exit(ausfuehren(Path(args[1] if len(args) > 1 else "selbsttest.json"), WURZEL, UI, APPID, version()))

    befehl = {"befehl": "zeigen"}
    if "--web" in args and args.index("--web") + 1 < len(args):
        befehl = {"befehl": "web", "id": args[args.index("--web") + 1]}
    elif "--beenden" in args:
        befehl = {"befehl": "beenden"}
    elif "--hintergrund" in args:
        befehl = {"befehl": "nichts"}
    if an_laufende_senden(befehl):
        return
    if befehl["befehl"] == "beenden":
        return

    protokoll_einrichten()
    log.info("Start %s %s", version(), " ".join(args))
    system.setze_app_id()
    try:
        import webview  # noqa: F401
    except ImportError:
        system.meldung("Für das Programmfenster fehlt „pywebview“.")
        sys.exit(1)

    dienst = Dienst(version(), APPID, eingebauter_katalog())
    if not dienst.e["eingerichtet"]:
        dienst.e["eingerichtet"] = True
        if FROZEN and dienst.e["autostart"]:
            system.autostart_setzen(True, autostart_befehl())
    elif FROZEN and system.autostart_eingetragen():
        system.autostart_setzen(True, autostart_befehl())  # Pfad aktuell halten

    holder: dict = {}
    server = Server(UI, dienst.ordner / "web", (UI / "bruecke.js").read_text(encoding="utf-8"),
                    befehl=lambda b: holder["w"].befehl(b))
    server.starten()
    instanz_merken(server.port, server.geheim, os.getpid())

    versteckt = "--hintergrund" in args
    w = Werkstatt(dienst, server, versteckt)
    holder["w"] = w
    w.infobereich()
    threading.Thread(target=w.hintergrund, name="hintergrund", daemon=True).start()
    if not versteckt or time.time() - float(dienst.e["zuletzt"] or 0) > 15 * 60:
        threading.Thread(target=dienst.pruefen, daemon=True).start()
    if befehl["befehl"] == "web":
        threading.Thread(target=w.web_oeffnen, args=(befehl["id"],), daemon=True).start()

    import webview
    try:
        webview.start(private_mode=False, storage_path=str(dienst.ordner / "webview"))
    finally:
        w.beenden_laeuft = True
        if w.tray is not None:
            try:
                w.tray.stop()
            except Exception:  # noqa: BLE001
                pass
        server.stoppen()
        log.info("Ende")


if __name__ == "__main__":
    main()
