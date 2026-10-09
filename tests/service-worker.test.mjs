/**
 * Arka plan hizmetinin entegrasyon testleri.
 *
 * service_worker.js gerçek bir chrome.* API'si olmadan çalışamaz; bu yüzden
 * kullanılan API'lerin küçük bir taklidi kurulur ve mesaj akışı sınanır.
 */

import test from "node:test";
import assert from "node:assert/strict";

import { DYNAMIC_RULE_BASE } from "../shared/temizweb.mjs";

/** chrome.* API'lerinin taklidi; kaydedilen çağrıları ve dinleyicileri tutar. */
function createChromeMock(initialStorage = {}) {
  const store = { ...initialStorage };
  const listeners = {
    installed: [],
    startup: [],
    message: [],
    storageChanged: []
  };
  const calls = {
    rulesets: [],
    dynamicUpdates: [],
    badgeText: [],
    badgeColor: []
  };
  let dynamicRules = [];

  const chrome = {
    runtime: {
      id: "temizweb-test",
      onInstalled: { addListener: (fn) => listeners.installed.push(fn) },
      onStartup: { addListener: (fn) => listeners.startup.push(fn) },
      onMessage: { addListener: (fn) => listeners.message.push(fn) },
      lastError: undefined
    },
    storage: {
      local: {
        get: async (defaults) => {
          const result = {};
          for (const key of Object.keys(defaults ?? {})) {
            result[key] = key in store ? store[key] : defaults[key];
          }
          return result;
        },
        set: async (items) => {
          const changes = {};
          for (const [key, value] of Object.entries(items)) {
            changes[key] = { oldValue: store[key], newValue: value };
            store[key] = value;
          }
          for (const listener of listeners.storageChanged) {
            listener(changes, "local");
          }
        }
      },
      onChanged: { addListener: (fn) => listeners.storageChanged.push(fn) }
    },
    declarativeNetRequest: {
      updateEnabledRulesets: async (options) => {
        calls.rulesets.push(options);
      },
      getDynamicRules: async () => dynamicRules.map((rule) => ({ ...rule })),
      updateDynamicRules: async ({ removeRuleIds = [], addRules = [] }) => {
        calls.dynamicUpdates.push({ removeRuleIds: [...removeRuleIds], addRules });
        const removed = new Set(removeRuleIds);
        dynamicRules = dynamicRules.filter((rule) => !removed.has(rule.id));
        dynamicRules.push(...addRules);
      }
    },
    action: {
      setBadgeText: async ({ text }) => calls.badgeText.push(text),
      setBadgeBackgroundColor: async ({ color }) => calls.badgeColor.push(color)
    }
  };

  return { chrome, store, listeners, calls };
}

const mock = createChromeMock();
globalThis.chrome = mock.chrome;

// Modül yüklenirken dinleyicileri kaydettiği için tek bir kez içe aktarılır.
await import("../service_worker.js");

/** Mesajı gönderir ve yanıtı bekler. */
function dispatch(message) {
  return new Promise((resolve) => {
    mock.listeners.message[0](message, {}, resolve);
  });
}

/** Her testten önce taklidi ve kurulum akışını sıfırlar. */
async function reset() {
  for (const key of Object.keys(mock.store)) delete mock.store[key];
  mock.calls.rulesets.length = 0;
  mock.calls.dynamicUpdates.length = 0;
  mock.calls.badgeText.length = 0;
  mock.calls.badgeColor.length = 0;
  mock.listeners.installed[0]({ reason: "install" });
  await settle();
}

/** Zincirlenmiş mikro görevlerin tamamlanmasını bekler. */
function settle() {
  return new Promise((resolve) => setTimeout(resolve, 0));
}

test("kurulumda varsayılan durum uygulanır", async () => {
  await reset();

  assert.deepEqual(mock.calls.rulesets.at(-1), {
    enableRulesetIds: ["ad_domains"],
    disableRulesetIds: ["tracking_domains"]
  });
  assert.equal(mock.calls.badgeText.at(-1), "");
  assert.equal(mock.store.enabled, undefined, "depolama yalnızca kullanıcı değiştirdiğinde yazılır");
});

test("get-state depolamadaki tercihleri döndürür", async () => {
  await reset();
  const response = await dispatch({ type: "get-state" });

  assert.equal(response.ok, true);
  assert.equal(response.state.enabled, true);
  assert.equal(response.state.trackingEnabled, false);
  assert.deepEqual(response.state.pausedSites, []);
});

test("set-enabled filtresi kapatır ve rozeti güncellenir", async () => {
  await reset();
  const response = await dispatch({ type: "set-enabled", enabled: false });
  await settle();

  assert.equal(response.ok, true);
  assert.equal(response.state.enabled, false);
  assert.equal(mock.store.enabled, false);
  assert.deepEqual(mock.calls.rulesets.at(-1), {
    enableRulesetIds: [],
    disableRulesetIds: ["ad_domains", "tracking_domains"]
  });
  assert.equal(mock.calls.badgeText.at(-1), "OFF");
});

test("set-tracking izleme kural kümesini açar", async () => {
  await reset();
  const response = await dispatch({ type: "set-tracking", enabled: true });
  await settle();

  assert.equal(response.ok, true);
  assert.equal(response.state.trackingEnabled, true);
  assert.deepEqual(mock.calls.rulesets.at(-1), {
    enableRulesetIds: ["ad_domains", "tracking_domains"],
    disableRulesetIds: []
  });
  // İzleme listesi kapalıyken filtre tamamen kapanmamalı.
  assert.equal(response.state.enabled, true);
});

test("set-paused-sites dinamik izin kuralları üretir", async () => {
  await reset();
  const response = await dispatch({
    type: "set-paused-sites",
    sites: ["https://www.ornek.com/reklamlar", "gecersiz alan", "test.com", "ornek.com"]
  });
  await settle();

  assert.equal(response.ok, true);
  assert.deepEqual(response.state.pausedSites, ["ornek.com", "test.com"]);
  assert.deepEqual(mock.store.pausedSites, ["ornek.com", "test.com"]);

  const update = mock.calls.dynamicUpdates.at(-1);
  assert.equal(update.addRules.length, 4, "site başına iki kural beklenir");

  for (const [index, rule] of update.addRules.entries()) {
    assert.equal(rule.action.type, "allow");
    assert.ok(rule.priority > 1);
    assert.equal(rule.id, DYNAMIC_RULE_BASE + index);
  }
  assert.deepEqual(update.addRules[0].condition.requestDomains, ["ornek.com"]);
  assert.deepEqual(update.addRules[1].condition.initiatorDomains, ["ornek.com"]);
  assert.deepEqual(update.addRules[2].condition.requestDomains, ["test.com"]);
  assert.deepEqual(update.addRules[3].condition.initiatorDomains, ["test.com"]);
});

test("duraklatma listesi değiştiğinde eski kurallar kaldırılır", async () => {
  await reset();
  await dispatch({ type: "set-paused-sites", sites: ["a.com", "b.com"] });
  await settle();
  await dispatch({ type: "set-paused-sites", sites: ["a.com"] });
  await settle();

  const update = mock.calls.dynamicUpdates.at(-1);
  assert.deepEqual(update.removeRuleIds, [DYNAMIC_RULE_BASE, DYNAMIC_RULE_BASE + 1, DYNAMIC_RULE_BASE + 2, DYNAMIC_RULE_BASE + 3]);
  assert.equal(update.addRules.length, 2);

  const rules = await chrome.declarativeNetRequest.getDynamicRules();
  assert.equal(rules.length, 2);
  assert.deepEqual(rules.map((rule) => rule.id), [DYNAMIC_RULE_BASE, DYNAMIC_RULE_BASE + 1]);
});

test("duraklatılan site varken rozet uyarı gösterir", async () => {
  await reset();
  await dispatch({ type: "set-paused-sites", sites: ["ornek.com"] });
  await settle();

  assert.equal(mock.calls.badgeText.at(-1), "II");
});

test("reset tüm tercihleri varsayılana döndürür", async () => {
  await reset();
  await dispatch({ type: "set-enabled", enabled: false });
  await dispatch({ type: "set-tracking", enabled: true });
  await dispatch({ type: "set-paused-sites", sites: ["a.com"] });
  await settle();

  const response = await dispatch({ type: "reset" });
  await settle();

  assert.equal(response.ok, true);
  assert.equal(response.state.enabled, true);
  assert.equal(response.state.trackingEnabled, false);
  assert.deepEqual(response.state.pausedSites, []);
  assert.deepEqual(mock.calls.rulesets.at(-1), {
    enableRulesetIds: ["ad_domains"],
    disableRulesetIds: ["tracking_domains"]
  });
  assert.equal(mock.calls.badgeText.at(-1), "");
  assert.equal((await chrome.declarativeNetRequest.getDynamicRules()).length, 0);
});

test("bilinmeyen ve hatalı istekler nazikçe reddedilir", async () => {
  await reset();

  const unknown = await dispatch({ type: "boyle-bir-sey-yok" });
  assert.equal(unknown.ok, false);
  assert.match(unknown.error, /Bilinmeyen istek/);

  const invalid = await dispatch({ type: "set-enabled", enabled: "evet" });
  assert.equal(invalid.ok, false);

  const invalidSites = await dispatch({ type: "set-paused-sites", sites: "a.com" });
  assert.equal(invalidSites.ok, true);
  assert.deepEqual(invalidSites.state.pausedSites, []);
});

test("tarayıcı açılışında tercihler yeniden uygulanır", async () => {
  await reset();
  await dispatch({ type: "set-enabled", enabled: false });
  await settle();
  mock.calls.rulesets.length = 0;

  mock.listeners.startup[0]();
  await settle();

  assert.deepEqual(mock.calls.rulesets.at(-1), {
    enableRulesetIds: [],
    disableRulesetIds: ["ad_domains", "tracking_domains"]
  });
});

test("bozuk depolama değerleri zararsızlaştırılır", async () => {
  await reset();
  await chrome.storage.local.set({
    enabled: "hayır",
    trackingEnabled: "belki",
    pausedSites: "a.com, b.com"
  });
  await settle();

  const response = await dispatch({ type: "get-state" });
  assert.equal(response.state.enabled, true);
  assert.equal(response.state.trackingEnabled, false);
  assert.deepEqual(response.state.pausedSites, []);
});
