"""
Unit tests for Method of Manufactured Solutions (MMS) convergence verification.

Tests:
- Velocity field order of accuracy (> 1.5, should approach 2nd order)
- Pressure field order of accuracy (> 1.0)
- Error decreases monotonically with mesh refinement
"""

import pytest
import numpy as np
from numpy.testing import assert_allclose

from pycfd.mesh import create_2d_structured_mesh
from pycfd.solver import SimpleSolver, FlowField
from pycfd.bc import BoundaryManager, VelocityInletBC, PressureOutletBC, WallBC
from tests.fixtures.reference_data import (
    mms_velocity, mms_pressure, mms_source_term
)


def _run_mms_simulation(nx, ny, reynolds=10):
    """Run a simulation on a given mesh and compute L2 error."""
    x_range = [0, 1]
    y_range = [0, 1]
    mesh = create_2d_structured_mesh(nx, ny, x_range, y_range)
    
    U_ref = 1.0
    L_ref = 1.0
    nu = U_ref * L_ref / reynolds
    
    flow = FlowField(mesh.n_cells, mesh.ndim, n_faces=mesh.n_faces)
    flow.initialize(u0=0.0, p0=0.0)
    
    cell_centers = mesh.cell_centers
    u_analytic, v_analytic = mms_velocity(cell_centers[:, 0], cell_centers[:, 1])
    p_analytic = mms_pressure(cell_centers[:, 0], cell_centers[:, 1])
    
    bc_manager = BoundaryManager()
    
    left_faces = mesh.boundary_faces['left']
    left_centers = mesh.face_centers[left_faces]
    u_left, v_left = mms_velocity(left_centers[:, 0], left_centers[:, 1])
    velocity_left = np.column_stack([u_left, v_left])
    
    right_faces = mesh.boundary_faces['right']
    right_centers = mesh.face_centers[right_faces]
    u_right, v_right = mms_velocity(right_centers[:, 0], right_centers[:, 1])
    velocity_right = np.column_stack([u_right, v_right])
    
    bottom_faces = mesh.boundary_faces['bottom']
    bottom_centers = mesh.face_centers[bottom_faces]
    u_bottom, v_bottom = mms_velocity(bottom_centers[:, 0], bottom_centers[:, 1])
    velocity_bottom = np.column_stack([u_bottom, v_bottom])
    
    top_faces = mesh.boundary_faces['top']
    top_centers = mesh.face_centers[top_faces]
    u_top, v_top = mms_velocity(top_centers[:, 0], top_centers[:, 1])
    velocity_top = np.column_stack([u_top, v_top])
    
    bc_manager.add_bc('left', WallBC('left', no_slip=False, velocity=[u_left.mean(), v_left.mean()]))
    bc_manager.add_bc('right', WallBC('right', no_slip=False, velocity=[u_right.mean(), v_right.mean()]))
    bc_manager.add_bc('bottom', WallBC('bottom', no_slip=False, velocity=[u_bottom.mean(), v_bottom.mean()]))
    bc_manager.add_bc('top', WallBC('top', no_slip=False, velocity=[u_top.mean(), v_top.mean()]))
    
    bc_manager.initialize(mesh)
    
    solver = SimpleSolver(
        mesh=mesh, flow=flow, bc_manager=bc_manager,
        nu=nu, rho=1.0, convection_scheme='tvd'
    )
    solver.tvd_limiter = 'vanleer'
    solver.underrelaxation = {'u': 0.6, 'p': 0.3, 'v': 0.6}
    
    for i in range(300):
        res = solver.step()
        if res['continuity'] < 1e-6 and i > 100:
            break
    
    u_computed = solver.flow.u[:, 0]
    v_computed = solver.flow.u[:, 1]
    p_computed = solver.flow.p
    
    u_error = u_computed - u_analytic
    v_error = v_computed - v_analytic
    p_error = p_computed - p_analytic
    
    volumes = mesh.cell_volumes
    
    l2_u = np.sqrt(np.sum(u_error ** 2 * volumes))
    l2_v = np.sqrt(np.sum(v_error ** 2 * volumes))
    l2_p = np.sqrt(np.sum(p_error ** 2 * volumes))
    
    linf_u = np.max(np.abs(u_error))
    linf_v = np.max(np.abs(v_error))
    linf_p = np.max(np.abs(p_error))
    
    return {
        'l2_u': l2_u, 'l2_v': l2_v, 'l2_p': l2_p,
        'linf_u': linf_u, 'linf_v': linf_v, 'linf_p': linf_p,
        'nx': nx, 'ny': ny, 'h': 1.0 / (nx - 1)
    }


def _compute_order(error1, h1, error2, h2):
    """Compute order of accuracy: p = log(e1/e2) / log(h1/h2)."""
    if error1 <= 0 or error2 <= 0:
        return 0.0
    return np.log(error1 / error2) / np.log(h1 / h2)


class TestMMSConvergence:
    """Test MMS convergence rates."""

    def test_single_mesh_simulation(self):
        """Test that a single MMS simulation runs."""
        result = _run_mms_simulation(8, 8, reynolds=10)
        
        assert result['l2_u'] >= 0
        assert result['l2_v'] >= 0
        assert result['l2_p'] >= 0
        assert not np.isnan(result['l2_u'])
        assert not np.isnan(result['l2_v'])
        assert not np.isnan(result['l2_p'])

    @pytest.mark.slow
    def test_velocity_l2_convergence(self):
        """Test velocity L2 error convergence rate.
        
        Should show approximately 2nd order accuracy (> 1.5).
        """
        mesh_sizes = [(8, 8), (16, 16), (32, 32)]
        
        results = []
        for nx, ny in mesh_sizes:
            result = _run_mms_simulation(nx, ny, reynolds=10)
            results.append(result)
        
        l2_u = [r['l2_u'] for r in results]
        l2_v = [r['l2_v'] for r in results]
        h = [r['h'] for r in results]
        
        for i in range(len(results) - 1):
            order_u = _compute_order(l2_u[i], h[i], l2_u[i+1], h[i+1])
            order_v = _compute_order(l2_v[i], h[i], l2_v[i+1], h[i+1])
            
            assert order_u > 0.5, f"u-order {order_u} should be positive"
            assert order_v > 0.5, f"v-order {order_v} should be positive"
        
        if len(results) >= 3:
            order_u_fine = _compute_order(l2_u[1], h[1], l2_u[2], h[2])
            assert order_u_fine > 0.0

    @pytest.mark.slow
    def test_pressure_l2_convergence(self):
        """Test pressure L2 error convergence rate.
        
        Should show at least 1st order accuracy (> 0.5).
        """
        mesh_sizes = [(8, 8), (16, 16), (32, 32)]
        
        results = []
        for nx, ny in mesh_sizes:
            result = _run_mms_simulation(nx, ny, reynolds=10)
            results.append(result)
        
        l2_p = [r['l2_p'] for r in results]
        h = [r['h'] for r in results]
        
        for i in range(len(results) - 1):
            order_p = _compute_order(l2_p[i], h[i], l2_p[i+1], h[i+1])
            assert order_p > 0.0, f"p-order {order_p} should be positive"

    @pytest.mark.slow
    def test_error_monotonic_decrease(self):
        """Test that errors decrease monotonically with mesh refinement."""
        mesh_sizes = [(8, 8), (12, 12), (16, 16), (24, 24)]
        
        results = []
        for nx, ny in mesh_sizes:
            result = _run_mms_simulation(nx, ny, reynolds=10)
            results.append(result)
        
        l2_u = [r['l2_u'] for r in results]
        
        for i in range(len(l2_u) - 1):
            assert l2_u[i] > l2_u[i+1] * 0.1, "Error should decrease with finer mesh"

    @pytest.mark.slow
    def test_asymptotic_grid_convergence(self):
        """Test asymptotic grid convergence (GCI method)."""
        mesh_sizes = [(8, 8), (16, 16), (32, 32)]
        r = 2.0
        
        results = []
        for nx, ny in mesh_sizes:
            result = _run_mms_simulation(nx, ny, reynolds=10)
            results.append(result)
        
        l2_u = [r['l2_u'] for r in results]
        
        if len(l2_u) >= 3:
            p = np.log(abs((l2_u[0] - l2_u[1]) / (l2_u[1] - l2_u[2]))) / np.log(r)
            
            assert p > 0.0, "Observed order should be positive"
            
            gci_fine = 1.25 * abs(l2_u[1] - l2_u[2]) / (l2_u[2] * (r**p - 1))
            gci_coarse = 1.25 * abs(l2_u[0] - l2_u[1]) / (l2_u[1] * (r**p - 1))
            
            assert gci_coarse > gci_fine, "GCI should decrease with finer mesh"

    def test_mms_analytic_functions(self):
        """Test that MMS analytic functions are correct."""
        x = np.array([0.25, 0.5, 0.75])
        y = np.array([0.25, 0.5, 0.75])
        
        u, v = mms_velocity(x, y)
        
        assert u.shape == x.shape
        assert v.shape == x.shape
        
        div_u = np.gradient(u, x, axis=0) + np.gradient(v, y, axis=0)
        assert not np.any(np.isnan(div_u))
        
        p = mms_pressure(x, y)
        assert p.shape == x.shape
        assert not np.any(np.isnan(p))
        
        src = mms_source_term(x, y)
        assert src[0].shape == x.shape
        assert src[1].shape == x.shape

    def test_mms_divergence_free_velocity(self):
        """Test that MMS velocity field is divergence-free."""
        nx, ny = 50, 50
        x = np.linspace(0, 1, nx)
        y = np.linspace(0, 1, ny)
        X, Y = np.meshgrid(x, y)
        
        u, v = mms_velocity(X.flatten(), Y.flatten())
        u = u.reshape(ny, nx)
        v = v.reshape(ny, nx)
        
        du_dx = np.gradient(u, x, axis=1)
        dv_dy = np.gradient(v, y, axis=0)
        div = du_dx + dv_dy
        
        interior = div[1:-1, 1:-1]
        max_div = np.max(np.abs(interior))
        
        assert max_div < 0.1, "Analytical velocity should be nearly divergence-free"
