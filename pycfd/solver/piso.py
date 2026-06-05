import numpy as np
from .simple import SimpleSolver
from ..core.jit import njit

class PisoSolver(SimpleSolver):
    def __init__(self, mesh, bc_manager=None, turbulence_model=None, **kwargs):
        super().__init__(mesh, bc_manager, turbulence_model, **kwargs)
        self.n_correctors = 2

    def _solve_inner_iteration(self):
        residuals = {}
        m = self.mesh
        self.flow.u_prev[:] = self.flow.u
        self.flow.p_prev[:] = self.flow.p
        self.flow.grad_p = self.compute_face_gradient(self.flow.p)
        self._compute_mass_flux()
        for direction in range(self.ndim):
            A, b, ap = self.assemble_momentum_matrix(direction)
            if self.bc_manager:
                A, b = self.bc_manager.apply_velocity_bc(A, b, direction, self.flow)
            u_star = A.solve(b, method='spsolve')
            self.flow.u_star[:, direction] = u_star
            residuals[f'u{direction}'] = np.linalg.norm(u_star - self.flow.u[:, direction]) / (np.linalg.norm(u_star) + 1e-12)
        self.flow.u = self.flow.u_star.copy()
        for corr in range(self.n_correctors):
            A_p, b_p = self._assemble_pressure_correction_matrix()
            if self.bc_manager:
                A_p, b_p = self.bc_manager.apply_pressure_bc(A_p, b_p, self.flow)
            p_prime = A_p.solve(b_p, method='spsolve')
            self._update_pressure_and_velocity_piso(p_prime, corr)
            self._compute_mass_flux()
            for direction in range(self.ndim):
                self._correct_velocity(direction)
        cont_res = self._compute_continuity_residual()
        residuals['p'] = np.linalg.norm(p_prime) / (np.linalg.norm(self.flow.p) + 1e-12)
        residuals['continuity'] = cont_res
        if self.turbulence:
            self._solve_turbulence()
        ur_u = self.underrelaxation['u']
        ur_p = self.underrelaxation['p']
        self.flow.u[:] = self.flow.u_prev + ur_u * (self.flow.u - self.flow.u_prev)
        self.flow.p[:] = self.flow.p_prev + ur_p * (self.flow.p - self.flow.p_prev)
        return residuals

    def _update_pressure_and_velocity_piso(self, p_prime, corrector_step):
        m = self.mesh
        ur_p = self.underrelaxation['p'] if corrector_step == 0 else 1.0
        self.flow.p[:] += ur_p * p_prime
        for cid in range(self.n_cells):
            dp = self.compute_face_gradient(p_prime)[cid]
            vol = m.cell_volumes[cid]
            a_p = self.flow.ap[cid] if self.flow.ap[cid] > 1e-10 else 1e-10
            self.flow.u[cid] -= vol * dp / a_p

    def _correct_velocity(self, direction):
        m = self.mesh
        A, b, ap = self.assemble_momentum_matrix(direction)
        self.flow.ap = ap
        u = A.solve(b, method='spsolve')
        self.flow.u[:, direction] = u

    def _assemble_pressure_correction_matrix(self):
        m = self.mesh
        n = self.n_cells
        from ..core.sparse import SparseMatrix
        A = SparseMatrix(n)
        b = np.zeros(n, dtype=np.float64)
        self.flow.ap = np.ones(n, dtype=np.float64) * 1e10
        for fid in range(self.n_faces):
            c1 = m.owner[fid]
            c2 = m.neighbour[fid]
            if c2 < 0:
                continue
            d = np.linalg.norm(m.cell_centers[c2] - m.cell_centers[c1])
            d_coeff = m.face_areas[fid] / (d + 1e-15)
            area = m.face_areas[fid]
            mf = self.flow.mass_flux[fid]
            a1 = self.rho * d_coeff * area * area / (0.5 * (self.flow.ap[c1] + self.flow.ap[c2]))
            A.add_value(c1, c1, a1)
            A.add_value(c1, c2, -a1)
            A.add_value(c2, c2, a1)
            A.add_value(c2, c1, -a1)
            b[c1] -= mf
            b[c2] += mf
        return A, b
