"""
Plattformneutraler Kern der Windows-Werkstatt: Katalog lesen, GitHub fragen, Versionen vergleichen.
Läuft auch unter Linux (für die Tests).
"""
from __future__ import annotations

import hashlib
import http.client
import json
import re
import ssl
import time
import urllib.error
import urllib.parse
import urllib.request
from dataclasses import dataclass, field
from datetime import datetime
from pathlib import Path
from typing import Callable, Optional

API = "https://api.github.com"
KATALOG_REPO = "honeybalecta/honeys-werkstatt"


class WerkstattFehler(Exception):
    """Fehler mit Erklärung, die so in der Oberfläche stehen kann."""

    def __init__(self, meldung: str, hinweis: Optional[str] = None):
        super().__init__(meldung)
        self.meldung = meldung
        self.hinweis = hinweis


class Abgebrochen(Exception):
    pass


# --------------------------------------------------------------------------- Katalog

@dataclass
class Eintrag:
    id: str
    name: str
    typ: str                      # "app" (Installer) oder "web"
    repo: str
    beschreibung: str = ""
    muster: Optional[re.Pattern] = None   # Dateiname des Installers im Release
    appid: Optional[str] = None           # Inno-Setup-AppId mit geschweiften Klammern
    exe: Optional[str] = None             # Programmdatei im Installationsordner
    dateien: list = field(default_factory=list)
    start: str = "index.html"

    @property
    def github_url(self) -> str:
        return f"https://github.com/{self.repo}" + ("/releases" if self.typ == "app" else "")


def lies_katalog(text: str) -> list[Eintrag]:
    """Nur Einträge, die unter Windows laufen: Web-Werkzeuge und alles mit windows-Block."""
    daten = json.loads(text)
    liste: list[Eintrag] = []
    for a in daten.get("apps") or []:
        try:
            if a.get("typ") == "web":
                start = (a.get("start") or "index.html").strip() or "index.html"
                dateien = [str(d) for d in (a.get("dateien") or [])] or [start]
                liste.append(Eintrag(id=a["id"], name=a["name"], typ="web", repo=a["repo"],
                                     beschreibung=a.get("beschreibung", ""), dateien=dateien, start=start))
                continue
            w = a.get("windows")
            if not isinstance(w, dict) or not w.get("datei"):
                continue  # reine Android-App
            liste.append(Eintrag(id=a["id"], name=a["name"], typ="app", repo=a["repo"],
                                 beschreibung=a.get("beschreibung", ""), muster=re.compile(w["datei"]),
                                 appid=(w.get("appid") or "").strip() or None, exe=(w.get("exe") or "").strip() or None))
        except (KeyError, TypeError, re.error):
            continue
    return liste


# --------------------------------------------------------------------------- Versionen

_ZAHL = re.compile(r"\d+(\.\d+)*")


def zahl_teil(s: Optional[str]) -> Optional[str]:
    if not s:
        return None
    m = _ZAHL.search(s)
    return m.group(0) if m else None


def vergleiche(a: Optional[str], b: Optional[str]) -> Optional[int]:
    """-1/0/1, wenn beide gleich aufgebaut sind; sonst None (nicht vergleichbar)."""
    x, y = zahl_teil(a), zahl_teil(b)
    if x is None or y is None:
        return None
    xs, ys = [int(t) for t in x.split(".")], [int(t) for t in y.split(".")]
    if len(xs) != len(ys):
        return None
    return (xs > ys) - (xs < ys)


def zeit(s: Optional[str]) -> float:
    if not s:
        return 0.0
    try:
        return datetime.fromisoformat(s.replace("Z", "+00:00")).timestamp()
    except ValueError:
        return 0.0


# --------------------------------------------------------------------------- GitHub

@dataclass
class Datei:
    id: int
    name: str
    groesse: int
    aktualisiert: float
    api_url: str
    sha256: Optional[str]


@dataclass
class Freigabe:
    tag: str
    titel: str
    veroeffentlicht: float
    datei: Datei

    @property
    def version(self) -> str:
        return zahl_teil(self.tag) or self.tag


class _OhneWeiterleitung(urllib.request.HTTPRedirectHandler):
    """Weiterleitungen selbst behandeln: Der Token darf nicht zum Speicher-Server mit."""

    def redirect_request(self, req, fp, code, msg, headers, newurl):  # noqa: D401
        return None


_ssl = ssl.create_default_context()
_oeffner = urllib.request.build_opener(_OhneWeiterleitung, urllib.request.HTTPSHandler(context=_ssl))
_oeffner_frei = urllib.request.build_opener(urllib.request.HTTPSHandler(context=_ssl))

USER_AGENT = "HoneysWerkstatt-Windows"


def _anfrage(url: str, token: Optional[str], accept: str) -> urllib.request.Request:
    h = {"Accept": accept, "User-Agent": USER_AGENT, "X-GitHub-Api-Version": "2022-11-28"}
    if token and urllib.parse.urlparse(url).hostname == "api.github.com":
        h["Authorization"] = f"Bearer {token}"
    return urllib.request.Request(url, headers=h)


def _fehler(e: urllib.error.HTTPError, repo: Optional[str]) -> WerkstattFehler:
    rest = e.headers.get("x-ratelimit-remaining") if e.headers else None
    if e.code == 401:
        return WerkstattFehler("GitHub lehnt den Token ab",
                               "Der Token ist abgelaufen oder falsch. Lege in den Einstellungen einen neuen an.")
    if e.code in (403, 429) and rest == "0":
        return WerkstattFehler("Abfragegrenze von GitHub erreicht",
                               "Ohne Token sind 60 Abfragen pro Stunde erlaubt. Mit Token sind es deutlich mehr.")
    if e.code == 404:
        return WerkstattFehler(f"Repo {repo} nicht erreichbar" if repo else "Nicht gefunden",
                               "Bei privaten Repos braucht die Werkstatt einen Lesetoken mit Zugriff auf dieses Repo (Einstellungen).")
    return WerkstattFehler(f"GitHub antwortet mit Fehler {e.code}")


def _offline(e: Exception) -> WerkstattFehler:
    return WerkstattFehler("Keine Verbindung zu GitHub", str(getattr(e, "reason", e)))


def _text(pfad: str, token: Optional[str], repo: Optional[str], accept: str = "application/vnd.github+json") -> str:
    try:
        with _oeffner.open(_anfrage(API + pfad, token, accept), timeout=30) as r:
            return r.read().decode("utf-8")
    except urllib.error.HTTPError as e:
        raise _fehler(e, repo) from None
    except (urllib.error.URLError, OSError, http.client.HTTPException) as e:
        raise _offline(e) from None


def roh_text(repo: str, pfad: str, token: Optional[str]) -> str:
    return _text(f"/repos/{repo}/contents/{urllib.parse.quote(pfad.lstrip('/'))}", token, repo,
                 "application/vnd.github.raw+json")


def inhalt_sha(repo: str, pfad: str, token: Optional[str]) -> str:
    o = json.loads(_text(f"/repos/{repo}/contents/{urllib.parse.quote(pfad.lstrip('/'))}", token, repo))
    return o["sha"]


def waehle_freigabe(releases: list, muster: re.Pattern, endung: str = ".exe") -> Optional[Freigabe]:
    """Neueste veröffentlichte Freigabe mit passender Datei (Liste wie von der API, neueste zuerst)."""
    for r in releases:
        if r.get("draft") or r.get("prerelease"):
            continue
        beste: Optional[Datei] = None
        for a in r.get("assets") or []:
            name = a.get("name", "")
            if not name.lower().endswith(endung) or not muster.search(name):
                continue
            if a.get("state", "uploaded") != "uploaded":
                continue
            digest = a.get("digest") or ""
            d = Datei(id=int(a["id"]), name=name, groesse=int(a.get("size") or 0),
                      aktualisiert=zeit(a.get("updated_at")), api_url=a["url"],
                      sha256=digest[7:] if digest.startswith("sha256:") else None)
            if beste is None or d.aktualisiert > beste.aktualisiert:
                beste = d
        if beste is not None:
            return Freigabe(tag=r["tag_name"], titel=r.get("name") or r["tag_name"],
                            veroeffentlicht=zeit(r.get("published_at")), datei=beste)
    return None


def neueste_freigabe(repo: str, muster: re.Pattern, token: Optional[str]) -> Optional[Freigabe]:
    for seite in (1, 2, 3):
        arr = json.loads(_text(f"/repos/{repo}/releases?per_page=50&page={seite}", token, repo))
        if not arr:
            return None
        f = waehle_freigabe(arr, muster)
        if f:
            return f
        if len(arr) < 50:
            return None
    return None


def pruefe_token(token: str) -> str:
    return json.loads(_text("/user", token, None)).get("login", "")


def lade(datei: Datei, token: Optional[str], ziel: Path, fortschritt: Callable[[int, int], None],
         abbrechen: Callable[[], bool]) -> None:
    """Lädt eine Release-Datei; prüft Größe und Prüfsumme. Bei Fehler bleibt keine halbe Datei liegen."""
    url = datei.api_url
    antwort = None
    try:
        for _ in range(6):
            try:
                antwort = _oeffner.open(_anfrage(url, token, "application/octet-stream"), timeout=60)
                break
            except urllib.error.HTTPError as e:
                if e.code in (301, 302, 303, 307, 308) and e.headers.get("Location"):
                    url = urllib.parse.urljoin(url, e.headers["Location"])
                    continue
                raise _fehler(e, None) from None
            except (urllib.error.URLError, OSError, http.client.HTTPException) as e:
                raise _offline(e) from None
        if antwort is None:
            raise WerkstattFehler("Zu viele Weiterleitungen")
        gesamt = int(antwort.headers.get("Content-Length") or 0) or datei.groesse
        ziel.parent.mkdir(parents=True, exist_ok=True)
        h = hashlib.sha256()
        gelesen, zuletzt = 0, 0.0
        with open(ziel, "wb") as aus:
            while True:
                if abbrechen():
                    raise Abgebrochen()
                try:
                    stueck = antwort.read(512 * 1024)
                except (OSError, http.client.HTTPException) as e:
                    raise WerkstattFehler("Download unterbrochen", str(e)) from None
                if not stueck:
                    break
                aus.write(stueck)
                h.update(stueck)
                gelesen += len(stueck)
                jetzt = time.monotonic()
                if jetzt - zuletzt > 0.25:
                    zuletzt = jetzt
                    fortschritt(gelesen, gesamt)
        fortschritt(gelesen, gesamt)
        if datei.groesse and gelesen != datei.groesse:
            raise WerkstattFehler(f"Download unvollständig ({gelesen} von {datei.groesse} Bytes)")
        if datei.sha256 and h.hexdigest().lower() != datei.sha256.lower():
            raise WerkstattFehler("Prüfsumme stimmt nicht",
                                  "Die Datei ist beim Laden beschädigt worden. Bitte noch einmal versuchen.")
    except BaseException:
        try:
            ziel.unlink()
        except OSError:
            pass
        raise
    finally:
        if antwort is not None:
            antwort.close()


def lade_inhalt(repo: str, pfad: str, token: Optional[str], ziel: Path) -> None:
    url = f"{API}/repos/{repo}/contents/{urllib.parse.quote(pfad.lstrip('/'))}"
    try:
        with _oeffner.open(_anfrage(url, token, "application/vnd.github.raw+json"), timeout=60) as r:
            daten = r.read()
    except urllib.error.HTTPError as e:
        raise _fehler(e, repo) from None
    except (urllib.error.URLError, OSError, http.client.HTTPException) as e:
        raise _offline(e) from None
    ziel.parent.mkdir(parents=True, exist_ok=True)
    tmp = ziel.with_name(ziel.name + ".neu")
    tmp.write_bytes(daten)
    tmp.replace(ziel)


# --------------------------------------------------------------------------- Update-Entscheidung

@dataclass
class Installiert:
    version: str
    zeit: float            # wann zuletzt installiert (Änderung des Uninstall-Schlüssels)
    ordner: Optional[str]
    exe: Optional[str]
    deinstallieren: Optional[str]


def braucht_update(inst: Optional[Installiert], frei: Optional[Freigabe], nachweis: Optional[dict]) -> bool:
    """
    Nachweis = was die Werkstatt zuletzt installiert hat ({datei, stand, version}).
    Ohne Nachweis entscheidet die Versionsnummer, bei gleicher Nummer das Hochladedatum.
    """
    if inst is None or frei is None:
        return False
    if nachweis and nachweis.get("version") == inst.version:
        return nachweis.get("datei") != frei.datei.id or abs(float(nachweis.get("stand", 0)) - frei.datei.aktualisiert) > 1
    v = vergleiche(frei.version, inst.version)
    if v is not None and v != 0:
        return v > 0
    return frei.datei.aktualisiert > inst.zeit + 600
