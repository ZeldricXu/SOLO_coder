import numpy as np
from ..core.jit import njit

@njit
def euler_implicit(phi_old, phi_new, dt, source, volume):
    return (phi_new - phi_old) * volume / dt + source

@njit
def crank_nicolson(phi_old, phi_new, source_old, source_new, dt, volume):
    return (phi_new - phi_old) * volume / dt + 0.5 * (source_old + source_new)

@njit
def adams_bashforth(phi_old, phi_new, source_old, source_prev, dt, volume):
    return (phi_new - phi_old) * volume / dt + 1.5 * source_old - 0.5 * source_prev

@njit
def bdf2(phi_prev, phi_old, phi_new, source_new, dt, volume):
    return (3.0 * phi_new - 4.0 * phi_old + phi_prev) * volume / (2.0 * dt) + source_new

def compute_cfl(u, dx, dt):
    return np.max(np.abs(u) * dt / dx)

def adaptive_time_step(u_max, dx, cfl_target=0.5):
    return cfl_target * dx / (u_max + 1e-15)

def local_time_step(u, dx, cfl=0.5):
    dt_local = np.zeros_like(u)
    for i in range(len(u)):
        dt_local[i] = cfl * dx[i] / (abs(u[i]) + 1e-15)
    return dt_local
