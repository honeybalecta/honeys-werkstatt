# Hinweise für Claude

- **Neue Apps nur nach Rückfrage:** Bevor eine App in `katalog.json` kommt, Raphael fragen. Das gilt für jede neu gebaute Android-App und jedes Web-Werkzeug.
- Der Katalog wird zur Laufzeit gelesen. Für einen neuen Eintrag reicht ein Commit an `katalog.json` – kein neuer Build nötig.
- APK-Einträge brauchen `paket` (applicationId) und `datei` (Regex auf den Asset-Namen). Bei Repos mit Windows- und Android-Releases (z. B. textschnitt) muss das Muster eindeutig auf die APK zeigen.
- Neue Android-Apps sollten mit festem Schlüssel signiert werden (Secret oder passwortgeschützte Keystore-Datei) und `versionCode = github.run_number` setzen, sonst scheitern Updates.
- Sprache der Oberfläche und der Texte: Deutsch, gegendert mit Doppelpunkt. Farben: Anthrazit #131312, Creme #EDE7DA, Sand #CFC6B0, Rot #E5402A (auf Hell #B8301D) als einziger Akzent, Grün #3E8A55 nur für „aktuell“; Schrift Inter.
- Build läuft nur über GitHub Actions; das Log steht im Zweig `ci-log-android` (README.md dort).
