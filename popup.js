/**
 * TemizWeb açılır penceresi.
 *
 * Durumu arka plandan okur, genel filtreyi açar/kapatır ve
 * gezilen site için filtrelemeyi duraklatma/devam ettirme işlemi yapar.
 */

import {
  STORAGE_KEYS,
  hostnameFromUrl,
  normalizeDomain,
  sanitizePausedSites,
  sendMessage
} from "./shared/temizweb.mjs";

const statusBadge = document.querySelector("#status");
const statusCopy = document.querySelector("#status-copy");
const toggleButton = document.querySelector("#toggle");
const errorMessage = document.querySelector("#error");
const siteSection = document.querySelector("#site-section");
const siteHost = document.querySelector("#site-name");
const siteToggle = document.querySelector("#site-toggle");
const siteHint = document.querySelector("#site-hint");
const adRuleCount = document.querySelector("#ad-rule-count");
const trackingRuleCount = document.querySelector("#tracking-rule-count");
const pausedCount = document.querySelector("#paused-count");

/** @type {{ enabled: boolean, trackingEnabled: boolean, pausedSites: string[] }} */
let state = { enabled: true, trackingEnabled: false, pausedSites: [] };
/** @type {string|null} */
let currentSite = null;

function showError(message) {
  errorMessage.textContent = message;
  errorMessage.hidden = false;
}

function clearError() {
  errorMessage.hidden = true;
  errorMessage.textContent = "";
}

function renderStatus() {
  statusBadge.textContent = state.enabled ? "Açık" : "Kapalı";
  statusBadge.className = `status ${state.enabled ? "on" : "off"}`;
  statusCopy.textContent = state.enabled
    ? "Seçili reklam ağlarına giden istekler engelleniyor."
    : "Reklam filtresi şu anda devre dışı.";
  toggleButton.textContent = state.enabled ? "Filtreyi kapat" : "Filtreyi aç";
  toggleButton.className = state.enabled ? "" : "off";
  toggleButton.setAttribute("aria-pressed", String(state.enabled));
  toggleButton.disabled = false;

  pausedCount.textContent =
    state.pausedSites.length === 0 ? "Yok" : `${state.pausedSites.length} site`;
  pausedCount.className = state.pausedSites.length === 0 ? "" : "off";

  renderSite();
}

function renderSite() {
  if (!currentSite) {
    siteSection.hidden = true;
    return;
  }

  const paused = state.pausedSites.includes(currentSite);
  siteSection.hidden = false;
  siteHost.textContent = currentSite;
  siteToggle.textContent = paused ? "Bu sitede filtrelemeye devam et" : "Filtreyi bu sitede duraklat";
  siteToggle.setAttribute("aria-pressed", String(paused));
  siteToggle.disabled = false;
  siteHint.hidden = false;
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
        element.textContent = `${rules.length} kural`;
        element.className = "";
      } catch {
        element.textContent = "Okunamadı";
        element.className = "off";
      }
    })
  );
}

/** Aktif sekmenin alan adını okur (activeTab izni sayesinde erişilebilir). */
async function loadCurrentSite() {
  try {
    const [tab] = await chrome.tabs.query({ active: true, currentWindow: true });
    const hostname = hostnameFromUrl(tab?.url ?? "");
    // chrome://, dosya veya IP adresi gibi bağlamlarda site belli olmaz.
    currentSite = hostname ? normalizeDomain(hostname) : null;
  } catch {
    currentSite = null;
  }
}

async function toggleSitePause() {
  if (!currentSite) return;

  const paused = state.pausedSites.includes(currentSite);
  const nextSites = paused
    ? state.pausedSites.filter((site) => site !== currentSite)
    : [...state.pausedSites, currentSite];

  siteToggle.disabled = true;
  clearError();
  try {
    const response = await sendMessage("set-paused-sites", { sites: nextSites });
    state = response.state;
    renderStatus();
  } catch (error) {
    showError(error.message || "Duraklatma ayarı kaydedilemedi.");
    siteToggle.disabled = false;
  }
}

toggleButton.addEventListener("click", async () => {
  toggleButton.disabled = true;
  clearError();
  try {
    const response = await sendMessage("set-enabled", { enabled: !state.enabled });
    state = response.state;
    renderStatus();
  } catch (error) {
    showError(error.message || "Ayar değiştirilemedi. Uzantıyı yeniden yükleyip tekrar deneyin.");
    toggleButton.disabled = false;
  }
});

siteToggle.addEventListener("click", () => {
  void toggleSitePause();
});

document.querySelector("#open-options").addEventListener("click", (event) => {
  event.preventDefault();
  chrome.runtime.openOptionsPage();
});

async function init() {
  try {
    const [response] = await Promise.all([
      sendMessage("get-state"),
      loadCurrentSite(),
      loadRuleCounts()
    ]);
    state = response.state;
    renderStatus();
  } catch (error) {
    statusBadge.textContent = "Hata";
    statusBadge.className = "status off";
    statusCopy.textContent = "Uzantı durumu okunamadı.";
    showError("Uzantıyı chrome://extensions sayfasından yeniden yüklemeyi deneyin.");
    toggleButton.disabled = true;
    siteToggle.disabled = true;
    console.error(error);
  }
}

// Ayarlar sayfası açıkken yapılan değişiklikleri anında yansıt.
chrome.storage.onChanged.addListener((changes, areaName) => {
  if (areaName !== "local") return;
  if (STORAGE_KEYS.pausedSites in changes || STORAGE_KEYS.enabled in changes) {
    state = {
      ...state,
      ...(STORAGE_KEYS.enabled in changes ? { enabled: changes[STORAGE_KEYS.enabled].newValue !== false } : {}),
      ...(STORAGE_KEYS.pausedSites in changes
        ? { pausedSites: sanitizePausedSites(changes[STORAGE_KEYS.pausedSites].newValue) }
        : {})
    };
    renderStatus();
  }
});

void init();
