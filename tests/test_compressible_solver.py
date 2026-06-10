import numpy as np
import pytest
import sys
import os

sys.path.insert(0, os.path.join(os.path.dirname(__file__), '..'))

from pycfd.mesh.structured import StructuredMesh
from pycfd.bc.boundary import BoundaryManager, FarfieldBC, SupersonicInletBC, SupersonicOutletBC
from pycfd.solver import (
    CompressibleSolver, CompressibleFlowField,
    roe_flux, ausm_plus_flux,
    conservative_to_primitive, primitive_to_conservative,
    compute_speed_of_sound, GAMMA, GAS_CONSTANT
)


class TestFluxSchemes:
    """Test numerical flux schemes (Roe and AUSM+)."""
    
    def test_primitive_conservative_conversion(self):
        """Test conversion between primitive and conservative variables."""
        rho = 1.225
        u = np.array([10.0, 2.0])
        p = 101325.0
        
        Q = primitive_to_conservative(rho, u, p)
        
        assert Q[0] == pytest.approx(rho, rel=1e-10)
        assert Q[1] == pytest.approx(rho * u[0], rel=1e-10)
        assert Q[2] == pytest.approx(rho * u[1], rel=1e-10)
        
        rho_out, u_out, p_out = conservative_to_primitive(Q)
        
        assert rho_out == pytest.approx(rho, rel=1e-10)
        assert u_out == pytest.approx(u, rel=1e-10)
        assert p_out == pytest.approx(p, rel=1e-10)
    
    def test_speed_of_sound(self):
        """Test speed of sound calculation."""
        rho = 1.225
        p = 101325.0
        c = compute_speed_of_sound(rho, p)
        expected = np.sqrt(GAMMA * p / rho)
        assert c == pytest.approx(expected, rel=1e-10)
    
    def test_roe_flux_supersonic(self):
        """Test Roe flux for supersonic flow."""
        rho_left, rho_right = 1.0, 0.125
        p_left, p_right = 1.0, 0.1
        u_left = np.array([0.75, 0.0])
        u_right = np.array([0.0, 0.0])
        
        Q_left = primitive_to_conservative(rho_left, u_left, p_left)
        Q_right = primitive_to_conservative(rho_right, u_right, p_right)
        
        normal = np.array([1.0, 0.0])
        
        flux = roe_flux(Q_left, Q_right, normal)
        
        assert len(flux) == 4
        assert not np.any(np.isnan(flux))
        assert not np.any(np.isinf(flux))
    
    def test_ausm_plus_flux_supersonic(self):
        """Test AUSM+ flux for supersonic flow."""
        rho_left, rho_right = 1.0, 0.125
        p_left, p_right = 1.0, 0.1
        u_left = np.array([0.75, 0.0])
        u_right = np.array([0.0, 0.0])
        
        Q_left = primitive_to_conservative(rho_left, u_left, p_left)
        Q_right = primitive_to_conservative(rho_right, u_right, p_right)
        
        normal = np.array([1.0, 0.0])
        
        flux = ausm_plus_flux(Q_left, Q_right, normal)
        
        assert len(flux) == 4
        assert not np.any(np.isnan(flux))
        assert not np.any(np.isinf(flux))
    
    def test_flux_consistency(self):
        """Test that Roe and AUSM+ give similar results for subsonic flow."""
        rho = 1.225
        p = 101325.0
        u = np.array([10.0, 0.0])
        
        Q_left = primitive_to_conservative(rho, u, p)
        Q_right = primitive_to_conservative(rho * 0.99, u * 1.01, p * 0.99)
        
        normal = np.array([1.0, 0.0])
        
        flux_roe = roe_flux(Q_left, Q_right, normal)
        flux_ausm = ausm_plus_flux(Q_left, Q_right, normal)
        
        assert np.allclose(flux_roe[0], flux_ausm[0], rtol=0.5)


class TestCompressibleFlowField:
    """Test CompressibleFlowField class."""
    
    def test_initialization(self):
        """Test flow field initialization."""
        n_cells = 100
        ndim = 2
        n_faces = 200
        
        field = CompressibleFlowField(n_cells, ndim, n_faces)
        
        assert field.n_cells == n_cells
        assert field.ndim == ndim
        assert field.n_vars == 4
        
        assert field.Q.shape == (n_cells, 4)
        assert field.rho.shape == (n_cells,)
        assert field.u.shape == (n_cells, 2)
        assert field.p.shape == (n_cells,)
        assert field.c.shape == (n_cells,)
    
    def test_uniform_initialization(self):
        """Test uniform initialization."""
        n_cells = 10
        field = CompressibleFlowField(n_cells, 2, 20)
        
        rho0 = 1.225
        u0 = 10.0
        p0 = 101325.0
        
        field.initialize(rho0, u0, p0)
        
        assert np.all(field.rho == rho0)
        assert np.all(field.u[:, 0] == u0)
        assert np.all(field.p == p0)
        assert np.all(field.c == pytest.approx(np.sqrt(GAMMA * p0 / rho0), rel=1e-10))
        assert np.all(field.Ma == pytest.approx(u0 / np.sqrt(GAMMA * p0 / rho0), rel=1e-10))
    
    def test_primitive_update(self):
        """Test primitive variable update."""
        n_cells = 5
        field = CompressibleFlowField(n_cells, 2, 10)
        
        for i in range(n_cells):
            rho = 1.0 + i * 0.1
            u = np.array([10.0 + i, 0.0])
            p = 1e5 + i * 1e3
            field.Q[i] = primitive_to_conservative(rho, u, p)
        
        field._update_primitive_variables()
        
        for i in range(n_cells):
            rho = 1.0 + i * 0.1
            u = np.array([10.0 + i, 0.0])
            p = 1e5 + i * 1e3
            
            assert field.rho[i] == pytest.approx(rho, rel=1e-10)
            assert field.u[i] == pytest.approx(u, rel=1e-10)
            assert field.p[i] == pytest.approx(p, rel=1e-10)


class TestSodShockTube:
    """Test Sod shock tube problem (1D Euler equations)."""
    
    def setup_method(self):
        """Set up Sod shock tube problem."""
        nx = 100
        x_range = (0.0, 1.0)
        y_range = (0.0, 0.01)
        self.mesh = StructuredMesh(nx, 2, x_range=x_range, y_range=y_range)
        
        self.bc_manager = BoundaryManager()
        
        self.rho_L, self.p_L = 1.0, 1.0
        self.rho_R, self.p_R = 0.125, 0.1
        self.u = np.array([0.0, 0.0])
        
        inlet_bc = SupersonicInletBC('left', self.rho_L, self.u, self.p_L)
        outlet_bc = SupersonicOutletBC('right')
        
        self.bc_manager.add_bc(inlet_bc)
        self.bc_manager.add_bc(outlet_bc)
        self.bc_manager.initialize(self.mesh)
    
    def test_initial_condition(self):
        """Test shock tube initial condition."""
        solver = CompressibleSolver(
            self.mesh, self.bc_manager,
            viscous=False, flux_scheme='roe',
            dt=1e-4
        )
        
        def init_ic(center):
            if center[0] < 0.5:
                return self.rho_L, self.u, self.p_L
            else:
                return self.rho_R, self.u, self.p_R
        
        solver.set_initial_condition(init_ic)
        
        left_cell = np.argmin(self.mesh.cell_centers[:, 0])
        right_cell = np.argmax(self.mesh.cell_centers[:, 0])
        
        assert solver.flow.rho[left_cell] == pytest.approx(self.rho_L, rel=1e-10)
        assert solver.flow.rho[right_cell] == pytest.approx(self.rho_R, rel=1e-10)
        assert solver.flow.p[left_cell] == pytest.approx(self.p_L, rel=1e-10)
        assert solver.flow.p[right_cell] == pytest.approx(self.p_R, rel=1e-10)
    
    def test_single_step(self):
        """Test single time step of shock tube."""
        solver = CompressibleSolver(
            self.mesh, self.bc_manager,
            viscous=False, flux_scheme='roe',
            dt=1e-5, local_time_stepping=False
        )
        
        def init_ic(center):
            if center[0] < 0.5:
                return self.rho_L, self.u, self.p_L
            else:
                return self.rho_R, self.u, self.p_R
        
        solver.set_initial_condition(init_ic)
        Q_initial = solver.flow.Q.copy()
        
        res = solver.step()
        
        assert not np.any(np.isnan(solver.flow.Q))
        assert not np.any(np.isinf(solver.flow.Q))
        
        assert 'Q0' in res
        assert res['Q0'] >= 0
    
    def test_shock_formation(self):
        """Test that shock wave forms after several steps."""
        solver = CompressibleSolver(
            self.mesh, self.bc_manager,
            viscous=False, flux_scheme='roe',
            dt=1e-5, local_time_stepping=False
        )
        
        def init_ic(center):
            if center[0] < 0.5:
                return self.rho_L, self.u, self.p_L
            else:
                return self.rho_R, self.u, self.p_R
        
        solver.set_initial_condition(init_ic)
        
        for _ in range(50):
            res = solver.step()
            if np.any(np.isnan(solver.flow.Q)):
                pytest.fail("Solution diverged with NaN")
        
        x_centers = self.mesh.cell_centers[:, 0]
        rho = solver.flow.rho
        
        interface_idx = np.argmin(np.abs(x_centers - 0.5))
        
        assert rho.max() > rho[interface_idx]
        assert rho.min() < rho[interface_idx]


class TestIsentropicVortex:
    """Test isentropic vortex convection (Euler equations exact solution)."""
    
    def setup_method(self):
        """Set up isentropic vortex problem."""
        nx = 50
        ny = 50
        x_range = (0.0, 10.0)
        y_range = (0.0, 10.0)
        self.mesh = StructuredMesh(nx, ny, x_range=x_range, y_range=y_range)
        
        self.rho_inf = 1.0
        self.p_inf = 1.0 / GAMMA
        self.u_inf = np.array([1.0, 0.0])
        self.M_inf = 0.5
        self.beta = 5.0
        self.x_c = np.array([5.0, 5.0])
        
        self.bc_manager = BoundaryManager()
        farfield_bc = FarfieldBC('farfield', self.rho_inf, self.u_inf, self.p_inf)
        self.bc_manager.add_bc(farfield_bc)
        self.bc_manager.initialize(self.mesh)
    
    def _isentropic_vortex_ic(self, center):
        """Initial condition for isentropic vortex."""
        dx = center - self.x_c
        r2 = dx[0]**2 + dx[1]**2
        
        u_pert = self.beta / (2 * np.pi) * np.exp(0.5 * (1 - r2)) * np.array([-dx[1], dx[0]])
        u = self.u_inf + u_pert
        
        T = 1.0 - (GAMMA - 1) * self.beta**2 / (8 * GAMMA * np.pi**2) * np.exp(1 - r2)
        p = self.p_inf * T ** (GAMMA / (GAMMA - 1))
        rho = self.rho_inf * T ** (1.0 / (GAMMA - 1))
        
        return rho, u, p
    
    def test_initial_condition(self):
        """Test isentropic vortex initial condition."""
        solver = CompressibleSolver(
            self.mesh, self.bc_manager,
            viscous=False, flux_scheme='roe',
            dt=0.1, local_time_stepping=False
        )
        
        solver.set_initial_condition(self._isentropic_vortex_ic)
        
        c_center = self.mesh.cell_centers[np.argmin(np.linalg.norm(self.mesh.cell_centers - self.x_c, axis=1))]
        corner_idx = np.argmin(self.mesh.cell_centers[:, 0] + self.mesh.cell_centers[:, 1])
        
        assert solver.flow.rho[corner_idx] == pytest.approx(self.rho_inf, rel=0.1)
        assert solver.flow.p[corner_idx] == pytest.approx(self.p_inf, rel=0.1)
    
    def test_vortex_convection(self):
        """Test that vortex convects at freestream velocity."""
        solver = CompressibleSolver(
            self.mesh, self.bc_manager,
            viscous=False, flux_scheme='roe',
            dt=0.1, local_time_stepping=False
        )
        
        solver.set_initial_condition(self._isentropic_vortex_ic)
        
        rho_initial = solver.flow.rho.copy()
        u_initial = solver.flow.u.copy()
        
        for _ in range(10):
            solver.step()
            if np.any(np.isnan(solver.flow.Q)):
                pytest.fail("Solution diverged with NaN")
        
        assert not np.allclose(solver.flow.rho, rho_initial, rtol=0.01)
        assert not np.allclose(solver.flow.u, u_initial, rtol=0.01)


class TestFarfieldBoundary:
    """Test farfield boundary condition."""
    
    def test_farfield_bc_initialization(self):
        """Test farfield BC initialization."""
        rho = 1.225
        velocity = np.array([10.0, 0.0])
        pressure = 101325.0
        
        bc = FarfieldBC('farfield', rho, velocity, pressure)
        
        assert bc.rho == rho
        assert np.all(bc.velocity == velocity)
        assert bc.pressure == pressure
        assert bc.speed_of_sound == pytest.approx(np.sqrt(GAMMA * pressure / rho), rel=1e-10)
    
    def test_freestream_setter(self):
        """Test freestream condition setter."""
        bc = FarfieldBC('farfield', 1.0, [0.0, 0.0], 1e5)
        
        new_rho = 1.5
        new_vel = np.array([20.0, 5.0])
        new_p = 2e5
        
        bc.set_freestream(new_rho, new_vel, new_p)
        
        assert bc.rho == new_rho
        assert np.all(bc.velocity == new_vel)
        assert bc.pressure == new_p


class TestSolverBaseFeatures:
    """Test base solver features for compressible solver."""
    
    def setup_method(self):
        """Set up test mesh and solver."""
        self.mesh = StructuredMesh(20, 20, x_range=(0.0, 1.0), y_range=(0.0, 1.0))
        
        self.bc_manager = BoundaryManager()
        farfield_bc = FarfieldBC('farfield', 1.225, [10.0, 0.0], 101325.0)
        self.bc_manager.add_bc(farfield_bc)
        self.bc_manager.initialize(self.mesh)
        
        self.solver = CompressibleSolver(
            self.mesh, self.bc_manager,
            viscous=False, flux_scheme='roe',
            dt=1e-4
        )
        self.solver.initialize()
    
    def test_local_timestep(self):
        """Test local time stepping calculation."""
        dt_local = self.solver.compute_local_timestep(cfl=0.5)
        
        assert dt_local.shape == (self.mesh.n_cells,)
        assert np.all(dt_local > 0)
        assert not np.any(np.isnan(dt_local))
        assert not np.any(np.isinf(dt_local))
    
    def test_numerical_flux_interface(self):
        """Test numerical flux interface through base class."""
        rho_left, rho_right = 1.0, 0.9
        u_left = np.array([10.0, 0.0])
        u_right = np.array([9.0, 0.0])
        p_left, p_right = 1e5, 0.99e5
        
        Q_left = primitive_to_conservative(rho_left, u_left, p_left)
        Q_right = primitive_to_conservative(rho_right, u_right, p_right)
        
        normal = np.array([1.0, 0.0])
        
        self.solver.flux_scheme = 'roe'
        flux_roe = self.solver.compute_numerical_flux(Q_left, Q_right, normal)
        
        self.solver.flux_scheme = 'ausm+'
        flux_ausm = self.solver.compute_numerical_flux(Q_left, Q_right, normal)
        
        assert len(flux_roe) == 4
        assert len(flux_ausm) == 4
    
    def test_save_solution_compressible(self, tmp_path):
        """Test saving compressible solution to HDF5."""
        from pycfd.core.hdf5_io import HDF5Writer
        
        h5_file = tmp_path / "test_compressible.h5"
        
        with HDF5Writer(h5_file) as writer:
            writer.write_mesh(self.mesh)
            self.solver.save_solution(writer, timestep=0)
        
        from pycfd.core.hdf5_io import HDF5Reader
        
        with HDF5Reader(h5_file) as reader:
            timesteps = reader.get_timesteps()
            assert len(timesteps) == 1
            
            field_names = reader.get_field_names(0)
            assert 'rho' in field_names
            assert 'u' in field_names
            assert 'p' in field_names
            assert 'Q' in field_names
            assert 'Ma' in field_names
            assert 'T' in field_names
            
            rho = reader.read_field('rho', 0)
            assert rho.shape == (self.mesh.n_cells,)
    
    def test_divergence_detection(self):
        """Test divergence detection for compressible solver."""
        res = {'Q0': 1e-5, 'Q1': 2e-5, 'Q2': 3e-5, 'Q3': 4e-5}
        
        diverged, msg = self.solver._check_divergence(res)
        assert not diverged
        
        res_bad = {'Q0': np.nan, 'Q1': 1.0, 'Q2': 1.0, 'Q3': 1.0}
        diverged, msg = self.solver._check_divergence(res_bad)
        assert diverged
        
        res_big = {'Q0': 1e15, 'Q1': 1.0, 'Q2': 1.0, 'Q3': 1.0}
        diverged, msg = self.solver._check_divergence(res_big)
        assert diverged


if __name__ == '__main__':
    pytest.main([__file__, '-v', '-x'])
