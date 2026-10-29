"""Tests für den plattformneutralen Kern. Aufruf: python -m unittest discover -s windows/tests"""
import json
import sys
import unittest
from pathlib import Path

HIER = Path(__file__).resolve().parent
sys.path.insert(0, str(HIER.parent))

from werkstatt import kern  # noqa: E402


def asset(i, name, zeit="2026-10-27T10:00:00Z", groesse=100, digest=None):
    a = {"id": i, "name": name, "size": groesse, "updated_at": zeit, "url": f"https://api.github.com/x/{i}", "state": "uploaded"}
    if digest:
        a["digest"] = digest
    return a


class Katalog(unittest.TestCase):
    def test_echter_katalog(self):
        text = (HIER.parent.parent / "katalog.json").read_text(encoding="utf-8")
        ids = [e.id for e in kern.lies_katalog(text)]
        self.assertEqual(ids, ["souffleur", "honeycut", "karussell", "werkstatt"])  # ohne Vorlesepult
        e = {x.id: x for x in kern.lies_katalog(text)}
        self.assertTrue(e["honeycut"].muster.search("HoneyCut-Setup-2.9.0.exe"))
        self.assertFalse(e["honeycut"].muster.search("HoneyCut-Android-2.9.0.apk"))
        self.assertEqual(e["karussell"].typ, "web")
        self.assertEqual(e["karussell"].dateien, ["index.html"])
        self.assertTrue(e["werkstatt"].appid.startswith("{"))

    def test_kaputte_eintraege_werden_uebersprungen(self):
        text = json.dumps({"apps": [{"id": "a"}, {"id": "b", "name": "B", "repo": "x/y", "windows": {"datei": "("}},
                                    {"id": "c", "name": "C", "repo": "x/y", "typ": "app", "windows": {"datei": "^C.*\\.exe$"}}]})
        self.assertEqual([e.id for e in kern.lies_katalog(text)], ["c"])


class Versionen(unittest.TestCase):
    def test_vergleich(self):
        self.assertEqual(kern.vergleiche("v2.9.0", "2.8.0"), 1)
        self.assertEqual(kern.vergleiche("android-v2.7.0", "2.7.0"), 0)
        self.assertEqual(kern.vergleiche("1.0.0", "1.0.1"), -1)
        self.assertIsNone(kern.vergleiche("build-3", "1.0.3"))
        self.assertEqual(kern.vergleiche("v1.10.0", "1.9.0"), 1)


class Freigaben(unittest.TestCase):
    def test_honeycut_gemischte_releases(self):
        releases = [
            {"tag_name": "v2.9.0", "assets": [asset(9, "HoneyCut-Setup-2.9.0.exe")]},
            {"tag_name": "android-v2.9.0", "assets": [asset(8, "HoneyCut-Android-2.9.0.apk")]},
        ]
        f = kern.waehle_freigabe(releases, kern.re.compile(r"^HoneyCut-Setup-.*\.exe$"))
        self.assertEqual((f.tag, f.version, f.datei.id), ("v2.9.0", "2.9.0", 9))

    def test_souffleur_beide_dateien_in_einem_release(self):
        releases = [{"tag_name": "v1.0.0", "assets": [asset(1, "Souffleur-1.0.0.apk"), asset(2, "Souffleur-Setup-1.0.0.exe", digest="sha256:ab")]}]
        f = kern.waehle_freigabe(releases, kern.re.compile(r"^Souffleur-Setup-.*\.exe$"))
        self.assertEqual(f.datei.id, 2)
        self.assertEqual(f.datei.sha256, "ab")

    def test_entwuerfe_und_vorabversionen_zaehlen_nicht(self):
        releases = [{"tag_name": "v3", "draft": True, "assets": [asset(3, "A-Setup-3.exe")]},
                    {"tag_name": "v2", "prerelease": True, "assets": [asset(2, "A-Setup-2.exe")]},
                    {"tag_name": "v1", "assets": [asset(1, "A-Setup-1.exe")]}]
        self.assertEqual(kern.waehle_freigabe(releases, kern.re.compile("^A-Setup")).tag, "v1")

    def test_keine_passende_datei(self):
        self.assertIsNone(kern.waehle_freigabe([{"tag_name": "v1", "assets": [asset(1, "x.apk")]}], kern.re.compile(".")))


class Update(unittest.TestCase):
    def frei(self, tag="v1.0.0", i=5, zeit="2026-10-27T10:00:00Z"):
        return kern.Freigabe(tag=tag, titel=tag, veroeffentlicht=0, datei=kern.Datei(i, "x.exe", 1, kern.zeit(zeit), "u", None))

    def inst(self, version="1.0.0", zeit="2026-10-27T12:00:00Z"):
        return kern.Installiert(version=version, zeit=kern.zeit(zeit), ordner=None, exe=None, deinstallieren=None)

    def test_nicht_installiert(self):
        self.assertFalse(kern.braucht_update(None, self.frei(), None))

    def test_neuere_version(self):
        self.assertTrue(kern.braucht_update(self.inst("2.8.0"), self.frei("v2.9.0"), None))
        self.assertFalse(kern.braucht_update(self.inst("2.9.0"), self.frei("v2.8.0"), None))

    def test_gleiche_version_neu_hochgeladen(self):
        # Souffleur lädt neue Builds unter derselben Versionsnummer hoch
        self.assertFalse(kern.braucht_update(self.inst(zeit="2026-10-27T12:00:00Z"), self.frei(zeit="2026-10-27T10:00:00Z"), None))
        self.assertTrue(kern.braucht_update(self.inst(zeit="2026-10-27T12:00:00Z"), self.frei(zeit="2026-10-28T10:00:00Z"), None))

    def test_nachweis_entscheidet(self):
        n = {"datei": 5, "stand": kern.zeit("2026-10-27T10:00:00Z"), "version": "1.0.0"}
        self.assertFalse(kern.braucht_update(self.inst(zeit="2026-01-01T00:00:00Z"), self.frei(), n))
        self.assertTrue(kern.braucht_update(self.inst(), self.frei(i=6), n))
        # Nachweis gilt nur für die Version, die damals installiert wurde
        self.assertTrue(kern.braucht_update(self.inst("0.9.0"), self.frei(), n))


if __name__ == "__main__":
    unittest.main()
