"""20 并发混合压测 + RSS 采样（需求文档 §10.4 验收方式）。

用法：
    cd backend
    uv run python scripts/load_test_rss.py [--duration 60] [--workers 20]

断言：空载 RSS <150MB；压测峰值 RSS <200MB；HTTP 错误率 ≤1%。达标退出 0。

环境变量：JWT_SECRET 必须与后端一致（登录失败时用于本地签发压测 token）。
"""
from __future__ import annotations

import argparse
import os
import random
import subprocess
import sys
import threading
import time
from datetime import datetime, timedelta
from pathlib import Path

import httpx
import psutil

BACKEND = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(BACKEND))

IDLE_LIMIT_MB = 150
PEAK_LIMIT_MB = 200
ERROR_RATE_LIMIT = 0.01


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
        # 直接预置用户进库：绕过 register 5次/小时/IP 限流（压测自身会触发）
        import sqlite3
        from datetime import datetime, timezone

        import jwt as pyjwt
        from argon2 import PasswordHasher

        db_path = BACKEND / "data" / "user_data.db"
        user_ids: dict[str, int] = {}
        if db_path.exists():
            conn = sqlite3.connect(str(db_path))
            ph = PasswordHasher(memory_cost=19456, time_cost=2, parallelism=1)
            now_iso = datetime.now(timezone.utc).isoformat()
            for name in usernames:
                try:
                    conn.execute(
                        "INSERT INTO users (username, password_hash, role, created_at) VALUES (?, ?, 'user', ?)",
                        (name, ph.hash("password123"), now_iso),
                    )
                except sqlite3.IntegrityError:
                    pass
            conn.commit()
            # 回查真实自增 id：本地签发 token 的 sub 必须与库中 id 对齐
            for name in usernames:
                row = conn.execute(
                    "SELECT id FROM users WHERE username = ?", (name,)
                ).fetchone()
                if row:
                    user_ids[name] = int(row[0])
            conn.close()

        secret = os.environ.get("JWT_SECRET")
        if not secret or len(secret) < 32:
            print(
                "错误: 环境变量 JWT_SECRET 未设置或长度不足 32 字符，"
                "无法在登录失败时本地签发压测 token。"
                "请设置与后端一致的 JWT_SECRET 后重试。"
            )
            return 1

        with httpx.Client(base_url=base_url, timeout=10) as c:
            for name in usernames:
                r = c.post("/api/auth/login", json={"username": name, "password": "password123"})
                if r.status_code == 200 and r.json().get("data", {}).get("access_token"):
                    tokens.append(r.json()["data"]["access_token"])
                elif r.status_code == 401:
                    uid = user_ids.get(name)
                    if uid is None:
                        continue
                    # 兜底：用真实 JWT_SECRET 本地签发与后端同构的 token（仅压测用途）
                    payload = {
                        "sub": str(uid), "role": "user",
                        "exp": datetime.now(timezone.utc) + timedelta(hours=12),
                        "iat": datetime.now(timezone.utc),
                    }
                    tokens.append(pyjwt.encode(payload, secret, algorithm="HS256"))

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
        total_requests = {"count": 0}

        def _check(resp: httpx.Response) -> None:
            total_requests["count"] += 1
            if resp.status_code != 200:
                errors["count"] += 1

        def worker_loop(token: str | None, idx: int) -> None:
            headers = {"Authorization": f"Bearer {token}"} if token else {}
            with httpx.Client(base_url=base_url, timeout=10, headers=headers) as c:
                while time.time() < deadline:
                    try:
                        roll = random.random()
                        bank = random.choice(banks)
                        if roll < 0.4:
                            _check(c.get("/api/catalog"))
                        elif roll < 0.7:
                            _check(c.get(f"/api/question-banks/{bank}/questions?page_size=20"))
                        elif token:
                            _check(c.get("/api/reviews/today"))
                            _check(c.get("/api/stats/overview"))
                        else:
                            _check(c.get(f"/api/question-banks/{bank}"))
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
        total = total_requests["count"]
        error_rate = errors["count"] / total if total else 1.0
        print(f"idle_rss_mb={idle_mb:.1f} (limit {IDLE_LIMIT_MB})")
        print(f"peak_rss_mb={peak_mb:.1f} (limit {PEAK_LIMIT_MB})")
        print(f"http_requests={total} http_errors={errors['count']} "
              f"error_rate={error_rate:.2%} (limit {ERROR_RATE_LIMIT:.0%})")

        ok = (
            idle_mb < IDLE_LIMIT_MB
            and peak_mb < PEAK_LIMIT_MB
            and error_rate <= ERROR_RATE_LIMIT
        )
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
