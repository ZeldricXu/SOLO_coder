#!/usr/bin/env python3
import subprocess
import sys

result = subprocess.run(
    [sys.executable, "-m", "pytest", "tests/test_data_access.py", "-v"],
    cwd="/Users/huangzitong/Desktop/SoloCoder/session117",
    capture_output=True,
    text=True
)

print("STDOUT:", result.stdout)
print("STDERR:", result.stderr)
print("Return code:", result.returncode)