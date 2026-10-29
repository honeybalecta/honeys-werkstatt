# Honeys Werkstatt

Eigener App-Launcher für Android und Windows: zeigt alle eigenen Apps aus den GitHub-Repos von honeybalecta, installiert sie per Knopf und spielt Updates ein – ohne Umweg über GitHub.

## Android

- **Apps (APK):** Die Werkstatt sucht im Release-Bereich des jeweiligen Repos die neueste passende APK, lädt sie, prüft Paketname und Prüfsumme und installiert sie. Ab Android 12 laufen Updates von Apps, die die Werkstatt selbst installiert hat, ohne Rückfrage.
- **Web-Werkzeuge:** Einzelne HTML-Dateien (z. B. Karussell-Werkstatt) lädt die Werkstatt aus dem Repo und öffnet sie im Vollbild. Exporte landen in „Bilder/Honeys Werkstatt“ bzw. „Download/Honeys Werkstatt“. Auf Wunsch mit eigenem Symbol auf dem Startbildschirm.
- **Im Hintergrund:** etwa alle sechs Stunden prüfen, benachrichtigen und – wenn eingeschaltet – still aktualisieren.
- **Private Repos:** brauchen einen Fine-grained-Token mit „Contents: Read-only“ (Einstellungen in der App). Er liegt verschlüsselt im Android-Schlüsselspeicher.

**Erste Installation:** Unter Releases die neueste `HoneysWerkstatt-….apk` auf dem Handy öffnen und installieren. Samsung: vorher die automatische Sperre ausschalten (Einstellungen › Sicherheit und Datenschutz › Automatische Sperre) – solange sie an ist, blockiert Android jede Installation außerhalb von Play Store und Galaxy Store, auch Updates. In der Werkstatt einmal „Installieren erlauben“ bestätigen. Apps, die schon über den Browser installiert wurden, fragen beim ersten Update über die Werkstatt einmal nach; danach laufen ihre Updates per Knopf bzw. still.

## Windows

- **Programme:** Die Werkstatt lädt den Inno-Setup-Installer aus dem Release, prüft Prüfsumme und Dateityp und spielt ihn still ein (`/VERYSILENT`). Alle Programme installieren pro Benutzer:in, deshalb kommt keine Rückfrage der Benutzerkontensteuerung. Welche Version installiert ist, liest die Werkstatt aus dem Eintrag `{AppId}_is1` unter „Apps“.
- **Geöffnete Programme:** Vor dem Update fragt die Werkstatt, ob sie das Programm schließen darf (wie ein Klick aufs X). Reagiert es nicht, bietet sie „Beenden erzwingen“ an. Im Hintergrund lässt sie geöffnete Programme in Ruhe.
- **Web-Werkzeuge:** öffnen sich in eigenen Fenstern (Edge WebView2). Exporte landen in „Downloads\Honeys Werkstatt“. Über das Menü: Verknüpfung auf dem Desktop oder im Startmenü.
- **Infobereich:** Die Werkstatt startet mit Windows unsichtbar neben der Uhr, sucht etwa alle sechs Stunden nach Updates und meldet sich mit einer Benachrichtigung; auf Wunsch installiert sie still.
- **Token:** liegt in der Windows-Anmeldeinformationsverwaltung (Ziel `HoneysWerkstatt/github`). Derselbe Token wie auf dem Handy funktioniert.
- **SmartScreen:** prüft nur Dateien aus Browser oder Mail. Updates lädt die Werkstatt selbst, deshalb erscheint die Warnung nur einmal bei der ersten Installation der Werkstatt („Weitere Informationen › Trotzdem ausführen“).

**Erste Installation:** Unter Releases `HoneysWerkstatt-Setup-….exe` aus dem neuesten Release `windows-v…` laden und ausführen. Danach aktualisiert sich die Werkstatt selbst.

Aufbau: `windows/werkstatt/` (Python): `kern.py` Katalog, GitHub, Update-Entscheidung · `system.py` Registry, Anmeldeinformationen, Prozesse, Installer, Verknüpfungen · `dienst.py` Warteschlange und Zustand · `server.py` lokaler Server mit Speichern-Brücke für Web-Werkzeuge · `app.py` Fenster (pywebview), Infobereich (pystray) · `selbsttest.py` Test im Build. Oberfläche in `windows/ui/`.

## Katalog

`katalog.json` legt fest, welche Apps erscheinen. Beide Werkstätten lesen die Datei bei jeder Prüfung aus diesem Repo – für eine neue App reicht ein neuer Eintrag, ohne neuen Build.

| Feld | Bedeutung |
|---|---|
| `id` | kurze, feste Kennung |
| `typ` | `apk` (bzw. `app`) oder `web` – Web-Werkzeuge gelten für beide Plattformen |
| `repo` | `besitzer/repo` |
| `paket` | Android: Paketname; ohne Paketnamen erscheint die App nicht auf dem Handy |
| `datei` | Android: regulärer Ausdruck für den APK-Dateinamen im Release |
| `fassungen` | Android, optional: mehrere APK-Fassungen derselben App (`id`, `name`, `datei`) |
| `windows` | Windows: `datei` (Regex auf den Installer), `appid` (Inno-Setup-AppId), `exe` (Programmdatei); ohne diesen Block erscheint die App nicht unter Windows |
| `dateien`, `start` | nur `web`: Dateien aus dem Repo und die Startseite |

Voraussetzungen für Updates: Android-Apps werden immer mit **demselben Schlüssel** signiert und bekommen bei jedem Build einen höheren `versionCode`. Windows-Programme behalten ihre Inno-Setup-`AppId` und installieren pro Benutzer:in (`PrivilegesRequired=lowest`).

## Bauen

- **Android:** GitHub Actions baut bei jeder Änderung unter `android/` und legt die APK im Release `v<version.txt>` ab. Signiert wird mit `android/keystore/release.p12` (passwortgeschützt); das Passwort steht im Secret `ANDROID_KEYSTORE_PASSWORD`. Log im Zweig `ci-log-android`.
- **Windows:** baut bei jeder Änderung unter `windows/` (PyInstaller + Inno Setup), führt Tests und den Selbsttest aus – der installiert Souffleur wirklich, einmal frisch und einmal bei geöffnetem Programm –, installiert die Werkstatt und fotografiert die Fenster. Release `windows-v<windows/version.txt>`, Bericht und Fotos im Zweig `ci-log-windows`.
