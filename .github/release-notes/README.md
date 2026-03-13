# Release Notes Policy

Dieses Verzeichnis enthaelt die manuellen Release-Notizen fuer Android-App-Releases.

## Format
- Fuer jede neue App-Version gehoeren zwei Dateien zusammen:
  - `vX.Y.Z.md`
  - `vX.Y.Z.json`
- Die `.md`-Datei wird als GitHub-Release-Body verwendet
- Die `.json`-Datei wird als `changelog.json` an das Release gehaengt und von der App genutzt

## Fallback-Verhalten
- Wenn fuer ein Tag keine manuelle `.md`-Datei vorhanden ist, erzeugt `release-android.yml` Release-Notes aus der Commit-Historie
- Wenn die `.md` vorhanden ist, aber keine `.json`, erzeugt der Workflow das JSON automatisch aus den deduplizierten Commit-Highlights

## Pflege-Regel
- Neue Releases ab jetzt immer mit beiden Dateien vorbereiten
- Alte Releases werden nur selektiv nachgepflegt, wenn sie noch aktiv verlinkt, verteilt oder supportet werden
- Oeffentliche Release-Texte sollen kurz Problem, Fix und Nutzerwirkung erklaeren, nicht nur interne Commit-Titel kopieren

## Release-Checkliste (vor Tag + Push)
- `android/app/build.gradle.kts`: `versionName` auf Zielversion gesetzt
- `android/app/build.gradle.kts`: `versionCode` gegenueber letztem Release erhoeht
- Tag entspricht exakt `versionName` (z. B. `v0.4.11` <-> `versionName = "0.4.11"`)
- Manuelle Notes vorhanden: `vX.Y.Z.md` und `vX.Y.Z.json`
- Nach Build APK-Version kurz verifiziert (Manifest/App-Info)
