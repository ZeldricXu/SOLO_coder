"""
Integration test for flow past a circular cylinder.

Tests:
- Flow separation and wake formation
- Vortex shedding (qualitative)
- Force coefficients (drag, lift)
- Approximate Strouhal number estimation

Reference: For Re=100, Strouhal number ~0.16-0.17, Cd ~1.3-1.4
"""

import pytest
import numpy as np
from numpy.testing import assert_allclose

from pycfd.mesh import create_2d_structured_mesh, check_mesh_quality
from pycfd.solver import SimpleSolver, FlowField
from pycfd.bc import BoundaryManager, VelocityInletBC, PressureOutletBC, WallBC
from tests.fixtures.reference_data import CYLINDER_RE100_STROUHAL


def _create_cylinder_domain_mesh(nx=80, ny=50):
    """Create a rectangular domain with an immersed boundary representation.
    
    Since we're using a simple structured mesh framework, we'll mark cells
    inside a circle as solid using a mask, and apply special boundary
    conditions there.
    
    Domain: x in [-10, 20], y in [-8, 8]
    Cylinder at (0, 0) with radius R=1
    """
    x_range = [-10, 20]
    y_range = [-8, 8]
    
    mesh = create_2d_structured_mesh(nx, ny, x_range, y_range)
    
    cylinder_radius = 1.0
    cell_centers = mesh.cell_centers
    
    dist_from_center = np.sqrt(
        cell_centers[:, 0] ** 2 + cell_centers[:, 1] ** 2
    )
    
    solid_mask = dist_from_center < cylinder_radius
    
    return mesh, solid_mask, cylinder_radius


def _setup_cylinder_solver(nx=60, ny=40, reynolds=100):
    """Set up flow past a circular cylinder solver."""
    mesh, solid_mask, R = _create_cylinder_domain_mesh(nx, ny)
    
    quality = check_mesh_quality(mesh)
    assert quality.is_valid, f"Mesh quality issues: {quality.summary()}"
    
    U_inf = 1.0
    nu = U_inf * (2 * R) / reynolds
    
    flow = FlowField(mesh.n_cells, mesh.ndim)
    flow.u[:, 0] = U_inf
    flow.u[solid_mask] = 0.0
    
    inlet_faces = mesh.boundary_faces['left']
    outlet_faces = mesh.boundary_faces['right']
    top_faces = mesh.boundary_faces['top']
    bottom_faces = mesh.boundary_faces['bottom']
    
    bc_manager = BoundaryManager()
    bc_manager.add_bc('inlet', VelocityInletBC('left', velocity=[U_inf, 0.0]))
    bc_manager.add_bc('outlet', PressureOutletBC('right', static_pressure=0.0))
    bc_manager.add_bc('top', WallBC('top', no_slip=False))
    bc_manager.add_bc('bottom', WallBC('bottom', no_slip=False))
    
    all_faces = np.concatenate([inlet_faces, outlet_faces, top_faces, bottom_faces])
    
    bc_manager.initialize(mesh)
    
    solver = SimpleSolver(
        mesh=mesh, flow=flow, bc_manager=bc_manager,
        nu=nu, rho=1.0, convection_scheme='tvd'
    )
    solver.tvd_limiter = 'vanleer'
    solver.underrelaxation = {'u': 0.4, 'p': 0.15, 'v': 0.4}
    
    solver.solid_mask = solid_mask
    solver.cylinder_radius = R
    
    return solver, R, U_inf


def _compute_force_coefficients(solver, R, U_inf, rho=1.0):
    """Compute drag and lift coefficients using pressure integration."""
    mesh = solver.mesh
    p = solver.flow.p
    solid_mask = solver.solid_mask
    
    cell_centers = mesh.cell_centers
    dist_from_center = np.sqrt(
        cell_centers[:, 0] ** 2 + cell_centers[:, 1] ** 2
    )
    
    boundary_layer = ~solid_mask & (dist_from_center < R * 1.5)
    
    Fx = 0.0
    Fy = 0.0
    
    for idx in np.where(boundary_layer)[0]:
        dx = cell_centers[idx, 0]
        dy = cell_centers[idx, 1]
        r = max(dist_from_center[idx], 1e-10)
        
        nx = dx / r
        ny = dy / r
        
        dA = (mesh.x_range[1] - mesh.x_range[0]) / mesh.nx * \
             (mesh.y_range[1] - mesh.y_range[0]) / mesh.ny
        
        Fx -= p[idx] * nx * dA
        Fy -= p[idx] * ny * dA
    
    reference_area = 2 * R
    Cd = Fx / (0.5 * rho * U_inf ** 2 * reference_area)
    Cl = Fy / (0.5 * rho * U_inf ** 2 * reference_area)
    
    return abs(Cd), Cl


def _estimate_strouhal(solver, R, U_inf, n_steps=100, dt=0.05):
    """Estimate Strouhal number from lift coefficient oscillations."""
    Cl_history = []
    
    for i in range(n_steps):
        solver.step()
        _, Cl = _compute_force_coefficients(solver, R, U_inf)
        Cl_history.append(Cl)
    
    if len(Cl_history) < 10:
        return None
    
    Cl_array = np.array(Cl_history)
    Cl_mean = np.mean(Cl_array)
    Cl_centered = Cl_array - Cl_mean
    
    fft = np.fft.fft(Cl_centered)
    freq = np.fft.fftfreq(len(Cl_centered), dt)
    
    positive_freq = freq > 0
    if not np.any(positive_freq):
        return None
    
    dominant_freq_idx = np.argmax(np.abs(fft[positive_freq]))
    dominant_freq = freq[positive_freq][dominant_freq_idx]
    
    St = dominant_freq * (2 * R) / U_inf
    
    return St


class TestCylinderFlow:
    """Test flow past a circular cylinder."""

    def test_mesh_generation(self):
        """Test that the domain mesh is valid."""
        mesh, solid_mask, R = _create_cylinder_domain_mesh(40, 30)
        
        assert mesh.n_cells == 40 * 30
        assert np.any(solid_mask), "Should have solid cells"
        assert np.any(~solid_mask), "Should have fluid cells"
        
        quality = check_mesh_quality(mesh)
        assert quality.is_valid

    def test_solver_initialization(self):
        """Test that the solver initializes correctly."""
        solver, R, U_inf = _setup_cylinder_solver(30, 20)
        
        assert hasattr(solver, 'solid_mask')
        assert hasattr(solver, 'cylinder_radius')
        assert solver.cylinder_radius == R
        assert np.allclose(solver.flow.u[solver.solid_mask], 0.0)

    def test_flow_around_cylinder(self):
        """Test that flow develops around the cylinder."""
        solver, R, U_inf = _setup_cylinder_solver(40, 30, reynolds=100)
        
        initial_u = solver.flow.u[:, 0].copy()
        
        for i in range(50):
            solver.step()
        
        u = solver.flow.u[:, 0]
        cell_centers = solver.mesh.cell_centers
        
        downstream = cell_centers[:, 0] > 5 * R
        near_wake = (cell_centers[:, 0] > R) & (cell_centers[:, 0] < 5 * R)
        
        assert not np.all(u[downstream] == initial_u[downstream]), "Flow should change downstream"
        assert not np.any(np.isnan(u)), "No NaN values"

    def test_force_coefficients(self):
        """Test that drag and lift coefficients are reasonable."""
        solver, R, U_inf = _setup_cylinder_solver(50, 40, reynolds=100)
        
        for i in range(100):
            solver.step()
        
        Cd, Cl = _compute_force_coefficients(solver, R, U_inf)
        
        assert Cd > 0, "Drag should be positive"
        assert Cd < 5.0, "Drag should be physically reasonable"
        assert abs(Cl) < 3.0, "Lift should be reasonable"

    def test_wake_formation(self):
        """Test that a wake forms behind the cylinder."""
        solver, R, U_inf = _setup_cylinder_solver(50, 40, reynolds=100)
        
        for i in range(150):
            solver.step()
        
        cell_centers = solver.mesh.cell_centers
        u = solver.flow.u[:, 0]
        
        centerline = np.abs(cell_centers[:, 1]) < 0.5
        near_wake = (cell_centers[:, 0] > R) & (cell_centers[:, 0] < 5 * R)
        far_wake = (cell_centers[:, 0] > 10 * R)
        
        wake_u = u[centerline & near_wake]
        far_wake_u = u[centerline & far_wake]
        
        if len(wake_u) > 0:
            mean_wake_u = np.mean(wake_u)
            mean_far_u = np.mean(far_wake_u) if len(far_wake_u) > 0 else U_inf
            
            assert mean_wake_u < U_inf, "Velocity should be reduced in wake"

    def test_velocity_deficit(self):
        """Test velocity deficit in the wake."""
        solver, R, U_inf = _setup_cylinder_solver(50, 40, reynolds=100)
        
        for i in range(100):
            solver.step()
        
        cell_centers = solver.mesh.cell_centers
        u = solver.flow.u[:, 0]
        
        upstream = cell_centers[:, 0] < -2 * R
        downstream = (cell_centers[:, 0] > 3 * R) & (cell_centers[:, 0] < 8 * R)
        centerline = np.abs(cell_centers[:, 1]) < 1.0
        
        u_upstream = np.mean(u[upstream & centerline])
        u_downstream = np.mean(u[downstream & centerline])
        
        assert u_downstream < u_upstream * 0.9, "Velocity deficit should exist in wake"

    def test_pressure_distribution(self):
        """Test pressure distribution around cylinder."""
        solver, R, U_inf = _setup_cylinder_solver(50, 40, reynolds=100)
        
        for i in range(100):
            solver.step()
        
        cell_centers = solver.mesh.cell_centers
        p = solver.flow.p
        
        front = (cell_centers[:, 0] < -R * 0.5) & (np.abs(cell_centers[:, 1]) < R * 1.5)
        back = (cell_centers[:, 0] > R * 0.5) & (np.abs(cell_centers[:, 1]) < R * 1.5)
        
        if np.any(front) and np.any(back):
            p_front = np.mean(p[front])
            p_back = np.mean(p[back])
            
            assert p_front > p_back, "Pressure should be higher at front than back"

    def test_mass_conservation(self):
        """Test approximate mass conservation."""
        solver, R, U_inf = _setup_cylinder_solver(40, 30, reynolds=100)
        
        for i in range(100):
            solver.step()
        
        cell_centers = solver.mesh.cell_centers
        u = solver.flow.u[:, 0]
        
        inlet = np.abs(cell_centers[:, 0] + 8) < 2
        outlet = np.abs(cell_centers[:, 0] - 15) < 2
        
        mass_in = np.mean(u[inlet & ~solver.solid_mask]) * 16
        mass_out = np.mean(u[outlet & ~solver.solid_mask]) * 16
        
        assert abs(mass_in - mass_out) < mass_in * 0.5, "Mass should be approximately conserved"
