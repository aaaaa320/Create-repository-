# TemizWeb Android — küçültme (R8) kuralları.
#
# Uygulama yansıma (reflection) kullanmaz; servisler, etkinlik ve alıcılar
# manifest üzerinden çözümlendiği için AGP bunları zaten korur.
# Yine de VPN yaşam döngüsü sistem tarafından yönetildiğinden servisleri
# ve hızlı ayar karosunu açıkça keep ediyoruz.

-keep class temizweb.android.FilterService { *; }
-keep class temizweb.android.tile.FilterTileService { *; }
-keep class temizweb.android.boot.AutoStartReceiver { *; }

# DNS çözümleyici yalnızca saf Kotlin/Java API'leri kullanır (java.net, java.nio).
-dontwarn java.lang.management.**
