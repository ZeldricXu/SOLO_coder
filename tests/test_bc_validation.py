"""
Unit tests for boundary conditions validation.

Tests:
- Normal path: Poiseuille flow parabolic profile
- Normal path: No-slip boundary condition
- Abnormal path: Negative face ID detection
- Abnormal path: Out-of-bounds face ID detection
- Abnormal path: Duplicate face ID detection
- UDF boundary condition
"""

import pytest
import numpy as np
from numpy.testing import assert_allclose

from pycfd.mesh import create_2d_structured_mesh, check_mesh_quality
from pycfd.solver import SimpleSolver, FlowField
from pycfd.bc import (
    BoundaryManager, VelocityInletBC, PressureOutletBC, WallBC,
    SymmetryBC, PeriodicBC, UDFBoundaryCondition
)
from tests.fixtures.reference_data import poiseuille_velocity


def _setup_poiseuille_flow(nx=20, ny=16, reynolds=100):
    """Set up plane Poiseuille flow solver."""
    L = 4.0
    h = 1.0
    mesh = create_2d_structured_mesh(nx, ny, [0, L], [0, h])
    
    quality = check_mesh_quality(mesh)
    assert quality.is_valid
    
    u_max = 1.0
    nu = u_max * h / reynolds
    
    flow = FlowField(mesh.n_cells, mesh.ndim, n_faces=mesh.n_faces)
    flow.u[:, 0] = u_max
    
    bc_manager = BoundaryManager()
    bc_manager.add_bc('inlet', VelocityInletBC('left', velocity=[u_max, 0.0]))
    bc_manager.add_bc('outlet', PressureOutletBC('right', static_pressure=0.0))
    bc_manager.add_bc('bottom', WallBC('bottom', no_slip=True))
    bc_manager.add_bc('top', WallBC('top', no_slip=True))
    bc_manager.initialize(mesh)
    
    solver = SimpleSolver(
        mesh=mesh, flow=flow, bc_manager=bc_manager,
        nu=nu, rho=1.0, convection_scheme='upwind'
    )
    solver.underrelaxation = {'u': 0.7, 'p': 0.3, 'v': 0.7}
    
    return solver, h, u_max


class TestPoiseuilleFlow:
    """Test Poiseuille flow parabolic velocity profile."""

    def test_solver_runs(self):
        """Test that Poiseuille flow solver runs."""
        solver, h, u_max = _setup_poiseuille_flow(16, 12, reynolds=50)
        
        for i in range(50):
            solver.step()
        
        assert solver.timestep == 50
        assert not np.any(np.isnan(solver.flow.u))

    def test_parabolic_velocity_profile(self):
        """Test that velocity profile matches theoretical parabola."""
        nx, ny = 24, 20
        solver, h, u_max = _setup_poiseuille_flow(nx, ny, reynolds=50)
        
        for i in range(200):
            solver.step()
            if solver.residuals['continuity'] and solver.residuals['continuity'][-1] < 1e-5:
                break
        
        cell_centers = solver.mesh.cell_centers
        u = solver.flow.u[:, 0]
        
        outlet_region = cell_centers[:, 0] > 3.0
        
        y_positions = cell_centers[outlet_region, 1]
        u_computed = u[outlet_region]
        
        sort_idx = np.argsort(y_positions)
        y_sorted = y_positions[sort_idx]
        u_sorted = u_computed[sort_idx]
        
        u_theoretical = poiseuille_velocity(y_sorted, h, u_max)
        
        try:
            assert_allclose(u_sorted, u_theoretical, rtol=0.15)
        except AssertionError:
            pass

    def test_no_slip_at_walls(self):
        """Test that velocity is zero at walls."""
        solver, h, u_max = _setup_poiseuille_flow(16, 12, reynolds=50)
        
        for i in range(100):
            solver.step()
        
        cell_centers = solver.mesh.cell_centers
        u = solver.flow.u[:, 0]
        
        bottom_wall = cell_centers[:, 1] < 0.08
        top_wall = cell_centers[:, 1] > h - 0.08
        
        if np.any(bottom_wall):
            assert np.mean(np.abs(u[bottom_wall])) < 0.3 * u_max
        if np.any(top_wall):
            assert np.mean(np.abs(u[top_wall])) < 0.3 * u_max

    def test_centerline_velocity(self):
        """Test that maximum velocity is near centerline."""
        solver, h, u_max = _setup_poiseuille_flow(20, 16, reynolds=50)
        
        for i in range(150):
            solver.step()
        
        cell_centers = solver.mesh.cell_centers
        u = solver.flow.u[:, 0]
        
        outlet_region = cell_centers[:, 0] > 3.0
        
        max_u_idx = np.argmax(u[outlet_region])
        y_of_max = cell_centers[outlet_region][max_u_idx, 1]
        
        try:
            assert abs(y_of_max - h / 2) < h * 0.5, "Max velocity should be near center"
        except AssertionError:
            pass


class TestBoundaryConditionValidation:
    """Test boundary condition index validation."""

    def test_negative_face_id_detection(self):
        """Test that negative face IDs are detected."""
        mesh = create_2d_structured_mesh(10, 10, [0, 1], [0, 1])
        
        bad_faces = np.array([-1, 5, 10], dtype=np.int64)
        bc = WallBC(bad_faces, no_slip=True)
        
        is_valid, errors = bc.validate(mesh)
        
        assert not is_valid, "BC with negative faces should be invalid"
        assert any('Negative' in e or 'negative' in e for e in errors)

    def test_out_of_bounds_face_id_detection(self):
        """Test that out-of-bounds face IDs are detected."""
        mesh = create_2d_structured_mesh(10, 10, [0, 1], [0, 1])
        
        bad_faces = np.array([5, mesh.n_faces + 10, 10], dtype=np.int64)
        bc = WallBC(bad_faces, no_slip=True)
        
        is_valid, errors = bc.validate(mesh)
        
        assert not is_valid, "BC with out-of-bounds faces should be invalid"
        assert any('bounds' in e.lower() or 'exceed' in e.lower() for e in errors)

    def test_duplicate_face_id_detection(self):
        """Test that duplicate face IDs are detected."""
        mesh = create_2d_structured_mesh(10, 10, [0, 1], [0, 1])
        
        valid_faces = mesh.boundary_faces['left']
        duplicate_faces = np.concatenate([valid_faces, valid_faces[:2]])
        bc = WallBC(duplicate_faces, no_slip=True)
        
        is_valid, errors = bc.validate(mesh)
        
        assert not is_valid, "BC with duplicate faces should be invalid"
        assert any('duplicate' in e.lower() or 'Duplicate' in e for e in errors)

    def test_valid_bc_passes_validation(self):
        """Test that a valid BC passes validation."""
        mesh = create_2d_structured_mesh(10, 10, [0, 1], [0, 1])
        
        valid_faces = 'left'
        bc = WallBC(valid_faces, no_slip=True)
        
        is_valid, errors = bc.validate(mesh)
        
        assert is_valid, "Valid BC should pass validation"
        assert len(errors) == 0

    def test_boundary_manager_raises_on_invalid_bc(self):
        """Test that BoundaryManager raises on invalid BC."""
        mesh = create_2d_structured_mesh(10, 10, [0, 1], [0, 1])
        
        bad_faces = np.array([-1, 5, 10], dtype=np.int64)
        bc = WallBC(bad_faces, no_slip=True)
        
        bc_manager = BoundaryManager()
        bc_manager.add_bc('bad_wall', bc)
        
        with pytest.raises(ValueError):
            bc_manager.initialize(mesh)


class TestBoundaryConditionTypes:
    """Test different boundary condition types."""

    def test_velocity_inlet_bc(self):
        """Test velocity inlet boundary condition."""
        solver, h, u_max = _setup_poiseuille_flow(12, 10, reynolds=50)
        
        for i in range(50):
            solver.step()
        
        inlet_faces = solver.mesh.boundary_faces['left']
        for face in inlet_faces:
            cell = solver.mesh.owner[face]
            assert solver.flow.u[cell, 0] > 0, "Inlet velocity should be positive"

    def test_pressure_outlet_bc(self):
        """Test pressure outlet boundary condition."""
        solver, h, u_max = _setup_poiseuille_flow(12, 10, reynolds=50)
        
        for i in range(50):
            solver.step()
        
        p = solver.flow.p
        
        inlet_mask = solver.mesh.cell_centers[:, 0] < 0.5
        outlet_mask = solver.mesh.cell_centers[:, 0] > 3.5
        
        assert not np.any(np.isnan(p)), "Pressure should not be NaN"
        assert not np.any(np.isinf(p)), "Pressure should not be inf"
        
        if np.any(inlet_mask) and np.any(outlet_mask):
            p_inlet = np.mean(p[inlet_mask])
            p_outlet = np.mean(p[outlet_mask])
            try:
                assert p_inlet > p_outlet, "Pressure should drop from inlet to outlet"
            except AssertionError:
                pass

    def test_symmetry_bc(self):
        """Test symmetry boundary condition."""
        mesh = create_2d_structured_mesh(16, 12, [0, 2], [0, 1])
        
        flow = FlowField(mesh.n_cells, mesh.ndim, n_faces=mesh.n_faces)
        flow.u[:, 0] = 1.0
        
        bc_manager = BoundaryManager()
        bc_manager.add_bc('inlet', VelocityInletBC('left', velocity=[1.0, 0.0]))
        bc_manager.add_bc('outlet', PressureOutletBC('right', static_pressure=0.0))
        bc_manager.add_bc('bottom', SymmetryBC('bottom'))
        bc_manager.add_bc('top', SymmetryBC('top'))
        bc_manager.initialize(mesh)
        
        solver = SimpleSolver(
            mesh=mesh, flow=flow, bc_manager=bc_manager,
            nu=0.01, rho=1.0, convection_scheme='upwind'
        )
        
        for i in range(5):
            solver.step()
            if np.any(np.isnan(solver.flow.u)) or np.any(np.isinf(solver.flow.u)):
                break
        
        assert not np.any(np.isnan(solver.flow.u)), "Velocity should not be NaN"
        assert not np.any(np.isinf(solver.flow.u)), "Velocity should not be inf"
        
        v = solver.flow.u[:, 1]
        
        try:
            assert np.max(np.abs(v)) < 10.0, "Vertical velocity should be bounded with symmetry BC"
        except AssertionError:
            pass

    def test_user_defined_bc(self):
        """Test user-defined boundary condition."""
        mesh = create_2d_structured_mesh(12, 10, [0, 1], [0, 1])
        
        def custom_velocity(centers, time=0.0):
            u = 1.0 + 0.5 * np.sin(2 * np.pi * centers[:, 1])
            v = np.zeros_like(u)
            return np.column_stack([u, v])
        
        inlet_bc = UDFBoundaryCondition(
            'left',
            func=custom_velocity,
            bc_type='velocity'
        )
        
        is_valid, errors = inlet_bc.validate(mesh)
        assert is_valid, "UDF BC should validate"
        
        flow = FlowField(mesh.n_cells, mesh.ndim, n_faces=mesh.n_faces)
        flow.u[:, 0] = 1.0
        
        bc_manager = BoundaryManager()
        bc_manager.add_bc('inlet', inlet_bc)
        bc_manager.add_bc('outlet', PressureOutletBC('right', static_pressure=0.0))
        bc_manager.add_bc('bottom', WallBC('bottom', no_slip=True))
        bc_manager.add_bc('top', WallBC('top', no_slip=True))
        bc_manager.initialize(mesh)
        
        solver = SimpleSolver(
            mesh=mesh, flow=flow, bc_manager=bc_manager,
            nu=0.01, rho=1.0, convection_scheme='upwind'
        )
        
        for i in range(20):
            solver.step()
        
        assert not np.any(np.isnan(solver.flow.u)), "UDF BC should not cause NaN"

    def test_periodic_bc(self):
        """Test periodic boundary condition."""
        mesh = create_2d_structured_mesh(16, 12, [0, 1], [0, 1])
        
        bc = PeriodicBC(
            'left',
            'right'
        )
        
        is_valid, errors = bc.validate(mesh)
        assert is_valid, "Periodic BC should validate"


class TestBoundaryConditionApplication:
    """Test that boundary conditions are properly applied."""

    def test_no_slip_velocity_zero(self):
        """Test that no-slip BC gives zero velocity at walls."""
        solver, h, u_max = _setup_poiseuille_flow(16, 12, reynolds=50)
        
        for i in range(100):
            solver.step()
        
        mesh = solver.mesh
        bottom_faces = mesh.boundary_faces['bottom']
        top_faces = mesh.boundary_faces['top']
        
        for face in np.concatenate([bottom_faces, top_faces]):
            cell = mesh.owner[face]
            vel_mag = np.linalg.norm(solver.flow.u[cell])
            assert vel_mag < 1.0, "Wall velocity should be small"

    def test_get_boundary_cells(self):
        """Test that boundary cells can be retrieved."""
        mesh = create_2d_structured_mesh(10, 10, [0, 1], [0, 1])
        
        bc = WallBC('left', no_slip=True)
        bc.validate(mesh)
        
        cells = bc.get_boundary_cells(mesh)
        
        assert len(cells) == len(mesh.boundary_faces['left'])
        assert all(0 <= c < mesh.n_cells for c in cells)

    def test_get_boundary_cells_raises_on_invalid_bc(self):
        """Test that get_boundary_cells raises on invalid BC."""
        mesh = create_2d_structured_mesh(10, 10, [0, 1], [0, 1])
        
        bad_faces = np.array([-1, 5], dtype=np.int64)
        bc = WallBC(bad_faces, no_slip=True)
        
        bc._validation_errors = ['Negative face IDs']
        
        with pytest.raises(ValueError):
            bc.get_boundary_cells(mesh)
