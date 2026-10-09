/**
 * Kural derleme zincirinin testleri: rules/domains.json → rules/*.json
 */

import test from "node:test";
import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";
import { fileURLToPath } from "node:url";
import path from "node:path";

import {
  BLOCKED_RESOURCE_TYPES,
  LIST_IDS,
  MAX_STATIC_RULES,
  assertDomain,
  buildRules,
  compileRules
} from "../scripts/build-rules.mjs";

const ROOT = path.resolve(path.dirname(fileURLToPath(import.meta.url)), "..");

async function readJson(relativePath) {
  return JSON.parse(await readFile(path.join(ROOT, relativePath), "utf8"));
}

const source = await readJson("rules/domains.json");
const compiled = compileRules(source);

test("kaynak dosyası yeterli sayıda alan adı içerir", () => {
  assert.ok(source.length >= 90, `beklenenden az alan adı var: ${source.length}`);
  assert.ok(compiled.ads.length >= 80);
  assert.ok(compiled.tracking.length >= 20);
});

test("derleme çıktısı beklenen yapıda", () => {
  for (const list of ["ads", "tracking"]) {
    const rules = compiled[list];
    assert.ok(rules.length > 0 && rules.length <= MAX_STATIC_RULES);
    assert.deepEqual(
      rules.map((rule) => rule.id),
      Array.from({ length: rules.length }, (_, index) => index + 1),
      "kimlikler 1'den başlayarak boşluksuz olmalı"
    );
  }
});

test("kayıtlar listelerine doğru dağıtılır", () => {
  const adsDomains = new Set(compiled.ads.map((rule) => rule.condition.urlFilter.slice(2, -1)));
  const trackingDomains = new Set(
    compiled.tracking.map((rule) => rule.condition.urlFilter.slice(2, -1))
  );

  for (const entry of source) {
    if (entry.list === "ads") {
      assert.ok(adsDomains.has(entry.domain), `${entry.domain} reklam listesinde olmalı`);
      assert.ok(!trackingDomains.has(entry.domain), `${entry.domain} iki listede birden olamaz`);
    } else {
      assert.ok(trackingDomains.has(entry.domain), `${entry.domain} izleme listesinde olmalı`);
    }
  }
});

test("kural koşulları güvenli kaynak türleriyle üretilir", () => {
  for (const rules of [compiled.ads, compiled.tracking]) {
    for (const rule of rules) {
      assert.equal(rule.action.type, "block");
      assert.equal(rule.priority, 1);
      assert.deepEqual(rule.condition.resourceTypes, BLOCKED_RESOURCE_TYPES);
      // Ana belge ve stil dosyaları bilerek engellenmez.
      assert.ok(!rule.condition.resourceTypes.includes("main_frame"));
      assert.ok(!rule.condition.resourceTypes.includes("stylesheet"));
    }
  }
});

test("diskteki kural dosyaları kaynakla eşleşiyor", async () => {
  for (const [list, file] of [
    ["ads", "rules/ads.json"],
    ["tracking", "rules/tracking.json"]
  ]) {
    const onDisk = await readJson(file);
    assert.deepEqual(onDisk, compiled[list], `${file} domains.json ile eşleşmiyor`);
  }
});

test("derleme tekrar çalıştırıldığında değişiklik üretmez", async () => {
  const result = await buildRules({ write: false });
  assert.deepEqual(result.changed, [], "kural dosyaları güncel değil");
  assert.equal(result.ads, compiled.ads.length);
  assert.equal(result.tracking, compiled.tracking.length);
});

test("compileRules hatalı kaynağı reddeder", () => {
  assert.throws(() => compileRules([]), /boş/);
  assert.throws(() => compileRules(null), /dizi/);
  assert.throws(() => compileRules([{ domain: "gecersiz_alan", list: "ads", note: "test" }]));
  assert.throws(
    () => compileRules([{ domain: "a.com", list: "ads", note: "test" }, { domain: "a.com", list: "ads", note: "test" }]),
    /birden fazla/
  );
  assert.throws(() => compileRules([{ domain: "a.com", list: "yok", note: "test" }]), /bilinmeyen liste/);
  assert.throws(() => compileRules([{ domain: "a.com", list: "ads", note: "" }]), /açıklama/);
  assert.throws(() => compileRules([{ domain: "localhost", list: "ads", note: "test" }]));
});

test("assertDomain alan adı biçimini doğrular", () => {
  assert.equal(assertDomain("ornek.com"), "ornek.com");
  assert.equal(assertDomain("a.b-c.ornek.co.uk"), "a.b-c.ornek.co.uk");
  assert.throws(() => assertDomain("ornek.com."), /geçersiz/);
  assert.throws(() => assertDomain("örnek.com"), /geçersiz/);
  assert.throws(() => assertDomain(""), /geçersiz/);
});

test("liste kimlikleri manifest'teki kural kümeleriyle uyumlu", () => {
  assert.equal(LIST_IDS.ads, "ad_domains");
  assert.equal(LIST_IDS.tracking, "tracking_domains");
});
