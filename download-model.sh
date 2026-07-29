#!/usr/bin/env bash

echo "=================================================="
echo "   Dorina Agent - S24 Ultra Gemma Model Downloader "
echo "=================================================="

TARGET_DIR="/sdcard/Documents/dorina-agent-android/app/src/main/assets"
MODEL_FILE="$TARGET_DIR/gemma-2b-it-cpu.bin"

mkdir -p "$TARGET_DIR"

if [ -f "$MODEL_FILE" ]; then
    echo "✅ Model zaten indirildi: $MODEL_FILE"
    exit 0
fi

HF_TOKEN="$1"

if [ -z "$HF_TOKEN" ]; then
    echo "⚠️ S24 Ultra cihazınız Snapdragon 8 Gen 3 ve 12GB RAM ile mükemmel donanıma sahiptir!"
    echo "Gemma modeli Google lisansı gereği HuggingFace veya Kaggle üzerinde korumalıdır (gated)."
    echo ""
    echo "İndirmek için 2 kolay yönteminiz var:"
    echo ""
    echo "YÖNTEM 1 (Otomatik Script):"
    echo "1. https://huggingface.co/google/gemma-2b-it-tflite adresine girip 'Accept License' butonuna basın."
    echo "2. https://huggingface.co/settings/tokens adresinden bir read token alın."
    echo "3. Termux'ta şu komutu çalıştırın:"
    echo "   ./download-model.sh HF_TOKEN_BURAYA"
    echo ""
    echo "YÖNTEM 2 (Manuel İndirme):"
    echo "HuggingFace veya Kaggle'dan 'gemma-2b-it-cpu-int4.bin' veya 'gemma-2b-it-gpu-int4.bin' dosyasını indirip telefonunuzda şu konuma kopyalayın:"
    echo "   $MODEL_FILE"
    exit 1
fi

echo "📥 S24 Ultra için Gemma 2B Int4 modeli indiriliyor (~1.3 GB)..."
echo "Hedef Konum: $MODEL_FILE"

curl -L -H "Authorization: Bearer $HF_TOKEN" \
     -o "$MODEL_FILE" \
     "https://huggingface.co/google/gemma-2b-it-tflite/resolve/main/gemma-2b-it-cpu-int4.bin?download=true"

if [ -f "$MODEL_FILE" ] && [ $(stat -c%s "$MODEL_FILE") -gt 100000000 ]; then
    echo "🎉 Gemma modeli S24 Ultra cihazınıza başarıyla indirildi ve yüklendi!"
else
    echo "❌ İndirme başarısız veya token geçersiz. Lütfen HuggingFace lisans onayını kontrol edin."
    rm -f "$MODEL_FILE"
fi
