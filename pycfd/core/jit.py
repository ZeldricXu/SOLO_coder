import numpy as np
from functools import wraps

try:
    from numba import jit as _jit
    from numba import njit as _njit
    from numba import prange as _prange
    from numba import float64, int32
    NUMBA_AVAILABLE = True
except ImportError:
    NUMBA_AVAILABLE = False
    float64 = None
    int32 = None
    def _jit(*args, **kwargs):
        if len(args) == 1 and callable(args[0]):
            return args[0]
        def decorator(func):
            @wraps(func)
            def wrapper(*f_args, **f_kwargs):
                return func(*f_args, **f_kwargs)
            return wrapper
        return decorator
    _njit = _jit
    _prange = range

def jit(*args, **kwargs):
    if not NUMBA_AVAILABLE:
        if len(args) == 1 and callable(args[0]):
            return args[0]
        def decorator(func):
            @wraps(func)
            def wrapper(*f_args, **f_kwargs):
                return func(*f_args, **f_kwargs)
            return wrapper
        return decorator
    kwargs.setdefault('nopython', True)
    kwargs.setdefault('fastmath', True)
    return _jit(*args, **kwargs)

def njit(*args, **kwargs):
    if not NUMBA_AVAILABLE:
        if len(args) == 1 and callable(args[0]):
            return args[0]
        def decorator(func):
            @wraps(func)
            def wrapper(*f_args, **f_kwargs):
                return func(*f_args, **f_kwargs)
            return wrapper
        return decorator
    kwargs.setdefault('fastmath', True)
    kwargs.setdefault('cache', True)
    return _njit(*args, **kwargs)

prange = _prange

@njit
def axpy(a, x, y):
    n = len(x)
    for i in prange(n):
        y[i] = a * x[i] + y[i]
    return y

@njit
def dot_product(x, y):
    result = 0.0
    n = len(x)
    for i in prange(n):
        result += x[i] * y[i]
    return result

@njit
def vector_norm(x):
    return np.sqrt(dot_product(x, x))

@njit
def matrix_vector_mult(A, x, y):
    n = A.shape[0]
    for i in prange(n):
        s = 0.0
        for j in range(A.shape[1]):
            s += A[i, j] * x[j]
        y[i] = s
    return y
