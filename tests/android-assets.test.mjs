/**
 * Android uygulamasının alan adı varlıklarını doğrular.
 *
 * Uzantı kuralları (`rules/ads.json`, `rules/tracking.json`) ve Android
 * varlıkları (`android/app/src/main/assets/*`) aynı kaynaktan üretilir; bu
 * test iki tarafın sapmadığını garantiler.
 */

import test from "node:test";
import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";
import path from "node:path";
import { fileURLToPath } from "node:url";

import {
  ASSET_FILES,
  buildAndroidAssets,
  compileAssets,
  serializeDomainList,
  serializeNotes
} from "../scripts/build-android-assets.mjs";
import { compileRules } from "../scripts/build-rules.mjs";

const ROOT = path.resolve(path.dirname(fileURLToPath(import.meta.url)), "..");

async function readAsset(file) {
  return readFile(path.join(ROOT, file), "utf8");
}

function domainsFromUrlFilter(rules) {
  return rules.map((rule) => rule.condition.urlFilter.replace(/^\|\|/, "").replace(/\^$/, ""));
}

test("android varlıkları kural kaynağıyla eşleşir", async () => {
  const result = await buildAndroidAssets({ write: false });

  assert.deepEqual(result.changed, [], "varlıklar güncel değil: npm run build:android çalıştırın");
  assert.equal(result.ads, 92);
  assert.equal(result.tracking, 32);
});

test("reklam varlığı ads.json ile aynı sırayı ve alan adlarını içerir", async () => {
  const source = JSON.parse(await readFile(path.join(ROOT, "rules/domains.json"), "utf8"));
  const compiled = compileRules(source);
  const asset = (await readAsset(ASSET_FILES.ads)).trimEnd().split("\n");

  assert.deepEqual(domainsFromUrlFilter(compiled.ads), asset);
});

test("izleme varlığı tracking.json ile aynı sırayı ve alan adlarını içerir", async () => {
  const source = JSON.parse(await readFile(path.join(ROOT, "rules/domains.json"), "utf8"));
  const compiled = compileRules(source);
  const asset = (await readAsset(ASSET_FILES.tracking)).trimEnd().split("\n");

  assert.deepEqual(domainsFromUrlFilter(compiled.tracking), asset);
});

test("notlar dosyası her alan adı için açıklama taşır", async () => {
  const notes = JSON.parse(await readAsset(ASSET_FILES.notes));
  const source = JSON.parse(await readFile(path.join(ROOT, "rules/domains.json"), "utf8"));

  assert.equal(notes.length, source.length);
  for (const entry of notes) {
    assert.ok(entry.domain, "alan adı eksik");
    assert.ok(entry.note.length >= 3, `${entry.domain}: açıklama çok kısa`);
    assert.ok(entry.list === "ads" || entry.list === "tracking");
  }
});

test("seri hale getirme kararlıdır", () => {
  assert.equal(serializeDomainList(["a.com", "b.com"]), "a.com\nb.com\n");
  assert.equal(
    serializeNotes([{ domain: "a.com", list: "ads", note: "Reklam ağı" }]),
    '[\n  {"domain":"a.com","list":"ads","note":"Reklam ağı"}\n]\n'
  );
});

test("kaynak doğrulaması boş listeleri reddeder", () => {
  assert.throws(() => compileAssets([]), /boş bir dizi/);
  assert.throws(
    () => compileAssets([{ domain: "a.com", list: "ads", note: "Reklam ağı" }]),
    /boş kaldı/
  );
  assert.throws(() => compileAssets([{ domain: "a.com", list: "ads", note: "ok" }]), /note/);
});
