# 🎵 Fluir Ses Sistemi

> **Güçlü Discord ses botu** — JDA (Java Discord API) + LavaPlayer ile geliştirilmiştir.

---

## ✨ Özellikler

- 🎵 **YouTube, SoundCloud, Twitch, Vimeo, Bandcamp** desteği
- 📋 **Çalma kuyruğu** — istediğin kadar parça ekle
- 🔁 **Döngü modu** — parçayı tekrar tekrar çal
- 🔀 **Kuyruk karıştırma**
- 🔊 **Ses seviyesi ayarı** (0–150%)
- ⏸️ **Duraklatma / Devam**
- 🔍 **Arama desteği** — URL vermene gerek yok
- 📝 **Hem Slash (`/`) hem Prefix (`!`) komutlar**

---

## 🤖 Komutlar

| Komut | Açıklama |
|-------|----------|
| `/çal <URL/arama>` | Parça veya çalma listesi çalar |
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
git clone https://github.com/KULLANICI_ADI/fluir-ses-sistemi.git
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
4. Deploy otomatik başlar ✅

---

## 📁 Proje Yapısı

```
fluir-ses-sistemi/
├── src/main/java/com/fluir/bot/
│   ├── FluirBot.java              # Ana giriş noktası
│   ├── audio/
│   │   ├── AudioPlayerManager.java  # LavaPlayer yöneticisi
│   │   ├── GuildAudioManager.java   # Sunucu bazlı ses yöneticisi
│   │   ├── TrackScheduler.java      # Kuyruk ve döngü yönetimi
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

- **Java 17**
- **JDA 5.x** — Java Discord API
- **LavaPlayer 2.x** — Ses motoru
- **Maven** — Bağımlılık yönetimi
- **Docker** — Railway deployment
- **Logback** — Loglama

---

## ⚠️ Önemli Notlar

- `.env` dosyasını **asla** GitHub'a yükleme! (`.gitignore`'a ekli)
- Railway'de token'ı **Variables** kısmından ayarla
- YouTube'un bazı kısıtlamaları nedeniyle zaman zaman `ytsearch:` ön eki gerekebilir

---

*Fluir Ses Sistemi — JDA + LavaPlayer*
