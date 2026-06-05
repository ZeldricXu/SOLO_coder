import numpy as np
from ..core.jit import njit, NUMBA_AVAILABLE

@njit
def upwind(mass_flux, phi_up, phi_down):
    if mass_flux > 0:
        return phi_up, 0.0
    else:
        return phi_down, 0.0

def tvd_minmod(r):
    r = np.asarray(r, dtype=np.float64)
    return np.where(r > 0, np.minimum(1.0, r), 0.0)

def tvd_superbee(r):
    r = np.asarray(r, dtype=np.float64)
    result = np.zeros_like(r)
    result = np.where(r <= 0, 0.0, result)
    result = np.where((r > 0) & (r <= 0.5), 2.0 * r, result)
    result = np.where((r > 0.5) & (r <= 1.0), 1.0, result)
    result = np.where((r > 1.0) & (r <= 2.0), r, result)
    result = np.where(r > 2.0, 2.0, result)
    return result

def tvd_vanleer(r):
    r = np.asarray(r, dtype=np.float64)
    return np.where(r <= 0, 0.0, (r + np.abs(r)) / (1.0 + np.abs(r)))

def tvd_vanalbada(r):
    r = np.asarray(r, dtype=np.float64)
    return np.where(r <= 0, 0.0, (r * r + r) / (r * r + 1.0))

@njit
def _tvd_minmod_scalar(r):
    if r > 0:
        return min(1.0, r)
    else:
        return 0.0

@njit
def _tvd_superbee_scalar(r):
    if r <= 0:
        return 0.0
    elif r <= 0.5:
        return 2.0 * r
    elif r <= 1.0:
        return 1.0
    elif r <= 2.0:
        return r
    else:
        return 2.0

@njit
def _tvd_vanleer_scalar(r):
    if r <= 0:
        return 0.0
    return (r + abs(r)) / (1.0 + abs(r))

@njit
def _tvd_vanalbada_scalar(r):
    if r <= 0:
        return 0.0
    return (r * r + r) / (r * r + 1.0)

@njit
def compute_tvd_face_value(phi_c, phi_d, phi_up, mass_flux, limiter):
    if mass_flux > 0:
        phi_phi_cd = phi_d - phi_c
        phi_phi_cu = phi_c - phi_up
    else:
        phi_phi_cd = phi_c - phi_d
        phi_phi_cu = phi_up - phi_c
    r = phi_phi_cu / (phi_phi_cd + 1e-15)
    if limiter == 'minmod':
        psi = _tvd_minmod_scalar(r)
    elif limiter == 'superbee':
        psi = _tvd_superbee_scalar(r)
    elif limiter == 'vanleer':
        psi = _tvd_vanleer_scalar(r)
    elif limiter == 'vanalbada':
        psi = _tvd_vanalbada_scalar(r)
    else:
        psi = _tvd_minmod_scalar(r)
    if mass_flux > 0:
        face_value = phi_c + 0.5 * psi * phi_phi_cd
    else:
        face_value = phi_d + 0.5 * psi * phi_phi_cd
    return face_value

@njit
def tvd_convection_flux(mass_flux, phi_c, phi_d, phi_up, limiter):
    face_value = compute_tvd_face_value(phi_c, phi_d, phi_up, mass_flux, limiter)
    return mass_flux * face_value

@njit
def muscl_reconstruction(phi_left, phi_center, phi_right, limiter='minmod'):
    dphi_left = phi_center - phi_left
    dphi_right = phi_right - phi_center
    r = dphi_left / (dphi_right + 1e-15)
    if limiter == 'minmod':
        psi = tvd_minmod(r)
    else:
        psi = tvd_vanleer(r)
    return phi_center + 0.25 * psi * ((1.0 + 1.0/3.0) * dphi_left + (1.0 - 1.0/3.0) * dphi_right)

@njit
def quick_scheme(phi_up, phi_c, phi_d, phi_down):
    face_value = 0.75 * phi_c + 0.375 * phi_d - 0.125 * phi_up
    if face_value > max(phi_up, phi_c, phi_d, phi_down):
        face_value = max(phi_up, phi_c, phi_d, phi_down)
    elif face_value < min(phi_up, phi_c, phi_d, phi_down):
        face_value = min(phi_up, phi_c, phi_d, phi_down)
    return face_value
