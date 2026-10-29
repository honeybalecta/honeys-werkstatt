# Hinweise für Claude

- **Neue Apps nur nach Rückfrage:** Bevor eine App in `katalog.json` kommt, Raphael fragen. Das gilt für jede neu gebaute Android-App, jedes Windows-Programm und jedes Web-Werkzeug.
- Der Katalog wird zur Laufzeit gelesen (Android und Windows). Für einen neuen Eintrag reicht ein Commit an `katalog.json` – kein neuer Build nötig.
- Android-Einträge brauchen `paket` (applicationId) und `datei` (Regex auf den Asset-Namen). Windows-Einträge brauchen den Block `windows` mit `datei`, `appid` (Inno-Setup-AppId mit geschweiften Klammern) und `exe`. Bei Repos mit Windows- und Android-Releases (z. B. textschnitt) müssen die Muster eindeutig auf APK bzw. EXE zeigen.
- Neue Android-Apps mit festem Schlüssel signieren und `versionCode = github.run_number` setzen, sonst scheitern Updates. Neue Windows-Programme als Inno-Setup-Installer pro Benutzer:in (`PrivilegesRequired=lowest`, fester `AppId`, `[Run]` mit `skipifsilent`), damit die Werkstatt still aktualisieren kann.
- Sprache der Oberfläche und der Texte: Deutsch, gegendert mit Doppelpunkt. Farben: Anthrazit #131312, Creme #EDE7DA, Sand #CFC6B0, Rot #E5402A (auf Hell #B8301D) als einziger Akzent, Grün #3E8A55 nur für „aktuell“; Schrift Inter.
- Builds laufen nur über GitHub Actions. Logs: Zweig `ci-log-android` bzw. `ci-log-windows` (README.md dort, dazu Selbsttest-Bericht und Bildschirmfotos).
- Windows-Tests lokal: `python3 -m unittest discover -s windows/tests` (Kern, Dienst-Ablauf mit nachgebauten Windows-Funktionen, Server und Brücke in Chromium) und `node windows/tests/ui_vorschau.mjs <ordner>` (Oberfläche mit Testdaten fotografieren).
