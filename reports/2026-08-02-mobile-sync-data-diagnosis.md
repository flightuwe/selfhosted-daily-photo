# Mobile Sync-, Upload- und Datenverbrauchsanalyse

Stand: 2026-08-02. Grundlage sind vier Android-Diagnoseexporte (12.07., 18.07., 23.07. und 02.08.2026), jeweils maximal 400 lokale Zeilen, sowie der aktuelle Stand von Android-Client und Backend. Die Logs sind daher Stichproben, keine vollständige Verbrauchsabrechnung.

## Ergebnis

Es gibt drei bestätigte Produktprobleme und zwei zusätzliche Risiken:

1. **Nach Offline-Situationen können zusätzliche Bilder endgültig abgelehnt werden.** Der Log vom 18.07. zeigt für denselben Extra-Upload zunächst einen abgebrochenen Worker und anschließend zwei `403 forbidden`-Antworten. Die Aufnahme war vom Vorabend; der Server lehnt `kind=extra` während eines aktiven Daily-Moment-Fensters grundsätzlich ab. Das erklärt genau das Muster „später nicht mehr hochgeladen und abgelehnt“. Der Datensatz verbleibt zwar als Fehlerfall in der Queue, der Nutzer bekommt aber keinen konfliktauflösenden Weg zurück zu einem sicheren Ergebnis.
2. **Die Timeline kann beim Wiederöffnen unvollständig bzw. veraltet wirken.** Der persistente Warm-Cache speichert nur 24 Timeline-Einträge. Beim Bootstrap ersetzt die App sie sogar durch die nur acht Einträge große Hub-Vorschau und markiert diese als frisch; beim Öffnen der Timeline wird deshalb bis zu 60 Sekunden kein Vollabruf ausgelöst. Der API-Aufruf selbst fordert immer bis zu 80 Einträge ohne Cursor oder Änderungsmarke an. Das ist zugleich Ursache für einen älteren sichtbaren Stand und für unnötige Wiederholungsdownloads.
3. **Der Feed startet regelmäßig ohne persistierten Feed-Cache.** Hub, Timeline und Kalender werden auf Disk gespeichert, `feedByDay` dagegen nicht. Nach App-Prozessende beginnt der Feed nachweisbar wieder mit `empty_feed` und lädt ein Fenster neu, obwohl Hub/Timeline bereits Daten haben. Das erklärt, warum der Umweg Hub → Timeline → Feed gefühlt schneller ist als der direkte Feed.
4. **Die Refresh-Strategie erzeugt vermeidbare Arbeit auf Mobilfunk.** Auf dem 23.07.-Export dauerte ein erfolgreicher Feed-Refresh 138.327 ms; weitere erfolgreiche Läufe lagen bei 3,3–4,0 s. Auf dem 02.08.-Export dauerte ein Hintergrundrefresh 14.617 ms. Es gibt automatische Refreshes alle 25–40 s, globale Refreshes bei Netzwerkwechsel und manuelle Fenster-Reloads. Bei instabiler Verbindung werden zusätzlich viele erfolglose Versuche protokolliert (z. B. 22 Feed-Refresh-Ergebnisse am 02.08.).
5. **Der aktuelle Verbrauch ist nicht messbar genug.** Serverseitig werden Request-Bytes aggregiert; clientseitig werden nur Upload-Bytes einzelner Queue-Vorgänge geloggt. Es fehlt eine unabhängige, pro Session/Netztyp/Endpunkt aggregierte Bytebilanz inklusive Bilddownloads, Wiederholungen, Cache-Treffern und WorkManager-Traffic.

DNS, TLS und Zertifikatspfad sind in allen vier Exports unauffällig. Die häufigste Infrastrukturklasse ist ein realer bzw. vom System gemeldeter Verlust der aktiven Verbindung, nicht ein Zertifikats- oder DNS-Problem.

## Belege aus den Logs

| Export | Feststellung |
| --- | --- |
| 12.07., App 0.6.25 | Ein echter `NullPointerException` in `FeedInteractionSnapshot.getCommentPreviewLimit()` führte zu `dashboard_load_failed` und fehlgeschlagenem Feed-Refresh. Dieser konkrete Codepfad ist im aktuellen Client inzwischen abgesichert, bleibt aber als Regressionstest relevant. |
| 18.07., App 0.6.29 | Extra-Queue-Eintrag (76.301 B) vom 17.07. wurde nach Worker-Abbruch erneut versucht und am 18.07. zweimal mit HTTP 403 abgelehnt. Der Fehler ist nicht Netzwerk; die Verbindung war validiertes WLAN. |
| 23.07., App 0.6.30 | 51 Feed-Refresh-Ergebnisse, 14 Refresh-Pläne und 163 Viewport-Änderungen im 400-Zeilen-Ausschnitt; ein erfolgreicher Refresh dauerte 138 s auf Mobilfunk. Ein Extra-Upload selbst gelang. |
| 02.08., App 0.6.30 | Extra-Upload (307.071 B) wurde durch `JobCancellationException` abgebrochen, nach 10 s wiederholt und bestätigt. Der Ablauf belegt Retry, aber auch, dass ein abgebrochener Worker eine vollständige erneute Übertragung verursachen kann. Im selben Ausschnitt: 242 Viewport-Änderungen, 22 Feed-Refresh-Ergebnisse, zehn Network-Recovery-Starts. |

Die sehr vielen `feed_viewport_anchor_changed`-Einträge sind überwiegend Diagnose-Rauschen, nicht je ein Netzwerkrequest. Sie erhöhen aber I/O, Diagnosegröße und die schwer lesbare Signalmenge. Sie sollten gedrosselt oder nur als zusammengefasste Metrik gespeichert werden.

## Ursache im aktuellen Code

- `GET /api/hub/timeline` akzeptiert nur `limit`, keinen Cursor, `since` oder ETag. Das Backend baut jedes Mal bis zu 80 Einträge aus einem festen Sieben-Tage-Fenster und aktualisiert beim Lesen unmittelbar `hub_timeline_last_viewed_at`.
- Der Disk-Cache schneidet die Timeline auf 24 Einträge ab; der Hub-Bootstrap auf 12 bzw. serverseitig acht Vorschau-Einträge. Beim Bootstrap wird diese Vorschau in denselben Timeline-State geschrieben und als frisch markiert. Der Timeline-Tab verzichtet innerhalb von 60 Sekunden auf den Vollabruf.
- Der Feed verwendet Fensterabrufe um den sichtbaren Tag, aber keine persistierte Feed-Metadaten-/Medien-Cache-Schicht. Nach Prozessstart entsteht deshalb `empty_feed`; Hub-Warm-Cache kann das nicht beheben.
- Automatische Feed-Refreshes laufen mit 25 s Basisintervall plus Jitter. Netzwerkwechsel und Wiederherstellung setzen zusätzlich erzwungene Refreshes an. Die Entscheidungen sind nicht an ein Datenbudget, eine sichtbare Oberfläche oder eine Änderungsmarke des Servers gekoppelt.
- Queue-Dateien und Queue-Metadaten sind zwar persistent und WorkManager wird verwendet. Für einen verspäteten `extra`-Upload existiert jedoch keine fachliche Konfliktbehandlung: Die Serverregel `extra unavailable during daily moment window` produziert 403. Ein Anhang an einen vorhandenen Beitrag ist technisch ein separater `ATTACHMENT`-Modus, wurde aber im vorliegenden Fehlerfall nicht verwendet.

## Fix-Roadmap

### P0 – Datenverlust und falscher Timeline-Stand

1. **Queue-Konflikt für Extra-Uploads lösen.** Für `403 extra_window_blocked` den Eintrag nicht still endgültig verwerfen: Status `ACTION_REQUIRED`, verständliche Erklärung und drei explizite Optionen: bei nächster erlaubter Gelegenheit als Extra senden, als Anhang an einen wählbaren eigenen Beitrag senden oder löschen. Falls Produktregel erlaubt, soll der Server bei einer vor dem Fenster aufgenommenen, offline gequeue'ten Extra-Aufnahme eine eng begrenzte Offline-Gnade gewähren. Entscheidung und Ziel-ID müssen persistent sein.
2. **Upload-Queue gegen Worker-Abbruch härten.** Lease/Claim atomar halten, Abbruchursache als transient behandeln, nicht parallel neu planen, und Server-Antworten mit `upload_client_id` idempotent bestätigen. Nach Prozessneustart muss jeder Queue-Status reproduzierbar fortgesetzt werden. Tests: Offline → App killen → Netzwechsel → Neustart → Erfolg; 403-Konflikt; Antwort verloren nach vollständigem Body-Upload.
3. **Timeline-Quelle und Frische trennen.** Bootstrap-Vorschau darf den Volltimeline-State nicht ersetzen. Sie wird als `preview` geführt; beim Öffnen des Timeline-Tabs erfolgt ein Vollabgleich, wenn kein vollständiger Cache vorliegt – unabhängig von der Bootstrap-Frische. Den vollständigen Cache mit `complete`, `newestSortAt` und `nextCursor` speichern.

### P1 – Direkt schneller Feed und geringer Datenverbrauch

4. **Persistenten Feed-Cache einführen.** Pro Tag Metadaten, Sortierung, ETag/Revision und Medienreferenzen persistieren; zuerst sofort aus Disk rendern, danach nur sichtbaren/neuesten Tag inkrementell prüfen. Kein `empty_feed` nach normalem Prozessneustart, sofern ein gültiger Cache existiert.
5. **Delta-Protokoll für Timeline und Feed einführen.** API um `cursor` bzw. monotone `revision`/`updatedSince` plus `ETag`/`If-None-Match` erweitern. Bei keiner Änderung 304 bzw. minimalen Delta-Response liefern. Der Client führt Merge/Deduplikation per stabiler ID aus; ein Refresh darf nicht die vorhandene Liste ersetzen oder zum Vortag zurückspringen.
6. **Refresh-Policy begrenzen.** Auto-Refresh nur bei sichtbarem Feed, App im Vordergrund, abgelaufener Frische und nicht gemessener Sparsamkeitsphase. Bei Mobilfunk längere Intervalle; bei `no_active_network` einen gemeinsamen Circuit-Breaker für Feed, Hub und Probe nutzen. Netzwerkwechsel erzeugt genau einen entprellten Delta-Abgleich, keine konkurrierenden Vollfenster.
7. **Medien-Cache prüfen und vereinheitlichen.** HTTP-Cache für Thumbnails aktiv nutzen; unterschiedliche Thumbnail-/Original-URLs vermeiden; Preload nur über WLAN oder explizit opt-in. Für Listen immer Thumbnail, Original erst beim Öffnen. Offline gespeicherte, bereits gezeigte Medien nicht erneut laden.

### P2 – Unabhängiges Datenstrom-Debugging

8. **Clientseitige Netzbuchhaltung bauen.** Ein dedizierter `NetworkUsageLedger` erfasst Request/Response-Bytes, Dauer, HTTP-Code, Retry, Cache-Status, Endpunktklasse, Netztyp und Vorder-/Hintergrundkontext. Ein OkHttp-Interceptor misst Header- und Body-Bytes pro Daily-Request; Upload-Fortschritt ergänzt die tatsächlichen gesendeten Bytes.
9. **Unabhängige Gegenmessung ergänzen.** Bei Sessionstart/-ende, App-Vorder-/Hintergrund und Workerstart/-ende die Android-UID-Zähler `TrafficStats.getUidRxBytes/getUidTxBytes` als Delta aufnehmen. Diese Messung umfasst auch Downloads, die an einzelnen Client-Hooks vorbeigehen. Sie wird als getrennte Kennzahl neben dem HTTP-Ledger ausgewiesen; Differenzen werden sichtbar statt kaschiert.
10. **Diagnoseansicht und Export.** Neue lokale Seite „Datenverbrauch“ mit Heute/7 Tage, WLAN vs. Mobilfunk, Upload/Download, Cache-Treffern, Top-Endpunkten, Bildbytes und Wiederholungsquote. Diagnoseexport erhält nur aggregierte, datensparsame Werte; keine URLs mit Tokens und keine Bildinhalte. Serverseitige `bytes_in/out` bleiben reine Kontrollmessung.

## Abnahmekriterien und Messziele

- Ein gequeue'tes Zusatzbild ist nach Offline/Neustart entweder bestätigt oder mit einer klaren, nicht destruktiven Nutzerentscheidung sichtbar; kein unsichtbares endgültiges Scheitern.
- Timeline öffnet aus Disk in unter 300 ms und zeigt nach der Synchronisation alle seit dem letzten Cursor neuen Einträge; Bootstrap-Vorschau kann den Vollstand nicht kürzen.
- Direktes Feed-Öffnen nach Neustart rendert den letzten persistierten Stand ohne Blocker; bei unverändertem Serverstand fallen nur ein kleiner Validierungsrequest und keine Bild-Redownloads an.
- Auf Mobilfunk dokumentiert die Diagnose pro Session exakt die UID-Deltas und plausibel zuordenbare HTTP-Bytes. Ziel nach P1: mindestens 70 % weniger Feed-/Timeline-Response-Bytes bei wiederholtem Öffnen ohne neue Inhalte; kein automatischer Feed-Vollabruf im 25–40-s-Takt im Hintergrund.
- Instrumentierte Tests decken Offline-Queue, App-Prozessende, Netzwerkwechsel, 304/Delta-Merge, Cache-Migration und Mobilfunk-Sparmodus ab. Ein End-to-End-Test misst direkten Feedstart gegenüber Hub → Timeline → Feed; der direkte Pfad darf nicht langsamer sein.

## Empfohlene Reihenfolge

Zuerst P0.1–P0.3 als ein Release, weil sie sichtbare Ablehnungen und den falschen Timeline-Eindruck beheben. Danach P1.4–P1.6 gemeinsam, da Disk-Cache ohne Delta-Protokoll den Verbrauch nur teilweise reduziert. P2 sollte parallel als Messbasis vorbereitet, aber erst nach der neuen Cache-/Sync-Architektur als verbindliche Optimierungsgrundlage verwendet werden.
