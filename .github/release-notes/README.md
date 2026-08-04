# Release Notes Policy

Dieses Verzeichnis enthaelt die manuellen Release-Notizen fuer Android-App-Releases.

## Format
- Fuer jede neue App-Version ist `vX.Y.Z.json` die kanonische Changelog-Quelle.
- Der Android-Release-Workflow validiert das JSON, haengt es als `changelog.json` an und erzeugt daraus den GitHub-Release-Body.
- Alte `vX.Y.Z.md`-Dateien bleiben als historische Dokumentation erhalten, sind aber keine zweite zu pflegende Quelle mehr.
- Pflichtfelder fuer neue Eintraege: `version`, `title`, `highlights`; `details`, `knownIssues` und `breakingChanges` sind optional.

## Release-Qualitaet
- Ein Android-Release ohne kanonisches JSON wird blockiert. Commit-Titel sind kein nutzerseitiger Changelog-Ersatz.
- Die Daily-App liest den gesamten Verlauf dynamisch aus den veroeffentlichten GitHub-Releases und cached ihn lokal fuer Offline-Nutzung.

## Pflege-Regel
- Neue Releases immer mit der JSON-Datei vorbereiten; die GitHub-Notes entstehen daraus automatisch.
- Alte Releases werden nur selektiv nachgepflegt, wenn sie noch aktiv verlinkt, verteilt oder supportet werden
- Oeffentliche Release-Texte sollen kurz Problem, Fix und Nutzerwirkung erklaeren, nicht nur interne Commit-Titel kopieren

## Release-Checkliste (vor Tag + Push)
- `android/app/build.gradle.kts`: `versionName` auf Zielversion gesetzt
- `android/app/build.gradle.kts`: `versionCode` gegenueber letztem Release erhoeht
- Tag entspricht exakt `versionName` (z. B. `v0.4.11` <-> `versionName = "0.4.11"`)
- Kanonische Notes vorhanden: `vX.Y.Z.json`
- Nach Build APK-Version kurz verifiziert (Manifest/App-Info)
