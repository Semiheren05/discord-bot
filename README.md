# Discord Moderasyon Botu 🛡️

[![Derleme](https://github.com/Semiheren05/discord-bot/actions/workflows/build.yml/badge.svg)](https://github.com/Semiheren05/discord-bot/actions/workflows/build.yml)

Java 21 ve [JDA](https://github.com/discord-jda/JDA) ile yazılmış, `!` ön ekli bir Discord moderasyon botu. Ceza ve uyarı sistemi, kanal ve üye yönetimi, çekiliş/anket/etkinlik, destek talepleri ve YouTube video / Kick yayın duyuruları içerir. Kanallar ve roller gibi ayarların hepsi Discord'dan komutla yapılır; dosyada sadece token durur.

Discord'da `!yardim` yazınca 9 sayfalık, butonlu bir yardım menüsü açılır.

## Teknolojiler

| Alan | Seçim |
|---|---|
| Dil | Java 21 |
| Discord | JDA 6 (gateway olayları, butonlar, embed'ler) |
| Derleme | Gradle (Kotlin DSL), bağımlılıklarıyla tek parça `ModBot.jar` |
| HTTP | Java `HttpClient` (YouTube RSS akışı, Kick API) |
| Ağ | `InetAddressResolverProvider` SPI ile şifreli DNS (DNS-over-HTTPS) |
| Kalıcılık | `data/` klasöründe properties dosyaları |
| CI | GitHub Actions |

## Öne çıkan teknik noktalar

- **Komut altyapısı:** Her komut bir `Command` nesnesi (ad, kategori, kullanım, açıklama, takma adlar). `CommandListener` mesajı ayrıştırır, yetkiyi kontrol eder, hatalı kullanımı kullanıcıya anlaşılır bir mesajla döner (`Ctx.UserError`).
- **Zamanlanmış işler:** YouTube ve Kick kontrolleri, çekiliş/etkinlik zamanlayıcıları ve durum yazısı ortak bir `ScheduledExecutorService` üzerinde çalışır.
- **Kapanmaya dayanıklılık:** Çekilişler, etkinlikler ve uyarılar diske yazılır. Bot kapalıyken süresi dolan çekilişler açılışta bitirilir, kaçırılan videolar duyurulur.
- **Şifreli DNS:** Discord'un DNS ile engellendiği ağlarda bot, adresleri Cloudflare / Google DNS-over-HTTPS üzerinden çözer. Bu, Java'nın `InetAddressResolverProvider` SPI'ı ile yalnızca botun kendi sürecine uygulanır.
- **Kick entegrasyonu:** Resmi API için OAuth client credentials ile token alınır, süresi dolunca yenilenir. API bilgisi yoksa yedek yönteme düşer.
- **Gizli bilgiler:** Token `config.properties` dosyasında ya da `DISCORD_TOKEN` ortam değişkeninde tutulur, dosya `.gitignore` ile repoya girmez.

## Komutlar

`<...>` zorunlu • `[...]` isteğe bağlı • Süre: `30s` `10m` `2h` `1d` `1w` `1d12h`

| Kategori | Komutlar |
|---|---|
| 🔨 Ceza | `!ban` `!unban` `!kick` `!mute` `!unmute` `!softban` |
| ⚠️ Uyarı | `!uyar` `!uyarilar` `!uyarisil` (belirlenen uyarı sayısında otomatik susturma) |
| 📢 Kanal | `!temizle` `!kilitle` `!kilitac` `!yavasmod` `!gizle` `!goster` `!yazdir` |
| 👤 Üye | `!rolver` `!rolal` `!isim` `!kullanici` |
| 📺 YouTube & Kick | `!youtube @kanal #duyuru-kanalı [@everyone]` `!youtube-test` `!yayin kanaladi #yayinlar [@here]` `!yayin-test` |
| 🎉 Etkinlik | `!cekilis` `!cekilis-bitir` `!cekilis-yenile` `!anket` `!etkinlik` |
| 🎫 Destek | `!ticket-kur` `!ticket-kapat` `!ticket-ekle` `!ticket-cikar` |
| ⚙️ Bilgi & Ayar | `!yardim` `!ping` `!sunucu` `!log` `!hosgeldin` `!otorol` |

Her komutun açıklaması ve örneği `!yardim` menüsünde.

- **`!yazdir #kanal [başlık]`** + bir `.txt` dosyası: dosyadaki yazıyı kanala renkli çerçeveli mesaj olarak yazar (kurallar, duyurular için). Uzun metinler parçalara bölünür.
- **Kick yayın bildirimi:** Yayın açılınca kanala başlık, kategori, izleyici sayısı ve önizleme resmiyle duyuru atar; yayın bitince mesajı günceller.
- **Durum yazısı:** Botun profilinde sırayla son video, abone sayısı ve `!yardim` görünür (`durum.youtube` ayarı).

## Kurulum

### 1. Discord'da botu oluştur

1. [Discord Developer Portal](https://discord.com/developers/applications) → **New Application**.
2. **Bot** sekmesi:
   - **Reset Token** ile token'ı al (şifre gibidir, kimseyle paylaşma).
   - **Privileged Gateway Intents** altında **SERVER MEMBERS INTENT** ve **MESSAGE CONTENT INTENT**'i aç.
3. **General Information** → **Application ID**'yi şu linkte `APP_ID` yerine yazıp botu sunucuna davet et:
   ```
   https://discord.com/oauth2/authorize?client_id=APP_ID&scope=bot&permissions=564324812975190
   ```
4. Sunucu ayarlarında botun rolünü üst sıralara taşı (bot sadece kendi rolünün altındakileri cezalandırabilir).

### 2. Çalıştır

Java 21 veya üstü gerekir.

**Windows:**
```bat
git clone https://github.com/Semiheren05/discord-bot.git
cd discord-bot
baslat.bat
```
İlk çalıştırmada `config.properties` oluşturulur ve Not Defteri açılır. `token=` satırını doldurup kaydet, bot derlenip başlar. Kodu değiştirdiysen önce `yeniden-derle.bat`, sonra `baslat.bat`.

**Linux / sunucu:**
```bash
git clone https://github.com/Semiheren05/discord-bot.git
cd discord-bot
cp config.example.properties config.properties   # ayarları düzenle
./gradlew botJar
DISCORD_TOKEN=... java -jar build/libs/ModBot.jar
```

### 3. İlk ayarlar (Discord'da bir kez)

```
!log #mod-log
!hosgeldin #hoş-geldin
!otorol @Üye
!youtube @kanaladi #videolar @everyone
!ticket-kur #destek @Yetkili
```

Tüm ayarlar `config.example.properties` içinde açıklamalı olarak duruyor.

## Proje yapısı

```
src/main/java/com/semih/modbot
├── Main.java        açılış, JDA kurulumu, özelliklerin başlatılması
├── command/         komut modeli, ayrıştırıcı, yetki kontrolü, yardım menüsü
├── core/            ayarlar, kalıcı veri, uyarılar, süre ve zaman yardımcıları
├── features/        moderasyon, etkinlik, destek, log, YouTube, Kick, durum
├── kick/            Kick API istemcisi
├── youtube/         YouTube RSS akışı okuyucu
└── net/             DNS-over-HTTPS çözümleyici (SPI)
```

## Bot kapalıyken ne olur?

- **Çekilişler** kaydedilir; kapalıyken süresi dolanlar açılışta bitirilir.
- **Etkinlikler** kaydedilir; vakti geçtiyse kimse etiketlenmeden "başladı" olarak güncellenir.
- **YouTube:** Kapalıyken yüklenen videolar (son 3 gün) açılışta duyurulur.
- **Uyarılar ve ayarlar** `data/` klasöründe durur.
- Kapalıyken silinen mesajlar ve giren/çıkan üyeler loglanamaz.

## Sorun giderme

| Sorun | Çözüm |
|---|---|
| `Token geçersiz` | Developer Portal'dan Reset Token yapıp `config.properties`'e yeniden yapıştır. |
| `INTENT` hatası | Kurulumdaki iki intent'i aç. |
| `!` komutlarına cevap vermiyor | MESSAGE CONTENT INTENT kapalı ya da komutu yazan yetkili değil. Başka birinin de kullanması için `sahip.id=` satırına ID'sini yaz. |
| "rolü benim rolümden yüksek" | Bot rolünü roller listesinde yukarı taşı. |

## Lisans

[MIT](LICENSE) © 2026 Semih Eren
