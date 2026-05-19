# -*- coding: utf-8 -*-
"""Manage server workers.

Usage:
    python scripts/workers.py status           # show all workers
    python scripts/workers.py restart llm       # restart LLM workers
    python scripts/workers.py restart all       # restart everything
    python scripts/workers.py kill llm          # kill LLM workers
    python scripts/workers.py redis             # check Redis queue sizes
    python scripts/workers.py cleanup           # clean stale resp:* streams
"""
import sys
import time
import os

sys.path.insert(0, os.path.dirname(__file__))
from server_lib import get_ssh, exec_cmd, PROJECT_PATH

WORKERS = {
    "llm": {
        "screens": ["worker_llm", "worker_llm_2", "worker_llm_3"],
        "process": "workers/llm_worker.py",
    },
    "tts": {
        "screens": ["worker_tts"],
        "process": "workers/tts_worker.py",
    },
    "stt": {
        "screens": ["worker_stt"],
        "process": "workers/stt_worker.py",
    },
    "kids": {
        "screens": ["ptalk_kids_new"],
        "process": "kids/main:app",
    },
}


def status(ssh):
    """Show status of all workers."""
    print("=== Screen Sessions ===")
    out, _ = exec_cmd(ssh, "screen -ls", cd=False, venv=False)
    print(out.strip() if out.strip() else "(no sessions)")

    print("\n=== Worker Processes ===")
    out, _ = exec_cmd(ssh, "ps aux | grep -E 'llm_worker|tts_worker|stt_worker|main:app' | grep -v grep", cd=False, venv=False)
    if out.strip():
        for line in out.strip().split("\n"):
            parts = line.split()
            pid = parts[1]
            cpu = parts[2]
            mem = parts[3]
            cmd = " ".join(parts[10:])
            print(f"  PID {pid} | CPU {cpu}% | MEM {mem}% | {cmd[:60]}")
    else:
        print("  (no workers running)")


def kill(ssh, target):
    """Kill workers for a target."""
    info = WORKERS[target]
    for name in info["screens"]:
        ssh.exec_command(f"screen -S {name} -X quit")
        print(f"  Killed {name}")
    # Also kill by process pattern
    exec_cmd(ssh, f'pkill -f "{info["process"]}" || true', cd=False, venv=False)
    time.sleep(1)


def restart(ssh, target):
    """Restart workers for a target."""
    info = WORKERS[target]
    kill(ssh, target)
    time.sleep(1)

    for name in info["screens"]:
        cmd = (
            f'screen -dmS {name} bash -c "'
            f"cd {PROJECT_PATH} && source venv/bin/activate && "
            f'export PYTHONUNBUFFERED=1 && python3 -u {info["process"]}"'
        )
        ssh.exec_command(cmd)
        print(f"  Started {name}")
        time.sleep(0.5)

    time.sleep(2)
    # Verify
    out, _ = exec_cmd(ssh, f'ps aux | grep "{info["process"]}" | grep -v grep | wc -l', cd=False, venv=False)
    count = out.strip()
    print(f"  {count} process(es) running")


def redis_check(ssh):
    """Check Redis queue sizes."""
    print("=== Redis Queue Sizes ===")
    for queue in ["jobs:stt", "jobs:llm", "jobs:tts"]:
        out, _ = exec_cmd(ssh, f"docker exec cloudptalk-redis-1 redis-cli LLEN {queue}", cd=False, venv=False)
        count = out.strip()
        print(f"  {queue}: {count}")

    # Count resp streams
    out, _ = exec_cmd(ssh, 'docker exec cloudptalk-redis-1 redis-cli KEYS "resp:*" | wc -l', cd=False, venv=False)
    count = out.strip()
    print(f"  resp:* streams: {count}")


def cleanup(ssh):
    """Clean stale resp:* streams."""
    print("Cleaning stale resp:* streams...")
    out, _ = exec_cmd(
        ssh,
        'docker exec cloudptalk-redis-1 redis-cli KEYS "resp:*" | xargs -r docker exec cloudptalk-redis-redis-1 redis-cli DEL',
        cd=False, venv=False,
    )
    print(f"  {out.strip()}")


def main():
    if len(sys.argv) < 2:
        print(__doc__)
        sys.exit(1)

    action = sys.argv[1].lower()
    ssh = get_ssh()

    if action == "status":
        status(ssh)
    elif action == "redis":
        redis_check(ssh)
    elif action == "cleanup":
        cleanup(ssh)
    elif action in ("restart", "kill"):
        if len(sys.argv) < 3:
            print(f"Usage: python scripts/workers.py {action} <target>")
            print(f"Targets: {', '.join(WORKERS.keys())}, all")
            sys.exit(1)
        target = sys.argv[2].lower()
        if target == "all":
            targets = list(WORKERS.keys())
        elif target in WORKERS:
            targets = [target]
        else:
            print(f"Unknown target: {target}")
            sys.exit(1)

        for t in targets:
            print(f"\n[{t.upper()}]")
            if action == "restart":
                restart(ssh, t)
            else:
                kill(ssh, t)
    else:
        print(f"Unknown action: {action}")
        print(__doc__)

    ssh.close()


if __name__ == "__main__":
    main()
