# Sürüm notları

## 2.0.0 — 2026-10-09

### Yeni özellikler

- **Site bazlı duraklatma**: Gezilen site için filtrelemeyi tek tıkla durdurma. Her site için iki dinamik `allow` kuralı üretilir (`requestDomains` + `initiatorDomains`); liste ayarlar sayfasından yönetilir, en fazla 100 site.
- **İzleme/ölçüm filtresi**: Varsayılan olarak kapalı ikinci kural kümesi (`tracking_domains`) — analiz, oturum kaydı ve mobil ilişkilendirme alan adları.
- **Ayarlar sayfası** (`options.html`): filtre anahtarları, duraklatılan site listesi, ayarları sıfırlama.
- **Araç çubuğu rozeti**: filtre kapalıyken `OFF`, duraklatılmış site varken `II`.
- **Koyu tema**, azaltılmış hareket desteği ve geliştirilmiş erişilebilirlik (`aria-pressed`, `role="status"`, `role="alert"`).
- **Uzantı simgeleri** (16/32/48/128) bağımlılık gerektirmeyen bir betikle üretiliyor.

### Liste

- Reklam ağı listesi 24 → 92 alan adına çıkarıldı; 32 izleme alan adı eklendi.
- Kurallar artık `rules/domains.json` kaynağından derleniyor; her kayıt için liste ve açıklama zorunlu.

### Geliştirici deneyimi

- `scripts/build-rules.mjs`: kural derleme ve `--check` kipi.
- `scripts/validate-rules.mjs`: kimlik sürekliliği, alan adı tekilliği ve kaynak eşleşmesi dahil genişletilmiş doğrulama.
- `scripts/generate-icons.mjs`: simge üretimi.
- `tests/`: 47 test (paylaşılan yardımcılar, kural zinciri, manifest/bütünlük, arayüz tutarlılığı ve `chrome.*` taklidiyle arka plan hizmeti).
- `package.json`: `build`, `build:check`, `validate`, `icons`, `test`, `check` betikleri.

### Davranış değişiklikleri

- `minimum_chrome_version` 101 olarak belirlendi (site bazlı duraklatma için gerekli).
- Arka plan hizmeti ES modülü olarak çalışıyor; paylaşılan yardımcılar `shared/temizweb.mjs` içinde.
- İzinler: `declarativeNetRequest`, `storage`, `activeTab`. Gezinme geçmişini görmeyi gerektiren `tabs` izni bilerek istenmiyor.
- `main_frame` ve `stylesheet` istekleri önceki gibi engellenmiyor (değişiklik yok, belgelendi).

## 1.0.0 — 2026-10-09

İlk sürüm: 24 alan adlı statik kural kümesi, açma/kapatma düğmesi ve temel açılır pencere.
