# PyInstaller-Bauplan für Honeys Werkstatt (Windows, Ordner-Build)
# Aufruf aus dem Projektordner:  pyinstaller windows/werkstatt.spec
from pathlib import Path
from PyInstaller.utils.hooks import collect_all

HERE = Path(SPECPATH)
ROOT = HERE.parent
datas = [(str(HERE / "ui"), "ui"), (str(ROOT / "katalog.json"), "."), (str(HERE / "werkstatt.ico"), ".")]
version_datei = ROOT / "build" / "app-version.txt"
if version_datei.exists():
    datas.append((str(version_datei), "."))
binaries, hiddenimports = [], ["werkstatt.app", "werkstatt.selbsttest", "pystray._win32", "PIL.ImageGrab"]
for pkg in ("webview", "clr_loader", "pythonnet", "pystray"):
    try:
        d, b, h = collect_all(pkg)
        datas += d; binaries += b; hiddenimports += h
    except Exception as e:
        print("collect_all", pkg, e)

a = Analysis([str(HERE / "app_entry.py")], pathex=[str(HERE)], binaries=binaries, datas=datas,
             hiddenimports=hiddenimports,
             excludes=["matplotlib", "PyQt5", "PyQt6", "PySide2", "PySide6", "IPython", "pytest", "tkinter", "numpy", "gi"],
             noarchive=False)
pyz = PYZ(a.pure)
exe = EXE(pyz, a.scripts, [], exclude_binaries=True, name="HoneysWerkstatt", console=False,
          icon=str(HERE / "werkstatt.ico"))
coll = COLLECT(exe, a.binaries, a.datas, strip=False, upx=False, name="HoneysWerkstatt")
