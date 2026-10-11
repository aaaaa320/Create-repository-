# TemizWeb Android — yerel DNS filtresi

Bu klasör, tarayıcı uzantısının Android karşılığıdır. Uzantı istekleri
tarayıcının `declarativeNetRequest` API'siyle engeller; Android'de aynı işi
**cihazın DNS sorguları** yapar. TemizWeb, `VpnService` ile küçük bir tünel
kurar, sistem DNS istemcisini kendi yerel sunucusuna yönlendirir ve her sorguyu
`rules/domains.json` kaynağından üretilen listeyle değerlendirir. Engellenen
alan adlarına `NXDOMAIN` döner; diğer sorgular bağlı olduğunuz ağın kendi DNS
sunucusuna iletilir.

Root gerekmez, üçüncü taraf kütüphane yoktur (yalnızca AndroidX/Material),
hiçbir veri cihaz dışına çıkmaz.

## Özellikler

- **Tek dokunuşla aç/kapat**: uygulama, bildirim ve hızlı ayarlar karosu.
- **Aynı listeler**: 92 reklam ağı + 32 izleme/ölçüm alan adı; uzantıyla aynı
  kaynaktan derlenir (`npm run build:android`).
- **İzleme filtresi anahtarı**: uzantıda olduğu gibi varsayılan kapalı.
- **Duraklatılan siteler (izin listesi)**: bir alan adı ve tüm alt alan adları
  için engellemeyi durdurur; en fazla 100 site, aynı normalleştirme kuralları.
- **Önbellek**: başarılı yanıtlar TTL kadar önbellekte tutulur (pil/veri
  tasarrufu); süre dolmadan servis edilen yanıtın TTL'i yaşlandırılır.
- **Sayaçlar**: toplam sorgu, engellenen (reklam/izleme), iletilen, önbellek
  isabeti ve çalışma süresi. Yalnızca toplam sayılar tutulur; sorgu adları
  hiçbir yerde saklanmaz.
- **Açılışta başlat** (isteğe bağlı) ve **IPv6 DNS desteği** (isteğe bağlı).
- **Üst DNS seçimi**: varsayılan olarak bağlı ağın kendi DNS sunucuları
  kullanılır; istenirse IP adresiyle elle yazılabilir.
- Koyu tema, Türkçe arayüz, uzantıyla aynı renk paleti ve simge tasarımı.

## Nasıl çalışır

```
Uygulamalar / sistem çözücü
        │  DNS sorgusu (UDP 53)
        ▼
VPN tüneli (yalnızca DNS sunucusunun adresi yönlendirilir)
        │
        ▼
DnsTunnel döngüsü ── IP/UDP başlığını çözümler
        │
        ├─ alan adı listede ve duraklatılmamış → NXDOMAIN yanıtı
        ├─ önbellekte taze yanıt var           → önbellekten yanıt
        └─ değil                               → üst sunucuya ilet, yanıtla, önbelleğe al
```

| Dosya | Sorumluluk |
| --- | --- |
| `FilterService.kt` | VPN tünelini kurar/yıkar, ön plan bildirimini yönetir |
| `dns/DnsTunnel.kt` | TUN'dan paket okur, kararı verir, yanıtı yazar |
| `dns/DomainFilter.kt` | Alan adı + alt alan adı eşleşmesi, izin listesi |
| `dns/DnsMessage.kt` | DNS tel biçimi: çözümleme, NXDOMAIN, kırpma, TTL yaşlandırma |
| `dns/IpPacket.kt` | IPv4/IPv6 + UDP başlıkları ve sağlama toplamları |
| `dns/DnsCache.kt` | TTL'e saygılı küçük LRU önbellek |
| `dns/UpstreamDns.kt` | Korumalı soketlerle üst sunucuya iletme |
| `UpstreamServers.kt` | Bağlı ağın DNS sunucularını bulur (VPN ağları atlanır) |
| `RuleLists.kt` | Varlıklardaki listeleri ve alan adı açıklamalarını yükler |
| `FilterPrefs.kt` | Tercihler (`enabled`, `trackingEnabled`, `pausedSites`, …) |
| `ui/MainActivity.kt` | Tek ekranlık arayüz |
| `tile/FilterTileService.kt` | Hızlı ayarlar karosu |
| `boot/AutoStartReceiver.kt` | Açılışta başlatma |

Saf Kotlin olan parçalar (`dns/*`, `util/*`) Android'siz çalışır ve birim
testlerle doğrulanır; `DnsTunnel` giriş/çıkış akışlarını dışarıdan aldığı için
döngü de test edilebilir durumdadır.

## APK üretme ve telefona yükleme

### Gereksinimler

- JDK 17
- Android SDK Platform 35 (Android Studio'daki **SDK Manager** üzerinden)
- Gradle 8.9; yalnızca ilk kez Gradle Wrapper üretmek için gerekir

Android Studio SDK'sı `ANDROID_HOME` veya `ANDROID_SDK_ROOT` ile bulunmalıdır.

### APK'yı yerelde üretme

Bu depoda Gradle Wrapper'ın JAR ve başlatıcı dosyaları tutulmaz. Makinede Gradle
8.9 kuruluysa, depoyu klonladıktan sonra ilk seferde wrapper'ı üretin:

```bash
cd android
gradle wrapper --gradle-version 8.9
```

Sonraki derlemelerde:

```bash
cd android
./gradlew testDebugUnitTest    # birim testler
./gradlew assembleDebug        # hata ayıklama APK'sını üretir
```

APK şu konumda oluşur: `android/app/build/outputs/apk/debug/app-debug.apk`.
Bu dosya Android'in debug anahtarıyla imzalanır; telefona elle kurup denemek için
uygundur. Google Play'de yayımlamak için kendi anahtarınızla bir release imzası
(`signingConfig`) yapılandırmanız gerekir.

### GitHub Actions'tan APK indirme

Derlemeyi yerelde yapmak istemiyorsanız GitHub deposunda **Actions → Android**
iş akışını açın, son başarılı çalıştırmayı seçin ve **Artifacts** bölümündeki
`temizweb-debug-apk` dosyasını indirin. ZIP'i açınca `app-debug.apk` çıkar.
İş akışı testler başarılı olduğunda APK'yı yükler; artefaktlar GitHub tarafından
90 gün saklanır.

### Android telefona kurma

1. `app-debug.apk` dosyasını telefona kopyalayın veya USB ile aktarın.
2. Telefonda APK dosyasına dokunun. Android sorarsa, kullandığınız Dosyalar ya da
tarayıcı uygulaması için **Bu kaynaktan uygulama yüklemeye izin ver** seçeneğini
   açın ve kurulumu onaylayın.
3. TemizWeb'i açıp **Filtreyi aç** düğmesine dokunun. Android'in gösterdiği VPN
   bağlantısı iznini onaylayın; filtre yerel DNS tüneliyle çalışır ve root
   gerektirmez.

USB hata ayıklama ve Android SDK Platform Tools kuruluysa, depoyu klonladığınız
bilgisayardan şu komutla da yükleyebilirsiniz:

```bash
adb install -r android/app/build/outputs/apk/debug/app-debug.apk
```

### Varlıklar ve simgeler

Alan adı listeleri depoda **elle düzenlenmez**; `rules/domains.json`
kaynağından üretilir:

```bash
npm run build:android          # assets/* dosyalarını yeniden yazar
npm run build:android:check    # CI: kaynakla eşleştiğini doğrular
npm run icons:android          # mipmap-*/ic_launcher*.png simgelerini üretir
```

`npm run check` uzantı testleriyle birlikte bu eşleşmeyi de doğrular.

## Tasarım kararları ve sınırlamalar

- **Yalnızca DNS engellenir.** Tünel, tüm trafiği değil sadece DNS sunucusunun
  adresini yönlendirir. Tam trafik vekilliği (uProxy/RethinkDNS tarzı) bir TCP
  yığını gerektirir ve bu projenin kapsamı dışındadır. Bunun sonucu olarak:
  - HTTPS üzerindeki DNS (DoH) veya TLS üzerindeki DNS (DoT) kullanan
    uygulamalar filtreyi atlayabilir.
  - Uygulamaların sabit kodladığı DNS sunucuları (ör. doğrudan 8.8.8.8) tünele
    girmez.
- **TCP DNS (53/TCP) desteklenmez.** Sorgular EDNS0/DNSSEC istemeyecek şekilde
  iletilir, böylece yanıtlar neredeyse her zaman 512 baytın altında kalır; MTU'yu
  aşan nadir yanıtlar kırpılır ve kırpma (TC) bitiyle işaretlenir.
- **IPv6 varsayılan kapalıdır.** Açıldığında tünel bir IPv6 adresi ve DNS
  sunucusu daha edinir; kapalıyken IPv6 sorguları filtrelenmeden geçer.
- **Birinci taraf reklamlar** (site kendi alan adından reklam sunuyorsa) DNS
  düzeyinde ayrıştırılamaz; uzantıda olduğu gibi bu bir başlangıç filtresidir ve
  uBlock Origin gibi olgun engelleyicilerin yerine geçmez.
- **Duraklatmanın anlamı farklıdır:** tarayıcıda "bu sekmedeki site" otomatik
  algılanır; Android'de gezinilen uygulamayı bilmenin güvenli bir yolu
  olmadığı için izin listesi kullanıcı tarafından yazılır.
- **Açılışta başlatma** bazı üretici yazılımlarının arka plan kısıtlarına
  takılabilir; bu durumda filtre elle açılır.

## Gizlilik

- Sorgu adları yalnızca bellekte karşılaştırılır; günlüğe, diske veya ağa
  yazılmaz. Sayaçlar toplam sayı tutar, alan adı tutmaz.
- Varsayılan üst sunucu, bağlı olduğunuz ağın kendi DNS sunucusudur; TemizWeb
  siz yazmadıkça üçüncü taraf bir çözücüye sorgu göndermez.
- Tercihler `SharedPreferences` içinde yalnızca bu cihazda saklanır; yedekleme
  (bulut/aygıt transferi) bilinçli olarak kapatılmıştır.

## Lisans

MIT — depo kökündeki uzantıyla aynı lisans.
