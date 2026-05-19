# -*- coding: utf-8 -*-
"""Check server logs.

Usage:
    python scripts/logs.py llm         # last 50 lines of LLM log
    python scripts/logs.py tts         # last 50 lines of TTS log
    python scripts/logs.py stt         # last 50 lines of STT log
    python scripts/logs.py kids        # last 50 lines of kids server log
    python scripts/logs.py all         # all logs
    python scripts/logs.py llm -n 100  # last 100 lines
    python scripts/logs.py llm -f      # follow (tail -f)
"""
import sys
import os

sys.path.insert(0, os.path.dirname(__file__))
from server_lib import get_ssh, exec_cmd

LOGS = {
    "llm": "logs/llm.log",
    "tts": "logs/tts.log",
    "stt": "logs/stt.log",
    "kids": "logs/kids.log",
}


def show_log(ssh, name, lines=50, follow=False):
    path = LOGS.get(name)
    if not path:
        print(f"Unknown log: {name}")
        return

    print(f"=== {name.upper()} LOG ({path}) ===")
    if follow:
        print("(Press Ctrl+C to stop)\n")
        # For follow mode, use a persistent command
        transport = ssh.get_transport()
        channel = transport.open_session()
        channel.exec_command(f"cd {exec_cmd.__defaults__[0] if False else '/home/namnx/Ptalk_project/CloudPTalk'} && tail -f {path}")
        try:
            while True:
                line = channel.recv(4096).decode("utf-8")
                if line:
                    print(line, end="")
                else:
                    break
        except KeyboardInterrupt:
            print("\nStopped.")
        channel.close()
    else:
        stdin, stdout, stderr = ssh.exec_command(f"tail -{lines} /home/namnx/Ptalk_project/CloudPTalk/{path}")
        print(stdout.read().decode())


def main():
    if len(sys.argv) < 2:
        print(__doc__)
        sys.exit(1)

    target = sys.argv[1].lower()
    lines = 50
    follow = False

    # Parse args
    for i, arg in enumerate(sys.argv[2:], 2):
        if arg == "-n" and i + 1 < len(sys.argv):
            lines = int(sys.argv[i + 1])
        elif arg == "-f":
            follow = True

    ssh = get_ssh()

    if target == "all":
        for name in LOGS:
            show_log(ssh, name, lines)
            print()
    elif target in LOGS:
        show_log(ssh, target, lines, follow)
    else:
        print(f"Unknown log: {target}")
        print(f"Available: {', '.join(LOGS.keys())}, all")

    ssh.close()


if __name__ == "__main__":
    main()
