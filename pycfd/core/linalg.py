import numpy as np
from scipy.sparse.linalg import spsolve
from .jit import njit, prange

def solve_linear_system(A, b, method='direct'):
    if method == 'direct':
        if hasattr(A, 'to_csr'):
            return spsolve(A.to_csr(), b)
        else:
            return np.linalg.solve(A, b)
    elif method == 'gauss_seidel':
        return gauss_seidel(A, b)
    elif method == 'sor':
        return sor(A, b)
    else:
        raise ValueError(f"Unknown method: {method}")

@njit
def gauss_seidel(A, b, x0=None, max_iter=10000, tol=1e-8):
    n = len(b)
    if x0 is None:
        x = np.zeros(n, dtype=np.float64)
    else:
        x = x0.copy()
    for it in range(max_iter):
        x_old = x.copy()
        for i in range(n):
            s1 = 0.0
            for j in range(i):
                s1 += A[i, j] * x[j]
            s2 = 0.0
            for j in range(i+1, n):
                s2 += A[i, j] * x_old[j]
            x[i] = (b[i] - s1 - s2) / A[i, i]
        residual = np.sqrt(np.sum((x - x_old)**2))
        if residual < tol:
            break
    return x

@njit
def sor(A, b, omega=1.5, x0=None, max_iter=10000, tol=1e-8):
    n = len(b)
    if x0 is None:
        x = np.zeros(n, dtype=np.float64)
    else:
        x = x0.copy()
    for it in range(max_iter):
        x_old = x.copy()
        for i in range(n):
            s1 = 0.0
            for j in range(i):
                s1 += A[i, j] * x[j]
            s2 = 0.0
            for j in range(i+1, n):
                s2 += A[i, j] * x_old[j]
            x[i] = (1 - omega) * x_old[i] + omega * (b[i] - s1 - s2) / A[i, i]
        residual = np.sqrt(np.sum((x - x_old)**2))
        if residual < tol:
            break
    return x

@njit
def conjugate_gradient(A, b, x0=None, max_iter=1000, tol=1e-8):
    n = len(b)
    if x0 is None:
        x = np.zeros(n, dtype=np.float64)
    else:
        x = x0.copy()
    r = b - A @ x
    p = r.copy()
    rs_old = np.sum(r * r)
    for it in range(max_iter):
        Ap = A @ p
        alpha = rs_old / np.sum(p * Ap)
        x = x + alpha * p
        r = r - alpha * Ap
        rs_new = np.sum(r * r)
        if np.sqrt(rs_new) < tol:
            break
        beta = rs_new / rs_old
        p = r + beta * p
        rs_old = rs_new
    return x

@njit
def tdma_solver(a, b, c, d):
    n = len(d)
    c_prime = np.zeros(n, dtype=np.float64)
    d_prime = np.zeros(n, dtype=np.float64)
    x = np.zeros(n, dtype=np.float64)
    c_prime[0] = c[0] / b[0]
    d_prime[0] = d[0] / b[0]
    for i in range(1, n):
        m = b[i] - a[i] * c_prime[i-1]
        c_prime[i] = c[i] / m
        d_prime[i] = (d[i] - a[i] * d_prime[i-1]) / m
    x[-1] = d_prime[-1]
    for i in range(n-2, -1, -1):
        x[i] = d_prime[i] - c_prime[i] * x[i+1]
    return x

def compute_residual(A, x, b):
    return np.linalg.norm(A @ x - b) / (np.linalg.norm(b) + 1e-12)
