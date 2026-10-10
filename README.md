# TemizWeb — Hafif Reklam Filtresi

TemizWeb, Chromium tabanlı tarayıcılar için hazırlanmış, Manifest V3 kullanan küçük bir reklam ve izleme engelleyicisidir. Seçili üçüncü taraf ağ alan adlarına yapılan istekleri tarayıcının yerleşik `declarativeNetRequest` API'siyle durdurur; sayfa içeriği okunmaz, istekler yorumlanmaz.

Aynı filtrenin **Android sürümü** de bu depodadır: [`android/`](android/README.md) klasöründeki uygulama, aynı alan adı listesiyle cihazın DNS sorgularını yerel bir tünelde denetler. İki sürüm de tek kaynaktan (`rules/domains.json`) beslenir.

## Özellikler

- **Araç çubuğu penceresinden tek tıkla açma/kapatma** — durum araç çubuğu rozetinde de görünür (`OFF`).
- **124 alan adlı iki liste**: 92 reklam ağı kuralı (varsayılan açık) ve 32 izleme/ölçüm kuralı (varsayılan kapalı, isteğe bağlı).
- **Site bazlı duraklatma** — bir sitede filtrelemeyi geçici olarak durdurma; liste ayarlar sayfasından yönetilir.
- **İzleme/ölçüm filtresi** — analiz, oturum kaydı ve mobil ilişkilendirme alan adlarını isteğe bağlı olarak engeller.
- **Ayarlar sayfası** — filtreleri aç/kapat, duraklatılan siteleri ekle/kaldır, ayarları sıfırla.
- **İstekleri içerik kodu çalıştırmadan tarayıcı düzeyinde filtreleme** — bellek kullanımı düşük, sayfa yüklemesi hızlı.
- **Koyu tema ve azaltılmış hareket desteği**, klavye ve ekran okuyucu ile uyumlu arayüz.
- Sunucu, telemetri, dış kaynak veya harici kütüphane yok.
- Tarama geçmişi tutulmaz; yerel depolamada yalnızca üç tercih saklanır.

## Kurulum

1. Bu depoyu indirin veya klonlayın.
2. Chrome'da `chrome://extensions` sayfasını açın. Brave veya Edge'de ilgili uzantılar sayfasını açın.
3. **Geliştirici modu**nu etkinleştirin.
4. **Paketlenmemiş öğe yükle** seçeneğine basıp bu klasörü seçin.
5. TemizWeb simgesini araç çubuğuna sabitleyip durumunu açın.

Chrome 101 ve sonrası sürümler hedeflenir (site bazlı duraklatma için `initiatorDomains`/`requestDomains` koşulları gerekir). Firefox'ta test edilmemiştir.

## Android uygulaması

Android'de tarayıcı uzantısı API'leri bulunmadığı için TemizWeb, cihazın DNS sorgularını yerel bir `VpnService` tünelinde filtreler: engellenen alan adlarına `NXDOMAIN` döner, diğer sorguları bağlı olduğunuz ağın kendi DNS sunucusuna iletir. Root gerekmez; reklam/izleme listeleri, izin listesi (duraklatılan siteler), izleme anahtarı ve sayaçlar uzantıyla aynı davranışı korur.

```bash
cd android
gradle wrapper --gradle-version 8.9   # ilk kez: sarmalayıcıyı üretir
./gradlew assembleDebug               # APK üretir
./gradlew testDebugUnitTest           # birim testler
```

Mimari, sınırlamalar (ör. DoH atlaması, IPv6'nın isteğe bağlı oluşu) ve gizlilik ayrıntıları için [`android/README.md`](android/README.md) dosyasına bakın. GitHub Actions iş akışı her değişiklikte APK'yı derleyip testleri çalıştırır.

## Kullanım

### Araç çubuğu penceresi

| Bölüm | İşlev |
| --- | --- |
| Filtre durumu | Filtreyi tamamen açar veya kapatır. |
| Bu site | Gezilen site için filtrelemeyi duraklatır/devam ettirir. |
| Filtre bilgileri | Yüklü kural sayılarını ve duraklatılan site sayısını gösterir. |

### Ayarlar sayfası

`chrome://extensions` → TemizWeb → **Ayrıntılar** → **Uzantı seçenekleri**, ya da açılır penceredeki **Ayarları aç** bağlantısı.

- **Reklam filtresi** ve **İzleme/ölçüm filtresi** anahtarları.
- **Duraklatılan siteler**: alan adı ekleme (`ornek.com`, `https://www.ornek.com/haber` veya `*.ornek.com` yazımı kabul edilir), tek tek kaldırma, tümünü temizleme. En fazla 100 site.
- **Ayarları sıfırla**: tüm tercihleri ilk kurulum durumuna döndürür.

Duraklatma bir sitede iki şekilde çalışır: sitenin kendi alan adına yapılan isteklere ve sitenin başlattığı üçüncü taraf isteklere dokunulmaz. Bu, bir sayfanın bozulması durumunda tek tıkla geri dönülmesini sağlar.

## Geliştirme

Node.js 18+ yeterlidir; bağımlılık yoktur.

```bash
npm run build      # rules/domains.json → rules/ads.json + rules/tracking.json
npm run build:check # kural dosyalarının kaynakla eşleştiğini doğrular (CI)
npm run build:android # domains.json → android/app/src/main/assets/*
npm run build:android:check # Android varlıklarının kaynakla eşleştiğini doğrular (CI)
npm run validate   # kural dosyalarını doğrular
npm run icons      # icons/*.png simgelerini yeniden üretir
npm run icons:android # android/.../mipmap-*/ başlatıcı simgelerini üretir
npm test           # tüm testleri çalıştırır
npm run check      # build:check + build:android:check + validate + test
```

### Kural ekleme veya çıkarma

Kurallar `rules/domains.json` dosyasından üretilir; `rules/ads.json` ve `rules/tracking.json` dosyaları elle düzenlenmez.

```json
{ "domain": "ornek-reklam.com", "list": "ads", "note": "Ne işe yaradığının kısa açıklaması" }
```

- `list`: `ads` (varsayılan açık) veya `tracking` (varsayılan kapalı).
- `note`: neden engellendiğini açıklar; boş bırakılamaz.
- Alan adları küçük harf, yinelenmemiş ve geçerli biçimde olmalıdır.

Yeni bir kural eklerken alan adının gerçekten reklam/izleme sunumu amacıyla kullanıldığını doğrulayın ve olası yanlış pozitifleri not alın. Bazı ağlar (örneğin CDN'ler veya ödeme sağlayıcıları) meşru içerik isteklerinde de kullanılabilir.

Engellenen kaynak türleri bilerek sınırlıdır: `script`, `image`, `xmlhttprequest`, `sub_frame`, `ping`, `media`. Ana belge (`main_frame`) ve stil dosyaları engellenmez; böylece bir yanlış pozitif sitenin kendisini bozmaz.

## Dosya düzeni

```
manifest.json            Uzantı tanımı (MV3)
service_worker.js        Arka plan: tercihleri uygular, dinamik kuralları yönetir
popup.html/.css/.js      Araç çubuğu penceresi
options.html/.css/.js    Ayarlar sayfası
shared/temizweb.mjs      Arayüz, arka plan ve testlerin paylaştığı yardımcılar
rules/domains.json       Kural kaynağı (elle düzenlenir)
rules/ads.json           Derlenmiş reklam kural kümesi (varsayılan açık)
rules/tracking.json      Derlenmiş izleme kural kümesi (varsayılan kapalı)
icons/                   Üretilen uzantı simgeleri
scripts/                 Kural derleme, doğrulama ve simge üretimi
tests/                   node:test ile çalışan testler
android/                 Android uygulaması (yerel DNS filtresi, bkz. android/README.md)
android/.../assets/      domains.json'dan üretilen alan adı listeleri (elle düzenlenmez)
```

## Testler

Testler harici bağımlılık gerektirmez:

```bash
npm test
```

Kapsanan alanlar: alan adı normalleştirme, duraklatma listesi temizleme, dinamik izin kuralı üretimi, kural derlemenin diske yansıması, doğrulayıcı betiği, manifest bütünlüğü, simge dosyaları ve arayüz tutarlılığı. Arka plan hizmeti, `chrome.*` API'lerinin taklidiyle uçtan uca sınanır.

## Kapsam ve sınırlamar

Bu, küçük bir başlangıç listesidir; kapsamlı filtre listelerinin veya uBlock Origin gibi olgun engelleyicilerin yerine geçmez. Yalnızca listelenen alan adlarına yapılan seçili istekleri engeller. Birinci taraf reklamları, reklam içeriğini kendi alan adından sunan siteleri ve listede bulunmayan yeni alan adlarını kaçırabilir. Reklam yerleri gizlenmez; bir sayfada boş alan kalabilir. Bir sayfa bozulursa o site için duraklatmayı açın veya TemizWeb'i kapatıp tekrar deneyin.

## Gizlilik

Uzantı sayfa içeriğini okumaz, gezinme geçmişini kaydetmez ve hiçbir yere veri göndermez. Filtreleme tarayıcının yerleşik istek-kural API'siyle yerel olarak yapılır. `activeTab` izni yalnızca açılır pencerede gezinilen siteyi göstermek için kullanılır; sekme adresi hiçbir yere kaydedilmez veya gönderilmez.

Yerel depolamada saklanan üç değer:

| Anahtar | Anlamı |
| --- | --- |
| `enabled` | Reklam filtresi açık mı |
| `trackingEnabled` | İzleme filtresi açık mı |
| `pausedSites` | Duraklatılan alan adları |

## Lisans

MIT — ayrıntılar için [`LICENSE`](LICENSE) dosyasına bakın.
