"""Ablauf im Dienst mit nachgebauten Windows-Funktionen: Installation, geöffnetes Programm, Erzwingen, Fehler, Hintergrund."""
import os
import sys
import tempfile
import time
import unittest
from pathlib import Path
from unittest import mock

HIER = Path(__file__).resolve().parent
sys.path.insert(0, str(HIER.parent))

from werkstatt import dienst as D, kern, system  # noqa: E402

KATALOG = (HIER.parent.parent / "katalog.json").read_text(encoding="utf-8")
APPID_SF = "{3F1B7C2E-8A44-4C6E-9D21-5B0E7A9C4F18}"


def freigabe(tag="v1.0.1", i=7):
    return kern.Freigabe(tag=tag, titel=tag, veroeffentlicht=0,
                         datei=kern.Datei(i, "Souffleur-Setup-1.0.1.exe", 4, time.time(), "https://api.github.com/x", None))


class FakeWindows:
    """Merkt sich installierte Versionen und laufende Programme."""

    def __init__(self):
        self.versionen = {APPID_SF: "1.0.0"}
        self.laeuft = []
        self.reagiert = True
        self.code = 0
        self.erzwungen = False

    def installiert(self, appid, exe=None):
        v = self.versionen.get(appid)
        return kern.Installiert(v, time.time() - 3600, "C:/Programme/Souffleur", "C:/Programme/Souffleur/Souffleur.exe", "unins") if v else None

    def prozesse_in(self, ordner):
        return list(self.laeuft)

    def fenster_schliessen(self, pids):
        if self.reagiert:
            self.laeuft = []
        return len(pids)

    def warte_bis_beendet(self, ordner, sek):
        return list(self.laeuft)

    def beenden_erzwingen(self, pids):
        self.erzwungen = True
        self.laeuft = []

    def installer_ausfuehren(self, setup, protokoll, zeitlimit=1800):
        if self.code == 0:
            self.versionen[APPID_SF] = "1.0.1"
            return 0, ""
        return self.code, "Ein Programm hält noch Dateien offen"


def lade(datei, token, ziel, fortschritt, abbrechen):
    ziel.parent.mkdir(parents=True, exist_ok=True)
    ziel.write_bytes(b"MZ\x90\x00")
    fortschritt(4, 4)


class Ablauf(unittest.TestCase):
    def setUp(self):
        self.tmp = Path(tempfile.mkdtemp())
        self.fw = FakeWindows()
        self.patches = [
            mock.patch.object(system, "daten_ordner", lambda: self.tmp),
            mock.patch.object(system, "WIN", True),
            mock.patch.object(system, "geheimnis_lesen", lambda z: None),
            mock.patch.object(kern, "lade", lade),
            mock.patch.object(kern, "neueste_freigabe", lambda repo, m, t: freigabe() if "souffleur" in repo else None),
            mock.patch.object(kern, "roh_text", lambda *a: KATALOG),
            mock.patch.object(kern, "inhalt_sha", lambda *a: "abc"),
            mock.patch.object(system, "programm_symbol", lambda exe, n=96: None),
        ]
        for name in ("installiert", "prozesse_in", "fenster_schliessen", "warte_bis_beendet", "beenden_erzwingen", "installer_ausfuehren"):
            self.patches.append(mock.patch.object(system, name, getattr(self.fw, name)))
        for p in self.patches:
            p.start()
        self.d = D.Dienst("1.0.0", "{EDD2D7CA-10E7-4F92-8F62-B5F948950037}", KATALOG)
        self.d.sichtbar = True

    def tearDown(self):
        for p in self.patches:
            p.stop()

    def warte(self, arten, sek=5):
        ende = time.time() + sek
        while time.time() < ende:
            v = self.d.vorgaenge.get("souffleur") or {}
            if v.get("art") in arten:
                return v
            time.sleep(0.02)
        self.fail(f"kein {arten}: {self.d.vorgaenge.get('souffleur')}")

    def sf(self):
        return next(a for a in self.d.zustand()["apps"] if a["id"] == "souffleur")

    def test_update_erkannt_und_installiert(self):
        self.d.pruefen()
        a = self.sf()
        self.assertTrue(a["update"])
        self.assertEqual((a["installiert"], a["neu"]), ("1.0.0", "1.0.1"))
        honeycut = next(x for x in self.d.zustand()["apps"] if x["id"] == "honeycut")
        self.assertIn("Windows-Installer", honeycut["fehler"]["meldung"])
        self.d.starte(["souffleur"])
        self.assertEqual(self.warte(("fertig",))["text"], "Aktualisiert")
        self.assertFalse(self.sf()["update"])
        self.assertEqual(self.d.e["nachweise"][APPID_SF]["version"], "1.0.1")
        self.assertEqual(list((self.tmp / "downloads").glob("*.exe")), [])  # Installer weggeräumt

    def test_geoeffnet_schliessen(self):
        self.d.pruefen()
        self.fw.laeuft = [123]
        self.d.starte(["souffleur"])
        self.warte(("geoeffnet",))
        self.d.entscheide("souffleur", "schliessen")
        self.warte(("fertig",))
        self.assertFalse(self.fw.erzwungen)

    def test_haengt_erzwingen(self):
        self.d.pruefen()
        self.fw.laeuft = [123]
        self.fw.reagiert = False
        self.d.starte(["souffleur"])
        self.warte(("geoeffnet",))
        self.d.entscheide("souffleur", "schliessen")
        self.warte(("haengt",))
        self.d.entscheide("souffleur", "erzwingen")
        self.warte(("fertig",))
        self.assertTrue(self.fw.erzwungen)

    def test_abbrechen_bei_geoeffnet(self):
        self.d.pruefen()
        self.fw.laeuft = [123]
        self.d.starte(["souffleur"])
        self.warte(("geoeffnet",))
        self.d.abbrechen("souffleur")
        ende = time.time() + 3
        while self.d.vorgaenge.get("souffleur") and time.time() < ende:
            time.sleep(0.02)
        self.assertIsNone(self.d.vorgaenge.get("souffleur"))
        self.assertEqual(self.fw.versionen[APPID_SF], "1.0.0")

    def test_installer_fehler(self):
        self.d.pruefen()
        self.fw.code = 7
        self.d.starte(["souffleur"])
        v = self.warte(("fehler",))
        self.assertIn("Dateien offen", v["meldung"])

    def test_hintergrund_still_aber_nicht_bei_geoeffnetem_programm(self):
        meldungen = []
        self.d.melden = lambda t, x: meldungen.append((t, x))
        self.d.sichtbar = False
        self.d.e["auto"] = True
        self.fw.laeuft = [5]
        self.d.hintergrund_runde()
        self.assertEqual(self.fw.versionen[APPID_SF], "1.0.0")  # geöffnet: nicht angefasst
        self.assertTrue(any("Update" in t for t, _ in meldungen), meldungen)
        self.fw.laeuft = []
        meldungen.clear()
        self.d.setze_vorgang("souffleur", None)
        self.d.hintergrund_runde()
        self.assertEqual(self.fw.versionen[APPID_SF], "1.0.1")
        self.assertTrue(any("aktualisiert" in t for t, _ in meldungen), meldungen)

    def test_selbst_update(self):
        aufrufe = []
        self.d.selbst_update = lambda setup, prot: aufrufe.append(setup)
        with mock.patch.object(kern, "neueste_freigabe", lambda repo, m, t: freigabe("windows-v1.0.1", 9)):
            self.fw.versionen["{EDD2D7CA-10E7-4F92-8F62-B5F948950037}"] = "1.0.0"
            self.d.pruefen()
            self.d.starte(["werkstatt"])
            ende = time.time() + 5
            while not aufrufe and time.time() < ende:
                time.sleep(0.02)
        self.assertEqual(len(aufrufe), 1)
        self.assertTrue(aufrufe[0].exists(), "Installer der Werkstatt muss liegen bleiben")
        self.assertIn("{EDD2D7CA-10E7-4F92-8F62-B5F948950037}", self.d.e["ausstehend"])


if __name__ == "__main__":
    unittest.main()
