# Analytics improvement report — 2026-08-03

## Scope and evidence

This assessment uses the restricted production report generated at
2026-08-03 14:50 CEST. Its SQLite connection is read-only (`mode=ro` and
`PRAGMA query_only=ON`); it contains no tokens or raw client log messages.
The report covers the 30- and 90-day scheduler, API, upload, debug and storage
aggregates.

## Findings

| Area | Evidence | Interpretation | Priority |
| --- | --- | --- | --- |
| Scheduler | 30 normal scheduler triggers; P95 delay 0.5 min; two `not_lease_owner` blocks | Healthy single-owner lease behaviour, not an incident | Observe |
| API latency | Calendar, hub/bootstrap and dashboard/bootstrap have the largest long tails; a multi-route spike occurred on 2026-08-03 | The hub duplicated a full calendar construction; broad simultaneous spikes can additionally indicate container/SQLite/I/O contention | High |
| Client diagnostics | Most events are `no_active_network`, cancellation or lifecycle/refresh telemetry; true crashes and 5xx are rare | The old raw-count warning rule turned normal mobile states into daily alerts | High |
| Diagnostic upload | Historic clients periodically submitted already acknowledged local rows | This inflated endpoint traffic and database writes without new diagnostic value | High |
| Storage | `/` 12 GB total, 4.3 GB used, 6.9 GB free (39%); backend data 730 MB; logs 108 MB | No capacity incident. Upload media is the material Daily growth driver; Docker-layer allocation needs the privileged node report | Medium |

## Implemented changes

1. Hub dashboard preview reads only its 60 displayed calendar days instead of
   rebuilding the 365-day public calendar, while the full calendar endpoint
   remains unchanged.
2. Client diagnostic batches have stable request IDs. A successful upload is
   marked locally; a new occurrence clears that acknowledgement. Local
   diagnostics therefore remain visible without being posted repeatedly.
3. The API treats identical retried diagnostics as a read-only acknowledgement;
   changed local aggregates replace the existing aggregate rather than add a
   row. Aggregated counts remain visible to analytics.
4. History analytics classify signals as `server`, `crash`, `client`,
   `connectivity`, or `cancelled`. Only server/crash and repeated
   multi-user client failures create operational anomalies.
5. The admin history view now presents the categories separately, including
   per-day detail. Existing trend, conversion, cohort, composition and anomaly
   charts remain available.
6. The storage dashboard exposes a directory-level capacity breakdown and
   deployment includes bounded gateway-log rotation.

## Rollout and validation

The change set is `codex/storage-operations`, containing commits `4cde70c` and
`96ac012`. It must first be merged to `main`, pass GitHub CI and publish the
backend/admin images. Android request acknowledgement requires the next Android
release; backend deduplication already protects current clients.

After the documented `nodea -> pct exec 9204` backend/admin rollout:

1. Verify `/api/health/live`, admin login, writable upload path and scheduler
   health.
2. Read the constrained production report immediately and again after at least
   one normal app-use window.
3. Confirm that calendar/hub P95 and maximum latencies no longer show the prior
   common long-tail pattern, and that `/api/debug/client-log` writes do not
   grow for unchanged retries.
4. Treat an actual 5xx, crash, sustained multi-user client failure, filesystem
   usage above 75%, or uncontrolled log growth as a follow-up incident.

The report's historical `daily conversion` aggregate is not used as a product
source of truth because its observed counts did not match the canonical admin
history semantics. Product conversion remains computed by the backend admin
history endpoint.
