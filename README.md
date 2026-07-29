# 🎵 Fluir Ses Sistemi

> **Güçlü Discord ses botu** — JDA 6.5 + DAVE + LavaPlayer 2.2.7 (SoundCloud) ile geliştirilmiştir.

---

## ✨ Özellikler

- 💾 **Kalıcı veriler** — sunucu ayarları, favoriler, geçmiş, çalma listeleri ve kuyruk kurtarma
- 🎛️ **Düğmeli kontrol paneli** — yalnızca ilk gerçek ses karesinden sonra “çalıyor” duyurusu
- 🛡️ **Ek güvenlik** — SSRF/LFI koruması, izinli HTTPS alan adları, oran ve kuyruk sınırı
- 🩺 **Gözlemlenebilirlik** — Railway `/health`, korumalı `/metrics` ve güvenli hata webhook'u
- ⚡ **Dayanıklılık** — sunucu bazlı devre kesici, bozuk URI kara listesi ve otomatik çalma

- 🎵 **SoundCloud, Twitch, Vimeo, Bandcamp** desteği
- 🟢 **Spotify metadata desteği** (Spotify bağlantıları otomatik olarak şarkı adı/sanatçı bilgisine dönüştürülüp SoundCloud üzerinden aratılır)
- 📋 **Çalma kuyruğu** — istediğin kadar parça ekle
- 🔁 **Döngü modu** — parçayı tekrar tekrar çal
- 🔀 **Kuyruk karıştırma**
- 🔊 **Ses seviyesi ayarı** (0–150%)
- ⏸️ **Duraklatma / Devam**
- 🔍 **Arama paneli desteği** — `scsearch:` ile SoundCloud üzerinden arama
- 📝 **Hem Slash (`/`) hem Prefix (`!`) komutlar**

---

## 🤖 Komutlar

| Komut | Açıklama |
|-------|----------|
| `/çal <URL/arama>` | SoundCloud parçası veya Spotify metadata araması çalar |
| `/dur` | Duraklatma/Devam |
| `/atla` | Sonraki parçaya geç |
| `/durdur` | Durdur ve ses kanalından çık |
| `/kuyruk` | Kuyruğu listele |
| `/karıştır` | Kuyruğu karıştır |
| `/temizle` | Kuyruğu temizle |
| `/ses <0-150>` | Ses seviyesini ayarla |
| `/döngü` | Döngü aç/kapat |
| `/şimdi` | Şu an çalan parçayı gör |
| `/yardım` | Komut listesi |

> Prefix komutlar da aynı şekilde çalışır: `!çal`, `!dur`, `!atla`, vb.

---

## 🚀 Kurulum

### 1. Discord Bot Oluştur

1. [Discord Developer Portal](https://discord.com/developers/applications) → **New Application**
2. **Bot** sekmesi → **Add Bot**
3. **TOKEN** kopyala
4. **Privileged Gateway Intents** → **MESSAGE CONTENT INTENT** aç
5. **OAuth2 → URL Generator** → `bot` + `applications.commands` seç
   - Yetki: `Connect`, `Speak`, `Send Messages`, `Read Messages`, `Embed Links`, `Use Slash Commands`

### 2. Yerel Geliştirme

```bash
# Projeyi klonla
git clone https://github.com/jonti4641/fluir-ses-sistemi.git
cd fluir-ses-sistemi

# .env dosyası oluştur
cp .env.example .env
# .env dosyasını düzenle ve token'ını yaz

# Derle ve çalıştır
mvn package -DskipTests
java -jar target/fluir-ses-sistemi-1.0.0.jar
```

### 3. Railway Deployment

1. [Railway.app](https://railway.app) → **New Project**
2. **Deploy from GitHub Repo** seç → bu repoyu bağla
3. **Variables** → `DISCORD_TOKEN` = `your_token` ekle
4. Kalıcı veriler için Railway Volume bağla; bot `RAILWAY_VOLUME_MOUNT_PATH` yolunu otomatik kullanır (`DATA_DIR` ile geçersiz kılabilirsin)
5. İstersen `ERROR_WEBHOOK_URL` ve uzun/rastgele bir `HEALTH_METRICS_TOKEN` tanımla
6. Deploy otomatik başlar; Railway `/health` yanıtını bekler ✅

---

## 📁 Proje Yapısı

```
fluir-ses-sistemi/
├── src/main/java/com/fluir/bot/
│   ├── FluirBot.java              # Ana giriş noktası
│   ├── audio/
│   │   ├── AudioPlayerManager.java  # LavaPlayer SoundCloud yöneticisi
│   │   ├── GuildAudioManager.java   # Sunucu bazlı ses yöneticisi
│   │   ├── TrackScheduler.java      # Kuyruk ve döngü yönetimi
│   │   ├── MusicPlaybackService.java# Müzik arama ve kurtarma servisi
│   │   └── AudioPlayerSendHandler.java # JDA ses köprüsü
│   └── commands/
│       └── CommandManager.java      # Slash + Prefix komutlar
├── src/main/resources/
│   └── logback.xml               # Loglama yapılandırması
├── Dockerfile                    # Railway için
├── pom.xml                       # Maven bağımlılıkları
└── .env.example                  # Ortam değişkeni şablonu
```

---

## 🛠️ Teknolojiler

- **Java 25**
- **JDA 6.5.0 + JDAVE 0.1.8** — Discord API ve ses şifreleme desteği
- **LavaPlayer 2.2.7** — Ses motoru (SoundCloud)
- **Maven** — Bağımlılık yönetimi
- **Docker** — Railway deployment
- **Logback** — Loglama

---

## ⚠️ Önemli Notlar

- `.env` dosyasını **asla** GitHub'a yükleme! (`.gitignore`'a ekli)
- Railway'de token'ı **Variables** kısmından ayarla
- Token/webhook değerlerini loglara, komutlara veya repoya yazma; bot hata webhook'unda bunları taşımaz.
- Genel HTTP URL ve yerel dosya oynatma güvenlik nedeniyle kapalıdır; yalnızca desteklenen HTTPS medya alan adları kabul edilir.
- **YouTube bağlantıları desteklenmez.** YouTube bağlantıları bot tarafından reddedilir.
- **Spotify Desteği:** Spotify bağlantıları yalnızca metadata (sanatçı + parça adı) çözümlemesidir; ses SoundCloud (`scsearch:`) üzerinden iletilir.

---

*Fluir Ses Sistemi — JDA 6.5 + DAVE + LavaPlayer 2.2.7 (SoundCloud)*
