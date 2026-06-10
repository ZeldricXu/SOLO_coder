"""
Test fixtures package.
Contains reference data, benchmark solutions, and helper utilities for CFD validation.
"""

from .reference_data import (
    # Lid-driven cavity
    LID_CAVITY_RE100_U_Y, LID_CAVITY_RE100_U,
    LID_CAVITY_RE100_V_X, LID_CAVITY_RE100_V,
    LID_CAVITY_RE400_U_Y, LID_CAVITY_RE400_U,
    
    # Channel flow
    poiseuille_velocity,
    
    # Backward-facing step
    BACKWARD_STEP_RE5000_REATTACH,
    BACKWARD_STEP_REATTACH_MIN, BACKWARD_STEP_REATTACH_MAX,
    
    # Cylinder flow
    cylinder_strouhal,
    CYLINDER_RE100_STROUHAL,
    CYLINDER_RE100_STROUHAL_MIN, CYLINDER_RE100_STROUHAL_MAX,
    
    # Natural convection
    NATURAL_CONVECTION_RA1E3_NU,
    NATURAL_CONVECTION_RA1E3_NU_MIN, NATURAL_CONVECTION_RA1E3_NU_MAX,
    NATURAL_CONVECTION_RA5_NU,
    NATURAL_CONVECTION_RA5_NU_MIN, NATURAL_CONVECTION_RA5_NU_MAX,
    
    # MMS
    mms_velocity, mms_pressure, mms_source_term,
    
    # Optimization test functions
    rosenbrock, ackley,
    
    # Grid specs
    REF_GRID_SIZES, REF_DOMAIN,
)

__all__ = [
    'LID_CAVITY_RE100_U_Y', 'LID_CAVITY_RE100_U',
    'LID_CAVITY_RE100_V_X', 'LID_CAVITY_RE100_V',
    'LID_CAVITY_RE400_U_Y', 'LID_CAVITY_RE400_U',
    'poiseuille_velocity',
    'BACKWARD_STEP_RE5000_REATTACH',
    'BACKWARD_STEP_REATTACH_MIN', 'BACKWARD_STEP_REATTACH_MAX',
    'cylinder_strouhal',
    'CYLINDER_RE100_STROUHAL',
    'CYLINDER_RE100_STROUHAL_MIN', 'CYLINDER_RE100_STROUHAL_MAX',
    'NATURAL_CONVECTION_RA1E3_NU',
    'NATURAL_CONVECTION_RA1E3_NU_MIN', 'NATURAL_CONVECTION_RA1E3_NU_MAX',
    'NATURAL_CONVECTION_RA5_NU',
    'NATURAL_CONVECTION_RA5_NU_MIN', 'NATURAL_CONVECTION_RA5_NU_MAX',
    'mms_velocity', 'mms_pressure', 'mms_source_term',
    'rosenbrock', 'ackley',
    'REF_GRID_SIZES', 'REF_DOMAIN',
]
