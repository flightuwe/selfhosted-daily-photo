import type { AdminUser } from "../api";
import type { DistributionProfileItem } from "./distributionTypes";

type Props = {
  users: AdminUser[];
  profiles: DistributionProfileItem[];
  busy: boolean;
  onAssign: (userId: number, profileId: number | null) => void;
};

export function DistributionUserAssignments({
  users,
  profiles,
  busy,
  onAssign,
}: Props) {
  const defaultProfile = profiles.find((item) => item.profile.isDefault)?.profile;
  return (
    <section className="stack">
      <div>
        <h3>Nutzerzuordnung</h3>
        <p className="small">
          Ohne Override verwenden Nutzer das Default-Profil
          {defaultProfile ? ` „${defaultProfile.name}“` : ""}.
        </p>
      </div>
      <div className="distribution-table-wrap">
        <table className="table">
          <thead>
            <tr><th>Nutzer</th><th>Effektives Profil</th><th>Zuweisung</th></tr>
          </thead>
          <tbody>
            {users.map((user) => {
              const assigned = profiles.find(
                (item) => item.profile.id === user.distributionProfileId,
              )?.profile;
              return (
                <tr key={user.id}>
                  <td>{user.username}</td>
                  <td>{assigned?.name || defaultProfile?.name || "Kein Default"}</td>
                  <td>
                    <select
                      disabled={busy}
                      value={user.distributionProfileId ?? ""}
                      onChange={(event) =>
                        onAssign(
                          user.id,
                          event.target.value ? Number(event.target.value) : null,
                        )
                      }
                    >
                      <option value="">Default verwenden</option>
                      {profiles.map((item) => (
                        <option key={item.profile.id} value={item.profile.id}>
                          {item.profile.name}{item.profile.enabled ? "" : " (deaktiviert)"}
                        </option>
                      ))}
                    </select>
                  </td>
                </tr>
              );
            })}
          </tbody>
        </table>
      </div>
    </section>
  );
}
