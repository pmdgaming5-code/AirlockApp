# AirLock

AirLock, seçtiğin uygulamalar öne geldiğinde uçak modunu otomatik açmak ve uygulamadan ayrıldığında önceki uçak modu durumuna dönmek için tasarlanmış Android uygulamasıdır.

## Önemli Android gereksinimi

AirLock'un uçak modunu otomatik değiştirebilmesi için uygulamanın **Device Owner** olması gerekir. Normal bir uygulama `Settings.Global` yazamaz; Android'in `DevicePolicyManager#setGlobalSetting()` API'si bu işlem için Device Owner yetkisi ister.

Ayrıca uygulamanın hangi uygulamanın önde olduğunu görebilmesi için **Usage Access** izni gerekir.

## Kurulum

1. AirLock APK'sını cihaza kur.
2. AirLock'u en az bir kez aç.
3. Ayarlar > Uygulamalar > Özel uygulama erişimi > Kullanım verilerine erişim bölümünden AirLock'a izin ver.
4. Test cihazı henüz yönetilen bir cihaz değilse, kurulumdan önce AirLock'un Device Owner yapılması gerekir. USB hata ayıklama açık bir test cihazında ADB ile örnek komut:

```bash
adb shell dpm set-device-owner com.pmdgaming.airlock/.AirlockAdminReceiver
```

Bu komut yeni/temiz test cihazı veya uygun şekilde provisioning yapılmış cihaz için kullanılmalıdır. Mevcut kişisel cihazlarda Android politikaları nedeniyle Device Owner kurulumu başarısız olabilir.

5. AirLock'u yeniden aç ve korunacak uygulamaların yanındaki anahtarları etkinleştir.

## Davranış

- Uygulama listesini isim ve ikonlarıyla gösterir.
- Uygulama araması desteklenir.
- Korunan uygulama öne geldiğinde AirLock mevcut uçak modu durumunu hatırlar ve kapalıysa açar.
- Korunan uygulamadan ayrılınca AirLock önceki uçak modu durumuna geri döner.
- Uygulama yeniden başlatıldığında/cihaz açıldığında izleme yeniden başlatılır.
- Foreground uygulama takibi Usage Events üzerinden yapılır ve her döngüde son event'i silip yeniden başlatmak yerine durum korunur.

## Fallow notu

Fallow Code Analysis TypeScript/JavaScript projeleri için tasarlanmıştır. AirLock Android tarafı Java/Gradle projesi olduğu için Fallow bu kod tabanındaki Java derleme hatalarını doğrulayan doğru araç değildir. Android derlemesi için Gradle/Android Gradle Plugin ve cihaz üstü test gereklidir.

## Build

GitHub Actions, `app:assembleDebug` ve `app:assembleRelease` görevlerini çalıştırır ve APK'ları artifact olarak yayınlar.
