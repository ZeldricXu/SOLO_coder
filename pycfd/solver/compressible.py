import numpy as np
from .base import SolverBase
from .compressible_field import CompressibleFlowField
from .fluxes import GAMMA, GAS_CONSTANT


class CompressibleSolver(SolverBase):
    """Compressible flow solver for Euler and RANS equations.
    
    Features:
    - Euler equations (inviscid) and RANS equations (viscous)
    - Roe and AUSM+ flux schemes
    - Local time stepping for convergence acceleration
    - Gradient reconstruction for second-order accuracy
    """
    
    def __init__(self, mesh, bc_manager=None, flow=None, 
                 viscous=False, turbulence_model=None, 
                 flux_scheme='roe', **kwargs):
        super().__init__(mesh, bc_manager, **kwargs)
        
        self.viscous = viscous
        self.turbulence_model = turbulence_model
        self.turbulence = turbulence_model is not None
        
        if flow is not None:
            self.flow = flow
        else:
            self.flow = CompressibleFlowField(
                self.n_cells, self.ndim, self.n_faces,
                self.turbulence, turbulence_model
            )
        
        self.flux_scheme = flux_scheme
        self.local_time_stepping = True
        self.cfl = 0.5
        self.reconstruction = 'muscl'
        self.limiter = 'minmod'
        
        self.underrelaxation = {'rho': 0.9, 'momentum': 0.9, 'energy': 0.9}
        self.ur_min = {'rho': 0.3, 'momentum': 0.3, 'energy': 0.3}
        self.ur_max = {'rho': 0.99, 'momentum': 0.99, 'energy': 0.99}
        
        self._initial_residual_norms = {}
    
    def initialize(self, rho0=1.225, u0=0.0, p0=101325):
        """Initialize flow field with uniform primitive variables."""
        self.flow.initialize(rho0, u0, p0)
        self.flow.Q_prev[:] = self.flow.Q
    
    def set_initial_condition(self, func):
        """Set initial condition using a function.
        
        Args:
            func: Function that takes cell_centers and returns (rho, u, p)
        """
        from .fluxes import primitive_to_conservative
        
        for cid in range(self.n_cells):
            center = self.mesh.cell_centers[cid]
            rho, u, p = func(center)
            if np.isscalar(u):
                u = np.array([u] + [0.0] * (self.ndim - 1))
            self.flow.Q[cid] = primitive_to_conservative(rho, u, p)
        
        self.flow.Q_prev[:] = self.flow.Q
        self.flow._update_primitive_variables()
        self.flow.T[:] = self.flow.p / (self.flow.rho * GAS_CONSTANT)
    
    def _solve_inner_iteration(self):
        """Perform one iteration of the compressible solver."""
        residuals = {}
        
        self.flow.Q_prev[:] = self.flow.Q
        
        if self.local_time_stepping:
            dt = self.compute_local_timestep(self.cfl)
        else:
            dt = np.ones(self.n_cells, dtype=np.float64) * self.dt
        
        self._reconstruct_face_states()
        self._compute_inviscid_fluxes()
        
        if self.viscous:
            self._compute_viscous_fluxes()
        
        self._update_solution(dt)
        
        if self.bc_manager:
            self._apply_boundary_conditions()
        
        self.flow._update_primitive_variables()
        self.flow.T[:] = self.flow.p / (self.flow.rho * GAS_CONSTANT)
        
        if self.turbulence:
            self._solve_turbulence()
        
        self.flow.Q[:] = self.flow.Q_prev + self.underrelaxation['rho'] * (self.flow.Q - self.flow.Q_prev)
        
        dQ = self.flow.Q - self.flow.Q_prev
        for i in range(self.flow.n_vars):
            res = np.linalg.norm(dQ[:, i])
            residuals[f'Q{i}'] = res
            
            if self.timestep == 1:
                self._initial_residual_norms[f'Q{i}'] = res
            else:
                residuals[f'Q{i}_rel'] = self.compute_residual_norm(res, f'Q{i}')
        
        return residuals
    
    def _reconstruct_face_states(self):
        """Reconstruct left/right states at faces using MUSCL scheme."""
        from .fluxes import conservative_to_primitive
        
        self.face_Q_left = np.zeros((self.n_faces, self.flow.n_vars), dtype=np.float64)
        self.face_Q_right = np.zeros((self.n_faces, self.flow.n_vars), dtype=np.float64)
        
        grad_Q = np.zeros((self.n_cells, self.flow.n_vars, self.ndim), dtype=np.float64)
        for iv in range(self.flow.n_vars):
            grad_Q[:, iv] = self.compute_face_gradient(self.flow.Q[:, iv])
        
        for fid in range(self.n_faces):
            c1 = self.mesh.owner[fid]
            c2 = self.mesh.neighbour[fid]
            
            if c2 < 0:
                self.face_Q_left[fid] = self.flow.Q[c1]
                self.face_Q_right[fid] = self.flow.Q[c1]
                continue
            
            d12 = self.mesh.cell_centers[c2] - self.mesh.cell_centers[c1]
            d21 = self.mesh.cell_centers[c1] - self.mesh.cell_centers[c2]
            
            for iv in range(self.flow.n_vars):
                phi1 = self.flow.Q[c1, iv]
                phi2 = self.flow.Q[c2, iv]
                grad1 = grad_Q[c1, iv]
                grad2 = grad_Q[c2, iv]
                
                phi_left = phi1 + 0.5 * np.dot(grad1, d12 / 2.0)
                phi_right = phi2 + 0.5 * np.dot(grad2, d21 / 2.0)
                
                if self.limiter == 'minmod':
                    dphi = phi2 - phi1
                    if dphi * (phi_left - phi1) < 0:
                        phi_left = phi1
                    if dphi * (phi_right - phi2) > 0:
                        phi_right = phi2
                
                self.face_Q_left[fid, iv] = phi_left
                self.face_Q_right[fid, iv] = phi_right
    
    def _compute_inviscid_fluxes(self):
        """Compute inviscid fluxes at all faces."""
        for fid in range(self.n_faces):
            normal = self.mesh.face_normals[fid]
            area = self.mesh.face_areas[fid]
            
            Q_left = self.face_Q_left[fid]
            Q_right = self.face_Q_right[fid]
            
            flux = self.compute_numerical_flux(Q_left, Q_right, normal, self.flux_scheme)
            self.flow.face_flux[fid] = flux * area
    
    def _compute_viscous_fluxes(self):
        """Compute viscous fluxes."""
        from .fluxes import conservative_to_primitive
        
        visc_flux = np.zeros((self.n_faces, self.flow.n_vars), dtype=np.float64)
        
        mu = np.zeros(self.n_cells, dtype=np.float64)
        for cid in range(self.n_cells):
            mu[cid] = self._compute_viscosity(self.flow.T[cid])
        
        grad_u = np.zeros((self.n_cells, self.ndim, self.ndim), dtype=np.float64)
        for d in range(self.ndim):
            grad_u[:, d] = self.compute_face_gradient(self.flow.u[:, d])
        
        grad_T = self.compute_face_gradient(self.flow.T)
        
        for fid in range(self.n_faces):
            c1 = self.mesh.owner[fid]
            c2 = self.mesh.neighbour[fid]
            if c2 < 0:
                continue
            
            normal = self.mesh.face_normals[fid]
            area = self.mesh.face_areas[fid]
            
            mu_face = 0.5 * (mu[c1] + mu[c2])
            k_face = mu_face * GAS_CONSTANT * 1.4 / 0.72
            
            grad_u_face = 0.5 * (grad_u[c1] + grad_u[c2])
            grad_T_face = 0.5 * (grad_T[c1] + grad_T[c2])
            
            tau = mu_face * (grad_u_face + grad_u_face.T - 2.0/3.0 * np.trace(grad_u_face) * np.eye(self.ndim))
            tau_n = tau @ normal
            
            u_face = 0.5 * (self.flow.u[c1] + self.flow.u[c2])
            
            visc_flux[fid, 0] = 0.0
            for d in range(self.ndim):
                visc_flux[fid, 1 + d] = tau_n[d]
            visc_flux[fid, -1] = np.dot(u_face, tau_n) + k_face * np.dot(grad_T_face, normal)
            
            self.flow.face_flux[fid] -= visc_flux[fid] * area
    
    def _compute_viscosity(self, T):
        """Compute dynamic viscosity using Sutherland's law."""
        S = 110.4
        mu0 = 1.716e-5
        T0 = 273.15
        return mu0 * (T / T0) ** 1.5 * (T0 + S) / (T + S)
    
    def _update_solution(self, dt):
        """Update conservative variables using finite volume method."""
        self.flow.Q[:] = self.flow.Q_prev
        
        for cid in range(self.n_cells):
            vol = self.mesh.cell_volumes[cid]
            dQ_dt = np.zeros(self.flow.n_vars, dtype=np.float64)
            
            for fid in self.mesh.get_cell_faces(cid):
                if self.mesh.owner[fid] == cid:
                    dQ_dt += self.flow.face_flux[fid]
                elif self.mesh.neighbour[fid] == cid:
                    dQ_dt -= self.flow.face_flux[fid]
            
            self.flow.Q[cid] -= dt[cid] * dQ_dt / vol
            
            self.flow.Q[cid, 0] = max(self.flow.Q[cid, 0], 0.001)
            rho, u, p = conservative_to_primitive_clip(self.flow.Q[cid], self.ndim)
            self.flow.Q[cid, -1] = max(self.flow.Q[cid, -1], 0.5 * rho * np.sum(u**2) + 100.0 * rho / GAMMA)
    
    def _apply_boundary_conditions(self):
        """Apply boundary conditions using the BC manager."""
        if hasattr(self.bc_manager, 'apply_compressible_bc'):
            self.flow.Q = self.bc_manager.apply_compressible_bc(
                self.flow.Q, self.flow, self.mesh
            )
        else:
            self._apply_farfield_bc()
    
    def _apply_farfield_bc(self):
        """Apply farfield boundary conditions for boundary faces."""
        if not hasattr(self.mesh, 'boundary_faces'):
            return
        
        for bname, faces in self.mesh.boundary_faces.items():
            if 'farfield' in bname.lower() or 'inlet' in bname.lower() or 'outlet' in bname.lower():
                for fid in faces:
                    cid = self.mesh.owner[fid]
                    self.flow.Q[cid] = self._farfield_riemann(fid, cid)
    
    def _farfield_riemann(self, fid, cid):
        """Compute farfield BC using Riemann invariants."""
        from .fluxes import conservative_to_primitive, primitive_to_conservative
        
        normal = self.mesh.face_normals[fid]
        Q_inner = self.flow.Q[cid]
        rho, u, p = conservative_to_primitive(Q_inner)
        c = np.sqrt(GAMMA * p / rho)
        u_normal = np.dot(u, normal)
        
        if not hasattr(self, 'farfield_rho'):
            self.farfield_rho = 1.225
            self.farfield_u = np.array([0.0, 0.0])
            self.farfield_p = 101325.0
            self.farfield_c = np.sqrt(GAMMA * self.farfield_p / self.farfield_rho)
        
        farfield_un = np.dot(self.farfield_u, normal)
        
        R_plus = u_normal + 2 * c / (GAMMA - 1)
        R_minus = farfield_un - 2 * self.farfield_c / (GAMMA - 1)
        
        if u_normal > 0:
            u_normal_new = 0.5 * (R_plus + R_minus)
            c_new = 0.25 * (GAMMA - 1) * (R_plus - R_minus)
            rho_new = self.farfield_rho * (c_new / self.farfield_c) ** (2 / (GAMMA - 1))
            p_new = rho_new * c_new ** 2 / GAMMA
            u_new = u.copy()
            u_new -= u_normal * normal
            u_new += u_normal_new * normal
        else:
            u_normal_new = 0.5 * (R_plus + R_minus)
            c_new = 0.25 * (GAMMA - 1) * (R_plus - R_minus)
            rho_new = rho * (c_new / c) ** (2 / (GAMMA - 1))
            p_new = rho_new * c_new ** 2 / GAMMA
            u_new = u.copy()
            u_new -= u_normal * normal
            u_new += u_normal_new * normal
        
        return primitive_to_conservative(rho_new, u_new, p_new)
    
    def set_farfield_condition(self, rho, u, p):
        """Set farfield boundary condition values."""
        self.farfield_rho = rho
        if np.isscalar(u):
            self.farfield_u = np.array([u] + [0.0] * (self.ndim - 1))
        else:
            self.farfield_u = np.array(u)
        self.farfield_p = p
        self.farfield_c = np.sqrt(GAMMA * p / rho)
    
    def _solve_turbulence(self):
        """Solve turbulence model equations."""
        if self.turbulence_model == 'k-epsilon':
            self._solve_k_epsilon()
        elif self.turbulence_model == 'k-omega-sst':
            self._solve_k_omega_sst()
    
    def _solve_k_epsilon(self):
        """Solve k-epsilon turbulence model."""
        from ..turbulence.k_epsilon import solve_k_epsilon_compressible
        self.flow.turb_k, self.flow.turb_epsilon, self.flow.nu_t = solve_k_epsilon_compressible(
            self.mesh, self.flow, self.dt, self.underrelaxation, self.bc_manager
        )
    
    def _solve_k_omega_sst(self):
        """Solve k-omega SST turbulence model."""
        from ..turbulence.k_omega_sst import solve_k_omega_sst_compressible
        self.flow.turb_k, self.flow.turb_omega, self.flow.nu_t = solve_k_omega_sst_compressible(
            self.mesh, self.flow, self.dt, self.underrelaxation, self.bc_manager
        )


def conservative_to_primitive_clip(Q, ndim):
    """Safe primitive variable conversion with clipping."""
    from .fluxes import conservative_to_primitive
    try:
        return conservative_to_primitive(Q)
    except:
        rho = max(Q[0], 0.001)
        u = np.zeros(ndim)
        for i in range(ndim):
            u[i] = Q[1+i] / rho
        p = max(100.0, (GAMMA - 1.0) * (Q[-1] - 0.5 * rho * np.sum(u**2)))
        return rho, u, p
