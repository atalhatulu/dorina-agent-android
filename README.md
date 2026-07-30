# Dorina Agent Android

**Dorina Agent** — Android cihazlarda çalışan, yerel yapay zeka destekli akıllı asistandır. [dorina-agent](https://github.com/atalhatulu/dorina-agent) (Python Hermes tabanlı AI ajanı) beynini kullanan bir Android ön yüz uygulamasıdır.

> "Dorina" — Yerel AI Ajanınız, her an yardıma hazır.

---

## 🧠 Mimari

```
┌──────────────────────────────────────────────────┐
│                 Kullanıcı Arayüzü                 │
│         (Jetpack Compose + Material3)             │
├──────────────────────────────────────────────────┤
│                  ChatViewModel                    │
│        (Konuşma yönetimi + kalıcı hafıza)         │
├──────────────────────────────────────────────────┤
│               LocalAgentManager                   │
│  ┌─────────────┬──────────────┬────────────────┐  │
│  │ Kural Motoru │ LLM Karar   │ LLM Özetleyici │  │
│  │ (Offline)    │ Verici      │ (Summarizer)   │  │
│  └─────────────┴──────────────┴────────────────┘  │
├──────────────────────────────────────────────────┤
│                  ToolRegistry                      │
│  (Terminal, Pil, Wi-Fi, Kamera, Flaş, Notlar...)  │
├──────────────────────────────────────────────────┤
│                  SpeechManager                    │
│           (STT + TTS — Türkçe ses)               │
└──────────────────────────────────────────────────┘
```

### Katmanlı AI Motoru (3 Seviye)

| # | Motor | Ne Zaman | Gereksinim |
|---|---|---|---|
| 1️⃣ | **Kural Motoru** | Her zaman önce çalışır | Hiçbir şey gerekmez — offline |
| 2️⃣ | **MediaPipe GenAI** | Kural motoru yetmezse | Gemma 2B `.bin` model dosyası |
| 3️⃣ | **Ollama** | MediaPipe yoksa/devre dışıysa | localhost:11434'te çalışan Ollama |

---

## 🛠️ Araçlar (Tools)

| Araç | Açıklama |
|---|---|
| `terminal` | Her türlü shell komutu. Destructive pattern koruması (rm -rf, mkfs, fork bomb engellenir). Timeout desteği. |
| `device_info` | Cihaz modeli, Android sürümü, pil, depolama, bağlantı bilgisi |
| `get_battery` | Pil/şarj seviyesi |
| `get_wifi_status` | Wi-Fi/mobil veri bağlantı durumu |
| `read_file` | Uygulama klasöründen dosya okuma |
| `write_note` | Not kaydetme |
| `read_notes` | Kayıtlı notları listeleme |
| `open_camera` | Kamera uygulamasını açma |
| `toggle_flash` | Feneri açma/kapama |
| `open_app` | Yüklü uygulamaları açma (Türkçe ek temizleyici + 3 aşamalı eşleştirme) |

---

## 🎯 Özellikler

- **✅ Kural motoru öncelikli mimari** — 20+ komut kalıbı tanır, LLM gerektirmez
- **✅ Konuşma hafızası** — Son 3 diyalogu hatırlar, LLM prompt'unda kullanılır
- **✅ Kalıcı sohbet geçmişi** — `chat_history.json` dosyasına kaydedilir, uygulama kapanıp açılınca geri yüklenir
- **✅ Türkçe ses girişi/çıkışı** — Speech-to-Text + Text-to-Speech
- **✅ Assist Intent** — Power tuşuna basılı tutarak Dorina'yı açma
- **✅ Gemma 2B Int4 GPU/CPU desteği** — MediaPipe GenAI ile on-device LLM
- **✅ Ollama yedekleme** — localhost'ta Ollama varsa ona yönlenir
- **✅ GitHub Actions ile otomatik APK derleme**

---

## 📦 Kurulum

### Gereksinimler

- Android 8.0+ (API 26)
- İsteğe bağlı: Gemma 2B model dosyası (`/sdcard/Download/gemma-2b-it-gpu-int4.bin` veya `...-cpu.bin`)

### APK'yı Yükleme

1. GitHub Actions'dan APK'yı indir
2. Telefona kur
3. İzinleri ver (Mikrofon, Kamera, Depolama)

### Model Yükleme (İsteğe Bağlı)

Gemma 2B Int4 modelini [HuggingFace](https://huggingface.co/google/gemma-2b-it-tflite)'den indirip `/sdcard/Download/` klasörüne at:
```bash
# Telefonda Termux ile:
curl -L -o /sdcard/Download/gemma-2b-it-gpu-int4.bin \
  "https://huggingface.co/google/gemma-2b-it-tflite/resolve/main/gemma-2b-it-gpu-int4.bin"
```

> 📄 **Lisans:** Gemma modelleri Google lisansına tabidir. HuggingFace'de lisans onayı gereklidir.

---

## 🔧 Derleme

GitHub Actions otomatik derler. Elle derlemek için:

```bash
git clone https://github.com/atalhatulu/dorina-agent-android.git
cd dorina-agent-android
ANDROID_HOME=/path/to/sdk ./gradlew assembleDebug
```

APK çıktısı: `app/build/outputs/apk/debug/app-debug.apk`

---

## 🔗 İlgili Projeler

- [dorina-agent](https://github.com/atalhatulu/dorina-agent) — Python Hermes tabanlı ana AI ajanı (beyin)
- [TEATHA v4](https://github.com/atalhatulu/teatha) — ESP32-S3 WiFi güvenlik aracı

---

## 📜 Lisans

Özel proje — Tüm hakları saklıdır.
