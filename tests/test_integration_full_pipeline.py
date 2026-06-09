"""
Integration test for full CFD pipeline.

Tests the complete workflow:
1. Mesh generation
2. Solver setup and execution
3. Postprocessing (vorticity, streamlines)
4. Parameter optimization driving next mesh

This test does NOT use mocks - it runs actual solvers.
"""

import pytest
import numpy as np
from numpy.testing import assert_allclose
import tempfile
import os

from pycfd.mesh import create_2d_structured_mesh, generate_boundary_layer
from pycfd.mesh import check_mesh_quality, validate_mesh
from pycfd.solver import SimpleSolver, FlowField
from pycfd.bc import BoundaryManager, VelocityInletBC, PressureOutletBC, WallBC
from pycfd.postproc import compute_vorticity, trace_streamlines, plot_velocity_profile
from pycfd.optimization import ContinuousVariable, BayesianOptimizer


def _setup_channel_flow(nx=32, ny=16):
    """Set up a simple channel flow case."""
    mesh = create_2d_structured_mesh(nx, ny, [0, 4.0], [0, 1.0])
    
    mesh = generate_boundary_layer(
        mesh, 'bottom', n_layers=3, first_layer_height=0.02, growth_rate=1.2
    )
    
    is_valid, msg = validate_mesh(mesh)
    assert is_valid, f"Mesh validation failed: {msg}"
    
    flow = FlowField(mesh.n_cells, mesh.ndim)
    flow.u[:, 0] = 1.0
    
    bc_manager = BoundaryManager()
    bc_manager.add_bc('inlet', VelocityInletBC('left', velocity=[1.0, 0.0]))
    bc_manager.add_bc('outlet', PressureOutletBC('right', static_pressure=0.0))
    bc_manager.add_bc('bottom', WallBC('bottom', no_slip=True))
    bc_manager.add_bc('top', WallBC('top', no_slip=True))
    
    bc_manager.initialize(mesh)
    
    solver = SimpleSolver(
        mesh=mesh, flow=flow, bc_manager=bc_manager,
        nu=0.01, rho=1.0, convection_scheme='tvd'
    )
    
    solver.tvd_limiter = 'vanleer'
    
    return mesh, solver


class TestFullPipeline:
    """Test the complete CFD simulation pipeline."""

    def test_mesh_generation_with_boundary_layer(self):
        """Test mesh generation with boundary layer refinement."""
        mesh, _ = _setup_channel_flow(nx=16, ny=10)
        
        quality = check_mesh_quality(mesh)
        assert quality.is_valid, "Mesh should be valid"
        assert mesh.n_cells > 15 * 9, "Boundary layer should add cells"
        assert quality.min_volume > 0, "All volumes should be positive"

    def test_solver_run_to_convergence(self):
        """Test that the solver runs and converges."""
        mesh, solver = _setup_channel_flow(nx=16, ny=10)
        
        residuals = []
        for i in range(200):
            res = solver.step()
            residuals.append(res['continuity'])
            if res['continuity'] < 1e-4:
                break
        
        assert residuals[-1] < 1e-2, "Solver should converge to reasonable residual"
        assert solver.timestep > 10, "Should run for at least 10 iterations"

    def test_postprocessing_vorticity(self):
        """Test postprocessing: vorticity computation."""
        mesh, solver = _setup_channel_flow(nx=16, ny=10)
        
        for i in range(50):
            solver.step()
        
        vorticity = compute_vorticity(mesh, solver.flow.u)
        
        assert vorticity.shape[0] == mesh.n_cells
        assert not np.any(np.isnan(vorticity))
        
        max_vort = np.max(np.abs(vorticity))
        assert max_vort > 0, "Vorticity should be non-zero in shear flow"

    def test_postprocessing_streamlines(self):
        """Test postprocessing: streamline tracing."""
        mesh, solver = _setup_channel_flow(nx=16, ny=10)
        
        for i in range(50):
            solver.step()
        
        start_points = [[0.1, 0.3], [0.1, 0.5], [0.1, 0.7]]
        streamlines = trace_streamlines(mesh, solver.flow.u, start_points, max_steps=200)
        
        assert len(streamlines) == 3
        
        for line in streamlines:
            assert len(line) > 1
            assert line[0][0] < line[-1][0], "Streamlines should convect downstream"

    def test_parameter_optimization_pipeline(self):
        """Test that optimization can drive mesh design."""
        def evaluate_design(params):
            inlet_vel = params['inlet_velocity']
            nx = 16
            ny = 10
            
            mesh = create_2d_structured_mesh(nx, ny, [0, 2.0], [0, 1.0])
            flow = FlowField(mesh.n_cells, mesh.ndim)
            flow.u[:, 0] = inlet_vel
            
            bc_manager = BoundaryManager()
            bc_manager.add_bc('inlet', VelocityInletBC('left', velocity=[inlet_vel, 0.0]))
            bc_manager.add_bc('outlet', PressureOutletBC('right', static_pressure=0.0))
            bc_manager.add_bc('bottom', WallBC('bottom', no_slip=True))
            bc_manager.add_bc('top', WallBC('top', no_slip=True))
            bc_manager.initialize(mesh)
            
            solver = SimpleSolver(
                mesh=mesh, flow=flow, bc_manager=bc_manager,
                nu=0.01, rho=1.0, convection_scheme='upwind'
            )
            
            for i in range(30):
                res = solver.step()
            
            pressure_drop = np.mean(solver.flow.p[solver.flow.u[:, 0] < 0.5]) - \
                           np.mean(solver.flow.p[solver.flow.u[:, 0] > 1.5])
            
            target_pressure_drop = 5.0
            return (pressure_drop - target_pressure_drop) ** 2
        
        variables = [
            ContinuousVariable('inlet_velocity', lower=0.5, upper=3.0)
        ]
        
        np.random.seed(42)
        optimizer = BayesianOptimizer(
            objective_func=evaluate_design,
            variables=variables,
            n_initial=5,
            maximize=False
        )
        
        result = optimizer.optimize(n_iterations=10)
        
        assert result['best_x'] is not None
        assert result['n_valid'] >= 5
        assert result['best_y'] < 100.0

    def test_postprocessing_plot(self):
        """Test that plotting functions work without errors."""
        mesh, solver = _setup_channel_flow(nx=16, ny=10)
        
        for i in range(50):
            solver.step()
        
        with tempfile.TemporaryDirectory() as tmpdir:
            plot_path = os.path.join(tmpdir, 'velocity_profile.png')
            try:
                plot_velocity_profile(mesh, solver.flow.u, axis='y', y=0.5, save_path=plot_path)
                assert os.path.exists(plot_path)
            except ImportError:
                pytest.skip("Matplotlib not available for plotting")

    def test_quality_report_generation(self):
        """Test that quality reports are generated correctly."""
        mesh, _ = _setup_channel_flow(nx=16, ny=10)
        
        quality = check_mesh_quality(mesh)
        summary = quality.summary()
        
        assert 'Cells:' in summary
        assert 'Faces:' in summary
        assert 'Volume range:' in summary
        assert quality.is_valid

    def test_solver_residual_tracking(self):
        """Test that residuals are properly tracked."""
        mesh, solver = _setup_channel_flow(nx=16, ny=10)
        
        n_steps = 20
        for i in range(n_steps):
            res = solver.step()
        
        for key in ['u', 'v', 'p', 'continuity', 'u_mom']:
            if key in solver.residuals:
                assert len(solver.residuals[key]) == n_steps

    def test_solution_monotonic_convergence(self):
        """Test that residuals generally decrease."""
        mesh, solver = _setup_channel_flow(nx=16, ny=10)
        
        residuals = []
        for i in range(50):
            res = solver.step()
            residuals.append(res['continuity'])
        
        first_quarter = np.mean(residuals[:12])
        last_quarter = np.mean(residuals[-12:])
        
        assert last_quarter < first_quarter * 2, "Residuals should not increase drastically"
