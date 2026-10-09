/**
 * shared/temizweb.mjs paylaşılan yardımcılarının testleri.
 */

import test from "node:test";
import assert from "node:assert/strict";

import {
  DYNAMIC_RULE_BASE,
  MAX_PAUSED_SITES,
  RULES_PER_SITE,
  buildAllowRules,
  formatRuleCount,
  hostnameFromUrl,
  isManagedRuleId,
  managedRuleIds,
  normalizeDomain,
  sanitizePausedSites
} from "../shared/temizweb.mjs";

test("normalizeDomain alan adını sadeleştirir", () => {
  assert.equal(normalizeDomain("  Ornek.COM  "), "ornek.com");
  assert.equal(normalizeDomain("https://www.ornek.com/haber?id=1"), "ornek.com");
  assert.equal(normalizeDomain("http://alt.ornek.com:8080/yol"), "alt.ornek.com");
  assert.equal(normalizeDomain("*.ornek.com"), "ornek.com");
  assert.equal(normalizeDomain(".ornek.com."), "ornek.com");
  assert.equal(normalizeDomain("kullanici@ornek.com"), "ornek.com");
  assert.equal(normalizeDomain("örnek.com"), null, "IDN alan adı doğrudan kabul edilmez");
});

test("normalizeDomain geçersiz girdileri reddeder", () => {
  for (const value of [
    "",
    "   ",
    "localhost",
    "127.0.0.1",
    "-ornek.com",
    "ornek.",
    "ornek",
    "https://",
    "chrome://extensions",
    "file:///tmp/index.html",
    null,
    undefined,
    42,
    {},
    []
  ]) {
    assert.equal(normalizeDomain(value), null, `"${String(value)}" reddedilmeli`);
  }
});

test("hostnameFromUrl yalnızca http/https adreslerinden ana makine döndürür", () => {
  assert.equal(hostnameFromUrl("https://ALT.Ornek.com/a"), "alt.ornek.com");
  assert.equal(hostnameFromUrl("http://ornek.com:8080"), "ornek.com");
  assert.equal(hostnameFromUrl("chrome://extensions"), null);
  assert.equal(hostnameFromUrl("file:///tmp/x.html"), null);
  assert.equal(hostnameFromUrl(""), null);
  assert.equal(hostnameFromUrl(null), null);
  assert.equal(hostnameFromUrl("gecersiz"), null);
});

test("sanitizePausedSites yinelenen ve geçersiz girdileri ayıklar", () => {
  const sites = sanitizePausedSites([
    "https://www.b.com/x",
    "b.com",
    "a.com",
    "gecersiz",
    "",
    null,
    "c.com",
    "a.com"
  ]);
  assert.deepEqual(sites, ["a.com", "b.com", "c.com"]);
  assert.deepEqual(sanitizePausedSites("a.com"), []);
  assert.deepEqual(sanitizePausedSites(null), []);
  assert.deepEqual(sanitizePausedSites(undefined), []);
});

test("sanitizePausedSites üst sınırı uygular", () => {
  const many = Array.from({ length: MAX_PAUSED_SITES + 25 }, (_, index) => `site${index}.com`);
  const sites = sanitizePausedSites(many);
  assert.equal(sites.length, MAX_PAUSED_SITES);
  // Alfabetik sıralama nedeniyle ilk site listede olmalı.
  assert.ok(sites.includes("site0.com"));
});

test("buildAllowRules site başına iki kural üretir", () => {
  const rules = buildAllowRules(["ornek.com"]);
  assert.equal(rules.length, RULES_PER_SITE);

  const [requestRule, initiatorRule] = rules;
  assert.equal(requestRule.id, DYNAMIC_RULE_BASE);
  assert.equal(requestRule.action.type, "allow");
  assert.deepEqual(requestRule.condition.requestDomains, ["ornek.com"]);
  assert.equal(initiatorRule.id, DYNAMIC_RULE_BASE + 1);
  assert.equal(initiatorRule.action.type, "allow");
  assert.deepEqual(initiatorRule.condition.initiatorDomains, ["ornek.com"]);

  // İzin kuralları statik "block" kurallarından (öncelik 1) daha yüksek olmalı.
  for (const rule of rules) {
    assert.ok(rule.priority > 1, "izin kuralı block kurallarını geçmeli");
  }
});

test("buildAllowRules kimlikleri yönetilen aralıkta ve tekildir", () => {
  const sites = ["a.com", "b.com", "c.com"];
  const rules = buildAllowRules(sites);
  const ids = rules.map((rule) => rule.id);

  assert.equal(ids.length, sites.length * RULES_PER_SITE);
  assert.equal(new Set(ids).size, ids.length, "kimlikler yinelenmemeli");
  for (const id of ids) {
    assert.ok(isManagedRuleId(id), `${id} yönetilen aralıkta olmalı`);
  }
  assert.deepEqual(managedRuleIds(sites.length), ids);
});

test("buildAllowRules boş liste için boş dizi döndürür", () => {
  assert.deepEqual(buildAllowRules([]), []);
  assert.deepEqual(buildAllowRules(null), []);
});

test("isManagedRuleId statik kimlikleri dışlar", () => {
  assert.equal(isManagedRuleId(1), false);
  assert.equal(isManagedRuleId(999), false);
  assert.equal(isManagedRuleId(DYNAMIC_RULE_BASE), true);
  assert.equal(isManagedRuleId(DYNAMIC_RULE_BASE + 7), true);
  assert.equal(isManagedRuleId("10001"), false);
});

test("formatRuleCount Türkçe kural sayısı metni üretir", () => {
  assert.equal(formatRuleCount(0), "0 kural");
  assert.equal(formatRuleCount(92), "92 kural");
});
