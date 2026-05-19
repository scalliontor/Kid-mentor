# -*- coding: utf-8 -*-
"""Deploy worker code to server and restart workers.

Usage:
    python scripts/deploy.py llm          # deploy + restart LLM workers
    python scripts/deploy.py llm --no-restart  # deploy only
    python scripts/deploy.py tts          # deploy + restart TTS worker
    python scripts/deploy.py stt          # deploy + restart STT worker
    python scripts/deploy.py kids         # deploy + restart kids server
    python scripts/deploy.py all          # deploy + restart everything
"""
import sys
import time
import os

sys.path.insert(0, os.path.dirname(__file__))
from server_lib import get_ssh, exec_cmd, upload_file, PROJECT_PATH

# Local → Remote file mappings
FILES = {
    "llm": {
        "local": r"E:\OneDrive - ptit.edu.vn\Documents\junior\web\projects\Kid-mentor\llm_worker_v2.py",
        "remote": f"{PROJECT_PATH}/workers/llm_worker.py",
        "screens": ["worker_llm", "worker_llm_2", "worker_llm_3"],
        "process": "workers/llm_worker.py",
        "log": "logs/llm.log",
    },
    "chunker": {
        "local": r"E:\OneDrive - ptit.edu.vn\Documents\junior\web\projects\Kid-mentor\smart_chunker_server.py",
        "remote": f"{PROJECT_PATH}/workers/smart_chunker.py",
        "screens": ["worker_llm", "worker_llm_2", "worker_llm_3"],
        "process": "workers/llm_worker.py",
        "log": "logs/llm.log",
    },
    "tts": {
        "local": None,  # no local copy
        "remote": f"{PROJECT_PATH}/workers/tts_worker.py",
        "screens": ["worker_tts"],
        "process": "workers/tts_worker.py",
        "log": "logs/tts.log",
    },
    "stt": {
        "local": None,
        "remote": f"{PROJECT_PATH}/workers/stt_worker.py",
        "screens": ["worker_stt"],
        "process": "workers/stt_worker.py",
        "log": "logs/stt.log",
    },
    "kids": {
        "local": None,
        "remote": f"{PROJECT_PATH}/kids/main.py",
        "screens": ["ptalk_kids_new"],
        "process": "kids/main:app",
        "log": "logs/kids.log",
    },
}


def kill_workers(ssh, process_pattern):
    """Kill worker processes by pattern."""
    exec_cmd(ssh, f'pkill -f "{process_pattern}" || true', cd=False, venv=False)
    time.sleep(2)


def start_screen(ssh, name, worker_cmd, log_file=None):
    """Start a screen session with the worker."""
    if log_file:
        redirect = f" >> {log_file} 2>&1"
    else:
        redirect = ""
    cmd = (
        f'screen -dmS {name} bash -c "'
        f"cd {PROJECT_PATH} && source venv/bin/activate && "
        f'export PYTHONUNBUFFERED=1 && python3 -u {worker_cmd}{redirect}"'
    )
    ssh.exec_command(cmd)


def deploy(target, restart=True):
    ssh = get_ssh()
    info = FILES[target]

    # Upload file if local copy exists
    if info["local"] and os.path.exists(info["local"]):
        upload_file(ssh, info["local"], info["remote"])
        print(f"  Uploaded {os.path.basename(info['local'])} -> {info['remote']}")
    else:
        print(f"  No local file for {target}, skipping upload")

    if not restart:
        print("  Skipping restart (--no-restart)")
        ssh.close()
        return

    # Kill existing workers
    kill_workers(ssh, info["process"])

    # Restart screen sessions
    for screen_name in info["screens"]:
        start_screen(ssh, screen_name, info["process"], info.get("log"))
        print(f"  Started {screen_name}")
        time.sleep(0.5)

    time.sleep(2)

    # Verify
    stdin, stdout, stderr = ssh.exec_command(
        f'ps aux | grep "{info["process"]}" | grep -v grep | wc -l'
    )
    count = stdout.read().decode().strip()
    print(f"  {count} process(es) running for {target}")

    ssh.close()


def main():
    if len(sys.argv) < 2:
        print(__doc__)
        sys.exit(1)

    target = sys.argv[1].lower()
    no_restart = "--no-restart" in sys.argv

    if target == "all":
        targets = ["chunker", "llm", "tts", "stt", "kids"]
    elif target in FILES:
        targets = [target]
    else:
        print(f"Unknown target: {target}")
        print(f"Available: {', '.join(FILES.keys())}, all")
        sys.exit(1)

    for t in targets:
        print(f"\n[{t.upper()}]")
        deploy(t, restart=not no_restart)

    print("\nDone.")


if __name__ == "__main__":
    main()
