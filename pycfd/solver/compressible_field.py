import numpy as np
from ..core.jit import njit, prange


class CompressibleFlowField:
    """Flow field for compressible flows using conservative variables."""
    
    def __init__(self, n_cells, ndim=2, n_faces=0, turbulence=False, turbulence_model=None):
        self.ndim = ndim
        self.n_cells = n_cells
        self.n_vars = ndim + 2
        
        self.Q = np.zeros((n_cells, self.n_vars), dtype=np.float64)
        self.Q_prev = np.zeros((n_cells, self.n_vars), dtype=np.float64)
        
        self.grad_Q = np.zeros((n_cells, self.n_vars, ndim), dtype=np.float64)
        
        self.face_flux = np.zeros((n_faces, self.n_vars), dtype=np.float64)
        
        self.rho = np.zeros(n_cells, dtype=np.float64)
        self.u = np.zeros((n_cells, ndim), dtype=np.float64)
        self.p = np.zeros(n_cells, dtype=np.float64)
        self.c = np.zeros(n_cells, dtype=np.float64)
        self.Ma = np.zeros(n_cells, dtype=np.float64)
        self.T = np.zeros(n_cells, dtype=np.float64)
        
        self.turbulence = turbulence
        if turbulence and turbulence_model:
            self.turb_k = np.ones(n_cells, dtype=np.float64) * 0.01
            self.turb_epsilon = np.ones(n_cells, dtype=np.float64) * 0.001
            if turbulence_model == 'k-omega-sst':
                self.turb_omega = np.ones(n_cells, dtype=np.float64) * 10.0
            self.nu_t = np.zeros(n_cells, dtype=np.float64)
    
    def initialize(self, rho0=1.0, u0=0.0, p0=1e5):
        """Initialize with uniform primitive variables."""
        from .fluxes import primitive_to_conservative, GAS_CONSTANT
        
        if np.isscalar(u0):
            u0 = np.array([u0] + [0.0] * (self.ndim - 1), dtype=np.float64)
        
        for cid in range(self.n_cells):
            self.Q[cid] = primitive_to_conservative(rho0, u0, p0)
        
        self.Q_prev[:] = self.Q
        self._update_primitive_variables()
        self.T[:] = self.p / (self.rho * GAS_CONSTANT)
    
    def _update_primitive_variables(self):
        """Update primitive variables from conservative variables."""
        from .fluxes import conservative_to_primitive, compute_speed_of_sound
        
        for cid in range(self.n_cells):
            rho, u, p = conservative_to_primitive(self.Q[cid])
            self.rho[cid] = rho
            self.u[cid] = u
            self.p[cid] = p
            self.c[cid] = compute_speed_of_sound(rho, p)
            self.Ma[cid] = np.linalg.norm(u) / self.c[cid]
