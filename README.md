# Dosya Yöneticisi +

> **Proje durumu: Yarım kaldı.** Geliştirmeyi bıraktım. Bu proje, yapay zekâ yardımıyla vibe coding yöntemiyle geliştirildi. Kaynak kodu mevcut haliyle paylaşıyorum. Bazı özellikler ve cihaz testleri tamamlanmadı.


Kotlin, Jetpack Compose ve Material 3 Expressive ile geliştirilen Android dosya yöneticisi. Minimum sürüm Android 10 (API 29), derleme ve hedef SDK Android 17 (API 37). Uygulama kimliği `dev.dosyayoneticisi.plus`.

## Derleme

JDK 17 veya 21 ve Android SDK komut satırı araçlarıyla Android Studio olmadan derleyebilirsiniz.

```bash
sdkmanager "platforms;android-37.0" "build-tools;36.0.0"
chmod +x gradlew
./gradlew assembleDebug
```

Windows PowerShell'de `./gradlew.bat assembleDebug` komutunu kullanın. Android SDK konumunu `ANDROID_HOME` ile belirtin. APK, `app/build/outputs/apk/debug/app-debug.apk` konumuna yazılır.

GitHub Actions, push veya **Run workflow** ile aynı debug derlemesini çalıştırır. Başarılı derlemede **dosya-yoneticisi-debug** adlı artifact APK'yı içerir.

## Kullanım

- **Dosyalar → Klasör bağla** ile Android belge seçicisinden erişim verin. Android 11 ve sonrasında **Paylaşılan depolamayı gez** seçeneği, paylaşılan depolamanın kökünden gezinmek için sistemin tüm dosyalara erişim ekranını açar.
- **Analiz Et**, bağlı klasörleri tarar. Kategori özetleri bu kapsamdaki erişilebilir dosyaları gösterir. **Yeni Dosyalar** son yedi günde değiştirilmiş dosyaları listeler.
- Dosya işlemlerini açmak için bir dosyaya uzun basın. Kopyalama veya taşıma seçtikten sonra hedef klasöre gidip **Yapıştır**'a dokunun.
- **Bulut**, desteklenen hizmetlerin Android belge sağlayıcılarından seçilen dosya veya klasörleri kaydeder. **Uzak**, ağ sağlayıcılarından klasör bağlar veya sunucu adreslerini uyumlu istemcide açar.
- Silinen dosyalar, geri dönüşüm kutusu açıkken uygulamanın özel alanına kopyalanır. Geri yükleme için özgün klasöre yazma erişimi gerekir. Aynı adlı dosya varsa işlem hata verir.
- Root erişimini Ayarlar'dan isteyin. Cihazdaki root yöneticisi izin verdikten sonra **Root gezgini** sistem dizinlerini listeler, `stat` çıktısını gösterir ve onayla sekizlik izin modu uygular. Uygulama yeniden başlatıldığında root gezginini açarken oturum yetkisi yeniden istenir.

## 1.1 güncellemesi

- **Uygulamalar** ekranında İndirilenler ve Tümü sekmeleri, simge, sürüm ve APK boyutu bulunur. Uzun basınca yedekleme, kaldırma, mağaza, paylaşma ve açma işlemleri çıkar. Bölünmüş paketler temel APK ve tüm ek APK parçalarını içeren bir ZIP olarak dışa aktarılır. Uygulama verileri yedeğe dahil değildir. Kaldırmayı Android onaylar.
- **Ayarlar → Shizuku** üzerinden çalışan bir Shizuku sunucusuna bağlanın ve yetki verin. Ayrı gezgin dizin listesi, dosya bilgileri ve onaylı sekizlik izin değişikliği sunar. Erişim Shizuku oturumunun (ADB veya root) sahip olduğu izinlerle sınırlıdır.
- Yan menü konumları, DataStore yer imlerini ve oturumdaki klasör geçmişini gösterir. Dışarıya bir kez dokunmak menüyü kapatır. Dosya önizlemeleri ve ilişkili uygulama simgeleri ayarlardan açılıp kapanabilir.
- Depolama doluluk bildirimi uygulama açıkken güncellenir. USB ve diğer çıkarılabilir depolama değişiklikleri uygulama açıkken algılanır. Kısayol oluşturmak için başlatıcının Android sabit kısayol API’sini desteklemesi gerekir.
- Başlatıcı simgesi adaptiftir. Android 13 ve sonrasında başlatıcı desteği varsa Material You temalı simge kullanılabilir.

## 1.2 güncellemesi

- Varsayılan uygulamalar dosya uzantısına göre uygulama içinde saklanır. Bir dosyayı seçip işlem menüsünden veya Ayarlar ekranından eşleştirebilirsiniz. Farklı Aç işlemi kayıtlı eşleştirmeyi atlar.
- Bulut sağlayıcısının dosya veya klasör seçimini Android'e açması gerekir. Uzak konumlarda Yerel Ağ için belge sağlayıcısı, SMB/FTP/SFTP/WebDAV için belge sağlayıcısı veya uygun protokol istemcisi gerekir. Uygulama kendi ağ tarayıcısını ve protokol istemcisini çalıştırmaz.
- Debug APK aynı anahtarla imzalanır. Gradle, R8 ile kod ve kullanılmayan kaynakları küçültür. SAF dizinlerinde desteklenen sağlayıcılardan dosya bilgileri tek sorguda alınır. Önizlemeler sınırlı bir bellek önbelleği kullanır.

## 1.3 güncellemesi

- Dosya gezgini, Ana Ekran depolama kartları ve yan menüden açılır. Alt gezinme Ana Ekran ve Ayarlar'dan oluşur. Tıklanabilir klasör yolu gerçek depolama kullanımını gösterir.
- Görünüm ve sıralama aynı pencereden ayarlanır. Liste, kompakt liste ve ızgara ile ad/tarih/boyut sıralaması klasöre özgü veya genel olarak DataStore'a kaydedilir.
- Fotoğraflar büyütülebilir, kaydırılabilir, döndürülebilir ve paylaşılabilir. Video oynatıcı Media3 ExoPlayer kullanır. TXT, JSON, XML, LOG ve MD düzenleyici 512 KiB'ye kadar geçerli UTF-8 içeriği kabul eder. Bir uzantıya atanmış uygun harici uygulama, normal açmada önceliklidir.
- Root ve Shizuku önizlemeleri uygulamaya özel geçici dosya kullanır. Bu önizlemeler salt okunurdur. Fotoğraf ve video için geçici kopya sınırı 256 MiB'dir. Geçici dosyalar kapatıldığında ve sonraki uygulama başlangıcında temizlenir.
- [Fossify File Manager](https://github.com/FossifyOrg/File-Manager) kaynaklarından klasöre özgü görünüm, URI üzerinden dosya açma ve SAF/yol ayrımı incelendi. [GPL-3.0 lisanslı](https://github.com/FossifyOrg/File-Manager/blob/main/LICENSE) uygulamadan kaynak kod veya görsel alınmadı.

## Kapsam

Bulut hesapları için uygulama içi oturum açma ve eşitleme yoktur. Yüklü uygulamalar ekranı, mevcut Android profilinin erişilebilir paketlerini listeler. Premium satın alma ekranı eklenmemiştir. Belge sağlayıcısı, izinler ve cihazdaki root yöneticisi dosya işlemlerinin kullanılabilirliğini belirler. Galaxy A35, One UI ve fiziksel root yöneticileri üzerinde cihaz testi gerekir.





