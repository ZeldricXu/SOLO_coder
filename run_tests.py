#!/usr/bin/env python3
import asyncio
import sys
import os

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

import pytest


def main():
    print("=" * 60)
    print("Starting Test Suite for Ticket Assignment System")
    print("=" * 60)

    args = [
        "-v",
        "--tb=short",
        "--asyncio-mode=auto",
        "tests/",
    ]

    exit_code = pytest.main(args)

    print("\n" + "=" * 60)
    if exit_code == 0:
        print("✓ All tests passed!")
    else:
        print(f"✗ Tests failed with exit code: {exit_code}")
    print("=" * 60)

    return exit_code


if __name__ == "__main__":
    sys.exit(main())
