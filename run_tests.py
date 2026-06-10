#!/usr/bin/env python3
import sys
import os
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

import pytest

if __name__ == '__main__':
    # Run parallel optimization tests
    exit_code = pytest.main([
        'tests/test_parallel_optimization.py',
        '-v',
        '--tb=short',
        '-x',
        '--disable-warnings'
    ])
    print(f"\n=== Test exit code: {exit_code} ===")
    sys.exit(exit_code)
