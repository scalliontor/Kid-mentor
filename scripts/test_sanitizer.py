# -*- coding: utf-8 -*-
"""Test sanitizer on the server.

Usage:
    python scripts/test_sanitizer.py                    # run default test suite
    python scripts/test_sanitizer.py "text to test"     # test a specific string
    python scripts/test_sanitizer.py --upload           # upload + run test_cao_bang.py
"""
import sys
import os

sys.path.insert(0, os.path.dirname(__file__))
from server_lib import get_ssh, exec_cmd, upload_file, PROJECT_PATH

DEFAULT_TESTS = [
    # Vietnamese natural text
    ("Đừng đau đầu nữa, nghỉ ngơi một chút nhé!", None),
    ("Tuy nhiên, tớ là Lisa — một trợ lý học tập AI", None),
    ("luận/Lời khuyên", "luận hoặc Lời khuyên"),
    # English compound adjectives
    ('"cutting-edge" tiên tiến', None),
    ("PTIT is well-known for its high-quality training", None),
    # Math
    ("x - 3 lớn hơn 0", "x trừ 3 lớn hơn không"),
    ("tháng 8 - tháng 10", "tháng 8 đến tháng 10"),
    ("v = s/t", "v bằng s chia t"),
    ("F = m * a", "F bằng m nhân a"),
    # Chemistry
    ("H2O", "H hai O"),
    ("CO2", "C O hai"),
    ("CaCO3", "Ca C O ba"),
    ("2H2 + O2 -> 2H2O", "2 H2  cộng  O2  cho ra  2 H hai O"),
    # Units
    ("km/h", "ki-lô-mét trên giờ"),
    ("m/s", "mét trên giây"),
]


def run_tests(ssh):
    """Run the default test suite on the server."""
    import json
    import tempfile

    test_json = json.dumps(DEFAULT_TESTS, ensure_ascii=False)
    # Write test script to temp file, upload, and run
    script = f"""import json
from workers.llm_worker import _sanitize_for_tts

tests = json.loads({test_json!r})
passed = 0
failed = 0

for inp, expected in tests:
    result = _sanitize_for_tts(inp)
    if expected is None:
        if result or not any(c.isalnum() for c in inp):
            status = "ok"
            passed += 1
        else:
            status = "EMPTY!"
            failed += 1
    else:
        if result == expected:
            status = "ok"
            passed += 1
        else:
            status = "FAIL"
            failed += 1

    marker = "ok" if status == "ok" else status
    print(f"[{{marker}}] {{inp[:50]}}")
    if status != "ok":
        print(f"  expected: {{expected}}")
        print(f"  got:      {{result}}")
    elif expected is None and result != inp:
        print(f"  -> {{result[:60]}}")

print(f"\\n{{passed}} passed, {{failed}} failed")
"""
    with tempfile.NamedTemporaryFile(mode="w", suffix=".py", delete=False, encoding="utf-8") as f:
        f.write(script)
        tmp_path = f.name

    try:
        upload_file(ssh, tmp_path, f"{PROJECT_PATH}/test_sanitizer_runner.py")
        out, err = exec_cmd(ssh, "python test_sanitizer_runner.py")
        print(out)
        if err and "Error" in err:
            print("STDERR:", err)
    finally:
        os.unlink(tmp_path)


def test_string(ssh, text):
    """Test a single string through the sanitizer."""
    import json

    escaped = json.dumps(text, ensure_ascii=False)
    cmd = f'''python3 -c "
from workers.llm_worker import _sanitize_for_tts
text = {escaped}
result = _sanitize_for_tts(text)
print(f'INPUT:  {{repr(text)}}')
print(f'OUTPUT: {{repr(result)}}')
"
'''
    out, err = exec_cmd(ssh, cmd)
    print(out)
    if err:
        print(err)


def main():
    if len(sys.argv) < 2:
        ssh = get_ssh()
        print("Running default sanitizer test suite...\n")
        run_tests(ssh)
        ssh.close()
        return

    if sys.argv[1] == "--upload":
        ssh = get_ssh()
        local_test = os.path.join(
            os.path.dirname(os.path.dirname(__file__)),
            "test_cao_bang.py",
        )
        if os.path.exists(local_test):
            upload_file(ssh, local_test, f"{PROJECT_PATH}/test_cao_bang.py")
            out, err = exec_cmd(ssh, "python test_cao_bang.py")
            print(out)
            if err:
                print(err)
        else:
            print(f"Test file not found: {local_test}")
        ssh.close()
        return

    # Test specific string
    text = " ".join(sys.argv[1:])
    ssh = get_ssh()
    test_string(ssh, text)
    ssh.close()


if __name__ == "__main__":
    main()
