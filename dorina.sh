#!/usr/bin/env bash
# Dorina Agent — Tek komutla başlat/durdur
# Kullanım:
#   ./dorina.sh start    # Ollama + Dorina Agent'ı başlat
#   ./dorina.sh stop     # Her şeyi durdur
#   ./dorina.sh restart  # Yeniden başlat
#   ./dorina.sh status   # Durumu göster

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
AGENT_SCRIPT="$SCRIPT_DIR/dorina_termux.py"
OLLAMA_PIDFILE="/tmp/dorina-ollama.pid"
AGENT_PIDFILE="/tmp/dorina-agent.pid"
LOG_FILE="$SCRIPT_DIR/dorina.log"

_red()    { echo -e "\033[1;31m$1\033[0m"; }
_green()  { echo -e "\033[1;32m$1\033[0m"; }
_yellow() { echo -e "\033[1;33m$1\033[0m"; }
_blue()   { echo -e "\033[1;34m$1\033[0m"; }

case "${1:-help}" in
  start)
    echo -n "$(_blue 'Dorina Agent başlatılıyor...')"

    # 1. Ollama kontrol et / başlat
    if pgrep -x ollama >/dev/null 2>&1; then
      echo -n "$(_yellow ' Ollama zaten çalışıyor,')"
    else
      echo -n "$(_yellow ' Ollama başlatılıyor...')"
      nohup ollama serve > "$LOG_FILE" 2>&1 &
      echo $! > "$OLLAMA_PIDFILE"
      # Ollama'nın hazır olmasını bekle
      for i in $(seq 1 30); do
        if curl -s http://127.0.0.1:11434/api/tags >/dev/null 2>&1; then
          echo -n "$(_green ' hazır,')"
          break
        fi
        sleep 1
      done
    fi

    # 2. Dorina Agent'ı başlat (daemon mod)
    if [ -f "$AGENT_PIDFILE" ] && kill -0 "$(cat "$AGENT_PIDFILE")" 2>/dev/null; then
      echo "$(_yellow ' Agent zaten çalışıyor.')"
    else
      nohup python3 "$AGENT_SCRIPT" --daemon > "$LOG_FILE" 2>&1 &
      echo $! > "$AGENT_PIDFILE"
      sleep 2
      echo -n "$(_green ' başladı')"
    fi

    echo "$(_green ' ✓')"
    echo "$(_blue '  Web UI: http://127.0.0.1:5792')"
    echo "$(_blue '  CLI:    python3 dorina_termux.py --cli')"
    echo "$(_blue '  Log:    tail -f dorina.log')"
    ;;

  stop)
    echo -n "$(_yellow 'Dorina Agent durduruluyor...')"

    # Agent'ı durdur (tam PID'den, grep ile kendini vurmaz)
    if [ -f "$AGENT_PIDFILE" ]; then
      kill "$(cat "$AGENT_PIDFILE")" 2>/dev/null && echo -n "$(_green ' agent durdu,')" || echo -n "$(_green ' agent temiz,')"
      rm -f "$AGENT_PIDFILE"
    fi
    # Yine de kalmasın diye ek kontrol
    AGENT_PID=$(pgrep -f 'python3.*dorina_termux' 2>/dev/null || true)
    if [ -n "$AGENT_PID" ]; then
      kill "$AGENT_PID" 2>/dev/null || true
    fi

    # Ollama'yı durdur
    if [ -f "$OLLAMA_PIDFILE" ]; then
      kill "$(cat "$OLLAMA_PIDFILE")" 2>/dev/null && echo -n "$(_green ' ollama durdu,')" || echo -n "$(_red ' ollama PID bulunamadı,')"
      rm -f "$OLLAMA_PIDFILE"
    else
      pkill -x ollama 2>/dev/null && echo -n "$(_green ' ollama durduruldu')" || echo -n "$(_yellow ' ollama çalışmıyor')"
    fi

    echo "$(_green ' ✓')"
    ;;

  restart)
    "$0" stop
    sleep 1
    "$0" start
    ;;

  status)
    echo "$(_blue 'Durum:')"
    if pgrep -x ollama >/dev/null 2>&1; then
      echo "  Ollama:  $(_green '✓ çalışıyor') ($(ollama --version 2>/dev/null || echo '?'))"
    else
      echo "  Ollama:  $(_red '✗ durdu')"
    fi
    if [ -f "$AGENT_PIDFILE" ] && kill -0 "$(cat "$AGENT_PIDFILE")" 2>/dev/null; then
      echo "  Dorina:  $(_green '✓ çalışıyor') (PID: $(cat "$AGENT_PIDFILE"))"
    elif pgrep -f "dorina_termux.py" >/dev/null 2>&1; then
      echo "  Dorina:  $(_green '✓ çalışıyor') (PID: $(pgrep -f 'dorina_termux.py' | head -1))"
    else
      echo "  Dorina:  $(_red '✗ durdu')"
    fi
    if curl -s http://127.0.0.1:5792/api/status >/dev/null 2>&1; then
      echo "  Web UI:  $(_green '✓ http://127.0.0.1:5792')"
    else
      echo "  Web UI:  $(_yellow '~ yanıt vermiyor')"
    fi
    ;;

  *)
    echo "$(_blue 'Kullanım:')"
    echo "  $(_green './dorina.sh start')   — Ollama + Dorina Agent başlat"
    echo "  $(_green './dorina.sh stop')    — Her şeyi durdur"
    echo "  $(_green './dorina.sh restart') — Yeniden başlat"
    echo "  $(_green './dorina.sh status')  — Durum kontrol"
    ;;
esac
