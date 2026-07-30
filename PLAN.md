# Dorina Agent — Plan ve Mimari

> **Hedef:** Android ve PC'de çalışan, local LLM kullanan, araç tabanlı bir AI asistanı.
> **Felsefe:** Basit, minimal, local-first. Her şey cihazda çalışır, bulut yok.

---

## 1. Ne İstiyoruz?

Kullanıcının telefonundan (ve PC'sinden) komut alıp **yerel modellerle** cevap veren,
gerektiğinde **Android araçlarını** (pil, wifi, dosya, SMS, arama, kamera vb.)
kullanabilen bir agent.

### Kullanım senaryoları:
- "Pil kaç?" → sistem aracı → cevap
- "WiFi'yi aç/kapat" → sistem aracı → işlem
- "Selam, nasılsın?" → LLM sohbet
- "/storage/Download/test.txt oku" → dosya aracı → LLM özeti
- "Bana 5dk sonra hatırlat" → alarm aracı

---

## 2. Çalışma Prensibi

```
Kullanıcı mesajı
    ↓
[1] Kural Motoru (Regex) — hızlı yol, LLM'siz
    ↓ eşleşmezse
[2] LLM (Gemma 2B) → Tool seç → Çalıştır → Sonucu LLM'ye ver → Cevap üret
```

**Kural Motoru:** Basit regex eşleştirme. "pil", "batarya", "wifi", "wi-fi" gibi
anahtar kelimeleri tanır, direkt aracı çağırır, LLM'ye gerek kalmaz. → **Anında cevap.**

**ReAct Loop:** LLM'nin düşünmesini gerektiren durumlar. LLM tool seçer,
çalıştırır, sonuca göre devam eder veya cevap üretir.

---

## 3. Aşamalar

### Aşama 1 — Sadece Sohbet ✅ (çalışıyor)
- Python scripti (`dorina.py`)
- Gemma 2B'ye soru sor, cevap al
- `python3 dorina.py "soru"` veya interaktif mod
- SSH üzerinden test edilebilir

**Durum:** Çalışıyor ama Ollama CPU'da yavaş (~5-10 sn/token).
Telefonda interaktif kullanımda `input()` ile çalışır.

### Aşama 2 — Araçlar (Tools)
Android'de çalışacak temel araçlar:

| Araç | Açıklama | Bağımlılık |
|------|----------|-----------|
| `get_battery` | Pil seviyesi, sıcaklık | `termux-battery-status` |
| `get_wifi` | WiFi durumu, SSID | `termux-wifi-scaninfo` |
| `set_wifi` | WiFi aç/kapa | `termux-wifi-enable` |
| `read_file` | Dosya oku (sdcard) | Dosya izni |
| `list_files` | Dizin listele | Dosya izni |
| `get_location` | GPS konum | `termux-location` |
| `send_sms` | SMS gönder | `termux-sms-send` |
| `notify` | Bildirim göster | `termux-notification` |
| `clipboard` | Panoya oku/yaz | `termux-clipboard-get/set` |
| `torch` | Flaş aç/kapa | `termux-torch` |
| `volume` | Ses seviyesi | `termux-volume` |

**Bağımlılık:** Termux:API Android uygulaması + `pkg install termux-api`

### Aşama 3 — Android Kotlin Uygulaması
MediaPipe Gemma GPU modeli (`.bin` dosyası) ile çalışan native Android uygulaması.

- MediaPipe TFLite GPU delegate → hızlı inference
- Aynı araç seti (Android API'leri ile)
- F-Droid / APK dağıtımı

---

## 4. Mimari Kararlar

### Termux mu, Ubuntu Proot mu?
- **Termux daha hızlı:** Doğrudan donanıma erişim, daha az overhead
- Ubuntu Proot'ta background process'ler proot kapanınca ölüyor
- **Karar:** Her şey Termux'ta çalışacak

### Ollama API (Chat vs Generate)
- `gemma:2b` sadece **completion** (`/api/generate`) destekliyor
- `llama3.2:1b` **chat** (`/api/chat`) + tool calling destekliyor
- **Karar:** `/api/generate` kullanılacak, chat API fallback olarak dene

### GPU Hızlandırma
- Termux Ollama'sında `libggml-vulkan.so` yok → GPU kullanılamıyor
- MediaPipe model (`.bin`) GPU destekli ama Python'da kullanılamaz
- **Çözüm:** Kotlin Android uygulaması MediaPipe ile GPU'da çalışacak
- Termux'ta CPU'da çalışmak yeterli (küçük modeller için)

### Communication (PC ↔ Telefon)
- SSH üzerinden bağlantı (port 8022, Termux sshd)
- PC'den telefona script gönderme: SCP
- Agent API'si: HTTP (port 5792) — sadece localhost
- PC'de Hermes Agent orchestrator, telefonda Dorina worker

---

## 5. Dosya Yapısı

```
~/Documents/GitHub/dorina-agent-android/
├── dorina.py              # Aşama 1: Basit sohbet (Python)
├── dorina_termux.py       # Aşama 2: Araçlı agent (Python, Termux)
├── dorina.sh              # Start/stop/status scripti
├── PLAN.md                # Bu dosya
├── app/                   # Aşama 3: Android Kotlin
│   ├── src/main/kotlin/com/dorina/agent/
│   │   ├── LocalAgentManager.kt   # ReAct loop
│   │   ├── ToolRegistry.kt         # Android araçları
│   │   ├── ui/ChatViewModel.kt     # UI state
│   │   └── ui/ChatScreen.kt        # Compose UI
│   └── build.gradle.kts
└── README.md
```

---

## 6. Bağımlılıklar

### Termux
```bash
pkg install python3 openssh termux-api
```

### Python
```python
# Sadece standart kütüphane (urllib, json, subprocess)
# Ekstra bağımlılık YOK
```

### Android Kotlin
- MediaPipe (Gemma modeli)
- Kotlin coroutines
- Jetpack Compose
- OkHttp (Ollama API için)

---

## 7. Yol Haritası

```
Aşama 1 [✅] → Basit sohbet çalışıyor
    ↓
Aşama 2 [⏳] → Araçlar + Kural Motoru
    ↓
Aşama 3 [⏳] → Android Kotlin APK
    ↓
Aşama 4 [ ] → ReAct Loop (LLM tool seçimi)
    ↓
Aşama 5 [ ] → Gateway (PC ↔ Telefon haberleşmesi)
```

---

## 8. Bilinen Sorunlar

1. **Ollama CPU'da yavaş** — Termux'ta GPU desteği yok. MediaPipe'ye geçince çözülür.
2. **Proot'ta background process** — Proot kapanınca ölüyor. Termux'ta çalış.
3. **SSH kill matching** — `pkill -f` SSH bağlantısını da öldürüyor. PID ile öldür.
4. **Termux-API gerekli** — Araçlar için. `pkg install termux-api` + Android uygulaması.

---

## 9. Hızlı Referans

### Telefonda başlatma
```bash
sshd                    # Termux SSH (port 8022)
ollama serve            # Ollama API
python3 dorina.py       # Aşama 1 sohbet
```

### PC'den bağlantı
```bash
ssh -p 8022 root@192.168.1.40
scp -P 8022 dosya.py root@192.168.1.40:~/
```

### Test
```bash
# Ollama hızlı test
ollama run gemma:2b "kisa cevap ver"

# Agent test (Aşama 1)
python3 dorina.py "merhaba"

# Agent API test (Aşama 2)
curl http://127.0.0.1:5792/api/status
curl -X POST http://127.0.0.1:5792/api/chat \
  -H "Content-Type: application/json" \
  -d '{"query":"pil kaç"}'
```
