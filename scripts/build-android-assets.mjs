/**
 * rules/domains.json kaynağını Android uygulamasının varlıklarına (assets) derler.
 *
 * Çıktılar (android/app/src/main/assets/):
 *   ads-domains.txt      — reklam ağı alan adları, satır başına bir alan adı
 *   tracking-domains.txt — izleme/ölçüm alan adları, satır başına bir alan adı
 *   domain-notes.json    — her alan adı için liste + açıklama (uygulamada bilgi amaçlı)
 *
 * Tarayıcı uzantısı ve Android uygulaması böylece aynı tek kaynaktan beslenir;
 * alan adı listeleri hiçbir zaman elle kopyalanmaz.
 *
 * Kullanım:
 *   node scripts/build-android-assets.mjs           # varlıkları yaz
 *   node scripts/build-android-assets.mjs --check   # yalnızca eşitliği doğrula (CI için)
 */

import { readFile, writeFile } from "node:fs/promises";
import { fileURLToPath, pathToFileURL } from "node:url";
import path from "node:path";

import { assertDomain, LIST_IDS } from "./build-rules.mjs";

const ROOT = path.resolve(path.dirname(fileURLToPath(import.meta.url)), "..");
const SOURCE_FILE = "rules/domains.json";
const ASSET_DIR = "android/app/src/main/assets";

export const ASSET_FILES = {
  ads: `${ASSET_DIR}/ads-domains.txt`,
  tracking: `${ASSET_DIR}/tracking-domains.txt`,
  notes: `${ASSET_DIR}/domain-notes.json`
};

/** Alan adı listesi dosyaları: sıralı, satır başına bir alan adı, sonda tek satır sonu. */
export function serializeDomainList(domains) {
  return `${domains.join("\n")}\n`;
}

/**
 * domain-notes.json için elle biçimlendirme: kayıt başına tek satır.
 * JSON.stringify çıktısı deterministiktir; dosya diff'leri okunur kalır.
 */
export function serializeNotes(entries) {
  const body = entries.map((entry) => `  ${JSON.stringify(entry)}`).join(",\n");
  return `[\n${body}\n]\n`;
}

/** Kaynağı doğrular ve listelere göre gruplanmış, sıralı kayıtlar döndürür. */
export function compileAssets(source) {
  if (!Array.isArray(source) || source.length === 0) {
    throw new Error(`${SOURCE_FILE} boş bir dizi olmalı.`);
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

  const entries = [...seen.values()].sort((a, b) => a.domain.localeCompare(b.domain));
  const domains = { ads: [], tracking: [] };
  for (const entry of entries) domains[entry.list].push(entry.domain);

  for (const [list, values] of Object.entries(domains)) {
    if (values.length === 0) throw new Error(`"${list}" listesi boş kaldı.`);
  }

  return { entries, domains };
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
 * Varlıkları derler ve isteğe bağlı olarak diske yazar.
 * @param {{ write?: boolean }} options
 * @returns {Promise<{ ads: number, tracking: number, changed: string[] }>}
 */
export async function buildAndroidAssets({ write = true } = {}) {
  const source = JSON.parse(await readFile(path.join(ROOT, SOURCE_FILE), "utf8"));
  const { entries, domains } = compileAssets(source);

  const outputs = [
    [ASSET_FILES.ads, serializeDomainList(domains.ads)],
    [ASSET_FILES.tracking, serializeDomainList(domains.tracking)],
    [ASSET_FILES.notes, serializeNotes(entries)]
  ];

  const changed = [];
  for (const [file, next] of outputs) {
    const current = await readIfExists(file);
    if (current === next) continue;
    changed.push(file);
    if (write) await writeFile(path.join(ROOT, file), next, "utf8");
  }

  return { ads: domains.ads.length, tracking: domains.tracking.length, changed };
}

async function main() {
  const checkOnly = process.argv.includes("--check");
  const result = await buildAndroidAssets({ write: !checkOnly });

  if (checkOnly && result.changed.length > 0) {
    console.error(
      `Android varlıkları ${SOURCE_FILE} ile eşleşmiyor: ${result.changed.join(", ")}\n` +
        "Güncellemek için `node scripts/build-android-assets.mjs` çalıştırın."
    );
    process.exitCode = 1;
    return;
  }

  console.log(
    `${checkOnly ? "Eşleşme" : "Yazıldı"} — ${result.ads} reklam alan adı, ` +
      `${result.tracking} izleme alan adı` +
      (result.changed.length > 0 ? ` (güncellenen: ${result.changed.join(", ")})` : "")
  );
}

const invokedDirectly =
  process.argv[1] !== undefined &&
  import.meta.url === pathToFileURL(path.resolve(process.argv[1])).href;

if (invokedDirectly) {
  await main();
}
