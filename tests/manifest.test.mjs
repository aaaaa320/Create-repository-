/**
 * Manifest, dosya bütünlüğü ve doğrulayıcı betiğinin uçtan uca testleri.
 */

import test from "node:test";
import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";
import { fileURLToPath } from "node:url";
import path from "node:path";
import { execFile } from "node:child_process";
import { promisify } from "node:util";

const execFileAsync = promisify(execFile);
const ROOT = path.resolve(path.dirname(fileURLToPath(import.meta.url)), "..");

async function readText(relativePath) {
  return readFile(path.join(ROOT, relativePath), "utf8");
}

const manifest = JSON.parse(await readText("manifest.json"));
const packageJson = JSON.parse(await readText("package.json"));

test("manifest MV3 ve gerekli alanları içerir", () => {
  assert.equal(manifest.manifest_version, 3);
  assert.equal(manifest.version, "2.0.0");
  assert.equal(manifest.background.service_worker, "service_worker.js");
  assert.equal(manifest.background.type, "module");
  assert.equal(manifest.action.default_popup, "popup.html");
  assert.equal(manifest.options_page, "options.html");
  assert.ok(manifest.permissions.includes("declarativeNetRequest"));
  assert.ok(manifest.permissions.includes("storage"));
  assert.ok(manifest.permissions.includes("activeTab"));
  // Gezinme geçmişini görmeyi gerektiren "tabs" izni bilerek istenmez.
  assert.ok(!manifest.permissions.includes("tabs"));
});

test("manifest'te tanımlı tüm dosyalar var", async () => {
  const files = [
    manifest.action.default_popup,
    manifest.options_page,
    manifest.background.service_worker,
    ...manifest.declarative_net_request.rule_resources.map((ruleset) => ruleset.path)
  ];

  for (const file of files) {
    await assert.doesNotReject(readText(file), `${file} bulunamadı`);
  }
});

test("manifest'teki kural kümeleri iki listeyle eşleşir", () => {
  const rulesets = manifest.declarative_net_request.rule_resources;
  const ids = rulesets.map((ruleset) => ruleset.id);
  assert.deepEqual(ids, ["ad_domains", "tracking_domains"]);

  const ads = rulesets.find((ruleset) => ruleset.id === "ad_domains");
  const tracking = rulesets.find((ruleset) => ruleset.id === "tracking_domains");
  assert.equal(ads.enabled, true, "reklam listesi varsayılan olarak açık olmalı");
  assert.equal(tracking.enabled, false, "izleme listesi varsayılan olarak kapalı olmalı");
  assert.equal(ads.path, "rules/ads.json");
  assert.equal(tracking.path, "rules/tracking.json");
});

test("manifest sürümü paket sürümüyle aynı", () => {
  assert.equal(manifest.version, packageJson.version);
});

test("paket betikleri çalıştırılabilir", () => {
  for (const script of ["build", "validate", "icons", "test"]) {
    assert.ok(packageJson.scripts[script], `scripts.${script} eksik`);
  }
  assert.equal(packageJson.type, "module");
});

test("doğrulayıcı betiği temiz depoda başarılı olur", async () => {
  const script = path.join(ROOT, "scripts/validate-rules.mjs");
  const { stdout } = await execFileAsync(process.execPath, [script], { cwd: ROOT });
  assert.match(stdout, /^OK —/);
});

test("kural derlemesi --check kipinde temiz depoda başarılı olur", async () => {
  const script = path.join(ROOT, "scripts/build-rules.mjs");
  const { stdout } = await execFileAsync(process.execPath, [script, "--check"], { cwd: ROOT });
  assert.match(stdout, /Eşleşme/);
});

test("simge dosyaları geçerli PNG olarak üretilmiştir", async () => {
  for (const size of [16, 32, 48, 128]) {
    const buffer = await readFile(path.join(ROOT, "icons", `icon${size}.png`));
    const signature = Buffer.from([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a]);
    assert.equal(buffer.subarray(0, 8).equals(signature), true, `icon${size}.png PNG değil`);
    assert.ok(buffer.length > 100);
  }
});

test("arayüz dosyaları paylaşılan modülü kullanır", async () => {
  const popup = await readText("popup.js");
  const options = await readText("options.js");
  const worker = await readText("service_worker.js");
  for (const [name, code] of [
    ["popup.js", popup],
    ["options.js", options],
    ["service_worker.js", worker]
  ]) {
    assert.match(code, /shared\/temizweb\.mjs/, `${name} paylaşılan modülü içe aktarmıyor`);
  }
  assert.match(await readText("popup.html"), /type="module"/);
  assert.match(await readText("options.html"), /type="module"/);
});

test("arayüzde dış kaynak (CDN, harici betik) yok", async () => {
  for (const file of ["popup.html", "options.html"]) {
    const html = await readText(file);
    assert.doesNotMatch(html, /src="https?:\/\//, `${file} dış betik yüklüyor`);
    assert.doesNotMatch(html, /href="https?:\/\//, `${file} dış stil yüklüyor`);
  }
});
