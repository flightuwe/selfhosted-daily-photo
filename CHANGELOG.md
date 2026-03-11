# Changelog

Dieses Projekt nutzt Release-gebundene Changelogs.

- Bei jedem Android-Release erzeugt der Workflow automatisch:
  - `release-notes.md` (Release-Text auf GitHub)
  - `changelog.json` (maschinenlesbar fuer die App)
- Die App versucht den Changelog zuerst aus `changelog.json` des passenden Release-Tags zu laden.
- Wenn das Asset nicht verfuegbar ist, faellt die App auf die GitHub Release Notes zurueck.

## Format von `changelog.json`

```json
{
  "version": "0.0.0",
  "releasedAt": "2026-03-10T00:00:00Z",
  "highlights": [
    "Kurzbeschreibung 1",
    "Kurzbeschreibung 2"
  ],
  "details": []
}
```

## Hinweis

Der Workflow erzeugt den Inhalt aus Commit-Titeln seit dem letzten `v*`-Tag
(mit Deduplizierung und Begrenzung), damit jede Version konsistente Release-Infos hat.

## Manuelle Release-Overrides

Wenn fuer eine Version ausnahmsweise ausfuehrlichere Release-Infos gewuenscht sind, koennen diese direkt im Repo
hinterlegt werden:

- `.github/release-notes/vX.Y.Z.md`
- `.github/release-notes/vX.Y.Z.json`

Wenn diese Dateien fuer ein Tag vorhanden sind, verwendet der Android-Release-Workflow sie vorrangig vor der
automatischen Commit-Zusammenfassung.
