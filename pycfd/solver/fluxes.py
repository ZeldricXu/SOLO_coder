import numpy as np
from ..core.jit import njit

GAMMA = 1.4
GAS_CONSTANT = 287.0


def conservative_to_primitive(Q):
    """Convert conservative variables to primitive variables.
    
    Q = [rho, rho*u, rho*v, rho*E] (2D) or [rho, rho*u, rho*v, rho*w, rho*E] (3D)
    
    Returns:
        rho: density
        u: velocity components
        p: pressure
    """
    ndim = Q.shape[0] - 2
    rho = Q[0]
    u = Q[1:1+ndim] / rho
    rhoE = Q[-1]
    kinetic = 0.5 * np.sum(u ** 2)
    p = (GAMMA - 1.0) * (rhoE - rho * kinetic)
    return rho, u, p


def primitive_to_conservative(rho, u, p):
    """Convert primitive variables to conservative variables."""
    ndim = len(u)
    kinetic = 0.5 * np.sum(u ** 2)
    E = p / ((GAMMA - 1.0) * rho) + kinetic
    Q = np.zeros(ndim + 2, dtype=np.float64)
    Q[0] = rho
    Q[1:1+ndim] = rho * u
    Q[-1] = rho * E
    return Q


def compute_speed_of_sound(rho, p):
    """Compute speed of sound."""
    return np.sqrt(GAMMA * p / rho)


def compute_flux_vector(Q, direction=0):
    """Compute Euler flux vector in given direction."""
    rho, u, p = conservative_to_primitive(Q)
    ndim = len(u)
    un = u[direction]
    
    F = np.zeros(ndim + 2, dtype=np.float64)
    F[0] = rho * un
    for i in range(ndim):
        F[1 + i] = rho * un * u[i]
        if i == direction:
            F[1 + i] += p
    F[-1] = un * (Q[-1] + p)
    return F


@njit
def roe_flux(Q_left, Q_right, normal, gamma=GAMMA):
    """Compute Roe flux between left and right states.
    
    Args:
        Q_left: Conservative variables on left side
        Q_right: Conservative variables on right side
        normal: Face normal vector (unit)
        
    Returns:
        Numerical flux vector
    """
    ndim = len(normal)
    
    rhoL, uL, pL = conservative_to_primitive_jit(Q_left, ndim)
    rhoR, uR, pR = conservative_to_primitive_jit(Q_right, ndim)
    
    HL = (Q_left[-1] + pL) / rhoL
    HR = (Q_right[-1] + pR) / rhoR
    
    # Roe averages
    sqrt_rhoL = np.sqrt(rhoL)
    sqrt_rhoR = np.sqrt(rhoR)
    sum_sqrt = sqrt_rhoL + sqrt_rhoR
    
    rho_roe = sqrt_rhoL * sqrt_rhoR
    u_roe = (sqrt_rhoL * uL + sqrt_rhoR * uR) / sum_sqrt
    H_roe = (sqrt_rhoL * HL + sqrt_rhoR * HR) / sum_sqrt
    
    u_norm_roe = np.dot(u_roe, normal)
    c_roe = np.sqrt((gamma - 1.0) * (H_roe - 0.5 * np.sum(u_roe ** 2)))
    
    # Wave speeds
    lambda_ = np.zeros(ndim + 2, dtype=np.float64)
    lambda_[0] = u_norm_roe - c_roe
    lambda_[1:1+ndim] = u_norm_roe
    lambda_[-1] = u_norm_roe + c_roe
    
    # Eigenvalues with entropy fix
    for i in range(len(lambda_)):
        if np.abs(lambda_[i]) < 0.1 * c_roe:
            lambda_[i] = 0.5 * (lambda_[i] ** 2 / (0.1 * c_roe) + 0.1 * c_roe)
        lambda_[i] = np.abs(lambda_[i])
    
    # Jump in conservative variables
    dQ = Q_right - Q_left
    
    # Wave amplitudes (alpha)
    dp = pR - pL
    du = uR - uL
    du_norm = np.dot(du, normal)
    
    alpha = np.zeros(ndim + 2, dtype=np.float64)
    alpha[0] = (dp - rho_roe * c_roe * du_norm) / (2.0 * c_roe ** 2)
    alpha[1:1+ndim] = 0.0
    alpha[1] = dQ[0] - (dp) / (c_roe ** 2)
    for i in range(1, ndim):
        alpha[1 + i] = rho_roe * (du[i] - du_norm * normal[i])
    alpha[-1] = (dp + rho_roe * c_roe * du_norm) / (2.0 * c_roe ** 2)
    
    # Right eigenvectors
    R = np.zeros((ndim + 2, ndim + 2), dtype=np.float64)
    
    # Entropy wave
    R[0, 0] = 1.0
    for i in range(ndim):
        R[1 + i, 0] = u_roe[i]
    R[-1, 0] = 0.5 * np.sum(u_roe ** 2)
    
    # Contact wave
    R[0, 1] = 1.0
    for i in range(ndim):
        R[1 + i, 1] = u_roe[i]
    R[-1, 1] = H_roe - c_roe ** 2 / (gamma - 1.0)
    
    # Shear waves
    for k in range(1, ndim):
        R[0, 1 + k] = 0.0
        for i in range(ndim):
            R[1 + i, 1 + k] = 0.0
        R[1 + k, 1 + k] = 1.0
        R[-1, 1 + k] = u_roe[k]
    
    # Right-going wave
    R[0, -1] = 1.0
    for i in range(ndim):
        R[1 + i, -1] = u_roe[i] + c_roe * normal[i]
    R[-1, -1] = H_roe + c_roe * u_norm_roe
    
    # Compute flux difference
    F_left = compute_flux_vector_jit(Q_left, ndim, 0, normal)
    F_right = compute_flux_vector_jit(Q_right, ndim, 0, normal)
    
    flux = 0.5 * (F_left + F_right)
    for i in range(len(lambda_)):
        flux -= 0.5 * lambda_[i] * alpha[i] * R[:, i]
    
    return flux


@njit
def ausm_plus_flux(Q_left, Q_right, normal, gamma=GAMMA):
    """Compute AUSM+ flux between left and right states.
    
    Args:
        Q_left: Conservative variables on left side
        Q_right: Conservative variables on right side
        normal: Face normal vector (unit)
        
    Returns:
        Numerical flux vector
    """
    ndim = len(normal)
    
    rhoL, uL, pL = conservative_to_primitive_jit(Q_left, ndim)
    rhoR, uR, pR = conservative_to_primitive_jit(Q_right, ndim)
    
    cL = np.sqrt(gamma * pL / rhoL)
    cR = np.sqrt(gamma * pR / rhoR)
    c_face = 0.5 * (cL + cR)
    
    u_norm_L = np.dot(uL, normal)
    u_norm_R = np.dot(uR, normal)
    
    Mach_L = u_norm_L / c_face
    Mach_R = u_norm_R / c_face
    
    # Mass flux splitting
    if np.abs(Mach_L) >= 1.0:
        M_pos = np.maximum(0.0, Mach_L)
    else:
        M_pos = 0.25 * (Mach_L + 1.0) ** 2 + 0.0625 * (Mach_L ** 2 - 1.0) ** 2
    
    if np.abs(Mach_R) >= 1.0:
        M_neg = np.minimum(0.0, Mach_R)
    else:
        M_neg = -0.25 * (Mach_R - 1.0) ** 2 - 0.0625 * (Mach_R ** 2 - 1.0) ** 2
    
    M_face = M_pos + M_neg
    
    # Pressure splitting
    if np.abs(Mach_L) >= 1.0:
        P_pos = 0.5 * (1.0 + np.sign(Mach_L))
    else:
        P_pos = 0.25 * (Mach_L + 1.0) ** 2 * (2.0 - Mach_L) + 0.1875 * Mach_L * (Mach_L ** 2 - 1.0) ** 2
    
    if np.abs(Mach_R) >= 1.0:
        P_neg = 0.5 * (1.0 - np.sign(Mach_R))
    else:
        P_neg = 0.25 * (Mach_R - 1.0) ** 2 * (2.0 + Mach_R) - 0.1875 * Mach_R * (Mach_R ** 2 - 1.0) ** 2
    
    P_face = P_pos * pL + P_neg * pR
    
    # Mass flux
    if M_face >= 0.0:
        rho_face = rhoL
        u_face = uL
        H_face = (Q_left[-1] + pL) / rhoL
    else:
        rho_face = rhoR
        u_face = uR
        H_face = (Q_right[-1] + pR) / rhoR
    
    m_dot = rho_face * c_face * M_face
    
    # Compute flux
    ndim = len(normal)
    flux = np.zeros(ndim + 2, dtype=np.float64)
    
    flux[0] = m_dot
    
    for i in range(ndim):
        flux[1 + i] = m_dot * u_face[i] + P_face * normal[i]
    
    flux[-1] = m_dot * H_face
    
    return flux


@njit
def conservative_to_primitive_jit(Q, ndim):
    """JIT version of conservative_to_primitive."""
    rho = Q[0]
    u = np.zeros(ndim, dtype=np.float64)
    for i in range(ndim):
        u[i] = Q[1 + i] / rho
    rhoE = Q[-1]
    kinetic = 0.0
    for i in range(ndim):
        kinetic += 0.5 * u[i] ** 2
    p = (GAMMA - 1.0) * (rhoE - rho * kinetic)
    return rho, u, p


@njit
def compute_flux_vector_jit(Q, ndim, direction, normal):
    """JIT version of flux computation with normal vector."""
    rho, u, p = conservative_to_primitive_jit(Q, ndim)
    un = 0.0
    for i in range(ndim):
        un += u[i] * normal[i]
    
    F = np.zeros(ndim + 2, dtype=np.float64)
    F[0] = rho * un
    for i in range(ndim):
        F[1 + i] = rho * un * u[i] + p * normal[i]
    F[-1] = un * (Q[-1] + p)
    return F
