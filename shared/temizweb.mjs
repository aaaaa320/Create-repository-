/**
 * TemizWeb'in tarayıcı ve test tarafında paylaşılan yardımcıları.
 * Bu dosya yalnızca saf işlevler içerir; chrome.* API'lerine ancak
 * işlev çağrıldığında erişilir, böylece Node testlerinde sorunsuz çalışır.
 */

/** chrome.storage.local anahtarları. */
export const STORAGE_KEYS = {
  enabled: "enabled",
  trackingEnabled: "trackingEnabled",
  pausedSites: "pausedSites"
};

/** Depolamada hiçbir şey yokken kullanılan varsayılanlar. */
export const DEFAULT_STATE = {
  [STORAGE_KEYS.enabled]: true,
  [STORAGE_KEYS.trackingEnabled]: false,
  [STORAGE_KEYS.pausedSites]: []
};

/** Manifest'te tanımlı statik kural kümeleri. */
export const RULESET_IDS = {
  ads: "ad_domains",
  tracking: "tracking_domains"
};

/** Dinamik "izin" kurallarının kimlik aralığı (statik kimliklerle çakışmasın). */
export const DYNAMIC_RULE_BASE = 10_000;

/** Duraklatılan site başına üretilen kural sayısı. */
export const RULES_PER_SITE = 2;

/** Aynı anda duraklatılabilen site sayısı üst sınırı. */
export const MAX_PAUSED_SITES = 100;

/**
 * Dinamik "izin" kuralları statik "block" kurallarından daha yüksek
 * önceliğe sahiptir; aynı öncelikte olsa bile Chrome "allow"ı öne alır.
 */
export const ALLOW_RULE_PRIORITY = 2;

const DOMAIN_PATTERN = /^(?!-)(?:[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?\.)+[a-z]{2,63}$/;

/**
 * Kullanıcı girdisini (alan adı, URL, "www." ön ekli veya noktalı yazım)
 * karşılaştırılabilir bir alan adına dönüştürür. Geçersizse null döner.
 */
export function normalizeDomain(value) {
  if (typeof value !== "string") return null;

  let candidate = value.trim().toLowerCase();
  if (!candidate) return null;

  // Şema, yol, sorgu, parça, kullanıcı bilgisi ve portu ayıkla.
  candidate = candidate.replace(/^[a-z][a-z0-9+.-]*:\/\//, "");
  candidate = candidate.split(/[/?#]/, 1)[0];
  candidate = candidate.split("@").pop() ?? "";
  candidate = candidate.replace(/:\d+$/, "");

  // Joker ve nokta ön eklerini kaldır.
  candidate = candidate.replace(/^\*\./, "").replace(/^\.+/, "");
  candidate = candidate.replace(/\.+$/, "");

  // "www.example.com" ve "example.com" aynı site sayılır.
  if (candidate.startsWith("www.")) candidate = candidate.slice(4);

  if (candidate.length === 0 || candidate.length > 253) return null;
  if (!DOMAIN_PATTERN.test(candidate)) return null;
  return candidate;
}

/** Bir sekmenin URL'sinden ana makine adını çıkarır; http/https dışıysa null. */
export function hostnameFromUrl(value) {
  if (typeof value !== "string" || value.length === 0) return null;
  let url;
  try {
    url = new URL(value);
  } catch {
    return null;
  }
  if (url.protocol !== "http:" && url.protocol !== "https:") return null;
  const hostname = url.hostname.toLowerCase().replace(/\.$/, "");
  return hostname || null;
}

/** Duraklatma listesini temizler: geçersizleri atar, yinelenenleri birleştirir, sıralar. */
export function sanitizePausedSites(value, limit = MAX_PAUSED_SITES) {
  if (!Array.isArray(value)) return [];
  const unique = new Set();
  for (const item of value) {
    const domain = normalizeDomain(item);
    if (domain) unique.add(domain);
    if (unique.size >= limit) break;
  }
  return [...unique].sort((a, b) => a.localeCompare(b));
}

/**
 * Duraklatılan her site için iki dinamik kural üretir:
 *   1) requestDomains    → sitenin kendi alan adına yapılan isteklere dokunma
 *   2) initiatorDomains  → sitenin başlattığı üçüncü taraf isteklere dokunma
 * Birlikte "bu sitede filtrelemeyi duraklat" davranışını verir.
 */
export function buildAllowRules(sites, baseId = DYNAMIC_RULE_BASE) {
  return sanitizePausedSites(sites).flatMap((domain, index) => {
    const id = baseId + index * RULES_PER_SITE;
    return [
      {
        id,
        priority: ALLOW_RULE_PRIORITY,
        action: { type: "allow" },
        condition: { requestDomains: [domain] }
      },
      {
        id: id + 1,
        priority: ALLOW_RULE_PRIORITY,
        action: { type: "allow" },
        condition: { initiatorDomains: [domain] }
      }
    ];
  });
}

/** Duraklatılmış kuralların kimlik aralığı içinde olup olmadığını bildirir. */
export function isManagedRuleId(id) {
  return Number.isInteger(id) && id >= DYNAMIC_RULE_BASE;
}

/** Duraklatılmış kuralların kimliklerini listeden yeniden üretir (kaldırma için). */
export function managedRuleIds(count) {
  const ids = [];
  for (let index = 0; index < count; index += 1) {
    ids.push(DYNAMIC_RULE_BASE + index * RULES_PER_SITE, DYNAMIC_RULE_BASE + index * RULES_PER_SITE + 1);
  }
  return ids;
}

/**
 * Arka plana mesaj gönderir; yanıt gelmezse veya hata dönerse reddedilir.
 * Chrome 99+ runtime.sendMessage promise döndürür.
 */
export async function sendMessage(type, payload = {}) {
  const response = await chrome.runtime.sendMessage({ type, ...payload });
  if (!response?.ok) {
    throw new Error(response?.error || "Arka plan hizmeti yanıt veremedi.");
  }
  return response;
}

/** Kural sayısını Türkçe okunur metne çevirir. */
export function formatRuleCount(count) {
  return `${count} kural`;
}

/** Duraklatılan site listesinin ekranda gösterilecek özeti. */
export function formatPausedSummary(sites) {
  if (sites.length === 0) return "Hiçbir site duraklatılmadı";
  if (sites.length === 1) return sites[0];
  return `${sites[0]} ve ${sites.length - 1} site daha`;
}
