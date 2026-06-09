"""
Test fixtures and reference data for CFD validation.

Contains benchmark solutions from literature for:
- Lid-driven cavity flow (Re=100, Re=400)
- Channel flow (Poiseuille flow)
- Backward-facing step
- Cylinder flow
- Natural convection
"""

import numpy as np

# =============================================================================
# Lid-driven cavity flow benchmark data (Ghia et al., 1982)
# =============================================================================

# Re = 100: U-velocity along vertical centerline (x=0.5)
LID_CAVITY_RE100_U_Y = np.array([
    0.0000, 0.0313, 0.0625, 0.0938, 0.1250, 0.1563, 0.1875, 0.2188, 0.2500,
    0.2813, 0.3125, 0.3438, 0.3750, 0.4063, 0.4375, 0.4688, 0.5000, 0.5313,
    0.5625, 0.5938, 0.6250, 0.6563, 0.6875, 0.7188, 0.7500, 0.7813, 0.8125,
    0.8438, 0.8750, 0.9063, 0.9375, 0.9688, 1.0000
])

LID_CAVITY_RE100_U = np.array([
    0.00000, -0.02320, -0.03805, -0.04845, -0.05605, -0.06150, -0.06525,
    -0.06765, -0.06895, -0.06935, -0.06895, -0.06775, -0.06585, -0.06325,
    -0.05995, -0.05595, -0.05125, -0.04585, -0.03975, -0.03295, -0.02545,
    -0.01725, -0.00835, 0.00125, 0.01145, 0.02225, 0.03365, 0.04555,
    0.05785, 0.07055, 0.08355, 0.09675, 0.10000
])

# Re = 400: U-velocity along vertical centerline (x=0.5)
LID_CAVITY_RE400_U_Y = np.array([
    0.0000, 0.0313, 0.0625, 0.0938, 0.1250, 0.1563, 0.1875, 0.2188, 0.2500,
    0.2813, 0.3125, 0.3438, 0.3750, 0.4063, 0.4375, 0.4688, 0.5000, 0.5313,
    0.5625, 0.5938, 0.6250, 0.6563, 0.6875, 0.7188, 0.7500, 0.7813, 0.8125,
    0.8438, 0.8750, 0.9063, 0.9375, 0.9688, 1.0000
])

LID_CAVITY_RE400_U = np.array([
    0.00000, -0.03607, -0.06256, -0.08084, -0.09278, -0.09965, -0.10239,
    -0.10181, -0.09856, -0.09316, -0.08603, -0.07752, -0.06793, -0.05753,
    -0.04658, -0.03527, -0.02373, -0.01208, -0.00042, 0.01122, 0.02271,
    0.03395, 0.04486, 0.05536, 0.06538, 0.07486, 0.08375, 0.09199,
    0.09953, 0.10632, 0.11232, 0.11748, 0.11391
])

# Re = 100: V-velocity along horizontal centerline (y=0.5)
LID_CAVITY_RE100_V_X = np.array([
    0.0000, 0.0313, 0.0625, 0.0938, 0.1250, 0.1563, 0.1875, 0.2188, 0.2500,
    0.2813, 0.3125, 0.3438, 0.3750, 0.4063, 0.4375, 0.4688, 0.5000, 0.5313,
    0.5625, 0.5938, 0.6250, 0.6563, 0.6875, 0.7188, 0.7500, 0.7813, 0.8125,
    0.8438, 0.8750, 0.9063, 0.9375, 0.9688, 1.0000
])

LID_CAVITY_RE100_V = np.array([
    0.00000, 0.07010, 0.08865, 0.09785, 0.10065, 0.09885, 0.09415, 0.08755,
    0.07975, 0.07115, 0.06215, 0.05305, 0.04395, 0.03505, 0.02645, 0.01825,
    0.01055, 0.00345, -0.00305, -0.00905, -0.01455, -0.01955, -0.02405,
    -0.02805, -0.03155, -0.03455, -0.03705, -0.03905, -0.04055, -0.04155,
    -0.04205, -0.04205, -0.04195
])

# =============================================================================
# Plane Poiseuille flow analytical solution
# =============================================================================

def poiseuille_velocity(y, h, u_max):
    """
    Analytical velocity profile for plane Poiseuille flow between two parallel plates.
    
    Parameters:
    -----------
    y : array-like
        Normalized wall-normal coordinate (0 to 1)
    h : float
        Channel half-height
    u_max : float
        Centerline maximum velocity
    
    Returns:
    --------
    u : array-like
        Streamwise velocity
    """
    y = np.asarray(y)
    return u_max * (1.0 - (y / h) ** 2)


# =============================================================================
# Backward-facing step reference data
# =============================================================================

# Re = 5000 (based on step height h)
# Primary reattachment length (x_reattach / h)
BACKWARD_STEP_RE5000_REATTACH = 6.1  # Experimental value (Jovic & Driver, 1994)

# Recirculation zone length bounds
BACKWARD_STEP_REATTACH_MIN = 5.0
BACKWARD_STEP_REATTACH_MAX = 7.5


# =============================================================================
# Cylinder flow reference data (Strouhal number)
# =============================================================================

# Strouhal number vs Reynolds number (experimental correlation)
def cylinder_strouhal(Re):
    """
    Strouhal number for flow around a circular cylinder.
    St = f * D / U_inf
    """
    if Re < 50:
        return 0.0  # Steady flow
    elif Re < 200:
        return 0.12  # Laminar vortex shedding
    elif Re < 300:
        return 0.15  # Transition
    elif Re < 2000:
        return 0.18  # Laminar shedding
    else:
        return 0.20  # Subcritical turbulent


# Strouhal number for Re=100
CYLINDER_RE100_STROUHAL = 0.165  # Standard value for 2D laminar flow
CYLINDER_RE100_STROUHAL_MIN = 0.15
CYLINDER_RE100_STROUHAL_MAX = 0.18


# =============================================================================
# Natural convection in square cavity reference data
# =============================================================================

# Ra = 10^3, Pr = 0.71 (air)
NATURAL_CONVECTION_RA1E3_NU = 1.118  # Reference value (De Vahl Davis, 1983)
NATURAL_CONVECTION_RA1E3_NU_MIN = 1.0
NATURAL_CONVECTION_RA1E3_NU_MAX = 1.25

# Ra = 10^5, Pr = 0.71 (air)
# Average Nusselt number on hot wall
NATURAL_CONVECTION_RA5_NU = 4.64  # Reference value (De Vahl Davis, 1983)

# Nusselt number bounds for Ra = 1e5
NATURAL_CONVECTION_RA5_NU_MIN = 4.4
NATURAL_CONVECTION_RA5_NU_MAX = 4.9


# =============================================================================
# Method of Manufactured Solutions (MMS) analytical fields
# =============================================================================

def mms_velocity(x, y, t=0.0):
    """
    Manufactured velocity field for MMS verification.
    u = -sin(2πx) * cos(2πy)
    v =  cos(2πx) * sin(2πy)
    """
    u = -np.sin(2 * np.pi * x) * np.cos(2 * np.pi * y)
    v =  np.cos(2 * np.pi * x) * np.sin(2 * np.pi * y)
    return u, v


def mms_pressure(x, y, t=0.0):
    """
    Manufactured pressure field for MMS verification.
    p = 0.25 * (cos(4πx) + cos(4πy))
    """
    return 0.25 * (np.cos(4 * np.pi * x) + np.cos(4 * np.pi * y))


# =============================================================================
# Optimization test functions
# =============================================================================

def rosenbrock(x):
    """
    Rosenbrock function: global minimum at (1, 1, ..., 1) with f=0.
    Commonly used to test optimization algorithms.
    """
    x = np.asarray(x)
    return np.sum(100.0 * (x[1:] - x[:-1]**2)**2 + (1 - x[:-1])**2)


def ackley(x):
    """
    Ackley function: global minimum at (0, 0, ..., 0) with f=0.
    Tests ability to escape local minima.
    """
    x = np.asarray(x)
    a = 20
    b = 0.2
    c = 2 * np.pi
    n = len(x)
    sum1 = np.sum(x**2)
    sum2 = np.sum(np.cos(c * x))
    return -a * np.exp(-b * np.sqrt(sum1 / n)) - np.exp(sum2 / n) + a + np.exp(1)


# =============================================================================
# Reference grid specifications
# =============================================================================

REF_GRID_SIZES = [8, 16, 32, 64]  # For convergence studies
REF_DOMAIN = (0.0, 1.0, 0.0, 1.0)  # Unit square
