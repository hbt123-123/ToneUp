"""20 并发混合压测 + RSS 采样（需求文档 §10.4 验收方式）。

用法：
    cd backend
    uv run python scripts/load_test_rss.py [--duration 60] [--workers 20]

断言：空载 RSS <150MB；压测峰值 RSS <200MB。达标退出 0。
"""
from __future__ import annotations

import argparse
import random
import subprocess
import sys
import threading
import time
from pathlib import Path

import httpx
import psutil

BACKEND = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(BACKEND))

IDLE_LIMIT_MB = 150
PEAK_LIMIT_MB = 200


def _sample_rss(proc: psutil.Process, stop: threading.Event, samples: list) -> None:
    while not stop.is_set():
        try:
            samples.append(proc.memory_info().rss / (1024 * 1024))
        except psutil.Error:
            break
        time.sleep(0.25)


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--duration", type=int, default=60)
    parser.add_argument("--workers", type=int, default=20)
    args = parser.parse_args()

    server = subprocess.Popen(
        [sys.executable, "-m", "uvicorn", "app.main:app", "--port", "8123"],
        cwd=str(BACKEND),
        stdout=subprocess.DEVNULL,
        stderr=subprocess.STDOUT,
    )
    proc = psutil.Process(server.pid)
    base_url = "http://127.0.0.1:8123"

    try:
        for _ in range(60):
            try:
                if httpx.get(f"{base_url}/api/health", timeout=2).status_code == 200:
                    break
            except httpx.HTTPError:
                time.sleep(0.5)
        else:
            print("server failed to start")
            return 1

        idle_samples: list[float] = []
        stop = threading.Event()
        sampler = threading.Thread(target=_sample_rss, args=(proc, stop, idle_samples))
        sampler.start()
        time.sleep(3)
        stop.set()
        sampler.join()
        idle_mb = max(idle_samples) if idle_samples else 0.0

        usernames = [f"loaduser_{i}" for i in range(args.workers)]
        tokens: list[str] = []
        with httpx.Client(base_url=base_url, timeout=10) as c:
            for name in usernames:
                r = c.post("/api/auth/register", json={"username": name, "password": "password123"})
                if r.status_code == 200:
                    r = c.post("/api/auth/login", json={"username": name, "password": "password123"})
                if r.status_code == 200 and r.json().get("data", {}).get("access_token"):
                    tokens.append(r.json()["data"]["access_token"])

        catalog = httpx.get(f"{base_url}/api/catalog", timeout=10).json()
        banks = [b["id"] for b in catalog.get("data", {}).get("banks", []) if b.get("enabled")]
        if not banks:
            print("no enabled banks; run validate_banks first")
            return 1

        peak_samples: list[float] = []
        stop.set()
        stop = threading.Event()
        sampler = threading.Thread(target=_sample_rss, args=(proc, stop, peak_samples))
        sampler.start()
        deadline = time.time() + args.duration
        errors = {"count": 0}

        def worker_loop(token: str | None, idx: int) -> None:
            headers = {"Authorization": f"Bearer {token}"} if token else {}
            with httpx.Client(base_url=base_url, timeout=10, headers=headers) as c:
                while time.time() < deadline:
                    try:
                        roll = random.random()
                        bank = random.choice(banks)
                        if roll < 0.4:
                            c.get("/api/catalog")
                        elif roll < 0.7:
                            c.get(f"/api/question-banks/{bank}/questions?page_size=20")
                        elif token:
                            c.get("/api/reviews/today")
                            c.get("/api/stats/overview")
                        else:
                            c.get(f"/api/question-banks/{bank}")
                    except httpx.HTTPError:
                        errors["count"] += 1

        threads = [
            threading.Thread(target=worker_loop, args=(tokens[i % len(tokens)] if tokens else None, i))
            for i in range(args.workers)
        ]
        for t in threads:
            t.start()
        for t in threads:
            t.join()
        stop.set()
        sampler.join()

        peak_mb = max(peak_samples) if peak_samples else 0.0
        print(f"idle_rss_mb={idle_mb:.1f} (limit {IDLE_LIMIT_MB})")
        print(f"peak_rss_mb={peak_mb:.1f} (limit {PEAK_LIMIT_MB})")
        print(f"http_errors={errors['count']}")

        ok = idle_mb < IDLE_LIMIT_MB and peak_mb < PEAK_LIMIT_MB
        print("RESULT:", "PASS" if ok else "FAIL")
        return 0 if ok else 1
    finally:
        server.terminate()
        try:
            server.wait(timeout=10)
        except subprocess.TimeoutExpired:
            server.kill()


if __name__ == "__main__":
    sys.exit(main())
