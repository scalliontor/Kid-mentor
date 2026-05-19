# -*- coding: utf-8 -*-
"""Shared server connection utilities for PTalk Kids scripts."""
import paramiko

SERVER_IP = "171.226.10.121"
USERNAME = "namnx"
PASSWORD = "PtitCie@2026"
PROJECT_PATH = "/home/namnx/Ptalk_project/CloudPTalk"
VENV_ACTIVATE = "source venv/bin/activate"


def get_ssh():
    """Create and return a connected SSH client."""
    ssh = paramiko.SSHClient()
    ssh.set_missing_host_key_policy(paramiko.AutoAddPolicy())
    ssh.connect(SERVER_IP, username=USERNAME, password=PASSWORD)
    return ssh


def exec_cmd(ssh, cmd, cd=True, venv=True):
    """Execute a command on the server. Returns (stdout, stderr) strings."""
    prefix = ""
    if cd:
        prefix += f"cd {PROJECT_PATH} && "
    if venv:
        prefix += f"{VENV_ACTIVATE} && "
    full_cmd = prefix + cmd
    stdin, stdout, stderr = ssh.exec_command(full_cmd)
    return stdout.read().decode("utf-8"), stderr.read().decode("utf-8")


def upload_file(ssh, local_path, remote_path):
    """Upload a file via SFTP."""
    sftp = ssh.open_sftp()
    sftp.put(local_path, remote_path)
    sftp.close()


def download_file(ssh, remote_path, local_path):
    """Download a file via SFTP."""
    sftp = ssh.open_sftp()
    sftp.get(remote_path, local_path)
    sftp.close()
