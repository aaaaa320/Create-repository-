/**
 * Kural dosyalarını doğrular.
 *
 * Kontroller:
 *   - JSON ayrıştırılabilir ve dizi biçiminde
 *   - Kural kimlikleri pozitif tam sayı, yinelenmiyor ve 1'den başlayarak boşluksuz
 *   - Eylem "block", urlFilter "||alanadi^" biçiminde
 *   - resourceTypes bilinen türlerden oluşuyor ve boş değil
 *   - Alan adları küçük harf, yinelenmemiş ve geçerli biçimde
 *   - rules/*.json dosyaları rules/domains.json kaynağıyla eşleşiyor
 *
 * Kullanım: node scripts/validate-rules.mjs
 */

import { readFile } from "node:fs/promises";
import { fileURLToPath, pathToFileURL } from "node:url";
import path from "node:path";
import {
  ALL_RESOURCE_TYPES,
  BLOCKED_RESOURCE_TYPES,
  LIST_IDS,
  MAX_STATIC_RULES,
  assertDomain,
  compileRules
} from "./build-rules.mjs";

const ROOT = path.resolve(path.dirname(fileURLToPath(import.meta.url)), "..");

const problems = [];

function fail(message) {
  problems.push(message);
}

function parseJson(text, label) {
  try {
    return JSON.parse(text);
  } catch (error) {
    fail(`${label}: JSON ayrıştırılamadı — ${error.message}`);
    return null;
  }
}

async function readSource() {
  const raw = await readFile(path.join(ROOT, "rules/domains.json"), "utf8");
  const source = parseJson(raw, "rules/domains.json");
  if (!source) return null;
  try {
    return compileRules(source);
  } catch (error) {
    fail(`rules/domains.json derlenemedi — ${error.message}`);
    return null;
  }
}

function validateRuleset(rules, label, { expectedResourceTypes, expectedDomains }) {
  if (!Array.isArray(rules) || rules.length === 0) {
    fail(`${label}: dosya boş veya dizi biçiminde değil.`);
    return;
  }
  if (rules.length > MAX_STATIC_RULES) {
    fail(`${label}: ${rules.length} kural ${MAX_STATIC_RULES} sınırını aşıyor.`);
  }

  const ids = new Set();
  const domains = [];

  for (const [index, rule] of rules.entries()) {
    const where = `${label} #${index + 1}`;

    if (!Number.isInteger(rule.id) || rule.id < 1) {
      fail(`${where}: kural kimliği pozitif tam sayı olmalı (${String(rule.id)}).`);
    }
    if (ids.has(rule.id)) fail(`${where}: yinelenen kural kimliği ${rule.id}.`);
    ids.add(rule.id);

    if (rule.action?.type !== "block") {
      fail(`${where}: beklenen eylem "block", bulunan "${String(rule.action?.type)}".`);
    }
    if (rule.priority !== 1) {
      fail(`${where}: öncelik 1 olmalı (bulunan ${String(rule.priority)}).`);
    }

    const urlFilter = rule.condition?.urlFilter;
    if (typeof urlFilter !== "string" || !urlFilter.startsWith("||") || !urlFilter.endsWith("^")) {
      fail(`${where}: geçersiz alan adı filtresi "${String(urlFilter)}".`);
      continue;
    }

    const domain = urlFilter.slice(2, -1);
    try {
      assertDomain(domain, where);
    } catch (error) {
      fail(error.message);
      continue;
    }
    if (domain !== domain.toLowerCase()) fail(`${where}: alan adı küçük harf olmalı.`);
    domains.push(domain);

    const resourceTypes = rule.condition.resourceTypes;
    if (!Array.isArray(resourceTypes) || resourceTypes.length === 0) {
      fail(`${where}: resourceTypes gerekli.`);
    } else {
      for (const type of resourceTypes) {
        if (!ALL_RESOURCE_TYPES.has(type)) fail(`${where}: bilinmeyen resourceType "${type}".`);
      }
      if (expectedResourceTypes) {
        const same =
          resourceTypes.length === expectedResourceTypes.length &&
          resourceTypes.every((type, position) => type === expectedResourceTypes[position]);
        if (!same) {
          fail(`${where}: resourceTypes beklenen liste ile uyuşmuyor.`);
        }
      }
    }
  }

  // Kimlikler 1..N aralığını boşluksuz doldurmalı.
  for (let expected = 1; expected <= rules.length; expected += 1) {
    if (!ids.has(expected)) fail(`${label}: kural kimliği ${expected} eksik.`);
  }

  const uniqueDomains = new Set(domains);
  if (uniqueDomains.size !== domains.length) {
    const duplicated = domains.filter((domain, index) => domains.indexOf(domain) !== index);
    fail(`${label}: yinelenen alan adları — ${[...new Set(duplicated)].join(", ")}`);
  }

  if (expectedDomains) {
    const expected = new Set(expectedDomains);
    const actual = new Set(domains);
    const missing = [...expected].filter((domain) => !actual.has(domain));
    const extra = [...actual].filter((domain) => !expected.has(domain));
    if (missing.length > 0) fail(`${label}: eksik alan adları — ${missing.join(", ")}`);
    if (extra.length > 0) fail(`${label}: beklenmeyen alan adları — ${extra.join(", ")}`);
  }
}

async function main() {
  const compiled = await readSource();

  const targets = [
    { file: "rules/ads.json", list: "ads" },
    { file: "rules/tracking.json", list: "tracking" }
  ];

  for (const { file, list } of targets) {
    const raw = await readFile(path.join(ROOT, file), "utf8");
    const rules = parseJson(raw, file);
    if (!rules) continue;
    validateRuleset(rules, file, {
      expectedResourceTypes: BLOCKED_RESOURCE_TYPES,
      expectedDomains: compiled
        ? compiled[list].map((rule) => rule.condition.urlFilter.slice(2, -1))
        : null
    });
  }

  if (problems.length > 0) {
    for (const problem of problems) console.error(`HATA: ${problem}`);
    console.error(`\n${problems.length} sorun bulundu.`);
    process.exitCode = 1;
    return;
  }

  const adCount = JSON.parse(await readFile(path.join(ROOT, "rules/ads.json"), "utf8")).length;
  const trackingCount = JSON.parse(
    await readFile(path.join(ROOT, "rules/tracking.json"), "utf8")
  ).length;
  console.log(
    `OK — ${adCount} reklam kuralı ve ${trackingCount} izleme kuralı doğrulandı ` +
      `(kimlikler boşluksuz, alan adları tekildir, kaynak türleri geçerli, ` +
      `kural kümeleri "${LIST_IDS.ads}"/"${LIST_IDS.tracking}").`
  );
}

await main();
