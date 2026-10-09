/**
 * TemizWeb ayarlar sayfası.
 *
 * Genel filtreyi, izleme listesini ve duraklatılan site listesini yönetir.
 * Tüm değişiklikler yalnızca chrome.storage.local içinde tutulur.
 */

import {
  MAX_PAUSED_SITES,
  STORAGE_KEYS,
  formatRuleCount,
  normalizeDomain,
  sanitizePausedSites,
  sendMessage
} from "./shared/temizweb.mjs";

const errorMessage = document.querySelector("#error");
const adsToggle = document.querySelector("#toggle-ads");
const trackingToggle = document.querySelector("#toggle-tracking");
const addForm = document.querySelector("#add-site");
const siteInput = document.querySelector("#site-input");
const addButton = document.querySelector("#add-button");
const pausedList = document.querySelector("#paused-list");
const emptyState = document.querySelector("#empty-state");
const clearAllButton = document.querySelector("#clear-all");
const resetAllButton = document.querySelector("#reset-all");
const adRuleCount = document.querySelector("#ad-rule-count");
const trackingRuleCount = document.querySelector("#tracking-rule-count");
const pausedCount = document.querySelector("#paused-count");

/** @type {{ enabled: boolean, trackingEnabled: boolean, pausedSites: string[] }} */
let state = { enabled: true, trackingEnabled: false, pausedSites: [] };

function showError(message) {
  errorMessage.textContent = message;
  errorMessage.hidden = false;
}

function clearError() {
  errorMessage.hidden = true;
  errorMessage.textContent = "";
}

function render() {
  adsToggle.textContent = state.enabled ? "Açık" : "Kapalı";
  adsToggle.setAttribute("aria-pressed", String(state.enabled));
  adsToggle.disabled = false;

  trackingToggle.textContent = state.trackingEnabled ? "Açık" : "Kapalı";
  trackingToggle.setAttribute("aria-pressed", String(state.trackingEnabled));
  trackingToggle.disabled = !state.enabled;

  renderPausedSites();
}

function renderPausedSites() {
  pausedList.replaceChildren();

  for (const site of state.pausedSites) {
    const item = document.createElement("li");

    const label = document.createElement("span");
    label.className = "domain";
    label.textContent = site;

    const remove = document.createElement("button");
    remove.type = "button";
    remove.className = "ghost";
    remove.textContent = "Kaldır";
    remove.setAttribute("aria-label", `${site} duraklatmasını kaldır`);
    remove.addEventListener("click", () => {
      void setPausedSites(state.pausedSites.filter((entry) => entry !== site));
    });

    item.append(label, remove);
    pausedList.append(item);
  }

  emptyState.hidden = state.pausedSites.length > 0;
  pausedList.hidden = state.pausedSites.length === 0;
  clearAllButton.disabled = state.pausedSites.length === 0;
  resetAllButton.disabled = false;
  pausedCount.textContent = `${state.pausedSites.length} / ${MAX_PAUSED_SITES}`;
}

async function setPausedSites(sites) {
  clearError();
  try {
    const response = await sendMessage("set-paused-sites", { sites });
    state = response.state;
    render();
  } catch (error) {
    showError(error.message || "Duraklatılan site listesi güncellenemedi.");
  }
}

async function loadRuleCounts() {
  const targets = [
    { element: adRuleCount, file: "rules/ads.json" },
    { element: trackingRuleCount, file: "rules/tracking.json" }
  ];

  await Promise.all(
    targets.map(async ({ element, file }) => {
      try {
        const response = await fetch(chrome.runtime.getURL(file));
        if (!response.ok) throw new Error(`HTTP ${response.status}`);
        const rules = await response.json();
        element.textContent = formatRuleCount(rules.length);
      } catch {
        element.textContent = "Okunamadı";
      }
    })
  );
}

adsToggle.addEventListener("click", async () => {
  clearError();
  try {
    const response = await sendMessage("set-enabled", { enabled: !state.enabled });
    state = response.state;
    render();
  } catch (error) {
    showError(error.message || "Filtre durumu değiştirilemedi.");
  }
});

trackingToggle.addEventListener("click", async () => {
  clearError();
  try {
    const response = await sendMessage("set-tracking", { enabled: !state.trackingEnabled });
    state = response.state;
    render();
  } catch (error) {
    showError(error.message || "İzleme filtresi değiştirilemedi.");
  }
});

addForm.addEventListener("submit", (event) => {
  event.preventDefault();
  const domain = normalizeDomain(siteInput.value);
  if (!domain) {
    siteInput.setAttribute("aria-invalid", "true");
    showError("Geçerli bir alan adı girin; örneğin ornek.com");
    siteInput.focus();
    return;
  }
  if (state.pausedSites.includes(domain)) {
    siteInput.setAttribute("aria-invalid", "true");
    showError(`${domain} listede zaten var.`);
    return;
  }
  if (state.pausedSites.length >= MAX_PAUSED_SITES) {
    showError(`En fazla ${MAX_PAUSED_SITES} site duraklatılabilir.`);
    return;
  }

  siteInput.removeAttribute("aria-invalid");
  siteInput.value = "";
  void setPausedSites([...state.pausedSites, domain]);
});

siteInput.addEventListener("input", () => {
  siteInput.removeAttribute("aria-invalid");
  clearError();
});

clearAllButton.addEventListener("click", () => {
  void setPausedSites([]);
});

resetAllButton.addEventListener("click", async () => {
  clearError();
  try {
    const response = await sendMessage("reset");
    state = response.state;
    render();
  } catch (error) {
    showError(error.message || "Ayarlar sıfırlanamadı.");
  }
});

async function init() {
  try {
    const [response] = await Promise.all([sendMessage("get-state"), loadRuleCounts()]);
    state = response.state;
    render();
  } catch (error) {
    showError("Ayarlar okunamadı. Uzantıyı chrome://extensions sayfasından yeniden yüklemeyi deneyin.");
    adsToggle.disabled = true;
    trackingToggle.disabled = true;
    console.error(error);
  }
}

void init();
