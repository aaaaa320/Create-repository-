/**
 * Arayüz dosyalarının tutarlılık testleri.
 *
 * Tarayıcı olmadan da yakalanabilen hatalar:
 *   - sözdizimi hataları (node --check)
 *   - HTML'de olmayan bir öğeyi sorgulayan betikler
 *   - yinelenen id değerleri
 *   - MV3'un yasakladığı satır içi olay işleyicileri
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

const pages = [
  { html: "popup.html", script: "popup.js" },
  { html: "options.html", script: "options.js" }
];

test("arayüz betikleri sözdizimi açısından geçerlidir", async () => {
  for (const file of ["popup.js", "options.js", "service_worker.js", "shared/temizweb.mjs"]) {
    await execFileAsync(process.execPath, ["--check", path.join(ROOT, file)]);
  }
});

test("betiklerin sorguladığı her öğe HTML'de vardır", async () => {
  for (const { html, script } of pages) {
    const markup = await readText(html);
    const code = await readText(script);

    const ids = new Set([...markup.matchAll(/\bid="([^"]+)"/g)].map((match) => match[1]));
    const queried = [...code.matchAll(/querySelector\("#([^"]+)"\)/g)].map((match) => match[1]);

    assert.ok(queried.length >= 5, `${script} yeterli öğe sorgulamıyor`);
    for (const id of queried) {
      assert.ok(ids.has(id), `${script}: "${id}" öğesi ${html} içinde yok`);
    }
  }
});

test("HTML kimlikleri tekildir", async () => {
  for (const { html } of pages) {
    const markup = await readText(html);
    const ids = [...markup.matchAll(/\bid="([^"]+)"/g)].map((match) => match[1]);
    assert.equal(new Set(ids).size, ids.length, `${html} içinde yinelenen id var`);
  }
});

test("MV3 arayüzlerinde satır içi olay işleyicisi yok", async () => {
  for (const { html } of pages) {
    const markup = await readText(html);
    assert.doesNotMatch(markup, /\son(click|change|submit|input|load)=/, `${html} satır içi işleyici içeriyor`);
    assert.doesNotMatch(markup, /<script>(?!\s*<\/script>)/, `${html} satır içi betik içeriyor`);
  }
});

test("düğmeler erişilebilirlik niteliklerine sahip", async () => {
  for (const { html } of pages) {
    const markup = await readText(html);
    const buttons = [...markup.matchAll(/<button\b[^>]*>([\s\S]*?)<\/button>/g)];
    assert.ok(buttons.length >= 2, `${html} içinde düğme bulunamadı`);

    for (const [button, label] of buttons) {
      assert.match(button, /type="/, `düğmede type eksik: ${button}`);
      // Erişilebilir ad: aria-pressed/aria-label ya da görünür metin.
      const hasAccessibleName =
        /aria-pressed=/.test(button) || /aria-label=/.test(button) || label.trim().length > 0;
      assert.ok(hasAccessibleName, `düğmede erişilebilir ad eksik: ${button}`);
    }
  }
});

test("diller ve karakter kümesi doğru", async () => {
  for (const { html } of pages) {
    const markup = await readText(html);
    assert.match(markup, /<html lang="tr">/);
    assert.match(markup, /<meta charset="utf-8">/);
  }
});

test("CSS koyu tema desteği içeriyor", async () => {
  for (const file of ["popup.css", "options.css"]) {
    const css = await readText(file);
    assert.match(css, /prefers-color-scheme:\s*dark/, `${file} koyu tema tanımı içermiyor`);
    assert.match(css, /prefers-reduced-motion|prefers-contrast/, `${file} hareket tercihini karşılamıyor`);
  }
});
