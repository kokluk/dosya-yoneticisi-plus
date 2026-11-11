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

## Görünüm 

<img width="520" height="1340" alt="Screenshot_20261112_001816" src="https://github.com/user-attachments/assets/67166f1d-f77c-4fd1-959e-184dc892b09d" />



## Kullanım

- **Dosyalar → Klasör bağla** ile Android belge seçicisinden erişim verin. Android 11 ve sonrasında **Paylaşılan depolamayı gez** seçeneği, paylaşılan depolamanın kökünden gezinmek için sistemin tüm dosyalara erişim ekranını açar.
- **Analiz Et**, bağlı klasörleri tarar. Kategori özetleri bu kapsamdaki erişilebilir dosyaları gösterir. **Yeni Dosyalar** son yedi günde değiştirilmiş dosyaları listeler.
- Dosya işlemlerini açmak için bir dosyaya uzun basın. Kopyalama veya taşıma seçtikten sonra hedef klasöre gidip **Yapıştır**'a dokunun.
- **Bulut**, desteklenen hizmetlerin Android belge sağlayıcılarından seçilen dosya veya klasörleri kaydeder. **Uzak**, ağ sağlayıcılarından klasör bağlar veya sunucu adreslerini uyumlu istemcide açar.
- Silinen dosyalar, geri dönüşüm kutusu açıkken uygulamanın özel alanına kopyalanır. Geri yükleme için özgün klasöre yazma erişimi gerekir. Aynı adlı dosya varsa işlem hata verir.
- Root erişimini Ayarlar'dan isteyin. Cihazdaki root yöneticisi izin verdikten sonra **Root gezgini** sistem dizinlerini listeler, `stat` çıktısını gösterir ve onayla sekizlik izin modu uygular. Uygulama yeniden başlatıldığında root gezginini açarken oturum yetkisi yeniden istenir.

- [Fossify File Manager](https://github.com/FossifyOrg/File-Manager) kaynaklarından klasöre özgü görünüm, URI üzerinden dosya açma ve SAF/yol ayrımı incelendi. [GPL-3.0 lisanslı](https://github.com/FossifyOrg/File-Manager/blob/main/LICENSE) uygulamadan kaynak kod veya görsel alınmadı.

## Kapsam

Bulut hesapları için uygulama içi oturum açma ve eşitleme yoktur. Yüklü uygulamalar ekranı, mevcut Android profilinin erişilebilir paketlerini listeler.





