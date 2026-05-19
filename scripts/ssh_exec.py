# -*- coding: utf-8 -*-
"""Execute commands on the server.

Usage:
    python scripts/ssh_exec.py "ls -la"
    python scripts/ssh_exec.py "screen -ls"
    python scripts/ssh_exec.py "docker ps"
    python scripts/ssh_exec.py --raw "free -h"  # no project dir / venv prefix
"""
import sys
import os

sys.path.insert(0, os.path.dirname(__file__))
from server_lib import get_ssh, exec_cmd


def main():
    if len(sys.argv) < 2:
        print(__doc__)
        sys.exit(1)

    raw = sys.argv[1] == "--raw"
    if raw:
        cmd = " ".join(sys.argv[2:])
    else:
        cmd = " ".join(sys.argv[1:])

    ssh = get_ssh()
    out, err = exec_cmd(ssh, cmd, cd=not raw, venv=not raw)
    if out:
        print(out)
    if err:
        print(err, file=sys.stderr)
    ssh.close()


if __name__ == "__main__":
    main()
