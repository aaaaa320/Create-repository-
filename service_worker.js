/**
 * TemizWeb arka plan hizmeti.
 *
 * Sorumlulukları:
 *   - Kullanıcı tercihlerini (açık/kapalı, izleme listesi, duraklatılan siteler) uygulamak
 *   - Duraklatılan siteler için dinamik "izin" kurallarını üretmek ve kaldırmak
 *   - Araç çubuğu rozetini güncel tutmak
 *
 * Hiçbir ağ isteği yapılmaz, hiçbir gezinme verisi saklanmaz; yalnızca
 * chrome.storage.local içindeki tercihler kullanılır.
 */

import {
  DEFAULT_STATE,
  RULESET_IDS,
  STORAGE_KEYS,
  buildAllowRules,
  isManagedRuleId,
  sanitizePausedSites
} from "./shared/temizweb.mjs";

const BADGE_OFF_TEXT = "OFF";
const BADGE_OFF_COLOR = "#9d493b";
const BADGE_PAUSED_TEXT = "II";
const BADGE_PAUSED_COLOR = "#8a6d1f";

/** Depolamadaki tercihleri okur ve eksik/bozuk değerleri varsayılana çeker. */
async function readState() {
  const stored = await chrome.storage.local.get(DEFAULT_STATE);
  return {
    [STORAGE_KEYS.enabled]: stored[STORAGE_KEYS.enabled] !== false,
    [STORAGE_KEYS.trackingEnabled]: stored[STORAGE_KEYS.trackingEnabled] === true,
    [STORAGE_KEYS.pausedSites]: sanitizePausedSites(stored[STORAGE_KEYS.pausedSites])
  };
}

/** Statik kural kümelerini tercihlere göre açar/kapatır. */
async function applyRulesetState({ enabled, trackingEnabled }) {
  const enableRulesetIds = [];
  const disableRulesetIds = [];

  if (enabled) {
    enableRulesetIds.push(RULESET_IDS.ads);
    if (trackingEnabled) {
      enableRulesetIds.push(RULESET_IDS.tracking);
    } else {
      disableRulesetIds.push(RULESET_IDS.tracking);
    }
  } else {
    disableRulesetIds.push(RULESET_IDS.ads, RULESET_IDS.tracking);
  }

  await chrome.declarativeNetRequest.updateEnabledRulesets({
    enableRulesetIds,
    disableRulesetIds
  });
}

/**
 * Duraklatılan sitelerin dinamik kurallarını eşitler.
 * Yönetilen kimlik aralığındaki kuralları kaldırır, güncel listeyi ekler.
 */
async function applyPausedSites(pausedSites) {
  const sites = sanitizePausedSites(pausedSites);
  const existing = await chrome.declarativeNetRequest.getDynamicRules();
  const removeRuleIds = [];
  for (const rule of existing) {
    if (isManagedRuleId(rule.id)) removeRuleIds.push(rule.id);
  }

  await chrome.declarativeNetRequest.updateDynamicRules({
    removeRuleIds,
    addRules: buildAllowRules(sites)
  });

  return sites;
}

/** Tüm tercihleri kural kümelerine ve rozete yansıtır. */
async function applyState(state) {
  await applyRulesetState(state);
  await applyPausedSites(state[STORAGE_KEYS.pausedSites]);
  await refreshBadge();
}

/** Filtre kapalıysa rozette "OFF", duraklatılmış site varsa "II" gösterir. */
async function refreshBadge() {
  const { enabled, pausedSites } = await readState();

  let text = "";
  let color = BADGE_OFF_COLOR;

  if (!enabled) {
    text = BADGE_OFF_TEXT;
  } else if (pausedSites.length > 0) {
    text = BADGE_PAUSED_TEXT;
    color = BADGE_PAUSED_COLOR;
  }

  await chrome.action.setBadgeBackgroundColor({ color });
  await chrome.action.setBadgeText({ text });
}

/** Kurulum ve tarayıcı açılışında tercihleri geri yükler. */
async function restoreState() {
  try {
    await applyState(await readState());
  } catch (error) {
    console.error("TemizWeb durumu geri yüklenemedi:", error);
  }
}

chrome.runtime.onInstalled.addListener(() => {
  // Etkin kural kümeleri güncelleme sonrası sıfırlanır; bu yüzden her kurulumda uygula.
  void restoreState();
});

chrome.runtime.onStartup.addListener(() => {
  void restoreState();
});

// Depolama başka bir görünümden (popup/ayarlar) değiştirildiğinde rozeti tazele.
chrome.storage.onChanged.addListener((changes, areaName) => {
  if (areaName !== "local") return;
  const tracked = [STORAGE_KEYS.enabled, STORAGE_KEYS.trackingEnabled, STORAGE_KEYS.pausedSites];
  const relevant = tracked.some((key) => key in changes);
  if (relevant) void refreshBadge();
});

chrome.runtime.onMessage.addListener((message, _sender, sendResponse) => {
  const respond = (promise) => {
    promise.then(
      (result) => sendResponse({ ok: true, ...result }),
      (error) => sendResponse({ ok: false, error: error?.message ?? String(error) })
    );
    return true;
  };

  switch (message?.type) {
    case "get-state":
      return respond(readState().then((state) => ({ state })));

    case "set-enabled":
      if (typeof message.enabled !== "boolean") {
        return respond(Promise.reject(new Error("Geçersiz durum değeri.")));
      }
      return respond(
        (async () => {
          const state = await readState();
          const next = { ...state, [STORAGE_KEYS.enabled]: message.enabled };
          await chrome.storage.local.set({ [STORAGE_KEYS.enabled]: message.enabled });
          await applyState(next);
          return { state: next };
        })()
      );

    case "set-tracking":
      if (typeof message.enabled !== "boolean") {
        return respond(Promise.reject(new Error("Geçersiz durum değeri.")));
      }
      return respond(
        (async () => {
          const state = await readState();
          const next = { ...state, [STORAGE_KEYS.trackingEnabled]: message.enabled };
          await chrome.storage.local.set({ [STORAGE_KEYS.trackingEnabled]: message.enabled });
          await applyState(next);
          return { state: next };
        })()
      );

    case "set-paused-sites":
      return respond(
        (async () => {
          const state = await readState();
          const sites = sanitizePausedSites(message.sites);
          await chrome.storage.local.set({ [STORAGE_KEYS.pausedSites]: sites });
          const next = { ...state, [STORAGE_KEYS.pausedSites]: sites };
          await applyState(next);
          return { state: next };
        })()
      );

    case "reset":
      return respond(
        (async () => {
          const next = {
            [STORAGE_KEYS.enabled]: true,
            [STORAGE_KEYS.trackingEnabled]: false,
            [STORAGE_KEYS.pausedSites]: []
          };
          await chrome.storage.local.set(next);
          await applyState(next);
          return { state: next };
        })()
      );

    default:
      sendResponse({ ok: false, error: `Bilinmeyen istek: ${String(message?.type)}` });
      return false;
  }
});
