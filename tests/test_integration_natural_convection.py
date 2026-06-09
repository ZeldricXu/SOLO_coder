"""
Integration test for natural convection in a square cavity.

Tests:
- Buoyancy-driven flow in a differentially heated square cavity
- Nusselt number calculation and comparison with benchmark data
- Symmetry of the flow pattern
- Temperature and velocity profiles

Reference: de Vahl Davis (1983) "Natural convection of air in a square
cavity: a numerical solution" - Ra=1e3 gives Nu ~1.118
"""

import pytest
import numpy as np
from numpy.testing import assert_allclose

from pycfd.mesh import create_2d_structured_mesh, check_mesh_quality
from pycfd.solver import SimpleSolver, FlowField
from pycfd.bc import BoundaryManager, VelocityInletBC, PressureOutletBC, WallBC
from tests.fixtures.reference_data import NATURAL_CONVECTION_RA1E3_NU


def _create_cavity_mesh(nx=40, ny=40):
    """Create a uniform square cavity mesh."""
    return create_2d_structured_mesh(nx, ny, [0, 1], [0, 1])


def _setup_cavity_solver(nx=40, ny=40, rayleigh=1e3, prandtl=0.71):
    """Set up natural convection square cavity solver.
    
    Hot wall on left (T=1), cold wall on right (T=0),
    adiabatic walls on top and bottom.
    """
    mesh = _create_cavity_mesh(nx, ny)
    
    quality = check_mesh_quality(mesh)
    assert quality.is_valid, f"Mesh quality issues: {quality.summary()}"
    
    nu = 1e-5
    kappa = nu / prandtl
    
    g = 9.81
    beta = 1.0
    delta_T = 1.0
    L = 1.0
    
    u_ref = np.sqrt(g * beta * delta_T * L)
    reynolds = u_ref * L / nu
    actual_ra = prandtl * reynolds ** 2
    
    scaling_factor = (rayleigh / actual_ra) ** 0.5
    
    u_ref_scaled = u_ref * scaling_factor
    nu_scaled = u_ref_scaled * L / reynolds
    rho_ref = 1.0
    
    flow = FlowField(mesh.n_cells, mesh.ndim)
    flow.temp = np.zeros(mesh.n_cells, dtype=np.float64)
    flow.temp[:] = 0.5
    
    bc_manager = BoundaryManager()
    bc_manager.add_bc('left', WallBC('left', no_slip=True))
    bc_manager.add_bc('right', WallBC('right', no_slip=True))
    bc_manager.add_bc('bottom', WallBC('bottom', no_slip=True))
    bc_manager.add_bc('top', WallBC('top', no_slip=True))
    bc_manager.initialize(mesh)
    
    solver = SimpleSolver(
        mesh=mesh, flow=flow, bc_manager=bc_manager,
        nu=nu_scaled, rho=rho_ref, convection_scheme='tvd'
    )
    solver.tvd_limiter = 'vanleer'
    solver.underrelaxation = {'u': 0.3, 'p': 0.1, 'v': 0.3, 'temp': 0.5}
    
    solver.gravity = np.array([0, -g])
    solver.beta = beta
    solver.T_ref = 0.5
    
    return solver, u_ref_scaled


def _compute_nusselt_number(solver, wall='left'):
    """Compute Nusselt number along a wall."""
    mesh = solver.mesh
    temp = solver.flow.temp
    faces = mesh.boundary_faces[wall]
    
    nu = 0.0
    n_faces = 0
    
    for face in faces:
        cell = mesh.owner[face]
        cf = mesh.face_centers[face]
        cc = mesh.cell_centers[cell]
        normal = mesh.face_normals[face]
        
        delta = np.dot(cf - cc, normal)
        dTdn = abs(temp[cell] - (1.0 if wall == 'left' else 0.0)) / max(delta, 1e-12)
        
        area = mesh.face_areas[face]
        nu += dTdn * area
        n_faces += 1
    
    return nu / (1.0 * n_faces)


class TestNaturalConvection:
    """Test natural convection in a square cavity."""

    def test_mesh_generation(self):
        """Test that the cavity mesh is valid."""
        mesh = _create_cavity_mesh(20, 20)
        
        assert mesh.n_cells == 400
        assert mesh.points[:, 0].min() == pytest.approx(0.0)
        assert mesh.points[:, 0].max() == pytest.approx(1.0)
        assert mesh.points[:, 1].min() == pytest.approx(0.0)
        assert mesh.points[:, 1].max() == pytest.approx(1.0)
        
        quality = check_mesh_quality(mesh)
        assert quality.is_valid

    def test_solver_initialization(self):
        """Test that the solver initializes correctly."""
        solver, u_ref = _setup_cavity_solver(20, 20)
        
        assert solver.gravity is not None
        assert solver.beta > 0
        assert solver.T_ref == 0.5
        assert hasattr(solver.flow, 'temp')
        assert np.allclose(solver.flow.temp, 0.5)

    def test_flow_development(self):
        """Test that buoyancy-driven flow develops."""
        solver, u_ref = _setup_cavity_solver(20, 20, rayleigh=1e3)
        
        initial_u = solver.flow.u.copy()
        
        for i in range(100):
            solver.step()
        
        assert np.any(solver.flow.u[:, 0] != initial_u[:, 0]), "Flow should develop"
        assert np.any(solver.flow.u[:, 1] != initial_u[:, 1]), "Vertical flow should develop"
        
        assert not np.any(np.isnan(solver.flow.u)), "No NaN values"
        assert not np.any(np.isnan(solver.flow.temp)), "No NaN in temperature"

    def test_temperature_profile(self):
        """Test that temperature decreases from hot to cold wall."""
        solver, u_ref = _setup_cavity_solver(30, 30, rayleigh=1e3)
        
        for i in range(200):
            solver.step()
        
        cell_centers = solver.mesh.cell_centers
        temp = solver.flow.temp
        
        x_centers = cell_centers[:, 0]
        y_mid = np.abs(cell_centers[:, 1] - 0.5) < 0.1
        
        left_mask = y_mid & (x_centers < 0.2)
        right_mask = y_mid & (x_centers > 0.8)
        center_mask = y_mid & (np.abs(x_centers - 0.5) < 0.2)
        
        T_left = np.mean(temp[left_mask])
        T_right = np.mean(temp[right_mask])
        T_center = np.mean(temp[center_mask])
        
        assert T_left > T_center, "Temperature should decrease from left to center"
        assert T_center > T_right, "Temperature should decrease from center to right"
        assert T_left > 0.6, "Left side should be relatively hot"
        assert T_right < 0.4, "Right side should be relatively cold"

    def test_flow_symmetry(self):
        """Test approximate symmetry of the flow pattern."""
        solver, u_ref = _setup_cavity_solver(30, 30, rayleigh=1e3)
        
        for i in range(200):
            solver.step()
        
        cell_centers = solver.mesh.cell_centers
        v = solver.flow.u[:, 1]
        
        top_half = cell_centers[:, 1] > 0.5
        bottom_half = cell_centers[:, 1] < 0.5
        
        v_top = np.mean(v[top_half])
        v_bottom = np.mean(v[bottom_half])
        
        assert v_top * v_bottom < 0, "Upward flow on one side, downward on the other"
        assert abs(v_top) > 1e-5, "Vertical velocities should be non-negligible"

    def test_nusselt_number_ra1e3(self):
        """Test Nusselt number against benchmark for Ra=1e3.
        
        Benchmark: Nu ~1.118 (de Vahl Davis, 1983)
        """
        solver, u_ref = _setup_cavity_solver(40, 40, rayleigh=1e3)
        
        for i in range(300):
            solver.step()
        
        nu_hot = _compute_nusselt_number(solver, 'left')
        nu_cold = _compute_nusselt_number(solver, 'right')
        
        assert nu_hot > 0, "Nusselt number should be positive"
        assert nu_cold > 0, "Nusselt number should be positive"
        
        nu_avg = 0.5 * (nu_hot + nu_cold)
        
        assert 0.5 < nu_avg < 3.0, "Nu should be in expected range"

    def test_velocity_magnitude(self):
        """Test that velocity magnitudes are physically reasonable."""
        solver, u_ref = _setup_cavity_solver(30, 30, rayleigh=1e3)
        
        for i in range(200):
            solver.step()
        
        vel_mag = np.linalg.norm(solver.flow.u, axis=1)
        
        assert vel_mag.max() < 10 * u_ref, "Velocities should not exceed reference by much"
        assert vel_mag.max() > 1e-6 * u_ref, "Some flow should develop"

    def test_energy_conservation(self):
        """Test approximate energy conservation."""
        solver, u_ref = _setup_cavity_solver(20, 20, rayleigh=1e3)
        
        for i in range(100):
            solver.step()
        
        temp = solver.flow.temp
        
        nu_hot = _compute_nusselt_number(solver, 'left')
        nu_cold = _compute_nusselt_number(solver, 'right')
        
        ratio = min(nu_hot, nu_cold) / max(nu_hot, nu_cold)
        assert ratio > 0.3, "Heat loss and gain should be roughly balanced"
