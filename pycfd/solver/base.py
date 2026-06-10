import numpy as np
from ..core.jit import njit, prange
from ..core.sparse import SparseMatrix
from ..bc.boundary import BoundaryCondition


class SolverBase:
    """Base class for all CFD solvers.
    
    Defines common interfaces for:
    - Geometric precomputation
    - Flux calculation interface
    - Time stepping framework
    - Residual evaluation
    - Boundary condition handling
    - Convergence checking
    - Divergence detection and recovery
    
    Subclasses must implement:
    - _solve_inner_iteration(): Perform one iteration of the solver
    """
    
    def __init__(self, mesh, bc_manager=None, dt=1e-3, **kwargs):
        self.mesh = mesh
        self.ndim = mesh.ndim
        self.n_cells = mesh.n_cells
        self.n_faces = mesh.n_faces
        self.bc_manager = bc_manager
        
        self.dt = dt
        self.time = 0.0
        self.timestep = 0
        
        self.underrelaxation = {}
        self.ur_min = {}
        self.ur_max = {}
        self.adjustment_history = []
        self.divergence_count = 0
        
        self.residuals = {}
        self.residual_history = []
        
        self.convergence_tolerance = 1e-6
        self.max_iterations = 1000
        
        self.flux_scheme = 'roe'
        
        self._precompute_geometric_quantities()
    
    @property
    def current_step(self):
        """Return the current step number (alias for timestep)."""
        return self.timestep
    
    def _precompute_geometric_quantities(self):
        """Precompute geometric quantities used in discretization."""
        m = self.mesh
        self.face_dist = np.zeros(self.n_faces, dtype=np.float64)
        self.face_weight = np.zeros(self.n_faces, dtype=np.float64)
        self.d_coeff = np.zeros(self.n_faces, dtype=np.float64)
        self.nonorthogonal_correction = np.zeros((self.n_faces, self.ndim), dtype=np.float64)
        
        for fid in range(self.n_faces):
            c1 = m.owner[fid]
            c2 = m.neighbour[fid]
            if c2 >= 0:
                d = m.cell_centers[c2] - m.cell_centers[c1]
                n = m.face_normals[fid]
                d_mag = np.linalg.norm(d)
                self.face_dist[fid] = d_mag
                self.face_weight[fid] = 0.5
                d_norm = d / d_mag
                k = np.dot(n, d_norm)
                k = n - k * d_norm
                self.nonorthogonal_correction[fid] = k
                self.d_coeff[fid] = m.face_areas[fid] / d_mag
    
    def compute_face_value(self, phi, fid):
        """Compute face value by linear interpolation."""
        c1 = self.mesh.owner[fid]
        c2 = self.mesh.neighbour[fid]
        if c2 < 0:
            return phi[c1]
        else:
            w = self.face_weight[fid]
            return w * phi[c1] + (1 - w) * phi[c2]
    
    def compute_face_gradient(self, phi):
        """Compute gradient using Green-Gauss theorem."""
        m = self.mesh
        grad = np.zeros((self.n_cells, self.ndim), dtype=np.float64)
        
        for fid in range(self.n_faces):
            c1 = m.owner[fid]
            c2 = m.neighbour[fid]
            if c2 < 0:
                continue
            dphi = phi[c2] - phi[c1]
            nf = m.face_normals[fid]
            area = m.face_areas[fid]
            grad[c1] += dphi * nf * area
            grad[c2] -= dphi * nf * area
        
        for cid in range(self.n_cells):
            grad[cid] /= m.cell_volumes[cid]
        
        return grad
    
    def compute_cell_gradient(self, phi):
        """Compute gradient in each cell (alias for compute_face_gradient)."""
        return self.compute_face_gradient(phi)
    
    def _tvd_limiter(self, r):
        """Return TVD limiter value."""
        if self.tvd_limiter == 'minmod':
            return max(0, min(1, r))
        elif self.tvd_limiter == 'superbee':
            return max(0, min(2, 2*r), min(1, 2*r))
        elif self.tvd_limiter == 'vanleer':
            return (r + abs(r)) / (1 + abs(r))
        elif self.tvd_limiter == 'vanalbada':
            return (r * r + r) / (r * r + 1)
        else:
            return max(0, min(1, r))
    
    def check_convergence(self, residuals):
        """Check if convergence criteria are met."""
        for name, res in residuals.items():
            if res < self.convergence_tolerance:
                return True
        return False
    
    def compute_residual_norm(self, residual, name=''):
        """Compute normalized residual."""
        initial_norm = self._initial_residual_norms.get(name, 1.0)
        if initial_norm < 1e-12:
            return residual
        return residual / initial_norm
    
    def step(self):
        """Advance one time step."""
        self.timestep += 1
        self.time += self.dt
        
        res = self._solve_inner_iteration()
        
        diverged, msg = self._check_divergence(res)
        if diverged:
            self._adjust_underrelaxation(True, msg)
            self._handle_nan_values()
        
        for k, v in res.items():
            if k not in self.residuals:
                self.residuals[k] = []
            self.residuals[k].append(v)
        self.residual_history.append(res)
        
        return res
    
    def _check_divergence(self, residuals):
        """Check if the solution has diverged."""
        for name, res in residuals.items():
            if np.isnan(res) or np.isinf(res):
                return True, f"{name} residual is NaN/Inf"
            if res > 1e10:
                return True, f"{name} residual {res} exceeds threshold"
        
        if hasattr(self.flow, 'u'):
            if np.any(np.isnan(self.flow.u)) or np.any(np.isinf(self.flow.u)):
                return True, "Velocity field contains NaN/Inf"
            if np.any(np.isnan(self.flow.p)) or np.any(np.isinf(self.flow.p)):
                return True, "Pressure field contains NaN/Inf"
        elif hasattr(self.flow, 'Q'):
            if np.any(np.isnan(self.flow.Q)) or np.any(np.isinf(self.flow.Q)):
                return True, "Conservative variables contain NaN/Inf"
        
        return False, "No divergence detected"
    
    def _adjust_underrelaxation(self, diverged, reason=""):
        """Adjust under-relaxation factors to improve stability."""
        if diverged:
            self.divergence_count += 1
            factor = 0.7
            
            for key in self.underrelaxation:
                new_val = self.underrelaxation[key] * factor
                min_val = self.ur_min.get(key, 0.1)
                if new_val >= min_val:
                    self.underrelaxation[key] = new_val
            
            if 'p' in self.underrelaxation:
                self.alpha_p = self.underrelaxation['p']
            if 'u' in self.underrelaxation:
                self.alpha_u = self.underrelaxation['u']
            
            self.adjustment_history.append({
                'step': self.timestep,
                'diverged': diverged,
                'reason': reason,
                'underrelaxation': self.underrelaxation.copy()
            })
    
    def _handle_nan_values(self):
        """Replace NaN/Inf values with previous or initial values."""
        if hasattr(self.flow, 'u'):
            if np.any(np.isnan(self.flow.u)) or np.any(np.isinf(self.flow.u)):
                prev_u = self.flow.u_prev.copy()
                nan_mask = np.isnan(self.flow.u) | np.isinf(self.flow.u)
                self.flow.u[nan_mask] = prev_u[nan_mask]
                self.flow.u[~nan_mask] = np.where(
                    np.abs(self.flow.u[~nan_mask]) > 1e6,
                    prev_u[~nan_mask],
                    self.flow.u[~nan_mask]
                )
            if np.any(np.isnan(self.flow.p)) or np.any(np.isinf(self.flow.p)):
                prev_p = self.flow.p_prev.copy()
                nan_mask = np.isnan(self.flow.p) | np.isinf(self.flow.p)
                self.flow.p[nan_mask] = prev_p[nan_mask]
                self.flow.p[~nan_mask] = np.where(
                    np.abs(self.flow.p[~nan_mask]) > 1e6,
                    prev_p[~nan_mask],
                    self.flow.p[~nan_mask]
                )
        elif hasattr(self.flow, 'Q'):
            if np.any(np.isnan(self.flow.Q)) or np.any(np.isinf(self.flow.Q)):
                prev_Q = self.flow.Q_prev.copy()
                nan_mask = np.isnan(self.flow.Q) | np.isinf(self.flow.Q)
                self.flow.Q[nan_mask] = prev_Q[nan_mask]
                self.flow.Q[~nan_mask] = np.where(
                    np.abs(self.flow.Q[~nan_mask]) > 1e10,
                    prev_Q[~nan_mask],
                    self.flow.Q[~nan_mask]
                )
    
    def solve(self, n_steps=100, transient=False):
        """Run solver for specified number of steps."""
        for _ in range(n_steps):
            res = self.step()
            print(f"Step {self.timestep}: {res}")
            if self.check_convergence(res):
                break
        return self.residuals
    
    def _solve_inner_iteration(self):
        """Perform one inner iteration. Must be implemented by subclass."""
        raise NotImplementedError
    
    def compute_numerical_flux(self, Q_left, Q_right, normal, scheme=None):
        """Compute numerical flux at a face.
        
        Args:
            Q_left: Conservative variables on left side
            Q_right: Conservative variables on right side
            normal: Face normal vector
            scheme: Flux scheme ('roe' or 'ausm+')
            
        Returns:
            Numerical flux vector
        """
        if scheme is None:
            scheme = self.flux_scheme
        
        from .fluxes import roe_flux, ausm_plus_flux
        
        if scheme.lower() == 'roe':
            return roe_flux(Q_left, Q_right, normal)
        elif scheme.lower() in ('ausm+', 'ausm'):
            return ausm_plus_flux(Q_left, Q_right, normal)
        else:
            raise ValueError(f"Unknown flux scheme: {scheme}")
    
    def compute_local_timestep(self, cfl=0.5):
        """Compute local time step for convergence acceleration.
        
        Args:
            cfl: CFL number
            
        Returns:
            Local time step for each cell
        """
        if not hasattr(self.flow, 'c'):
            self.flow._update_primitive_variables()
        
        dt_local = np.zeros(self.n_cells, dtype=np.float64)
        
        for cid in range(self.n_cells):
            c = self.flow.c[cid]
            u = self.flow.u[cid]
            vol = self.mesh.cell_volumes[cid]
            
            area_sum = 0.0
            u_norm = np.linalg.norm(u)
            for fid in self.mesh.get_cell_faces(cid):
                area_sum += self.mesh.face_areas[fid]
            
            if area_sum > 0 and (u_norm + c) > 0:
                dt_local[cid] = cfl * vol / (area_sum * (u_norm + c))
            else:
                dt_local[cid] = self.dt
        
        return dt_local
    
    def save_solution(self, writer, timestep=None):
        """Save solution to HDF5 writer."""
        ts = timestep if timestep else self.timestep
        
        if hasattr(self.flow, 'Q'):
            writer.write_field('rho', self.flow.rho, ts)
            writer.write_field('u', self.flow.u, ts)
            writer.write_field('p', self.flow.p, ts)
            writer.write_field('Q', self.flow.Q, ts)
            writer.write_field('Ma', self.flow.Ma, ts)
            writer.write_field('T', self.flow.T, ts)
            if self.turbulence:
                writer.write_field('k', self.flow.turb_k, ts)
                if self.turbulence_model == 'k-epsilon':
                    writer.write_field('epsilon', self.flow.turb_epsilon, ts)
                elif self.turbulence_model == 'k-omega-sst':
                    writer.write_field('omega', self.flow.turb_omega, ts)
                writer.write_field('nu_t', self.flow.nu_t, ts)
        elif hasattr(self.flow, 'u'):
            writer.write_field('u', self.flow.u, ts)
            writer.write_field('p', self.flow.p, ts)
            if self.ndim >= 2 and hasattr(self.flow, 'v'):
                writer.write_field('v', self.flow.v, ts)
            if self.turbulence:
                writer.write_field('k', self.flow.turb_k, ts)
                if self.turbulence_model == 'k-epsilon':
                    writer.write_field('epsilon', self.flow.turb_epsilon, ts)
                elif self.turbulence_model == 'k-omega-sst':
                    writer.write_field('omega', self.flow.turb_omega, ts)
                writer.write_field('nu_t', self.flow.nu_t, ts)


class FlowField:
    """Flow field for incompressible flows."""
    
    def __init__(self, n_cells, ndim=2, turbulence=False, turbulence_model=None, n_faces=0):
        self.ndim = ndim
        self.n_cells = n_cells
        self.u = np.zeros((n_cells, ndim), dtype=np.float64)
        self.u_prev = np.zeros((n_cells, ndim), dtype=np.float64)
        self.v = np.zeros(n_cells, dtype=np.float64)
        self.p = np.zeros(n_cells, dtype=np.float64)
        self.p_prev = np.zeros(n_cells, dtype=np.float64)
        self.ap = np.ones(n_cells, dtype=np.float64)
        self.phi = np.zeros(n_cells, dtype=np.float64)
        self.u_star = np.zeros((n_cells, ndim), dtype=np.float64)
        self.grad_u = np.zeros((n_cells, ndim, ndim), dtype=np.float64)
        self.grad_p = np.zeros((n_cells, ndim), dtype=np.float64)
        self.mass_flux = np.zeros(n_faces, dtype=np.float64)
        self.turbulence = turbulence
        if turbulence and turbulence_model:
            self.turb_k = np.ones(n_cells, dtype=np.float64) * 0.01
            self.turb_epsilon = np.ones(n_cells, dtype=np.float64) * 0.001
            if turbulence_model == 'k-omega-sst':
                self.turb_omega = np.ones(n_cells, dtype=np.float64) * 10.0
            self.nu_t = np.zeros(n_cells, dtype=np.float64)

    def initialize(self, u0=0.0, p0=0.0, v0=None):
        self.u[:] = u0
        self.u_prev[:] = u0
        self.p[:] = p0
        self.p_prev[:] = p0
        self.ap[:] = 1.0
        if v0 is not None and self.ndim >= 2:
            self.v[:] = v0


class FlowSolver(SolverBase):
    """Incompressible flow solver base class.
    
    Inherits from SolverBase and adds incompressible-specific functionality:
    - Pressure-velocity coupling
    - SIMPLE/PISO algorithms
    - Turbulence model solving
    
    This class is kept for backward compatibility.
    """
    
    def __init__(self, mesh, bc_manager=None, turbulence_model=None, 
                 flow=None, nu=1e-6, rho=1.0, convection_scheme='upwind', **kwargs):
        super().__init__(mesh, bc_manager, **kwargs)
        
        self.turbulence_model = turbulence_model
        self.turbulence = turbulence_model is not None
        
        if flow is not None:
            self.flow = flow
            if len(self.flow.mass_flux) != self.n_faces:
                self.flow.mass_flux = np.zeros(self.n_faces, dtype=np.float64)
        else:
            self.flow = FlowField(self.n_cells, self.ndim, self.turbulence, 
                                 turbulence_model, self.n_faces)
        
        self.rho = rho
        self.nu = nu
        self.convection_scheme = convection_scheme
        self.tvd_limiter = 'minmod'
        
        self.underrelaxation = {'u': 0.7, 'p': 0.3, 'k': 0.8, 'epsilon': 0.8, 'omega': 0.8}
        self.ur_min = {'u': 0.1, 'p': 0.05, 'k': 0.3, 'epsilon': 0.3, 'omega': 0.3}
        self.ur_max = {'u': 0.9, 'p': 0.5, 'k': 0.9, 'epsilon': 0.9, 'omega': 0.9}
        self.alpha_p = self.underrelaxation['p']
        self.alpha_u = self.underrelaxation['u']
        
        self.p_matrix = None
        self.p_rhs = None
        self._initial_residual_norms = {}
    
    def assemble_momentum_matrix(self, direction=0):
        """Assemble momentum equation matrix."""
        m = self.mesh
        n = self.n_cells
        A = SparseMatrix(n)
        b = np.zeros(n, dtype=np.float64)
        u = self.flow.u[:, direction]
        ap = np.zeros(n, dtype=np.float64)
        
        for fid in range(self.n_faces):
            c1 = m.owner[fid]
            c2 = m.neighbour[fid]
            if c2 < 0:
                continue
            
            mf = self.flow.mass_flux[fid]
            d_coeff = self.d_coeff[fid]
            area = m.face_areas[fid]
            
            if self.convection_scheme == 'upwind':
                conv_coeff, conv_source = upwind(mf, u[c1], u[c2])
            else:
                conv_coeff, conv_source = self._tvd_convection(fid, u, direction)
            
            diff_coeff = self.nu * d_coeff
            a_e = diff_coeff + max(0, -mf)
            a_w = diff_coeff + max(0, mf)
            
            ap[c1] += a_e + conv_coeff
            ap[c2] += a_w - conv_coeff
            
            A.add_value(c1, c1, a_e + a_e + conv_coeff)
            A.add_value(c1, c2, -a_e + conv_coeff)
            A.add_value(c2, c2, a_w + a_w - conv_coeff)
            A.add_value(c2, c1, -a_w - conv_coeff)
            
            b[c1] -= conv_source * area
            b[c2] += conv_source * area
        
        for cid in range(n):
            dpdx = self.flow.grad_p[cid, direction]
            b[cid] += m.cell_volumes[cid] * (-dpdx / self.rho)
            A.add_value(cid, cid, m.cell_volumes[cid] / self.dt)
            b[cid] += m.cell_volumes[cid] * self.flow.u_prev[cid, direction] / self.dt
        
        return A, b, ap
    
    def _tvd_convection(self, fid, phi, direction=0):
        """Compute TVD convection flux."""
        c1 = self.mesh.owner[fid]
        c2 = self.mesh.neighbour[fid]
        mf = self.flow.mass_flux[fid]
        m = self.mesh
        
        if mf > 0:
            phi_c = phi[c1]
            phi_d = phi[c2]
            c_up = self._get_upwind_cell(c1, fid, direction)
            phi_up = phi[c_up] if c_up >= 0 else phi_c
            phi_phi_cd = phi_d - phi_c
            phi_phi_cu = phi_c - phi_up
        else:
            phi_c = phi[c2]
            phi_d = phi[c1]
            c_up = self._get_upwind_cell(c2, fid, direction)
            phi_up = phi[c_up] if c_up >= 0 else phi_c
            phi_phi_cd = phi_d - phi_c
            phi_phi_cu = phi_c - phi_up
        
        r = phi_phi_cu / (phi_phi_cd + 1e-15)
        psi = self._tvd_limiter(r)
        face_value = phi_c + 0.5 * psi * phi_phi_cd
        
        a_c = max(0, mf) * face_value + max(0, -mf) * phi_d
        source = max(0, mf) * (face_value - phi_c) + max(0, -mf) * (face_value - phi_d)
        
        return a_c, source
    
    def _get_upwind_cell(self, cid, fid, direction):
        """Get upwind cell for TVD scheme."""
        m = self.mesh
        best = -1
        min_dist = np.inf
        
        for nb in m.get_neighbors(cid):
            if nb == self.mesh.neighbour[fid] or nb == self.mesh.owner[fid]:
                continue
            d = np.linalg.norm(m.cell_centers[nb] - m.cell_centers[cid])
            if d < min_dist:
                min_dist = d
                best = nb
        
        return best


@njit
def upwind(mass_flux, phi_up, phi_down):
    """First-order upwind scheme."""
    if mass_flux > 0:
        return phi_up, 0.0
    else:
        return phi_down, 0.0
