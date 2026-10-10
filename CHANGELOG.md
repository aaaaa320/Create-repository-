# Sürüm notları

## Yayınlanmadı — Android uygulaması

### Yeni özellikler

- `android/`: TemizWeb'in Android sürümü. `VpnService` üzerinde çalışan yerel DNS filtresi; cihazın DNS sorgularını aynı alan adı listesiyle denetler, engellenen alan adlarına `NXDOMAIN` döner, diğerlerini bağlı ağın kendi DNS sunucusuna iletir.
  - Tek ekranlık Türkçe arayüz (Material 3, koyu tema), kalıcı bildirim ve hızlı ayarlar karosu ile tek dokunuşla aç/kapat.
  - İzleme/ölçüm anahtarı, duraklatılan siteler (izin listesi, uzantıyla aynı 100 site sınırı ve normalleştirme kuralları), açılışta başlatma ve isteğe bağlı IPv6 DNS desteği.
  - TTL'e saygılı LRU DNS önbelleği; sayaçlar (sorgu, engellenen, iletilen, önbellek, çalışma süresi) yalnızca toplam tutar, sorgu adlarını saklamaz.
  - Saf Kotlin DNS/IP katmanı (`dns/DnsMessage`, `dns/IpPacket`, `dns/DnsCache`, `dns/DomainFilter`) ve tünel döngüsü birim testlerle doğrulanır.
- `scripts/build-android-assets.mjs`: `rules/domains.json` → `android/app/src/main/assets/{ads-domains.txt,tracking-domains.txt,domain-notes.json}`. Uzantı ve Android tek kaynaktan beslenir; `--check` kipi CI için.
- `scripts/generate-android-icons.mjs`: `mipmap-*/ic_launcher.png` ve `ic_launcher_round.png` (uzantıyla aynı tasarım); API 26+ için `mipmap-anydpi-v26` içinde uyarlanabilir vektör simgeler.
- `.github/workflows/android.yml`: Android birim testleri + hata ayıklama APK derlemesi; APK iş akışı çıktısı olarak yüklenir.

### Geliştirici deneyimi

- `package.json`: `build:android`, `build:android:check`, `icons:android` betikleri; `check` artık Android varlık eşleşmesini de doğrular.
- `scripts/generate-icons.mjs`: `render`/`toPng` dışa aktarıldı, betik yalnızca doğrudan çalıştırıldığında yazıyor; Android simge betiği aynı çizimi yeniden kullanıyor.
- `tests/android-assets.test.mjs`: Android varlıklarının derlenmiş uzantı kurallarıyla birebir eşleştiğini doğrular.

### Belgeler

- `android/README.md`: mimari, derleme adımları, tasarım kararları, sınırlamalar ve gizlilik.
- Kök `README.md`: Android bölümü, yeni betikler ve dosya düzeni güncellendi.

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
