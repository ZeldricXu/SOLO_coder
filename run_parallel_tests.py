import sys
import os
sys.path.insert(0, os.path.dirname(__file__))

import pytest

if __name__ == '__main__':
    result = pytest.main(['tests/test_parallel_optimization.py', '-v', '--tb=short', '-x'])
    print(f"\nTest result exit code: {result}")
