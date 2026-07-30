#!/usr/bin/env python3
"""
Dorina Agent — Termux Minimal
Sıfır bağımlılık, sadece Python stdlib.
Ollama API ile local LLM, terminal aracı, HTTP API.
"""

import http.client
import json
import os
import shlex
import signal
import subprocess
import sys
import threading
import time
import urllib.request
import urllib.error
from datetime import datetime
from http.server import HTTPServer, BaseHTTPRequestHandler
from pathlib import Path

# ── Config ──────────────────────────────────────────────────
OLLAMA_HOST = "http://127.0.0.1:11434"
OLLAMA_MODEL = "gemma:2b"
HOST = "127.0.0.1"
PORT = 5792
DATA_DIR = Path(os.environ.get("HOME", "/tmp")) / ".dorina-termux"
DATA_DIR.mkdir(parents=True, exist_ok=True)
MEMORY_FILE = DATA_DIR / "memory.json"
CHAT_FILE = DATA_DIR / "chat_history.json"

# ── Tools ───────────────────────────────────────────────────
DESTRUCTIVE_PATTERNS = [
    "rm -rf /", "rm -rf /*", "rm -rf --no-preserve-root",
    "mkfs", "dd if=", "> /dev/sd", "> /dev/block",
    ":(){ :|:& };:", "chmod 777 /", "chown -R",
]


def is_destructive(cmd: str) -> bool:
    c = cmd.lower().strip()
    for p in DESTRUCTIVE_PATTERNS:
        if p in c:
            return True
    return False


def run_terminal(command: str, timeout: int = 30) -> dict:
    if is_destructive(command):
        return {"success": False, "result": "Güvenlik Engeli: Bu komut engellendi."}
    try:
        proc = subprocess.run(
            ["sh", "-c", command],
            capture_output=True, text=True, timeout=timeout
        )
        output = proc.stdout.strip()
        error = proc.stderr.strip()
        result = output
        if error:
            result += f"\nSTDERR: {error}" if output else error
        if proc.returncode != 0:
            result += f"\n(exit: {proc.returncode})"
        return {"success": True, "result": result or f"Komut çalıştı (exit: {proc.returncode})"}
    except subprocess.TimeoutExpired:
        return {"success": False, "result": f"Komut {timeout}s içinde tamamlanamadı."}
    except Exception as e:
        return {"success": False, "result": f"Hata: {e}"}


AVAILABLE_TOOLS = {
    "terminal": {
        "description": "Shell komutu çalıştırır. ping, curl, ls, df, cat, her şey.",
        "args": {"command": "string", "timeout": "integer (opsiyonel, varsayılan 30)"}
    },
    "get_battery": {
        "description": "Pil/şarj seviyesini gösterir.",
        "args": {}
    },
    "get_wifi": {
        "description": "Wi-Fi/internet bağlantı durumunu gösterir.",
        "args": {}
    },
    "device_info": {
        "description": "Cihaz modeli, Android sürümü, depolama bilgisi.",
        "args": {}
    },
    "read_file": {
        "description": "Dosya okur.",
        "args": {"path": "string"}
    },
    "list_dir": {
        "description": "Klasör içeriğini listeler.",
        "args": {"path": "string (opsiyonel)"}
    },
}

TOOLS_DOC = "\n".join(
    f"  • {name} - {info['description']}"
    for name, info in AVAILABLE_TOOLS.items()
)


# ── LLM ─────────────────────────────────────────────────────

def ollama_chat(messages: list[dict], system: str = None) -> str | None:
    """Ollama API'ye chat isteği gönder."""
    payload = {
        "model": OLLAMA_MODEL,
        "messages": messages,
        "stream": False,
        "options": {"temperature": 0.7, "num_predict": 1024}
    }
    if system:
        payload["system"] = system

    try:
        data = json.dumps(payload).encode()
        req = urllib.request.Request(
            f"{OLLAMA_HOST}/api/chat",
            data=data,
            headers={"Content-Type": "application/json"},
            method="POST"
        )
        with urllib.request.urlopen(req, timeout=60) as resp:
            result = json.loads(resp.read())
            return result.get("message", {}).get("content", "")
    except Exception as e:
        return None


def ollama_generate(prompt: str, system: str = None) -> str | None:
    """Ollama generate API (non-chat)."""
    payload = {
        "model": OLLAMA_MODEL,
        "prompt": prompt,
        "system": system or "",
        "stream": False,
        "options": {"temperature": 0.7, "num_predict": 1024}
    }
    try:
        data = json.dumps(payload).encode()
        req = urllib.request.Request(
            f"{OLLAMA_HOST}/api/generate",
            data=data,
            headers={"Content-Type": "application/json"},
            method="POST"
        )
        with urllib.request.urlopen(req, timeout=60) as resp:
            result = json.loads(resp.read())
            return result.get("response", "")
    except Exception as e:
        return None


def check_ollama() -> bool:
    """Ollama çalışıyor mu?"""
    try:
        req = urllib.request.Request(f"{OLLAMA_HOST}/api/tags")
        with urllib.request.urlopen(req, timeout=3) as resp:
            return resp.status == 200
    except Exception:
        return False


# ── Bellek ──────────────────────────────────────────────────

def load_memory():
    if MEMORY_FILE.exists():
        try:
            return json.loads(MEMORY_FILE.read_text())
        except Exception:
            return {}
    return {}


def save_memory(data: dict):
    MEMORY_FILE.write_text(json.dumps(data, indent=2, ensure_ascii=False))


def load_chat():
    if CHAT_FILE.exists():
        try:
            return json.loads(CHAT_FILE.read_text())
        except Exception:
            return []
    return []


def save_chat(messages: list):
    CHAT_FILE.write_text(json.dumps(messages[-20:], indent=2, ensure_ascii=False))


# ── Kural Motoru ────────────────────────────────────────────

def rule_engine(text: str) -> dict | None:
    t = text.lower().strip()

    # Pil
    if any(k in t for k in ["şarj", "pil", "batarya", "battery", "yüzde", "dolum", "charge"]):
        return {"tool": "get_battery", "args": {}}

    # Wi-Fi
    if any(k in t for k in ["wifi", "wi-fi", "internet", "bağlantı", "modem", "network"]):
        return {"tool": "get_wifi", "args": {}}

    # Cihaz bilgisi
    if any(k in t for k in ["cihaz", "model", "telefon", "sistem", "özellik", "donanım", "hardware"]):
        return {"tool": "device_info", "args": {}}

    # Hava durumu
    if any(k in t for k in ["hava", "weather", "sıcaklık", "derece", "yağmur", "kar", "rüzgar"]):
        return {"tool": "terminal", "args": {"command": "curl -s 'wttr.in/Istanbul?format=%C+%t+%w'"}}

    # Tarih/saat
    if any(k in t for k in ["tarih", "saat", "zaman", "gün", "date", "time"]):
        return {"tool": "terminal", "args": {"command": "date '+%A, %d %B %Y - %H:%M:%S'"}}

    # Depolama
    if any(k in t for k in ["depolama", "hafıza", "disk", "boş alan", "storage", "gb"]):
        return {"tool": "terminal", "args": {"command": "df -h /sdcard /data 2>/dev/null; echo '---'; free -h 2>/dev/null"}}

    # Ping
    if "ping" in t and "pingle" not in t:
        target = "8.8.8.8" if "8.8.8.8" in t else ("google.com" if "google" in t else "1.1.1.1")
        return {"tool": "terminal", "args": {"command": f"ping -c 4 {target}"}}

    # CPU
    if any(k in t for k in ["cpu", "işlemci", "processor", "çekirdek"]):
        return {"tool": "terminal", "args": {"command": "cat /proc/cpuinfo | grep -E 'processor|model name|Hardware' | head -10"}}

    # RAM
    if any(k in t for k in ["ram", "bellek", "memory"]):
        return {"tool": "terminal", "args": {"command": "free -h 2>/dev/null || cat /proc/meminfo | head -10"}}

    # Ağ/IP
    if any(k in t for k in ["ağ", "ip", "mac", "ip adres"]):
        return {"tool": "terminal", "args": {"command": "ip addr show | grep -E 'inet|link' || ifconfig"}}

    # Kimler bağlı
    if any(k in t for k in ["kimler", "kim bağlı", "oturum", "who"]):
        return {"tool": "terminal", "args": {"command": "who 2>/dev/null; echo '---'; ps -A 2>/dev/null | head -10"}}

    # Uygulama listesi
    if any(k in t for k in ["uygulama listele", "yüklü uygulama", "paket listele", "program listele"]):
        return {"tool": "terminal", "args": {"command": "pm list packages | awk -F: '{print $2}' | sort | head -40"}}

    # Dosya listele
    if any(k in t for k in ["dosya listele", "klasör", "dizin", "neler var"]):
        path = "/sdcard/Download" if "indir" in t else "/sdcard"
        return {"tool": "terminal", "args": {"command": f"ls -la '{path}' 2>/dev/null | head -30"}}

    # Uptime
    if any(k in t for k in ["uptime", "çalışma süresi", "açık kalma", "ne zamandır"]):
        return {"tool": "terminal", "args": {"command": "uptime"}}

    return None


# ── Tool Executor ───────────────────────────────────────────

def execute_tool(name: str, args: dict) -> dict:
    name = name.lower()
    if name == "terminal":
        return run_terminal(args.get("command", ""), args.get("timeout", 30))
    elif name == "get_battery":
        return run_terminal("dumpsys battery 2>/dev/null || cat /sys/class/power_supply/*/capacity 2>/dev/null || echo 'Pil bilgisi alınamadı'")
    elif name == "get_wifi":
        return run_terminal("dumpsys connectivity 2>/dev/null | grep -E 'NetworkAgentInfo|ActiveNetwork' | head -5 || echo 'Wi-Fi durumu:' && ip addr show wlan0 2>/dev/null | grep 'inet ' || echo 'Wi-Fi bilgisi alınamadı'")
    elif name == "device_info":
        model = run_terminal("getprop ro.product.model 2>/dev/null || echo '?'")
        android = run_terminal("getprop ro.build.version.release 2>/dev/null || echo '?'")
        sdk = run_terminal("getprop ro.build.version.sdk 2>/dev/null || echo '?'")
        storage = run_terminal("df -h /sdcard 2>/dev/null | tail -1 | awk '{print $4 \" boş\"}' || echo '?'")
        return {
            "success": True,
            "result": (
                f"Cihaz: {model.get('result', '?')}\n"
                f"Android: {android.get('result', '?')} (API {sdk.get('result', '?')})\n"
                f"Depolama: {storage.get('result', '?')}\n"
                f"Model: {OLLAMA_MODEL}"
            )
        }
    elif name == "read_file":
        path = args.get("path", "")
        try:
            content = Path(path).read_text()
            return {"success": True, "result": content[:2000]}
        except Exception as e:
            return {"success": False, "result": f"Dosya okunamadı: {e}"}
    elif name == "list_dir":
        path = args.get("path", "/sdcard")
        try:
            items = "\n".join(os.listdir(path))[:2000]
            return {"success": True, "result": items}
        except Exception as e:
            return {"success": False, "result": f"Klasör okunamadı: {e}"}
    else:
        return {"success": False, "result": f"Bilinmeyen araç: {name}"}


# ── Ana Agent Loop ──────────────────────────────────────────

SYSTEM_PROMPT = (
    "Sen Dorina'sın, Android cihazda çalışan bir yapay zeka asistanısın. "
    "Kullanıcıya yardım etmek için aşağıdaki araçları kullanabilirsin. "
    "Sadece JSON formatında cevap ver: {\"tool\": \"adı\", \"args\": {...}}\n\n"
    "KULLANILABİLİR ARAÇLAR:\n"
    + TOOLS_DOC +
    "\n\nEğer araç gerekmiyorsa normal Türkçe cevap ver."
)


def process_query(user_input: str, history: list[dict] | None = None) -> tuple[str, dict | None]:
    """Ana sorgu işleme: kural motoru → LLM → tool executor → özetleme."""
    if history is None:
        history = []

    # 1. Kural Motoru
    rule_tool = rule_engine(user_input)
    if rule_tool:
        tool_result = execute_tool(rule_tool["tool"], rule_tool["args"])
        summary = summarize_with_llm(user_input, rule_tool["tool"], tool_result)
        return summary, tool_result

    # 2. LLM Karar
    chat_history = history[-6:] if history else []
    messages = [
        {"role": "system", "content": SYSTEM_PROMPT}
    ]
    for h in chat_history:
        messages.append({"role": "user", "content": h["user"]})
        if h.get("assistant"):
            messages.append({"role": "assistant", "content": h["assistant"]})
    messages.append({"role": "user", "content": user_input})

    llm_response = ollama_chat(messages)
    if not llm_response:
        return "Ollama'ya bağlanamadım. Servis çalışıyor mu? (ollama serve)", None

    # JSON tool call var mı?
    tool_call = parse_json_tool(llm_response)
    if tool_call:
        tool_result = execute_tool(tool_call["tool"], tool_call.get("args", {}))
        summary = summarize_with_llm(user_input, tool_call["tool"], tool_result, llm_response)
        return summary, tool_result

    # LLM normal cevap verdi
    return llm_response, None


def parse_json_tool(text: str) -> dict | None:
    """JSON tool call parse et."""
    try:
        start = text.index('{')
        end = text.rindex('}') + 1
        obj = json.loads(text[start:end])
        if "tool" in obj:
            return obj
    except (ValueError, json.JSONDecodeError):
        pass
    return None


def summarize_with_llm(query: str, tool: str, result: dict, llm_hint: str = None) -> str:
    """Tool sonucunu LLM ile özetle."""
    if not result.get("success"):
        return f"❌ {tool} hatası: {result.get('result', '?')}"

    prompt = (
        f"Kullanıcı: {query}\n"
        f"Araç: {tool}\n"
        f"{f'Seçim: {llm_hint[:200]}' if llm_hint else ''}\n"
        f"Sonuç: {result.get('result', '')}\n\n"
        "Kullanıcıya sonucu kısa ve doğal Türkçe ile söyle. "
        "Araç adını söyleme, direkt cevap ver."
    )

    llm_resp = ollama_generate(prompt)
    if llm_resp and len(llm_resp) > 10:
        return llm_resp.strip()

    # LLM yoksa direkt sonuç
    return result.get("result", "İşlem tamam.")


# ── HTTP API (FastAPI yerine stdlib http.server) ────────────

conversation_history: list[dict] = load_chat()


class AgentHandler(BaseHTTPRequestHandler):

    def log_message(self, format, *args):
        pass  # sessiz

    def _send_json(self, data: dict, status=200):
        body = json.dumps(data, ensure_ascii=False).encode()
        self.send_response(status)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Access-Control-Allow-Origin", "*")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def _send_html(self, html: str):
        body = html.encode("utf-8")
        self.send_response(200)
        self.send_header("Content-Type", "text/html; charset=utf-8")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def do_OPTIONS(self):
        self.send_response(204)
        self.send_header("Access-Control-Allow-Origin", "*")
        self.send_header("Access-Control-Allow-Methods", "GET, POST, OPTIONS")
        self.send_header("Access-Control-Allow-Headers", "Content-Type")
        self.end_headers()

    def do_GET(self):
        if self.path == "/":
            self._send_html(INDEX_HTML)
        elif self.path == "/api/status":
            self._send_json({
                "name": "Dorina Agent (Termux)",
                "model": OLLAMA_MODEL,
                "ollama": check_ollama(),
                "history": len(conversation_history),
                "active": True,
            })
        elif self.path == "/api/history":
            self._send_json(conversation_history[-20:])
        else:
            self._send_json({"error": "Not found"}, 404)

    def do_POST(self):
        global conversation_history

        if self.path == "/api/chat":
            length = int(self.headers.get("Content-Length", 0))
            body = self.rfile.read(length).decode()
            try:
                data = json.loads(body)
                query = data.get("query", "").strip()
            except (json.JSONDecodeError, Exception):
                self._send_json({"error": "Geçersiz JSON"}, 400)
                return

            if not query:
                self._send_json({"error": "Sorgu boş"}, 400)
                return

            # İşle
            answer, tool_result = process_query(query, conversation_history)
            conversation_history.append({"user": query, "assistant": answer, "tool": tool_result})
            save_chat(conversation_history)

            self._send_json({
                "response": answer,
                "tool": tool_result,
                "history_count": len(conversation_history),
            })
        elif self.path == "/api/clear":
            conversation_history = []
            save_chat([])
            self._send_json({"ok": True, "message": "Geçmiş temizlendi."})
        else:
            self._send_json({"error": "Not found"}, 404)


# ── Web Arayüzü ─────────────────────────────────────────────

INDEX_HTML = """<!DOCTYPE html>
<html lang="tr">
<head>
<meta charset="UTF-8">
<meta name="viewport" content="width=device-width, initial-scale=1.0">
<title>Dorina Agent - Termux</title>
<style>
* { margin:0; padding:0; box-sizing:border-box; }
body { font-family: -apple-system, 'Segoe UI', sans-serif; background: #0a0a0c; color: #e0e0e0; height:100vh; display:flex; flex-direction:column; }
.header { background:#121212; padding:12px 20px; display:flex; justify-content:space-between; align-items:center; border-bottom:1px solid #2a2a2a; }
.header h1 { font-size:18px; color:#8E44AD; }
.header span { font-size:12px; color:#888; }
.chat { flex:1; overflow-y:auto; padding:16px; display:flex; flex-direction:column; gap:10px; }
.msg { max-width:85%; padding:12px 16px; border-radius:16px; font-size:14px; line-height:1.5; white-space:pre-wrap; }
.msg.user { align-self:flex-end; background:linear-gradient(135deg,#6C5CE7,#8E44AD); color:#fff; border-bottom-right-radius:4px; }
.msg.agent { align-self:flex-start; background:#1e1e26; color:#e0e0e0; border-bottom-left-radius:4px; }
.msg.system { align-self:center; background:#1a1a20; color:#aaa; font-size:12px; padding:8px 16px; border-radius:8px; }
.msg .tool { font-size:11px; color:#00E676; margin-top:6px; padding:6px 8px; background:#121216; border-radius:6px; }
.input-bar { display:flex; padding:12px; gap:8px; background:#16161a; border-top:1px solid #2a2a2a; }
.input-bar input { flex:1; padding:12px 16px; border-radius:24px; border:1px solid #333; background:#1a1a20; color:#fff; font-size:14px; outline:none; }
.input-bar input:focus { border-color:#8E44AD; }
.input-bar button { width:48px; height:48px; border-radius:50%; border:none; background:#8E44AD; color:#fff; font-size:20px; cursor:pointer; }
.input-bar button:hover { background:#9B59B6; }
.spinner { display:inline-block; width:14px; height:14px; border:2px solid #8E44AD; border-top-color:transparent; border-radius:50%; animation:spin .6s linear infinite; }
@keyframes spin { to{transform:rotate(360deg)} }
</style>
</head>
<body>
<div class="header">
  <h1>Dorina Agent</h1>
  <span id="model">Ollama: """ + OLLAMA_MODEL + """</span>
</div>
<div class="chat" id="chat"></div>
<div class="input-bar">
  <input id="input" placeholder="Dorina'ya sor..." autofocus>
  <button id="send">➔</button>
</div>
<script>
const chat = document.getElementById('chat');
const input = document.getElementById('input');
const send = document.getElementById('send');

function addMsg(text, type, tool=null) {
  const div = document.createElement('div');
  div.className = 'msg ' + type;
  div.textContent = text;
  if (tool) {
    const t = document.createElement('div');
    t.className = 'tool';
    t.textContent = '🔧 ' + (tool.toolName || Object.keys(tool).length ? JSON.stringify(tool).slice(0,100) : '');
    div.appendChild(t);
  }
  chat.appendChild(div);
  chat.scrollTop = chat.scrollHeight;
}

let loading = false;
async function sendQuery() {
  const q = input.value.trim();
  if (!q || loading) return;
  input.value = '';
  addMsg(q, 'user');
  loading = true;
  addMsg('...', 'system');
  try {
    const r = await fetch('/api/chat', { method:'POST', headers:{'Content-Type':'application/json'}, body:JSON.stringify({query:q}) });
    const d = await r.json();
    chat.removeChild(chat.lastChild);
    addMsg(d.response, 'agent', d.tool);
  } catch(e) {
    chat.removeChild(chat.lastChild);
    addMsg('Bağlantı hatası: ' + e.message, 'system');
  }
  loading = false;
}

send.onclick = sendQuery;
input.onkeydown = e => { if(e.key === 'Enter') sendQuery(); };

// Geçmişi yükle
fetch('/api/history').then(r=>r.json()).then(h => {
  h.forEach(m => addMsg(m.assistant || m.user, m.assistant ? 'agent' : 'user', m.tool));
});
</script>
</body>
</html>"""


# ── CLI Modu ────────────────────────────────────────────────

def cli_mode():
    """Terminal içinde etkileşimli sohbet."""
    print(f"\n  Dorina Agent — Termux  (model: {OLLAMA_MODEL})")
    print(f"  Yaz 'exit' çıkmak için\n")

    history = []
    while True:
        try:
            q = input("  \033[1;35mSen\033[0m > ").strip()
        except (EOFError, KeyboardInterrupt):
            print()
            break
        if not q:
            continue
        if q.lower() in ("exit", "quit", "çık"):
            break
        if q.lower() in ("clear", "temizle"):
            history = []
            print("  \033[33mGeçmiş temizlendi.\033[0m")
            continue

        print(f"  \033[1;34mDorina\033[0m > ", end="", flush=True)
        answer, tool_result = process_query(q, history)
        history.append({"user": q, "assistant": answer, "tool": tool_result})
        save_chat(history)
        print(f"{answer}")
        if tool_result:
            print(f"  \033[90m[tool: {tool_result.get('toolName', '?')}]\033[0m")
        print()


# ── Ana ─────────────────────────────────────────────────────

def main():
    if not check_ollama():
        print("⚠️  Ollama çalışmıyor! Önce 'ollama serve' çalıştır.")
        print(f"   Model: {OLLAMA_MODEL}")
        sys.exit(1)

    print(f"✅ Ollama bağlantısı tam — {OLLAMA_MODEL}")

    # Arg kontrol
    if len(sys.argv) > 1 and sys.argv[1] in ("--cli", "-c"):
        cli_mode()
        return

    # HTTP API + CLI
    server = HTTPServer((HOST, PORT), AgentHandler)
    print(f"\n  🌐 Dorina Agent → http://{HOST}:{PORT}")
    print(f"  💬 CLI için: python3 {sys.argv[0]} --cli\n")

    # CLI thread'inde de dinle
    def serve():
        server.serve_forever()
    t = threading.Thread(target=serve, daemon=True)
    t.start()

    try:
        cli_mode()
    except KeyboardInterrupt:
        print("\nGörüşürüz!")
    finally:
        server.shutdown()


if __name__ == "__main__":
    main()
