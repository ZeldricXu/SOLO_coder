"""Unit tests for Navier-Stokes solver module."""
import sys
import os
sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

import numpy as np
import pytest
from pycfd.mesh import create_2d_structured_mesh
from pycfd.solver import FlowField, SimpleSolver, PisoSolver
from pycfd.solver.convection import tvd_minmod, tvd_vanleer, tvd_superbee
from pycfd.bc import BoundaryManager, WallBC, VelocityInletBC, PressureOutletBC
from pycfd.turbulence import KEpsilonModel, KOmegaSSTModel

def test_flow_field():
    n_cells, ndim = 100, 2
    flow = FlowField(n_cells, ndim)
    assert flow.u.shape == (n_cells, ndim)
    assert flow.p.shape == (n_cells,)
    assert flow.grad_u.shape == (n_cells, ndim, ndim)

def test_tvd_limiters():
    r = np.linspace(-1, 4, 100)
    for limiter in [tvd_minmod, tvd_vanleer, tvd_superbee]:
        phi = limiter(r)
        assert np.all(phi >= 0)
        assert np.all(phi <= np.maximum(0, np.minimum(2, 2 * r)) + 1e-10)
        assert np.all(phi[r < 0] == 0)

def test_limiter_bounds():
    phi_minmod = tvd_minmod(1.0)
    assert np.isclose(phi_minmod, 1.0)
    phi_vanleer = tvd_vanleer(1.0)
    assert np.isclose(phi_vanleer, 1.0)
    phi_superbee = tvd_superbee(1.0)
    assert np.isclose(phi_superbee, 1.0)

def test_solver_creation():
    nx, ny = 10, 10
    mesh = create_2d_structured_mesh(nx, ny, [0, 1], [0, 1])
    flow = FlowField(mesh.n_cells, mesh.ndim)
    bc_manager = BoundaryManager()
    bc_manager.add_bc('top', WallBC(mesh.boundary_map.get('top', 0), velocity=[1.0, 0.0]))
    bc_manager.add_bc('bottom', WallBC(mesh.boundary_map.get('bottom', 1), velocity=[0.0, 0.0]))
    bc_manager.add_bc('left', WallBC(mesh.boundary_map.get('left', 2), velocity=[0.0, 0.0]))
    bc_manager.add_bc('right', WallBC(mesh.boundary_map.get('right', 3), velocity=[0.0, 0.0]))
    solver = SimpleSolver(
        mesh=mesh, flow=flow, bc_manager=bc_manager,
        nu=0.01, rho=1.0, convection_scheme='upwind'
    )
    assert solver.mesh is mesh
    assert solver.flow is flow

def test_single_iteration():
    nx, ny = 8, 8
    mesh = create_2d_structured_mesh(nx, ny, [0, 1], [0, 1])
    flow = FlowField(mesh.n_cells, mesh.ndim)
    flow.u[:, 0] = 1.0
    bc_manager = BoundaryManager()
    bc_manager.add_bc('left', VelocityInletBC(mesh.boundary_map.get('left', 2), velocity=[1.0, 0.0]))
    bc_manager.add_bc('right', PressureOutletBC(mesh.boundary_map.get('right', 3), pressure=0.0))
    bc_manager.add_bc('top', WallBC(mesh.boundary_map.get('top', 0), no_slip=True))
    bc_manager.add_bc('bottom', WallBC(mesh.boundary_map.get('bottom', 1), no_slip=True))
    solver = SimpleSolver(
        mesh=mesh, flow=flow, bc_manager=bc_manager,
        nu=0.01, rho=1.0
    )
    residuals = solver.step()
    assert 'u_mom' in residuals
    assert 'p' in residuals
    assert 'continuity' in residuals
    assert all(v >= 0 for v in residuals.values())

def test_k_epsilon_model():
    n_cells = 100
    k = np.full(n_cells, 0.01)
    epsilon = np.full(n_cells, 0.001)
    nu = np.full(n_cells, 1e-5)
    grad_u = np.zeros((n_cells, 2, 2))
    grad_u[:, 0, 1] = 0.5
    model = KEpsilonModel()
    nu_t = model.compute_eddy_viscosity(k, epsilon)
    assert nu_t.shape == (n_cells,)
    assert np.all(nu_t > 0)
    P = model.compute_production(grad_u, nu_t, k)
    assert P.shape == (n_cells,)

def test_k_omega_sst_model():
    n_cells = 100
    k = np.full(n_cells, 0.01)
    omega = np.full(n_cells, 10.0)
    nu = np.full(n_cells, 1e-5)
    grad_u = np.zeros((n_cells, 2, 2))
    grad_u[:, 0, 1] = 1.0
    y = np.linspace(0.001, 0.5, n_cells)
    model = KOmegaSSTModel()
    S_mag = np.sqrt(2 * np.sum(grad_u[:, 0, 1] ** 2)) * np.ones(n_cells)
    nu_t = model.compute_eddy_viscosity(k, omega, S_mag)
    assert nu_t.shape == (n_cells,)
    F1 = model._F1(k, omega, nu, y)
    assert F1.shape == (n_cells,)
    assert np.all((F1 >= 0) & (F1 <= 1))
    F2 = model._F2(k, omega, nu, y)
    assert F2.shape == (n_cells,)
    assert np.all((F2 >= 0) & (F2 <= 1))

def test_pressure_correction():
    nx, ny = 6, 6
    mesh = create_2d_structured_mesh(nx, ny, [0, 1], [0, 1])
    flow = FlowField(mesh.n_cells, mesh.ndim)
    flow.u.fill(0.1)
    flow.p.fill(0.0)
    bc_manager = BoundaryManager()
    for bid in [0, 1, 2, 3]:
        bc_manager.add_bc(f'wall_{bid}', WallBC(bid, no_slip=True))
    solver = SimpleSolver(
        mesh=mesh, flow=flow, bc_manager=bc_manager,
        nu=0.01, rho=1.0
    )
    old_continuity = np.mean(np.abs(solver._compute_continuity_residual()))
    solver._assemble_pressure_correction_matrix()
    dp = np.zeros(mesh.n_cells)
    if solver.p_matrix is not None:
        solver.p_matrix, b_p = solver._assemble_pressure_correction_matrix()
        from scipy.sparse.linalg import spsolve
        dp = spsolve(solver.p_matrix, b_p)
    flow.p += solver.alpha_p * dp
    solver._correct_velocity(dp)
    new_continuity = np.mean(np.abs(solver._compute_continuity_residual()))
    assert new_continuity <= old_continuity

if __name__ == '__main__':
    test_flow_field()
    test_tvd_limiters()
    test_limiter_bounds()
    test_solver_creation()
    test_single_iteration()
    test_k_epsilon_model()
    test_k_omega_sst_model()
    print("All solver tests passed!")
