import numpy as np
from ..core.jit import njit, prange
from ..core.sparse import SparseMatrix
from ..bc.boundary import BoundaryCondition

class FlowField:
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

class FlowSolver:
    def __init__(self, mesh, bc_manager=None, turbulence_model=None, 
                 flow=None, nu=1e-6, rho=1.0, convection_scheme='upwind'):
        self.mesh = mesh
        self.ndim = mesh.ndim
        self.n_cells = mesh.n_cells
        self.n_faces = mesh.n_faces
        self.bc_manager = bc_manager
        self.turbulence_model = turbulence_model
        self.turbulence = turbulence_model is not None
        if flow is not None:
            self.flow = flow
            if len(self.flow.mass_flux) != self.n_faces:
                self.flow.mass_flux = np.zeros(self.n_faces, dtype=np.float64)
        else:
            self.flow = FlowField(self.n_cells, self.ndim, self.turbulence, turbulence_model, self.n_faces)
        self.rho = rho
        self.nu = nu
        self.dt = 1e-3
        self.time = 0.0
        self.timestep = 0
        self.convection_scheme = convection_scheme
        self.tvd_limiter = 'minmod'
        self.underrelaxation = {'u': 0.7, 'p': 0.3, 'k': 0.8, 'epsilon': 0.8, 'omega': 0.8}
        self.alpha_p = self.underrelaxation['p']
        self.alpha_u = self.underrelaxation['u']
        self.residuals = {}
        self.residual_history = []
        self.p_matrix = None
        self.p_rhs = None
        self.max_iterations = 1000
        self.convergence_tolerance = 1e-6
        self._precompute_geometric_quantities()
        
    @property
    def current_step(self):
        """Return the current step number (alias for timestep)."""
        return self.timestep

    def _precompute_geometric_quantities(self):
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
        c1 = self.mesh.owner[fid]
        c2 = self.mesh.neighbour[fid]
        if c2 < 0:
            return phi[c1]
        else:
            w = self.face_weight[fid]
            return w * phi[c1] + (1 - w) * phi[c2]

    def compute_face_gradient(self, phi):
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

    def assemble_momentum_matrix(self, direction=0):
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

    def _tvd_limiter(self, r):
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
        for name, res in residuals.items():
            if len(res) < self.convergence_tolerance:
                return True
        return False

    def step(self):
        self.timestep += 1
        self.time += self.dt
        res = self._solve_inner_iteration()
        for k, v in res.items():
            if k not in self.residuals:
                self.residuals[k] = []
            self.residuals[k].append(v)
        self.residual_history.append(res)
        return res

    def solve(self, n_steps=100, transient=False):
        for _ in range(n_steps):
            res = self.step()
            print(f"Step {self.timestep}: {res}")
            if self.check_convergence(res):
                break
        return self.residuals

    def _solve_inner_iteration(self):
        raise NotImplementedError

    def save_solution(self, writer, timestep=None):
        ts = timestep if timestep else self.timestep
        writer.write_field('u', self.flow.u, ts)
        writer.write_field('p', self.flow.p, ts)
        if self.ndim >= 2:
            writer.write_field('v', self.flow.v, ts)
        if self.turbulence:
            writer.write_field('k', self.flow.turb_k, ts)
            if self.turbulence_model == 'k-epsilon':
                writer.write_field('epsilon', self.flow.turb_epsilon, ts)
            elif self.turbulence_model == 'k-omega-sst':
                writer.write_field('omega', self.flow.turb_omega, ts)
            writer.write_field('nu_t', self.flow.nu_t, ts)

@njit
def upwind(mass_flux, phi_up, phi_down):
    if mass_flux > 0:
        return phi_up, 0.0
    else:
        return phi_down, 0.0
