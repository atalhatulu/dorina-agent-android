#!/usr/bin/env bash

echo "========================================="
echo "   Dorina Agent Android - Termux Build   "
echo "========================================="

# Java kontrolü
if ! command -v java &> /dev/null; then
    echo "❌ Java (JDK) bulunamadı."
    echo "Lütfen Termux'ta şu komutu çalıştırın:"
    echo "   pkg install openjdk-17 gradle"
    exit 1
fi

echo "🚀 APK derlemesi başlatılıyor..."
gradle assembleDebug

if [ -f "app/build/outputs/apk/debug/app-debug.apk" ]; then
    echo "✅ Derleme Başarılı!"
    echo "📦 APK Konumu: app/build/outputs/apk/debug/app-debug.apk"
    echo "📲 Kurmak için Termux'ta şu komutu çalıştırabilirsiniz:"
    echo "   termux-open app/build/outputs/apk/debug/app-debug.apk"
else
    echo "❌ Derleme sırasında bir hata oluştu."
fi
