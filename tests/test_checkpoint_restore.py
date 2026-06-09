"""
Unit tests for checkpoint restore functionality.

Tests:
- Solver state save and restore
- Continuous vs interrupted simulation equivalence
- Checkpoint file creation and cleanup
- Auto-interval checkpointing
- Residual history preservation
"""

import pytest
import numpy as np
from numpy.testing import assert_allclose
import tempfile
import os
import shutil

from pycfd.mesh import create_2d_structured_mesh
from pycfd.solver import SimpleSolver, FlowField
from pycfd.bc import BoundaryManager, VelocityInletBC, PressureOutletBC, WallBC
from pycfd.scheduler import CheckpointManager


def _setup_solver(nx=20, ny=16, reynolds=50):
    """Set up a simple channel flow solver for checkpoint testing."""
    mesh = create_2d_structured_mesh(nx, ny, [0, 2], [0, 1])
    
    u_inlet = 1.0
    nu = u_inlet * 1.0 / reynolds
    
    flow = FlowField(mesh.n_cells, mesh.ndim, n_faces=mesh.n_faces)
    flow.u[:, 0] = u_inlet
    
    bc_manager = BoundaryManager()
    bc_manager.add_bc('inlet', VelocityInletBC('left', velocity=[u_inlet, 0.0]))
    bc_manager.add_bc('outlet', PressureOutletBC('right', static_pressure=0.0))
    bc_manager.add_bc('bottom', WallBC('bottom', no_slip=True))
    bc_manager.add_bc('top', WallBC('top', no_slip=True))
    bc_manager.initialize(mesh)
    
    solver = SimpleSolver(
        mesh=mesh, flow=flow, bc_manager=bc_manager,
        nu=nu, rho=1.0, convection_scheme='upwind'
    )
    solver.underrelaxation = {'u': 0.6, 'p': 0.3, 'v': 0.6}
    
    return solver


class TestCheckpointManager:
    """Test CheckpointManager functionality."""

    def test_init(self):
        """Test CheckpointManager initialization."""
        with tempfile.TemporaryDirectory() as tmpdir:
            ckpt = CheckpointManager(
                checkpoint_dir=tmpdir,
                max_checkpoints=5,
                interval=10
            )
            
            assert ckpt.checkpoint_dir == tmpdir
            assert ckpt.max_checkpoints == 5
            assert ckpt.interval == 10
            assert os.path.exists(tmpdir)

    def test_repr(self):
        """Test CheckpointManager repr."""
        with tempfile.TemporaryDirectory() as tmpdir:
            ckpt = CheckpointManager(checkpoint_dir=tmpdir, interval=20)
            repr_str = repr(ckpt)
            
            assert 'CheckpointManager' in repr_str
            assert tmpdir in repr_str
            assert '20' in repr_str

    def test_save_and_load_state(self):
        """Test basic save and load of arbitrary state."""
        with tempfile.TemporaryDirectory() as tmpdir:
            ckpt = CheckpointManager(checkpoint_dir=tmpdir)
            
            state = {
                'data': {
                    'array': np.array([1, 2, 3]),
                    'scalar': 42.0,
                    'string': 'test'
                },
                'step': 100,
                'time': 0.5
            }
            
            filename = ckpt.save(state['data'], step=100, time=0.5)
            
            assert os.path.exists(filename)
            
            loaded = ckpt.load(filename)
            
            assert_allclose(loaded['data']['array'], state['data']['array'])
            assert loaded['data']['scalar'] == state['data']['scalar']
            assert loaded['data']['string'] == state['data']['string']
            assert loaded['step'] == 100
            assert abs(loaded['time'] - 0.5) < 1e-10

    def test_save_checkpoint(self):
        """Test save_checkpoint with solver state."""
        solver = _setup_solver(12, 10)
        
        for i in range(25):
            solver.step()
        
        with tempfile.TemporaryDirectory() as tmpdir:
            ckpt = CheckpointManager(checkpoint_dir=tmpdir)
            
            filename = ckpt.save_checkpoint(solver, step=25)
            
            assert os.path.exists(filename)
            
            loaded = ckpt.load(filename)
            
            assert 'flow' in loaded['data']
            assert 'solver' in loaded['data']
            assert 'residuals' in loaded['data']
            assert loaded['step'] == 25

    def test_load_checkpoint(self):
        """Test load_checkpoint restores solver state."""
        solver1 = _setup_solver(12, 10)
        
        for i in range(20):
            solver1.step()
        
        with tempfile.TemporaryDirectory() as tmpdir:
            ckpt = CheckpointManager(checkpoint_dir=tmpdir)
            
            ckpt.save_checkpoint(solver1, step=20)
            
            solver2 = _setup_solver(12, 10)
            
            assert solver2.timestep == 0
            
            ckpt.load_checkpoint(solver2, step=20)
            
            assert solver2.timestep == 20
            assert_allclose(solver2.flow.u, solver1.flow.u, rtol=1e-10)
            assert_allclose(solver2.flow.p, solver1.flow.p, rtol=1e-10)

    def test_checkpoint_restore_equivalence(self):
        """Test that continuous run = interrupted run.
        
        Run solver for 50 steps, save at step 25, restore to new solver
        and run 25 more steps. Results should match 50 continuous steps
        within machine precision.
        """
        solver_continuous = _setup_solver(16, 12, reynolds=50)
        
        for i in range(50):
            solver_continuous.step()
        
        solver_interrupted = _setup_solver(16, 12, reynolds=50)
        
        with tempfile.TemporaryDirectory() as tmpdir:
            ckpt = CheckpointManager(checkpoint_dir=tmpdir)
            
            for i in range(25):
                solver_interrupted.step()
            
            ckpt.save_checkpoint(solver_interrupted, step=25)
            
            solver_restored = _setup_solver(16, 12, reynolds=50)
            ckpt.load_checkpoint(solver_restored, step=25)
            
            for i in range(25):
                solver_restored.step()
            
            assert solver_restored.timestep == 50
            
            assert_allclose(solver_restored.flow.u, solver_continuous.flow.u, rtol=1e-10, atol=1e-12)
            assert_allclose(solver_restored.flow.p, solver_continuous.flow.p, rtol=1e-10, atol=1e-12)
            
            if hasattr(solver_continuous.flow, 'u_prev'):
                assert_allclose(solver_restored.flow.u_prev, solver_continuous.flow.u_prev, rtol=1e-10, atol=1e-12)

    def test_checkpoint_restores_underrelaxation(self):
        """Test that under-relaxation factors are restored."""
        solver = _setup_solver(12, 10)
        
        solver.underrelaxation['u'] = 0.3
        solver.underrelaxation['p'] = 0.1
        
        for i in range(10):
            solver.step()
        
        with tempfile.TemporaryDirectory() as tmpdir:
            ckpt = CheckpointManager(checkpoint_dir=tmpdir)
            ckpt.save_checkpoint(solver, step=10)
            
            solver2 = _setup_solver(12, 10)
            ckpt.load_checkpoint(solver2, step=10)
            
            assert solver2.underrelaxation['u'] == 0.3
            assert solver2.underrelaxation['p'] == 0.1

    def test_save_if_needed(self):
        """Test save_if_needed with interval."""
        solver = _setup_solver(12, 10)
        
        with tempfile.TemporaryDirectory() as tmpdir:
            ckpt = CheckpointManager(checkpoint_dir=tmpdir, interval=5)
            
            saved_files = []
            
            for i in range(15):
                solver.step()
                result = ckpt.save_if_needed(solver)
                if result is not None:
                    saved_files.append(result)
            
            assert len(saved_files) == 3
            assert os.path.exists(saved_files[0])

    def test_list_checkpoints(self):
        """Test listing available checkpoints."""
        solver = _setup_solver(12, 10)
        
        with tempfile.TemporaryDirectory() as tmpdir:
            ckpt = CheckpointManager(checkpoint_dir=tmpdir, interval=5)
            
            for i in range(15):
                solver.step()
                ckpt.save_if_needed(solver)
            
            available = ckpt.list_checkpoints()
            
            assert len(available) == 3
            assert 5 in available
            assert 10 in available
            assert 15 in available

    def test_max_checkpoints_cleanup(self):
        """Test that old checkpoints are cleaned up when max is reached."""
        solver = _setup_solver(12, 10)
        
        with tempfile.TemporaryDirectory() as tmpdir:
            ckpt = CheckpointManager(checkpoint_dir=tmpdir, max_checkpoints=3, interval=2)
            
            for i in range(12):
                solver.step()
                ckpt.save_if_needed(solver)
            
            available = ckpt.list_checkpoints()
            
            assert len(available) <= 3

    def test_residual_history_preserved(self):
        """Test that residual history is preserved across checkpoint."""
        solver = _setup_solver(12, 10)
        
        for i in range(20):
            solver.step()
        
        n_residuals = len(solver.residuals['continuity'])
        
        with tempfile.TemporaryDirectory() as tmpdir:
            ckpt = CheckpointManager(checkpoint_dir=tmpdir)
            ckpt.save_checkpoint(solver, step=20)
            
            solver2 = _setup_solver(12, 10)
            ckpt.load_checkpoint(solver2, step=20)
            
            assert 'continuity' in solver2.residuals
            assert len(solver2.residuals['continuity']) == n_residuals
            assert_allclose(
                solver2.residuals['continuity'],
                solver.residuals['continuity'],
                rtol=1e-10
            )

    def test_load_nonexistent_checkpoint(self):
        """Test that loading non-existent checkpoint raises error."""
        solver = _setup_solver(12, 10)
        
        with tempfile.TemporaryDirectory() as tmpdir:
            ckpt = CheckpointManager(checkpoint_dir=tmpdir)
            
            with pytest.raises(FileNotFoundError):
                ckpt.load_checkpoint(solver, step=999)

    def test_checkpoint_file_format(self):
        """Test checkpoint file format."""
        solver = _setup_solver(12, 10)
        
        for i in range(10):
            solver.step()
        
        with tempfile.TemporaryDirectory() as tmpdir:
            ckpt = CheckpointManager(checkpoint_dir=tmpdir)
            
            filename = ckpt.save_checkpoint(solver, step=10)
            
            assert filename.endswith('.h5') or filename.endswith('.pkl') or filename.endswith('.npz')
            assert 'step_00010' in filename

    def test_multiple_checkpoint_versions(self):
        """Test saving and loading multiple checkpoint versions."""
        solver = _setup_solver(12, 10)
        
        with tempfile.TemporaryDirectory() as tmpdir:
            ckpt = CheckpointManager(checkpoint_dir=tmpdir)
            
            for i in range(30):
                solver.step()
                if (i + 1) % 10 == 0:
                    ckpt.save_checkpoint(solver, step=i + 1)
            
            available = ckpt.list_checkpoints()
            assert 10 in available
            assert 20 in available
            assert 30 in available
            
            for step in [10, 20, 30]:
                solver2 = _setup_solver(12, 10)
                ckpt.load_checkpoint(solver2, step=step)
                assert solver2.timestep == step

    def test_checkpoint_ap_field(self):
        """Test that ap (momentum matrix diagonal) is saved and restored."""
        solver = _setup_solver(12, 10)
        
        for i in range(15):
            solver.step()
        
        ap_before = solver.flow.ap.copy()
        
        with tempfile.TemporaryDirectory() as tmpdir:
            ckpt = CheckpointManager(checkpoint_dir=tmpdir)
            ckpt.save_checkpoint(solver, step=15)
            
            solver2 = _setup_solver(12, 10)
            ckpt.load_checkpoint(solver2, step=15)
            
            assert_allclose(solver2.flow.ap, ap_before, rtol=1e-10, atol=1e-12)
