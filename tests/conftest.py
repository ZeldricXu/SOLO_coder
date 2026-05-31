import os
os.environ['OPENBLAS_NUM_THREADS'] = '1'
os.environ['OMP_NUM_THREADS'] = '1'
os.environ['OPENBLAS_DEFAULT_NUM_THREADS'] = '1'
os.environ['GOTO_NUM_THREADS'] = '1'

import pytest
import sys

sys.path.insert(0, os.path.join(os.path.dirname(__file__), ".."))

pytest_plugins = [
    "tests.fixtures",
]
