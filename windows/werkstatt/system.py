"""
Alles, was nur unter Windows geht: Programmliste (Registry), Anmeldeinformationsverwaltung,
laufende Programme, stille Installation, Verknüpfungen, Autostart, Programmsymbole.
Unter anderen Systemen liefern die Funktionen leere Ergebnisse (für Tests).
"""
from __future__ import annotations

import ctypes
import os
import subprocess
import sys
import time
import uuid
from pathlib import Path
from typing import Optional

from .kern import Installiert

WIN = sys.platform == "win32"
APP_NAME = "HoneysWerkstatt"
AUMID = "honeybalecta.HoneysWerkstatt"
TOKEN_ZIEL = "HoneysWerkstatt/github"

CREATE_NO_WINDOW = 0x08000000
CREATE_NEW_PROCESS_GROUP = 0x00000200
CREATE_BREAKAWAY_FROM_JOB = 0x01000000

if WIN:
    import winreg
    from ctypes import wintypes as wt

    kernel32 = ctypes.WinDLL("kernel32", use_last_error=True)
    user32 = ctypes.WinDLL("user32", use_last_error=True)
    advapi32 = ctypes.WinDLL("advapi32", use_last_error=True)
    shell32 = ctypes.WinDLL("shell32", use_last_error=True)
    ole32 = ctypes.WinDLL("ole32", use_last_error=True)
    gdi32 = ctypes.WinDLL("gdi32", use_last_error=True)


def daten_ordner() -> Path:
    basis = os.environ.get("LOCALAPPDATA") if WIN else None
    p = Path(basis) / APP_NAME if basis else Path.home() / ".honeys-werkstatt"
    p.mkdir(parents=True, exist_ok=True)
    return p


def meldung(text: str, titel: str = "Honeys Werkstatt") -> None:
    if WIN:
        user32.MessageBoxW(None, text, titel, 0x40)
    else:
        print(f"{titel}: {text}", file=sys.stderr)


def setze_app_id() -> None:
    if WIN:
        try:
            shell32.SetCurrentProcessExplicitAppUserModelID(AUMID)
        except Exception:  # noqa: BLE001
            pass


# --------------------------------------------------------------------------- Programmliste

_UNINSTALL = r"Software\Microsoft\Windows\CurrentVersion\Uninstall"


def _wert(k, name: str) -> Optional[str]:
    try:
        v, _ = winreg.QueryValueEx(k, name)
        return str(v) if v is not None else None
    except OSError:
        return None


def installiert(appid: Optional[str], exe_name: Optional[str] = None) -> Optional[Installiert]:
    """Liest den Eintrag, den Inno Setup unter „Apps & Features“ anlegt ({AppId}_is1)."""
    if not WIN or not appid:
        return None
    schluessel = f"{_UNINSTALL}\\{appid}_is1"
    orte = [(winreg.HKEY_CURRENT_USER, 0), (winreg.HKEY_LOCAL_MACHINE, winreg.KEY_WOW64_64KEY),
            (winreg.HKEY_LOCAL_MACHINE, winreg.KEY_WOW64_32KEY)]
    for wurzel, sicht in orte:
        try:
            with winreg.OpenKey(wurzel, schluessel, 0, winreg.KEY_READ | sicht) as k:
                version = _wert(k, "DisplayVersion") or "?"
                ordner = _wert(k, "InstallLocation") or _wert(k, "Inno Setup: App Path")
                symbol = (_wert(k, "DisplayIcon") or "").split(",")[0].strip('"') or None
                exe = None
                if ordner and exe_name and (Path(ordner) / exe_name).exists():
                    exe = str(Path(ordner) / exe_name)
                elif symbol and symbol.lower().endswith(".exe") and Path(symbol).exists():
                    exe = symbol
                # Zeitpunkt der letzten Änderung des Schlüssels = letzte Installation (100 ns seit 1601)
                geaendert = winreg.QueryInfoKey(k)[2] / 1e7 - 11644473600
                return Installiert(version=version, zeit=geaendert, ordner=ordner.rstrip("\\/") if ordner else None,
                                   exe=exe, deinstallieren=_wert(k, "UninstallString"))
        except OSError:
            continue
    return None


# --------------------------------------------------------------------------- Anmeldeinformationen

if WIN:
    class _FILETIME(ctypes.Structure):
        _fields_ = [("lo", wt.DWORD), ("hi", wt.DWORD)]

    class _CREDENTIAL(ctypes.Structure):
        _fields_ = [("Flags", wt.DWORD), ("Type", wt.DWORD), ("TargetName", wt.LPWSTR), ("Comment", wt.LPWSTR),
                    ("LastWritten", _FILETIME), ("CredentialBlobSize", wt.DWORD),
                    ("CredentialBlob", ctypes.POINTER(ctypes.c_ubyte)), ("Persist", wt.DWORD),
                    ("AttributeCount", wt.DWORD), ("Attributes", ctypes.c_void_p),
                    ("TargetAlias", wt.LPWSTR), ("UserName", wt.LPWSTR)]

    advapi32.CredWriteW.argtypes = [ctypes.POINTER(_CREDENTIAL), wt.DWORD]
    advapi32.CredWriteW.restype = wt.BOOL
    advapi32.CredReadW.argtypes = [wt.LPCWSTR, wt.DWORD, wt.DWORD, ctypes.POINTER(ctypes.POINTER(_CREDENTIAL))]
    advapi32.CredReadW.restype = wt.BOOL
    advapi32.CredDeleteW.argtypes = [wt.LPCWSTR, wt.DWORD, wt.DWORD]
    advapi32.CredDeleteW.restype = wt.BOOL
    advapi32.CredFree.argtypes = [ctypes.c_void_p]

_CRED_TYPE_GENERIC = 1
_CRED_PERSIST_LOCAL_MACHINE = 2
_test_tresor: dict = {}


def geheimnis_schreiben(ziel: str, wert: str) -> None:
    if not WIN:
        _test_tresor[ziel] = wert
        return
    daten = wert.encode("utf-8")
    puffer = (ctypes.c_ubyte * len(daten)).from_buffer_copy(daten)
    c = _CREDENTIAL()
    c.Type = _CRED_TYPE_GENERIC
    c.TargetName = ziel
    c.CredentialBlobSize = len(daten)
    c.CredentialBlob = ctypes.cast(puffer, ctypes.POINTER(ctypes.c_ubyte))
    c.Persist = _CRED_PERSIST_LOCAL_MACHINE
    c.UserName = "honeybalecta"
    if not advapi32.CredWriteW(ctypes.byref(c), 0):
        raise OSError(ctypes.get_last_error(), "Token ließ sich nicht speichern")


def geheimnis_lesen(ziel: str) -> Optional[str]:
    if not WIN:
        return _test_tresor.get(ziel)
    p = ctypes.POINTER(_CREDENTIAL)()
    if not advapi32.CredReadW(ziel, _CRED_TYPE_GENERIC, 0, ctypes.byref(p)):
        return None
    try:
        c = p.contents
        return ctypes.string_at(c.CredentialBlob, c.CredentialBlobSize).decode("utf-8")
    finally:
        advapi32.CredFree(p)


def geheimnis_loeschen(ziel: str) -> None:
    if not WIN:
        _test_tresor.pop(ziel, None)
        return
    advapi32.CredDeleteW(ziel, _CRED_TYPE_GENERIC, 0)


# --------------------------------------------------------------------------- laufende Programme

if WIN:
    kernel32.OpenProcess.argtypes = [wt.DWORD, wt.BOOL, wt.DWORD]
    kernel32.OpenProcess.restype = wt.HANDLE
    kernel32.QueryFullProcessImageNameW.argtypes = [wt.HANDLE, wt.DWORD, wt.LPWSTR, ctypes.POINTER(wt.DWORD)]
    kernel32.QueryFullProcessImageNameW.restype = wt.BOOL
    kernel32.CloseHandle.argtypes = [wt.HANDLE]
    kernel32.K32EnumProcesses.argtypes = [ctypes.POINTER(wt.DWORD), wt.DWORD, ctypes.POINTER(wt.DWORD)]
    kernel32.K32EnumProcesses.restype = wt.BOOL
    _WNDENUMPROC = ctypes.WINFUNCTYPE(wt.BOOL, wt.HWND, wt.LPARAM)
    user32.EnumWindows.argtypes = [_WNDENUMPROC, wt.LPARAM]
    user32.GetWindowThreadProcessId.argtypes = [wt.HWND, ctypes.POINTER(wt.DWORD)]
    user32.IsWindowVisible.argtypes = [wt.HWND]
    user32.PostMessageW.argtypes = [wt.HWND, wt.UINT, wt.WPARAM, wt.LPARAM]


def prozesse_in(ordner: Optional[str]) -> list[int]:
    """Prozess-IDs aller Programme, die aus diesem Ordner laufen."""
    if not WIN or not ordner:
        return []
    praefix = os.path.normcase(os.path.abspath(ordner)).rstrip("\\") + "\\"
    arr = (wt.DWORD * 8192)()
    noetig = wt.DWORD()
    if not kernel32.K32EnumProcesses(arr, ctypes.sizeof(arr), ctypes.byref(noetig)):
        return []
    eigen = os.getpid()
    treffer = []
    puffer = ctypes.create_unicode_buffer(1024)
    for pid in arr[: noetig.value // ctypes.sizeof(wt.DWORD)]:
        if not pid or pid == eigen:
            continue
        h = kernel32.OpenProcess(0x1000, False, pid)  # PROCESS_QUERY_LIMITED_INFORMATION
        if not h:
            continue
        try:
            n = wt.DWORD(len(puffer))
            if kernel32.QueryFullProcessImageNameW(h, 0, puffer, ctypes.byref(n)):
                if os.path.normcase(puffer.value).startswith(praefix):
                    treffer.append(int(pid))
        finally:
            kernel32.CloseHandle(h)
    return treffer


def fenster_schliessen(pids: list[int]) -> int:
    """Schickt allen sichtbaren Fenstern dieser Prozesse „Schließen“ – wie ein Klick aufs X."""
    if not WIN or not pids:
        return 0
    ziel = set(pids)
    gezaehlt = [0]

    def rueckruf(hwnd, _):
        pid = wt.DWORD()
        user32.GetWindowThreadProcessId(hwnd, ctypes.byref(pid))
        if pid.value in ziel and user32.IsWindowVisible(hwnd):
            user32.PostMessageW(hwnd, 0x0010, 0, 0)  # WM_CLOSE
            gezaehlt[0] += 1
        return True

    user32.EnumWindows(_WNDENUMPROC(rueckruf), 0)
    return gezaehlt[0]


def beenden_erzwingen(pids: list[int]) -> None:
    for pid in pids:
        subprocess.run(["taskkill", "/PID", str(pid), "/T", "/F"], creationflags=CREATE_NO_WINDOW,
                       stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)


def warte_bis_beendet(ordner: Optional[str], sekunden: float) -> list[int]:
    ende = time.monotonic() + sekunden
    while True:
        rest = prozesse_in(ordner)
        if not rest or time.monotonic() > ende:
            return rest
        time.sleep(0.5)


# --------------------------------------------------------------------------- Installation

_INNO_CODES = {
    1: "Der Installer ließ sich nicht starten",
    2: "Installation abgebrochen",
    3: "Fehler bei der Vorbereitung",
    4: "Fehler während der Installation",
    5: "Installation abgebrochen",
    6: "Der Installer wurde beendet",
    7: "Ein Programm hält noch Dateien offen",
    8: "Ein Neustart ist nötig, bevor installiert werden kann",
}

STILL = ["/VERYSILENT", "/SUPPRESSMSGBOXES", "/NORESTART", "/SP-", "/NOCANCEL", "/CLOSEAPPLICATIONS", "/FORCECLOSEAPPLICATIONS"]


def installer_ausfuehren(setup: Path, protokoll: Path, zeitlimit: int = 1800) -> tuple[int, str]:
    """Startet einen Inno-Setup-Installer still. Liefert (Exit-Code, Fehlertext)."""
    if not WIN:
        return 0, ""
    try:
        p = subprocess.run([str(setup), *STILL, f"/LOG={protokoll}"], creationflags=CREATE_NO_WINDOW, timeout=zeitlimit)
    except subprocess.TimeoutExpired:
        return 99, "Der Installer hat sich nach einer halben Stunde nicht beendet"
    except OSError as e:
        return 98, f"Der Installer ließ sich nicht starten ({e.strerror or e})"
    if p.returncode == 0:
        return 0, ""
    text = _INNO_CODES.get(p.returncode, f"Installer endete mit Code {p.returncode}")
    try:
        zeilen = [z for z in protokoll.read_text(encoding="utf-8", errors="replace").splitlines() if z.strip()]
        fehler = [z for z in zeilen if any(w in z.lower() for w in ("error", "fehler", "failed", "abort"))]
        if fehler:
            text += ": " + fehler[-1].split("   ", 1)[-1].strip()[:200]
    except OSError:
        pass
    return p.returncode, text


def selbst_aktualisieren(setup: Path, protokoll: Path, start_arg: str) -> None:
    """Wartet in einem eigenen Prozess, bis die Werkstatt beendet ist, und spielt dann ihr Update ein."""
    if not WIN:
        return
    argumente = " ".join(STILL) + f' /LOG="{protokoll}" /werkstattstart={start_arg}'
    skript = ("Wait-Process -Id $env:WK_PID -Timeout 90 -ErrorAction SilentlyContinue; Start-Sleep -Milliseconds 700; "
              "Start-Process -FilePath $env:WK_SETUP -ArgumentList $env:WK_ARGS -Wait")
    env = dict(os.environ, WK_PID=str(os.getpid()), WK_SETUP=str(setup), WK_ARGS=argumente)
    befehl = ["powershell", "-NoProfile", "-NonInteractive", "-ExecutionPolicy", "Bypass", "-WindowStyle", "Hidden",
              "-Command", skript]
    for flags in (CREATE_NO_WINDOW | CREATE_NEW_PROCESS_GROUP | CREATE_BREAKAWAY_FROM_JOB,
                  CREATE_NO_WINDOW | CREATE_NEW_PROCESS_GROUP):
        try:
            subprocess.Popen(befehl, env=env, creationflags=flags, close_fds=True,
                             stdin=subprocess.DEVNULL, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
            return
        except OSError:
            continue
    raise OSError("Update ließ sich nicht starten")


def deinstallieren(befehl: Optional[str]) -> None:
    if WIN and befehl:
        subprocess.Popen(befehl)


def starte(pfad: str, argumente: Optional[list] = None) -> None:
    if not WIN:
        return
    if argumente:
        subprocess.Popen([pfad, *argumente], cwd=str(Path(pfad).parent), creationflags=CREATE_NEW_PROCESS_GROUP)
    else:
        os.startfile(pfad)  # noqa: S606


def zeige_im_ordner(pfad: str) -> None:
    if WIN:
        subprocess.Popen(["explorer", "/select,", pfad])


def oeffne_ordner(pfad: str) -> None:
    if WIN:
        os.startfile(pfad)  # noqa: S606


def oeffne_url(url: str) -> None:
    if WIN:
        os.startfile(url)  # noqa: S606
    else:
        import webbrowser
        webbrowser.open(url)


# --------------------------------------------------------------------------- Ordner und Verknüpfungen

_ORDNER = {
    "downloads": "{374DE290-123F-4565-9164-39C4925E467B}",
    "desktop": "{B4BFCC3A-DB2C-424C-B029-7FE99A87C641}",
    "programme": "{A77F5D77-2E2B-44C3-A6A2-ABA601054A51}",
}


def bekannter_ordner(name: str) -> Path:
    if WIN:
        try:
            class GUID(ctypes.Structure):
                _fields_ = [("a", ctypes.c_ubyte * 16)]
            g = GUID.from_buffer_copy(uuid.UUID(_ORDNER[name]).bytes_le)
            p = ctypes.c_wchar_p()
            if shell32.SHGetKnownFolderPath(ctypes.byref(g), 0, None, ctypes.byref(p)) == 0:
                try:
                    return Path(p.value)
                finally:
                    ole32.CoTaskMemFree(p)
        except Exception:  # noqa: BLE001
            pass
    fallback = {"downloads": Path.home() / "Downloads", "desktop": Path.home() / "Desktop",
                "programme": Path(os.environ.get("APPDATA", Path.home())) / "Microsoft/Windows/Start Menu/Programs"}
    return fallback[name]


def _powershell(skript: str, env: dict, zeitlimit: int = 30) -> subprocess.CompletedProcess:
    return subprocess.run(["powershell", "-NoProfile", "-NonInteractive", "-ExecutionPolicy", "Bypass", "-Command", skript],
                          env=dict(os.environ, **env), creationflags=CREATE_NO_WINDOW, capture_output=True, timeout=zeitlimit)


def verknuepfung(lnk: Path, ziel: str, argumente: str, symbol: Optional[str], text: str) -> None:
    if not WIN:
        return
    lnk.parent.mkdir(parents=True, exist_ok=True)
    skript = ("$s = (New-Object -ComObject WScript.Shell).CreateShortcut($env:WK_LNK); $s.TargetPath = $env:WK_ZIEL; "
              "$s.Arguments = $env:WK_ARGS; $s.WorkingDirectory = $env:WK_DIR; $s.Description = $env:WK_TEXT; "
              "if ($env:WK_ICON) { $s.IconLocation = $env:WK_ICON }; $s.Save()")
    r = _powershell(skript, {"WK_LNK": str(lnk), "WK_ZIEL": ziel, "WK_ARGS": argumente, "WK_DIR": str(Path(ziel).parent),
                             "WK_TEXT": text, "WK_ICON": symbol or ""})
    if r.returncode != 0 or not lnk.exists():
        raise OSError((r.stderr or b"").decode("utf-8", "replace").strip()[:200] or "Verknüpfung ließ sich nicht anlegen")


# --------------------------------------------------------------------------- Autostart

_RUN = r"Software\Microsoft\Windows\CurrentVersion\Run"


def autostart_setzen(an: bool, befehl: str) -> None:
    if not WIN:
        return
    with winreg.CreateKey(winreg.HKEY_CURRENT_USER, _RUN) as k:
        if an:
            winreg.SetValueEx(k, APP_NAME, 0, winreg.REG_SZ, befehl)
        else:
            try:
                winreg.DeleteValue(k, APP_NAME)
            except FileNotFoundError:
                pass


def autostart_eingetragen() -> bool:
    if not WIN:
        return False
    try:
        with winreg.OpenKey(winreg.HKEY_CURRENT_USER, _RUN) as k:
            return bool(_wert(k, APP_NAME))
    except OSError:
        return False


# --------------------------------------------------------------------------- Programmsymbole

if WIN:
    class _ICONINFO(ctypes.Structure):
        _fields_ = [("fIcon", wt.BOOL), ("xHotspot", wt.DWORD), ("yHotspot", wt.DWORD),
                    ("hbmMask", wt.HBITMAP), ("hbmColor", wt.HBITMAP)]

    class _BITMAP(ctypes.Structure):
        _fields_ = [("bmType", wt.LONG), ("bmWidth", wt.LONG), ("bmHeight", wt.LONG), ("bmWidthBytes", wt.LONG),
                    ("bmPlanes", wt.WORD), ("bmBitsPixel", wt.WORD), ("bmBits", ctypes.c_void_p)]

    class _BITMAPINFOHEADER(ctypes.Structure):
        _fields_ = [("biSize", wt.DWORD), ("biWidth", wt.LONG), ("biHeight", wt.LONG), ("biPlanes", wt.WORD),
                    ("biBitCount", wt.WORD), ("biCompression", wt.DWORD), ("biSizeImage", wt.DWORD),
                    ("biXPelsPerMeter", wt.LONG), ("biYPelsPerMeter", wt.LONG), ("biClrUsed", wt.DWORD),
                    ("biClrImportant", wt.DWORD)]

    user32.PrivateExtractIconsW.argtypes = [wt.LPCWSTR, ctypes.c_int, ctypes.c_int, ctypes.c_int,
                                            ctypes.POINTER(wt.HICON), ctypes.POINTER(wt.UINT), wt.UINT, wt.UINT]
    user32.PrivateExtractIconsW.restype = wt.UINT
    user32.GetIconInfo.argtypes = [wt.HICON, ctypes.POINTER(_ICONINFO)]
    user32.DestroyIcon.argtypes = [wt.HICON]
    user32.GetDC.argtypes = [wt.HWND]
    user32.GetDC.restype = wt.HDC
    user32.ReleaseDC.argtypes = [wt.HWND, wt.HDC]
    gdi32.GetObjectW.argtypes = [wt.HANDLE, ctypes.c_int, ctypes.c_void_p]
    gdi32.GetDIBits.argtypes = [wt.HDC, wt.HBITMAP, wt.UINT, wt.UINT, ctypes.c_void_p, ctypes.c_void_p, wt.UINT]
    gdi32.DeleteObject.argtypes = [wt.HANDLE]


def _bitmap_bytes(hdc, hbm, w: int, h: int) -> bytes:
    bi = _BITMAPINFOHEADER(biSize=40, biWidth=w, biHeight=-h, biPlanes=1, biBitCount=32, biCompression=0)
    puffer = ctypes.create_string_buffer(w * h * 4)
    gdi32.GetDIBits(hdc, hbm, 0, h, puffer, ctypes.byref(bi), 0)
    return puffer.raw


def programm_symbol(exe: str, groesse: int = 96):
    """Symbol einer EXE als Pillow-Bild (RGBA) oder None."""
    if not WIN or not exe:
        return None
    from PIL import Image
    hicon = wt.HICON()
    kennung = wt.UINT()
    if user32.PrivateExtractIconsW(exe, 0, groesse, groesse, ctypes.byref(hicon), ctypes.byref(kennung), 1, 0) in (0, 0xFFFFFFFF) or not hicon:
        return None
    info = _ICONINFO()
    try:
        if not user32.GetIconInfo(hicon, ctypes.byref(info)) or not info.hbmColor:
            return None
        bm = _BITMAP()
        gdi32.GetObjectW(info.hbmColor, ctypes.sizeof(bm), ctypes.byref(bm))
        w, h = bm.bmWidth, abs(bm.bmHeight)
        hdc = user32.GetDC(None)
        try:
            farbe = _bitmap_bytes(hdc, info.hbmColor, w, h)
            bild = Image.frombuffer("RGBA", (w, h), farbe, "raw", "BGRA", 0, 1)
            if bild.getextrema()[3][1] == 0 and info.hbmMask:  # altes Symbol ohne Alphakanal: Maske nutzen
                maske = Image.frombuffer("RGBA", (w, h), _bitmap_bytes(hdc, info.hbmMask, w, h), "raw", "BGRA", 0, 1)
                alpha = maske.convert("L").point(lambda v: 0 if v > 127 else 255)
                bild = bild.convert("RGB").convert("RGBA")
                bild.putalpha(alpha)
        finally:
            user32.ReleaseDC(None, hdc)
        return bild.copy()
    finally:
        if info.hbmColor:
            gdi32.DeleteObject(info.hbmColor)
        if info.hbmMask:
            gdi32.DeleteObject(info.hbmMask)
        user32.DestroyIcon(hicon)
