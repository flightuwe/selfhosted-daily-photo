const INDEX_URL = "https://releases.daily.harzcloud.de/index.json";

async function loadRelease() {
  try {
    const response = await fetch(INDEX_URL, { cache: "no-store" });
    if (!response.ok) throw new Error(`HTTP ${response.status}`);
    const index = await response.json();
    const latest = (index.releases || []).find(item => item.version === index.latest);
    if (!latest || !latest.apkUrl) return;

    const button = document.querySelector("#download-button");
    button.href = latest.apkUrl;
    button.textContent = `Daily ${latest.version} herunterladen`;
    button.classList.remove("disabled");
    button.removeAttribute("aria-disabled");
    document.querySelector("#release-status").textContent = "Signierte Android-APK · Installation wird von Android bestätigt";
    document.querySelector("#release-version").textContent = latest.version;
    document.querySelector("#release-sha").textContent = latest.sha256 || "–";
    document.querySelector("#release-date").textContent = latest.releasedAt ? new Date(latest.releasedAt).toLocaleDateString("de-DE") : "–";
    document.querySelector("#release-link").href = latest.releaseUrl || document.querySelector("#release-link").href;
  } catch (error) {
    console.warn("Release-Index ist noch nicht verfügbar", error);
  }
}

loadRelease();

