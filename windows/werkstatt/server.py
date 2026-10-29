"""
Lokaler Server (nur 127.0.0.1): liefert die Oberfläche und die Web-Werkzeuge aus,
baut in deren Seiten die Brücke zum Speichern ein und nimmt Befehle einer zweiten Instanz an.
"""
from __future__ import annotations

import http.server
import json
import logging
import mimetypes
import re
import secrets
import socket
import threading
import urllib.parse
from pathlib import Path
from typing import Callable, Optional

from . import system

log = logging.getLogger("werkstatt")

FESTER_PORT = 47361  # fest, damit Entwürfe der Web-Werkzeuge (Browserspeicher) erhalten bleiben
MAX_DATEI = 2 * 1024 ** 3

TYPEN = {".html": "text/html", ".htm": "text/html", ".js": "text/javascript", ".mjs": "text/javascript",
         ".css": "text/css", ".json": "application/json", ".svg": "image/svg+xml", ".woff2": "font/woff2",
         ".woff": "font/woff", ".ttf": "font/ttf", ".wasm": "application/wasm", ".png": "image/png",
         ".jpg": "image/jpeg", ".jpeg": "image/jpeg", ".webp": "image/webp", ".ico": "image/x-icon",
         ".onnx": "application/octet-stream", ".bin": "application/octet-stream"}


def typ(name: str) -> str:
    endung = Path(name).suffix.lower()
    return TYPEN.get(endung) or mimetypes.guess_type(name)[0] or "application/octet-stream"


def sauberer_name(name: str) -> str:
    name = re.sub(r'[\\/:*?"<>|\x00-\x1f]', "_", (name or "").strip()).strip(" .")
    if not name or name.upper().split(".")[0] in {"CON", "PRN", "AUX", "NUL", *(f"COM{i}" for i in range(10)), *(f"LPT{i}" for i in range(10))}:
        name = "datei" + (Path(name).suffix if name else "")
    return name[:150]


def freier_pfad(ordner: Path, name: str) -> Path:
    ziel = ordner / name
    stamm, endung = Path(name).stem, Path(name).suffix
    n = 2
    while ziel.exists():
        ziel = ordner / f"{stamm} ({n}){endung}"
        n += 1
    return ziel


class Server:
    def __init__(self, ui_ordner: Path, web_ordner: Path, bruecke: str,
                 befehl: Callable[[dict], None], ablage: Optional[Callable[[], Path]] = None):
        self.ui_ordner = ui_ordner
        self.web_ordner = web_ordner
        self.bruecke = bruecke
        self.befehl = befehl
        self.ablage = ablage or (lambda: system.bekannter_ordner("downloads") / "Honeys Werkstatt")
        self.geheim = secrets.token_urlsafe(24)
        self.port = 0
        self._srv: Optional[http.server.ThreadingHTTPServer] = None

    @property
    def basis(self) -> str:
        return f"http://127.0.0.1:{self.port}"

    def starten(self, port: int = FESTER_PORT) -> int:
        handler = self._handler()
        for versuch in (port, 0):
            try:
                srv = http.server.ThreadingHTTPServer(("127.0.0.1", versuch), handler)
                break
            except OSError:
                continue
        else:
            raise OSError("Kein lokaler Port frei")
        srv.daemon_threads = True
        self._srv = srv
        self.port = srv.server_address[1]
        threading.Thread(target=srv.serve_forever, name="server", daemon=True).start()
        return self.port

    def stoppen(self):
        if self._srv:
            self._srv.shutdown()

    def einbauen(self, html: str) -> str:
        tag = ("<script>window.__WERKSTATT=" + json.dumps({"geheim": self.geheim}) + ";</script>"
               "<script>" + self.bruecke + "</script>")
        for muster in (r"<head[^>]*>", r"<html[^>]*>"):
            m = re.search(muster, html, re.IGNORECASE)
            if m:
                return html[:m.end()] + tag + html[m.end():]
        return tag + html

    def _handler(self):
        server = self

        class H(http.server.BaseHTTPRequestHandler):
            protocol_version = "HTTP/1.1"

            def log_message(self, *a):
                pass

            def _host_ok(self) -> bool:
                # Schutz vor DNS-Rebinding: nur Aufrufe an 127.0.0.1/localhost mit unserem Port
                host = (self.headers.get("Host") or "").lower()
                return host in (f"127.0.0.1:{server.port}", f"localhost:{server.port}")

            def _senden(self, code: int, daten: bytes, art: str = "text/plain; charset=utf-8", extra: Optional[dict] = None):
                self.send_response(code)
                self.send_header("Content-Type", art)
                self.send_header("Content-Length", str(len(daten)))
                self.send_header("Cache-Control", "no-store")
                self.send_header("X-Content-Type-Options", "nosniff")
                for k, v in (extra or {}).items():
                    self.send_header(k, v)
                self.end_headers()
                if self.command != "HEAD":
                    self.wfile.write(daten)

            def _json(self, code: int, obj):
                self._senden(code, json.dumps(obj, ensure_ascii=False).encode("utf-8"), "application/json; charset=utf-8")

            def _datei(self, wurzel: Path, rel: str, einbauen: bool):
                wurzel = wurzel.resolve()
                try:
                    ziel = (wurzel / urllib.parse.unquote(rel)).resolve()
                except (OSError, ValueError):
                    return self._senden(404, b"Nicht gefunden")
                if wurzel not in ziel.parents or not ziel.is_file():
                    return self._senden(404, b"Nicht gefunden")
                art = typ(ziel.name)
                if art == "text/html":
                    html = ziel.read_text(encoding="utf-8", errors="replace")
                    if einbauen:
                        html = server.einbauen(html)
                    return self._senden(200, html.encode("utf-8"), "text/html; charset=utf-8")
                daten = ziel.read_bytes()
                if art.startswith("text/") or art in ("application/json", "image/svg+xml"):
                    art += "; charset=utf-8"
                self._senden(200, daten, art)

            def do_HEAD(self):
                self.do_GET()

            def do_GET(self):
                if not self._host_ok():
                    return self._senden(403, b"Verboten")
                pfad = urllib.parse.urlsplit(self.path).path
                if pfad in ("/", "/ui", "/ui/"):
                    return self._senden(302, b"", extra={"Location": "/ui/index.html"})
                if pfad.startswith("/ui/"):
                    return self._datei(server.ui_ordner, pfad[4:], False)
                if pfad.startswith("/web/"):
                    return self._datei(server.web_ordner, pfad[5:], True)
                if pfad == "/__werkstatt/lebt":
                    return self._senden(200, b"Honeys Werkstatt")
                self._senden(404, b"Nicht gefunden")

            def do_POST(self):
                if not self._host_ok() or self.headers.get("X-Werkstatt") != server.geheim:
                    return self._senden(403, b"Verboten")
                pfad = urllib.parse.urlsplit(self.path).path
                laenge = int(self.headers.get("Content-Length") or 0)
                if pfad == "/__werkstatt/speichern":
                    return self._speichern(laenge)
                daten = self.rfile.read(min(laenge, 64 * 1024)) if laenge else b"{}"
                try:
                    obj = json.loads(daten or b"{}")
                except ValueError:
                    return self._senden(400, b"Kein JSON")
                if pfad == "/__werkstatt/ordner":
                    p = str(obj.get("pfad") or "")
                    ablage = server.ablage().resolve()
                    if p and ablage in Path(p).resolve().parents:
                        system.zeige_im_ordner(p)
                    return self._json(200, {"ok": True})
                if pfad == "/__steuer":
                    threading.Thread(target=server.befehl, args=(obj,), daemon=True).start()
                    return self._json(200, {"ok": True})
                self._senden(404, b"Nicht gefunden")

            def _speichern(self, laenge: int):
                if laenge <= 0 or laenge > MAX_DATEI:
                    return self._json(413, {"fehler": "Datei leer oder zu groß"})
                name = sauberer_name(urllib.parse.unquote(self.headers.get("X-Dateiname") or "datei"))
                ordner = server.ablage()
                try:
                    ordner.mkdir(parents=True, exist_ok=True)
                    ziel = freier_pfad(ordner, name)
                    rest = laenge
                    with open(ziel, "wb") as aus:
                        while rest > 0:
                            stueck = self.rfile.read(min(rest, 1024 * 1024))
                            if not stueck:
                                break
                            aus.write(stueck)
                            rest -= len(stueck)
                    if rest:
                        ziel.unlink(missing_ok=True)
                        return self._json(400, {"fehler": "Übertragung unvollständig"})
                except OSError as e:
                    log.exception("Speichern")
                    return self._json(500, {"fehler": e.strerror or str(e)})
                self._json(200, {"pfad": str(ziel), "anzeige": f"Downloads › Honeys Werkstatt › {ziel.name}"})

        return H


# --------------------------------------------------------------------------- Einzelinstanz

def instanz_datei() -> Path:
    return system.daten_ordner() / "instanz.json"


def an_laufende_senden(befehl: dict, zeitlimit: float = 3) -> bool:
    """Gibt den Befehl an eine laufende Werkstatt weiter. True, wenn eine lief."""
    import urllib.request
    try:
        info = json.loads(instanz_datei().read_text(encoding="utf-8"))
        port, geheim = int(info["port"]), info["geheim"]
    except (OSError, ValueError, KeyError):
        return False
    try:
        with socket.create_connection(("127.0.0.1", port), timeout=1):
            pass
    except OSError:
        return False
    req = urllib.request.Request(f"http://127.0.0.1:{port}/__steuer", data=json.dumps(befehl).encode(),
                                 headers={"X-Werkstatt": geheim, "Content-Type": "application/json"}, method="POST")
    try:
        with urllib.request.build_opener(urllib.request.ProxyHandler({})).open(req, timeout=zeitlimit) as r:
            return r.status == 200
    except OSError:
        return False


def instanz_merken(port: int, geheim: str, pid: int) -> None:
    datei = instanz_datei()
    tmp = datei.with_suffix(".neu")
    tmp.write_text(json.dumps({"port": port, "geheim": geheim, "pid": pid}), encoding="utf-8")
    tmp.replace(datei)
