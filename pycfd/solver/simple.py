import numpy as np
from .base import FlowSolver
from ..core.jit import njit, prange
from ..core.sparse import SparseMatrix

class SimpleSolver(FlowSolver):
    def __init__(self, mesh, bc_manager=None, turbulence_model=None, **kwargs):
        super().__init__(mesh, bc_manager, turbulence_model, **kwargs)
        self.flow.u_prev = np.zeros_like(self.flow.u)
        self.nonorthogonal_corrections = 1
        self.pressure_correction_iterations = 3

    def _solve_inner_iteration(self):
        residuals = {}
        m = self.mesh
        self.flow.u_prev[:] = self.flow.u
        self.flow.grad_p = self.compute_face_gradient(self.flow.p)
        self._compute_mass_flux()
        vel_names = ['u', 'v', 'w']
        mom_residuals = []
        for direction in range(self.ndim):
            A, b, ap = self.assemble_momentum_matrix(direction)
            if self.bc_manager:
                A, b = self.bc_manager.apply_velocity_bc(A, b, direction, self.flow, self.mesh)
            u_star = A.solve(b, method='spsolve')
            ur = self.underrelaxation['u']
            self.flow.u_star[:, direction] = self.flow.u_prev[:, direction] + ur * (u_star - self.flow.u_prev[:, direction])
            res = np.linalg.norm(u_star - self.flow.u[:, direction]) / (np.linalg.norm(u_star) + 1e-12)
            name = vel_names[direction] if direction < len(vel_names) else f'u{direction}'
            residuals[name] = res
            mom_residuals.append(res)
        residuals['u_mom'] = max(mom_residuals) if mom_residuals else 0.0
        self.flow.u = self.flow.u_star.copy()
        for _ in range(self.pressure_correction_iterations):
            A_p, b_p = self._assemble_pressure_correction_matrix()
            if self.bc_manager:
                A_p, b_p = self.bc_manager.apply_pressure_bc(A_p, b_p, self.flow, self.mesh)
            from scipy.sparse.linalg import spsolve
            try:
                p_prime = spsolve(A_p, b_p)
            except:
                p_prime = np.zeros_like(b_p)
            self._update_pressure_and_velocity(p_prime)
            self._compute_mass_flux()
        cont_res = self._compute_continuity_residual()
        residuals['p'] = np.linalg.norm(p_prime) / (np.linalg.norm(self.flow.p) + 1e-12)
        residuals['continuity'] = cont_res
        if self.turbulence:
            self._solve_turbulence()
        return residuals

    def _assemble_pressure_correction_matrix(self):
        m = self.mesh
        n = self.n_cells
        A = SparseMatrix(n)
        b = np.zeros(n, dtype=np.float64)
        self.flow.ap = np.ones(n, dtype=np.float64) * 1e10
        for fid in range(self.n_faces):
            c1 = m.owner[fid]
            c2 = m.neighbour[fid]
            if c2 < 0:
                continue
            d_coeff = self.d_coeff[fid]
            area = m.face_areas[fid]
            mf = self.flow.mass_flux[fid]
            a1 = area * d_coeff / (m.cell_volumes[c1] if m.cell_volumes[c1] > 1e-10 else 1e-10)
            A.add_value(c1, c1, a1)
            A.add_value(c1, c2, -a1)
            A.add_value(c2, c2, a1)
            A.add_value(c2, c1, -a1)
            b[c1] -= mf
            b[c2] += mf
        A.add_value(0, 0, 1e-10)
        A_csr = A.to_csr()
        self.p_matrix = A_csr
        self.p_rhs = b
        return A_csr, b

    def _compute_mass_flux(self):
        m = self.mesh
        self.flow.mass_flux = np.zeros(self.n_faces, dtype=np.float64)
        for fid in range(self.n_faces):
            c1 = m.owner[fid]
            c2 = m.neighbour[fid]
            u_face = self.compute_face_value(self.flow.u[:, 0], fid)
            v_face = self.compute_face_value(self.flow.u[:, 1], fid) if self.ndim >= 2 else 0.0
            normal = m.face_normals[fid]
            area = m.face_areas[fid]
            if c2 < 0:
                u_normal = u_face * normal[0]
                if self.ndim >= 2:
                    u_normal += v_face * normal[1]
                if self.ndim == 3:
                    w_face = self.compute_face_value(self.flow.u[:, 2], fid)
                    u_normal += w_face * normal[2]
                self.flow.mass_flux[fid] = self.rho * u_normal * area
            else:
                u_normal = 0.5 * (self.flow.u[c1, 0] + self.flow.u[c2, 0]) * normal[0]
                if self.ndim >= 2:
                    u_normal += 0.5 * (self.flow.u[c1, 1] + self.flow.u[c2, 1]) * normal[1]
                self.flow.mass_flux[fid] = self.rho * u_normal * area

    def _update_pressure_and_velocity(self, p_prime):
        ur_p = self.underrelaxation['p']
        self.flow.p[:] += ur_p * p_prime
        self._correct_velocity(p_prime)

    def _correct_velocity(self, p_prime):
        m = self.mesh
        grad_p_prime = self.compute_face_gradient(p_prime)
        for cid in range(self.n_cells):
            dp = grad_p_prime[cid]
            vol = m.cell_volumes[cid]
            self.flow.u[cid] -= vol * dp / (self.flow.ap[cid] if self.flow.ap[cid] > 1e-10 else 1e-10)

    def _compute_continuity_residual(self):
        m = self.mesh
        total = 0.0
        for cid in range(self.n_cells):
            div = 0.0
            for fid in range(self.n_faces):
                if m.owner[fid] == cid:
                    div += self.flow.mass_flux[fid]
                elif m.neighbour[fid] == cid:
                    div -= self.flow.mass_flux[fid]
            total += abs(div)
        return total / self.n_cells

    def _solve_turbulence(self):
        if self.turbulence_model == 'k-epsilon':
            self._solve_k_epsilon()
        elif self.turbulence_model == 'k-omega-sst':
            self._solve_k_omega_sst()

    def _solve_k_epsilon(self):
        from ..turbulence.k_epsilon import solve_k_epsilon
        self.flow.turb_k, self.flow.turb_epsilon, self.flow.nu_t = solve_k_epsilon(
            self.mesh, self.flow, self.nu, self.dt, self.underrelaxation, self.bc_manager
        )

    def _solve_k_omega_sst(self):
        from ..turbulence.k_omega_sst import solve_k_omega_sst
        self.flow.turb_k, self.flow.turb_omega, self.flow.nu_t = solve_k_omega_sst(
            self.mesh, self.flow, self.nu, self.dt, self.underrelaxation, self.bc_manager
        )
