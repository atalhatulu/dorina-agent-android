#!/usr/bin/env python3
"""Dorina Agent — Aşama 1: Sadece sohbet (Gemma 2B ile)"""

import json, urllib.request, sys

OLLAMA_HOST = "http://127.0.0.1:11434"
MODEL = "gemma:2b"

def sor(prompt: str) -> str:
    """Gemma 2B'ye soru sor, cevabı döndür."""
    data = json.dumps({
        "model": MODEL,
        "prompt": prompt,
        "stream": False,
        "options": {"temperature": 0.7, "num_predict": 256}
    }).encode()

    req = urllib.request.Request(
        f"{OLLAMA_HOST}/api/generate",
        data=data,
        headers={"Content-Type": "application/json"}
    )
    with urllib.request.urlopen(req, timeout=120) as resp:
        return json.loads(resp.read())["response"]

def main():
    import sys
    if len(sys.argv) > 1:
        # SSH üzerinden test: python3 dorina.py "soru"
        q = " ".join(sys.argv[1:])
        print(f"\n  Dorina (Aşama 1) — {MODEL}")
        print(f"  Sen > {q}")
        print("  Dorina > ", end="", flush=True)
        try:
            cevap = sor(q)
            print(cevap)
        except Exception as e:
            print(f"Hata: {e}")
        return

    print(f"\n  Dorina (Aşama 1) — {MODEL}")
    print("  Çıkmak için: exit\n")

    while True:
        try:
            q = input("  Sen > ").strip()
            if not q: continue
            if q in ("exit", "çık"): break
            print("  Dorina > ", end="", flush=True)
            cevap = sor(q)
            print(cevap)
            print()
        except KeyboardInterrupt:
            break
        except EOFError:
            break
        except Exception as e:
            print(f"\n  Hata: {e}")

if __name__ == "__main__":
    main()
