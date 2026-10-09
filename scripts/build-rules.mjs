/**
 * rules/domains.json kaynağını declarativeNetRequest kurallarına derler.
 *
 * Çıktılar:
 *   rules/ads.json       — varsayılan olarak açık olan reklam ağı listesi
 *   rules/tracking.json  — varsayılan olarak kapalı olan izleme/ölçüm listesi
 *
 * Kullanım:
 *   node scripts/build-rules.mjs            # kuralları yaz
 *   node scripts/build-rules.mjs --check    # yalnızca eşitliği doğrula (CI için)
 */

import { readFile, writeFile } from "node:fs/promises";
import { fileURLToPath, pathToFileURL } from "node:url";
import path from "node:path";

const ROOT = path.resolve(path.dirname(fileURLToPath(import.meta.url)), "..");

/** Kurallarda bilerek engellenen kaynak türleri. */
export const BLOCKED_RESOURCE_TYPES = [
  "script",
  "image",
  "xmlhttprequest",
  "sub_frame",
  "ping",
  "media"
];

/** declarativeNetRequest'te geçerli tüm kaynak türleri (doğrulama için). */
export const ALL_RESOURCE_TYPES = new Set([
  "main_frame",
  "sub_frame",
  "stylesheet",
  "script",
  "image",
  "font",
  "object",
  "xmlhttprequest",
  "ping",
  "csp_report",
  "media",
  "websocket",
  "other"
]);

/** Tek statik kural kümesindeki üst sınır (Chrome: GUARANTEED_MINIMUM_STATIC_RULES). */
export const MAX_STATIC_RULES = 30_000;

export const LIST_IDS = {
  ads: "ad_domains",
  tracking: "tracking_domains"
};

const LIST_FILES = {
  ads: "rules/ads.json",
  tracking: "rules/tracking.json"
};

const DOMAIN_PATTERN = /^(?!-)(?:[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?\.)+[a-z]{2,63}$/;

export function assertDomain(domain, context = "domain") {
  if (typeof domain !== "string" || !DOMAIN_PATTERN.test(domain) || domain.length > 253) {
    throw new Error(`${context}: geçersiz alan adı "${domain}".`);
  }
  return domain;
}

function readSource(source) {
  if (!Array.isArray(source) || source.length === 0) {
    throw new Error("rules/domains.json boş bir dizi olmalı.");
  }

  const seen = new Map();
  for (const [index, entry] of source.entries()) {
    if (typeof entry !== "object" || entry === null) {
      throw new Error(`Kayıt ${index}: nesne bekleniyordu.`);
    }
    const domain = String(entry.domain ?? "").trim().toLowerCase();
    assertDomain(domain, `Kayıt ${index}`);

    if (seen.has(domain)) {
      throw new Error(`"${domain}" birden fazla kez tanımlanmış (kayıt ${index}).`);
    }

    if (!LIST_IDS[entry.list]) {
      throw new Error(`Kayıt ${index} (${domain}): bilinmeyen liste "${entry.list}".`);
    }
    if (typeof entry.note !== "string" || entry.note.trim().length < 3) {
      throw new Error(`Kayıt ${index} (${domain}): açıklama (note) gerekli.`);
    }
    seen.set(domain, { domain, list: entry.list, note: entry.note.trim() });
  }

  return [...seen.values()].sort((a, b) => a.domain.localeCompare(b.domain));
}

/**
 * Kaynak girdiyi iki statik kural kümesine derer.
 * Kural kimlikleri listenin içinde 1'den başlar ve alan adı sırasına göre atanır.
 */
export function compileRules(source) {
  const entries = readSource(source);
  const output = { ads: [], tracking: [] };

  for (const entry of entries) {
    output[entry.list].push({
      id: output[entry.list].length + 1,
      priority: 1,
      action: { type: "block" },
      condition: {
        urlFilter: `||${entry.domain}^`,
        resourceTypes: [...BLOCKED_RESOURCE_TYPES]
      }
    });
  }

  for (const [list, rules] of Object.entries(output)) {
    if (rules.length === 0) throw new Error(`"${list}" listesi boş kaldı.`);
    if (rules.length > MAX_STATIC_RULES) {
      throw new Error(`"${list}" listesi ${MAX_STATIC_RULES} kural sınırını aşıyor.`);
    }
  }

  return output;
}

function serialize(rules) {
  return `${JSON.stringify(rules, null, 2)}\n`;
}

async function readIfExists(file) {
  try {
    return await readFile(path.join(ROOT, file), "utf8");
  } catch (error) {
    if (error && error.code === "ENOENT") return null;
    throw error;
  }
}

/**
 * Kuralları derler ve isteğe bağlı olarak diske yazar.
 * @param {{ write?: boolean }} options
 * @returns {Promise<{ ads: number, tracking: number, changed: string[] }>}
 */
export async function buildRules({ write = true } = {}) {
  const sourcePath = path.join(ROOT, "rules/domains.json");
  const source = JSON.parse(await readFile(sourcePath, "utf8"));
  const compiled = compileRules(source);

  const changed = [];
  for (const [list, file] of Object.entries(LIST_FILES)) {
    const next = serialize(compiled[list]);
    const current = await readIfExists(file);
    if (current !== next) {
      if (!write) changed.push(file);
      else {
        await writeFile(path.join(ROOT, file), next, "utf8");
        changed.push(file);
      }
    }
  }

  return {
    ads: compiled.ads.length,
    tracking: compiled.tracking.length,
    changed
  };
}

async function main() {
  const checkOnly = process.argv.includes("--check");
  const result = await buildRules({ write: !checkOnly });

  if (checkOnly && result.changed.length > 0) {
    console.error(
      `Kural dosyaları domains.json ile eşleşmiyor: ${result.changed.join(", ")}\n` +
        "Güncellemek için `node scripts/build-rules.mjs` çalıştırın."
    );
    process.exitCode = 1;
    return;
  }

  console.log(
    `${checkOnly ? "Eşleşme" : "Yazıldı"} — ${result.ads} reklam kuralı, ` +
      `${result.tracking} izleme kuralı` +
      (result.changed.length > 0 ? ` (güncellenen: ${result.changed.join(", ")})` : "")
  );
}

const invokedDirectly =
  process.argv[1] !== undefined &&
  import.meta.url === pathToFileURL(path.resolve(process.argv[1])).href;

if (invokedDirectly) {
  await main();
}
