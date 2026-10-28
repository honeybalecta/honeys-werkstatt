# Honeys Werkstatt

Eigener App-Launcher für Android: zeigt alle eigenen Apps aus den GitHub-Repos von honeybalecta, installiert sie per Knopf und spielt Updates ein – ohne Umweg über GitHub.

- **Apps (APK):** Die Werkstatt sucht im Release-Bereich des jeweiligen Repos die neueste passende APK, lädt sie, prüft Paketname und Prüfsumme und installiert sie. Ab Android 12 laufen Updates von Apps, die die Werkstatt selbst installiert hat, ohne Rückfrage.
- **Web-Werkzeuge:** Einzelne HTML-Dateien (z. B. Karussell-Werkstatt) lädt die Werkstatt aus dem Repo und öffnet sie im Vollbild. Exporte landen in „Bilder/Honeys Werkstatt“ bzw. „Download/Honeys Werkstatt“. Auf Wunsch mit eigenem Symbol auf dem Startbildschirm.
- **Im Hintergrund:** etwa alle sechs Stunden prüfen, benachrichtigen und – wenn eingeschaltet – still aktualisieren.
- **Private Repos:** brauchen einen Fine-grained-Token mit „Contents: Read-only“ (Einstellungen in der App). Er liegt verschlüsselt im Android-Schlüsselspeicher.

## Erste Installation

1. Unter **Releases** die neueste `HoneysWerkstatt-….apk` auf dem Handy öffnen und installieren.
2. Samsung: vorher die **automatische Sperre** ausschalten (Einstellungen › Sicherheit und Datenschutz › Automatische Sperre). Solange sie an ist, blockiert Android jede Installation außerhalb von Play Store und Galaxy Store – auch Updates.
3. In der Werkstatt einmal „Installieren erlauben“ bestätigen.

Apps, die schon über den Browser installiert wurden, fragen beim ersten Update über die Werkstatt einmal nach; danach laufen ihre Updates per Knopf bzw. still.

## Katalog

`katalog.json` legt fest, welche Apps erscheinen. Die App liest die Datei bei jeder Prüfung aus diesem Repo – für eine neue App reicht ein neuer Eintrag, ohne neuen Build der Werkstatt.

| Feld | Bedeutung |
|---|---|
| `id` | kurze, feste Kennung |
| `typ` | `apk` oder `web` |
| `repo` | `besitzer/repo` |
| `paket` | Paketname der App (nur `apk`) |
| `datei` | regulärer Ausdruck für den APK-Dateinamen im Release |
| `fassungen` | optional: mehrere APK-Fassungen derselben App (`id`, `name`, `datei`) |
| `dateien`, `start` | nur `web`: Dateien aus dem Repo und die Startseite |

Voraussetzung für Updates: Jede App wird immer mit **demselben Schlüssel** signiert und bekommt bei jedem Build einen höheren `versionCode`.

## Bauen

GitHub Actions baut bei jeder Änderung unter `android/` und legt die APK im Release `v<version.txt>` ab. Signiert wird mit `android/keystore/release.p12` (passwortgeschützt); das Passwort steht im Secret `ANDROID_KEYSTORE_PASSWORD`. Das Build-Log landet im Zweig `ci-log-android`.
