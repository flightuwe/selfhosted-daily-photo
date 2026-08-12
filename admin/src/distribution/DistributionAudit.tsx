import type { DistributionAuditItem } from "./distributionTypes";

type Props = {
  items: DistributionAuditItem[];
};

function displayDate(value: string): string {
  const date = new Date(value);
  return Number.isNaN(date.getTime()) ? value : date.toLocaleString("de-DE");
}

export function DistributionAudit({ items }: Props) {
  return (
    <section className="stack">
      <div>
        <h3>Audit</h3>
        <p className="small">
          Append-only Ereignisse; URL-Querywerte und fremde Antwortkörper werden nicht angezeigt.
        </p>
      </div>
      <div className="distribution-table-wrap">
        <table className="table">
          <thead>
            <tr><th>Zeit</th><th>Aktion</th><th>Actor</th><th>Ziel</th><th>Ergebnis</th></tr>
          </thead>
          <tbody>
            {items.length === 0 ? (
              <tr><td colSpan={5}>Noch keine Auditereignisse.</td></tr>
            ) : items.map((item) => (
              <tr key={item.id}>
                <td>{displayDate(item.createdAt)}</td>
                <td>{item.action}</td>
                <td>{item.actorUsername || `#${item.actorUserId || "–"}`}</td>
                <td>
                  {item.profileId ? `Profil #${item.profileId}` : ""}
                  {item.targetUserId ? ` Nutzer #${item.targetUserId}` : ""}
                </td>
                <td>
                  {item.testResult
                    ? item.testResult.success ? "Test erfolgreich" : `Test fehlgeschlagen (${item.testResult.errorClass || "unbekannt"})`
                    : item.errorClass || "ok"}
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
    </section>
  );
}
