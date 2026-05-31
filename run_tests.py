#!/usr/bin/env bash
"""
DIDAuth Test Suite Runner
Entry point for running the complete test suite
"""
import os
import sys
import argparse
import subprocess
from pathlib import Path


def run_tests(test_type="all", verbose=False, coverage=False, concurrent=False):
    """Run the test suite with specified options."""
    cmd = [sys.executable, "-m", "pytest"]

    if test_type == "unit":
        cmd.extend(["-m", "unit"])
    elif test_type == "integration":
        cmd.extend(["-m", "integration"])
    elif test_type == "concurrent":
        cmd.extend(["-m", "concurrent"])
    elif test_type == "zkp":
        cmd.extend(["-m", "zkp"])
    elif test_type == "hdwallet":
        cmd.extend(["-m", "hdwallet"])
    elif test_type == "indexer":
        cmd.extend(["-m", "indexer"])
    elif test_type == "normal":
        cmd.extend(["-m", "normal"])
    elif test_type == "exception":
        cmd.extend(["-m", "exception"])
    elif test_type == "resource":
        cmd.extend(["-m", "resource"])

    if verbose:
        cmd.append("-v")

    if coverage:
        cmd.extend([
            "--cov=didauth",
            "--cov-report=html",
            "--cov-report=term",
            "--cov-fail-under=70"
        ])

    if concurrent:
        cmd.extend(["-n", "auto"])

    cmd.append("tests/")

    print(f"Running: {' '.join(cmd)}")
    result = subprocess.run(cmd, cwd=Path(__file__).parent.parent)

    return result.returncode


def main():
    parser = argparse.ArgumentParser(description="DIDAuth Test Suite Runner")
    parser.add_argument(
        "test_type",
        nargs="?",
        default="all",
        choices=["all", "unit", "integration", "concurrent", "zkp", "hdwallet", "indexer", "normal", "exception", "resource"],
        help="Type of tests to run (default: all)"
    )
    parser.add_argument("-v", "--verbose", action="store_true", help="Verbose output")
    parser.add_argument("-c", "--coverage", action="store_true", help="Generate coverage report")
    parser.add_argument("--concurrent", action="store_true", help="Run tests concurrently")

    args = parser.parse_args()

    exit_code = run_tests(
        test_type=args.test_type,
        verbose=args.verbose,
        coverage=args.coverage,
        concurrent=args.concurrent
    )

    sys.exit(exit_code)


if __name__ == "__main__":
    main()
