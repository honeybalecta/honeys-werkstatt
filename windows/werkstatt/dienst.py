"""
Die Werkstatt selbst: Katalog, Stände, Warteschlange für Downloads und Installationen, Web-Werkzeuge.
Die Oberfläche fragt den Zustand ab und ruft Aktionen auf; alles Langsame läuft in eigenen Threads.
"""
from __future__ import annotations

import base64
import io
import json
import logging
import queue
import threading
import time
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path
from typing import Callable, Optional

from . import kern, system
from .kern import Abgebrochen, Eintrag, WerkstattFehler

log = logging.getLogger("werkstatt")


class Einstellungen:
    STANDARD = {"autostart": True, "hintergrund": True, "auto": False, "zuletzt": 0.0, "katalog": None,
                "gemeldet": "", "nachweise": {}, "ausstehend": {}, "web": {}, "eingerichtet": False,
                "hinweis_infobereich": False}

    def __init__(self, datei: Path):
        self._datei = datei
        self._lock = threading.RLock()
        try:
            geladen = json.loads(datei.read_text(encoding="utf-8"))
        except (OSError, ValueError):
            geladen = {}
        self._d = {**json.loads(json.dumps(self.STANDARD)), **(geladen if isinstance(geladen, dict) else {})}

    def __getitem__(self, k):
        with self._lock:
            return self._d.get(k, self.STANDARD.get(k))

    def __setitem__(self, k, v):
        with self._lock:
            self._d[k] = v
            self._speichern()

    def aendere(self, k, f: Callable[[dict], None]):
        with self._lock:
            wert = self._d.setdefault(k, {})
            f(wert)
            self._speichern()

    def _speichern(self):
        tmp = self._datei.with_suffix(".neu")
        tmp.write_text(json.dumps(self._d, ensure_ascii=False, indent=1), encoding="utf-8")
        tmp.replace(self._datei)


class Dienst:
    def __init__(self, version: str, selbst_appid: str, eingebauter_katalog: Optional[str]):
        self.version = version
        self.selbst_appid = selbst_appid
        self.ordner = system.daten_ordner()
        self.e = Einstellungen(self.ordner / "einstellungen.json")
        self._lock = threading.RLock()
        self._pruef_lock = threading.Lock()
        self.eintraege: list[Eintrag] = []
        self.freigaben: dict[str, kern.Freigabe] = {}
        self.fehler: dict[str, WerkstattFehler] = {}
        self.web_updates: dict[str, bool] = {}
        self.vorgaenge: dict[str, dict] = {}
        self._entscheidungen: dict[str, tuple[threading.Event, list]] = {}
        self._abgebrochen: set[str] = set()
        self._symbole: dict[str, Optional[str]] = {}
        self.prueft = False
        self.sichtbar = False
        self.melden: Callable[[str, str], None] = lambda titel, text: None
        self.selbst_update: Callable[[Path, Path], None] = lambda setup, protokoll: None
        self._reihe: "queue.Queue[tuple[str, bool]]" = queue.Queue()

        text = self.e["katalog"] or eingebauter_katalog
        if text:
            try:
                self.eintraege = kern.lies_katalog(text)
            except ValueError:
                self.eintraege = kern.lies_katalog(eingebauter_katalog or '{"apps":[]}')
        self._abschliessen_nach_update()
        threading.Thread(target=self._arbeiter, name="installieren", daemon=True).start()

    # ------------------------------------------------------------------ Hilfen

    def token(self) -> Optional[str]:
        try:
            return system.geheimnis_lesen(system.TOKEN_ZIEL)
        except Exception:  # noqa: BLE001
            return None

    def eintrag(self, id_: str) -> Optional[Eintrag]:
        return next((e for e in self.eintraege if e.id == id_), None)

    def ist_selbst(self, e: Eintrag) -> bool:
        return bool(e.appid) and e.appid.lower() == self.selbst_appid.lower()

    def setze_vorgang(self, id_: str, v: Optional[dict]):
        with self._lock:
            if v is None:
                self.vorgaenge.pop(id_, None)
            else:
                self.vorgaenge[id_] = v

    # ------------------------------------------------------------------ Web-Werkzeuge

    def web_ordner(self, e: Eintrag) -> Path:
        return self.ordner / "web" / e.id

    def web_bereit(self, e: Eintrag) -> bool:
        return (self.web_ordner(e) / e.start).is_file()

    def _web_pfad(self, e: Eintrag, datei: str) -> Path:
        wurzel = self.web_ordner(e).resolve()
        ziel = (wurzel / datei).resolve()
        if wurzel not in ziel.parents:
            raise WerkstattFehler(f"Ungültiger Dateipfad im Katalog: {datei}")
        return ziel

    def web_gibt_update(self, e: Eintrag, token: Optional[str]) -> bool:
        stand = self.e["web"].get(e.id, {})
        for d in e.dateien:
            if kern.inhalt_sha(e.repo, d, token) != stand.get(d) or not self._web_pfad(e, d).is_file():
                return True
        return False

    def web_aktualisiere(self, e: Eintrag, token: Optional[str]) -> None:
        for d in e.dateien:
            ziel = self._web_pfad(e, d)
            sha = kern.inhalt_sha(e.repo, d, token)
            if sha == self.e["web"].get(e.id, {}).get(d) and ziel.is_file():
                continue
            kern.lade_inhalt(e.repo, d, token, ziel)
            self.e.aendere("web", lambda w: w.setdefault(e.id, {}).__setitem__(d, sha))
        self.web_updates[e.id] = False

    def web_entfernen(self, e: Eintrag) -> None:
        import shutil
        shutil.rmtree(self.web_ordner(e), ignore_errors=True)
        self.e.aendere("web", lambda w: w.pop(e.id, None))
        for lnk in self.verknuepfungen(e):
            try:
                lnk.unlink()
            except OSError:
                pass

    def verknuepfungen(self, e: Eintrag) -> list[Path]:
        return [system.bekannter_ordner("desktop") / f"{e.name}.lnk",
                system.bekannter_ordner("programme") / "Honeys Werkstatt" / f"{e.name}.lnk"]

    # ------------------------------------------------------------------ Zustand für die Oberfläche

    def _symbol(self, e: Eintrag, exe: Optional[str]) -> Optional[str]:
        if not exe:
            return None
        schluessel = f"{exe}|{Path(exe).stat().st_mtime if Path(exe).exists() else 0}"
        if schluessel in self._symbole:
            return self._symbole[schluessel]
        self._symbole[schluessel] = None

        def holen():
            try:
                bild = system.programm_symbol(exe, 96)
                if bild is not None:
                    puffer = io.BytesIO()
                    bild.save(puffer, "PNG")
                    self._symbole[schluessel] = "data:image/png;base64," + base64.b64encode(puffer.getvalue()).decode()
            except Exception:  # noqa: BLE001
                log.exception("Symbol für %s", exe)
        threading.Thread(target=holen, daemon=True).start()
        return None

    def zustand_app(self, e: Eintrag) -> dict:
        d = {"id": e.id, "name": e.name, "typ": e.typ, "beschreibung": e.beschreibung, "github": e.github_url,
             "selbst": self.ist_selbst(e), "vorgang": self.vorgaenge.get(e.id), "symbol": None,
             "fehler": None, "update": False, "installiert": None, "neu": None, "groesse": None, "bereit": False}
        f = self.fehler.get(e.id)
        if f:
            d["fehler"] = {"meldung": f.meldung, "hinweis": f.hinweis}
        if e.typ == "web":
            d["bereit"] = self.web_bereit(e)
            d["update"] = d["bereit"] and self.web_updates.get(e.id, False)
            d["verknuepft"] = any(p.exists() for p in self.verknuepfungen(e))
            return d
        inst = system.installiert(e.appid, e.exe)
        frei = self.freigaben.get(e.id)
        if inst:
            d["installiert"] = inst.version
            d["symbol"] = self._symbol(e, inst.exe)
            d["oeffnen"] = bool(inst.exe)
        if frei:
            d["neu"] = frei.version
            d["groesse"] = frei.datei.groesse
        d["update"] = kern.braucht_update(inst, frei, self.e["nachweise"].get(e.appid or ""))
        return d

    def zustand(self) -> dict:
        with self._lock:
            apps = [self.zustand_app(e) for e in self.eintraege]
        apps.sort(key=lambda a: 1 if a["selbst"] else 0)
        return {"apps": apps, "prueft": self.prueft, "zuletzt": self.e["zuletzt"], "version": self.version,
                "token": self.token() is not None}

    # ------------------------------------------------------------------ Prüfen

    def pruefen(self) -> bool:
        """Katalog und neueste Stände holen. False, wenn gar nichts erreichbar war."""
        if not self._pruef_lock.acquire(blocking=False):
            while self.prueft:
                time.sleep(0.2)
            return True
        self.prueft = True
        try:
            token = self.token()
            erreichbar = False
            try:
                text = kern.roh_text(kern.KATALOG_REPO, "katalog.json", token)
                neu = kern.lies_katalog(text)
                if neu:
                    with self._lock:
                        self.eintraege = neu
                    self.e["katalog"] = text
                erreichbar = True
            except (WerkstattFehler, ValueError) as x:
                log.warning("Katalog: %s", x)

            def eins(e: Eintrag) -> bool:
                try:
                    if e.typ == "web":
                        self.web_updates[e.id] = self.web_gibt_update(e, token)
                    else:
                        f = kern.neueste_freigabe(e.repo, e.muster, token)
                        if not f:
                            raise WerkstattFehler("Noch kein Windows-Installer im Release",
                                                  f"Im Repo {e.repo} gibt es kein Release mit passender EXE.")
                        self.freigaben[e.id] = f
                    self.fehler.pop(e.id, None)
                    return True
                except WerkstattFehler as x:
                    self.fehler[e.id] = x
                except Exception as x:  # noqa: BLE001
                    log.exception("Prüfen %s", e.id)
                    self.fehler[e.id] = WerkstattFehler("Abfrage fehlgeschlagen", str(x))
                return False

            with ThreadPoolExecutor(max_workers=6) as pool:
                erreichbar = any(list(pool.map(eins, list(self.eintraege)))) or erreichbar
            if erreichbar:
                self.e["zuletzt"] = time.time()
            return erreichbar
        finally:
            self.prueft = False
            self._pruef_lock.release()

    def updates(self) -> list[dict]:
        return [a for a in self.zustand()["apps"] if a["update"]]

    # ------------------------------------------------------------------ Warteschlange

    def starte(self, ids: list[str], unbeaufsichtigt: bool = False) -> None:
        reihe = sorted(ids, key=lambda i: 1 if (self.eintrag(i) and self.ist_selbst(self.eintrag(i))) else 0)
        for id_ in reihe:
            v = self.vorgaenge.get(id_)
            if v and v.get("art") not in ("fehler", "fertig"):
                continue  # läuft schon
            self._abgebrochen.discard(id_)
            self.setze_vorgang(id_, {"art": "wartet"})
            self._reihe.put((id_, unbeaufsichtigt))

    def abbrechen(self, id_: str) -> None:
        self._abgebrochen.add(id_)
        self.entscheide(id_, "abbrechen")
        v = self.vorgaenge.get(id_)
        if v and v.get("art") in ("wartet", "fehler", "fertig"):
            self.setze_vorgang(id_, None)

    def entscheide(self, id_: str, wahl: str) -> None:
        warten = self._entscheidungen.get(id_)
        if warten:
            warten[1].append(wahl)
            warten[0].set()

    def _warte_auf_entscheidung(self, id_: str, vorgang: dict, minuten: int = 15) -> str:
        """Erst bereit machen, dann der Oberfläche die Frage zeigen – so geht kein schneller Klick verloren."""
        ereignis = threading.Event()
        self._entscheidungen[id_] = (ereignis, [])
        self.setze_vorgang(id_, vorgang)
        try:
            if not ereignis.wait(minuten * 60):
                return "abbrechen"
            return self._entscheidungen[id_][1][-1]
        finally:
            self._entscheidungen.pop(id_, None)

    def leer(self) -> bool:
        return self._reihe.empty() and not any(v.get("art") in ("wartet", "laedt", "einspielen", "geoeffnet", "schliesst", "haengt")
                                               for v in list(self.vorgaenge.values()))

    def _arbeiter(self):
        while True:
            id_, unbeaufsichtigt = self._reihe.get()
            try:
                self.bearbeite(id_, unbeaufsichtigt)
            except Exception:  # noqa: BLE001
                log.exception("Auftrag %s", id_)

    def bearbeite(self, id_: str, unbeaufsichtigt: bool) -> None:
        e = self.eintrag(id_)
        if e is None or id_ in self._abgebrochen:
            self.setze_vorgang(id_, None)
            return
        try:
            if e.typ == "web":
                self.setze_vorgang(id_, {"art": "laedt", "gelesen": 0, "gesamt": 0})
                self.web_aktualisiere(e, self.token())
                self.fehler.pop(id_, None)
                self.setze_vorgang(id_, {"art": "fertig", "text": "Aktuell"})
            else:
                self._installiere(e, unbeaufsichtigt)
        except Abgebrochen:
            self.setze_vorgang(id_, None)
        except WerkstattFehler as x:
            log.warning("%s: %s (%s)", id_, x.meldung, x.hinweis)
            self.setze_vorgang(id_, {"art": "fehler", "meldung": x.meldung, "hinweis": x.hinweis})
            if unbeaufsichtigt or not self.sichtbar:
                self.melden(f"{e.name}: Update hat nicht geklappt", x.meldung)
        except Exception as x:  # noqa: BLE001
            log.exception("Installation %s", id_)
            self.setze_vorgang(id_, {"art": "fehler", "meldung": "Unerwarteter Fehler", "hinweis": str(x)[:300]})

    def _installiere(self, e: Eintrag, unbeaufsichtigt: bool) -> None:
        token = self.token()
        frei = self.freigaben.get(e.id) or kern.neueste_freigabe(e.repo, e.muster, token)
        if not frei:
            raise WerkstattFehler("Kein Windows-Installer im Release gefunden")
        self.freigaben[e.id] = frei
        if not system.WIN:
            raise WerkstattFehler("Installieren geht nur unter Windows")

        ziel = self.ordner / "downloads" / f"{e.id}-{frei.datei.id}.exe"
        self.setze_vorgang(e.id, {"art": "laedt", "gelesen": 0, "gesamt": frei.datei.groesse})
        kern.lade(frei.datei, token, ziel,
                  fortschritt=lambda g, t: self.setze_vorgang(e.id, {"art": "laedt", "gelesen": g, "gesamt": t}),
                  abbrechen=lambda: e.id in self._abgebrochen)
        try:
            with open(ziel, "rb") as f:
                if f.read(2) != b"MZ":
                    raise WerkstattFehler("Die Datei im Release ist kein Windows-Programm")
            protokoll = self.ordner / "downloads" / f"{e.id}-installation.log"

            if self.ist_selbst(e):
                start = time.time()
                self.e.aendere("ausstehend", lambda a: a.__setitem__(e.appid, {"datei": frei.datei.id, "stand": frei.datei.aktualisiert, "start": start}))
                self.setze_vorgang(e.id, {"art": "einspielen", "selbst": True})
                time.sleep(0.8)  # damit die Oberfläche den Hinweis noch zeigt
                self.selbst_update(ziel, protokoll)
                return

            inst = system.installiert(e.appid, e.exe)
            if inst and inst.ordner:
                self._programm_schliessen(e, inst.ordner, unbeaufsichtigt)
            if e.id in self._abgebrochen:
                raise Abgebrochen()

            self.setze_vorgang(e.id, {"art": "einspielen"})
            code, text = system.installer_ausfuehren(ziel, protokoll)
            if code != 0:
                raise WerkstattFehler(text or "Installation fehlgeschlagen",
                                      "Läuft das Programm noch oder blockiert ein Virenschutz die Datei? Das Protokoll liegt im Datenordner der Werkstatt.")
            neu = system.installiert(e.appid, e.exe)
            if neu:
                self.e.aendere("nachweise", lambda n: n.__setitem__(e.appid, {"datei": frei.datei.id, "stand": frei.datei.aktualisiert, "version": neu.version}))
            self.fehler.pop(e.id, None)
            self.setze_vorgang(e.id, {"art": "fertig", "text": "Installiert" if inst is None else "Aktualisiert"})
            if not self.sichtbar:
                self.melden(f"{e.name} aktualisiert", f"Jetzt Version {neu.version}" if neu else "Fertig")
        finally:
            if not self.ist_selbst(e):
                try:
                    ziel.unlink()
                except OSError:
                    pass

    def _programm_schliessen(self, e: Eintrag, ordner: str, unbeaufsichtigt: bool) -> None:
        pids = system.prozesse_in(ordner)
        if not pids:
            return
        if unbeaufsichtigt:
            raise WerkstattFehler(f"{e.name} ist geöffnet", "Das Update wird eingespielt, sobald das Programm geschlossen ist.")
        if self._warte_auf_entscheidung(e.id, {"art": "geoeffnet"}) != "schliessen":
            raise Abgebrochen()
        self.setze_vorgang(e.id, {"art": "schliesst"})
        system.fenster_schliessen(pids)
        rest = system.warte_bis_beendet(ordner, 25)
        if rest:
            if self._warte_auf_entscheidung(e.id, {"art": "haengt"}) != "erzwingen":
                raise Abgebrochen()
            system.beenden_erzwingen(rest)
            if system.warte_bis_beendet(ordner, 15):
                raise WerkstattFehler(f"{e.name} ließ sich nicht beenden", "Bitte den Rechner neu starten und es dann noch einmal versuchen.")

    def _abschliessen_nach_update(self) -> None:
        """Nach einem Selbst-Update startet die Werkstatt neu: Nachweis für die neue Fassung ablegen."""
        for appid, a in list((self.e["ausstehend"] or {}).items()):
            inst = system.installiert(appid)
            if inst and inst.zeit >= float(a.get("start", 0)) - 5:
                self.e.aendere("nachweise", lambda n: n.__setitem__(appid, {"datei": a["datei"], "stand": a["stand"], "version": inst.version}))
            if (inst and inst.zeit >= float(a.get("start", 0)) - 5) or time.time() - float(a.get("start", 0)) > 1800:
                self.e.aendere("ausstehend", lambda x: x.pop(appid, None))

    # ------------------------------------------------------------------ Hintergrund

    def hintergrund_runde(self) -> None:
        if not self.pruefen():
            return
        offen = self.updates()
        if offen and self.e["auto"]:
            still = [a["id"] for a in offen]
            self.starte(still, unbeaufsichtigt=True)
            # warten, bis die Warteschlange leer ist (höchstens eine Stunde)
            ende = time.time() + 3600
            while not self.leer() and time.time() < ende:
                time.sleep(2)
            offen = self.updates()
        kennung = ",".join(f"{a['id']}@{a['neu']}" for a in offen)
        if offen and kennung != self.e["gemeldet"] and not self.sichtbar:
            namen = [a["name"] for a in offen]
            titel = f"Update für {namen[0]}" if len(namen) == 1 else f"{len(namen)} Updates bereit"
            self.melden(titel, ", ".join(namen))
        self.e["gemeldet"] = kennung
