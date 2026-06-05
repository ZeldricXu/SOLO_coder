import numpy as np
from ..core.jit import njit
from ..core.sparse import SparseMatrix

class KOmegaSSTModel:
    def __init__(self):
        self.beta_star = 0.09
        self.gamma1 = 5.0/9.0
        self.gamma2 = 0.44
        self.beta1 = 0.075
        self.beta2 = 0.0828
        self.sigma_k1 = 0.85
        self.sigma_k2 = 1.0
        self.sigma_omega1 = 0.5
        self.sigma_omega2 = 0.856
        self.a1 = 0.31

    def _F1(self, k, omega, nu, y):
        arg1 = np.sqrt(k) / (self.beta_star * omega * y + 1e-15)
        arg2 = 500.0 * nu / (y * y * omega + 1e-15)
        arg3 = 4.0 * k / (omega * y * y + 1e-15)
        arg = np.minimum(np.maximum(arg1, arg2), arg3)
        F1 = np.tanh(arg * arg * arg * arg)
        return F1

    def _F2(self, k, omega, nu, y):
        arg = np.maximum(2.0 * np.sqrt(k) / (self.beta_star * omega * y + 1e-15), 500.0 * nu / (y * y * omega + 1e-15))
        F2 = np.tanh(arg * arg)
        return F2

    def blend(self, F1, val1, val2):
        return F1 * val1 + (1 - F1) * val2

    def compute_eddy_viscosity(self, k, omega, S_mag, F2=None):
        if F2 is None:
            F2 = np.ones_like(k)
        alpha_star = self._blend_alpha(1.0, S_mag, omega)
        nu_t = alpha_star * k / (omega + 1e-15)
        return nu_t

    def _blend_alpha(self, alpha, S_mag, omega):
        arg = self.a1
        return alpha * (arg * omega + S_mag) / (1 + arg * S_mag / (omega + 1e-15))

    def compute_production(self, grad_u, nu_t, k, omega):
        ndim = grad_u.shape[1]
        n = grad_u.shape[0]
        S = np.zeros_like(grad_u)
        for i in range(ndim):
            for j in range(ndim):
                S[:, i, j] = 0.5 * (grad_u[:, i, j] + grad_u[:, j, i])
        S_mag = np.zeros(n)
        for cid in range(n):
            S_mag[cid] = np.sqrt(2.0 * np.sum(S[cid] * S[cid]))
        P_k = np.minimum(2.0 * nu_t * S_mag * S_mag, 10.0 * self.beta_star * k * omega)
        return P_k, S_mag

    def assemble_k_equation(self, mesh, flow, nu, dt, F1, F2):
        n = mesh.n_cells
        A = SparseMatrix(n)
        b = np.zeros(n, dtype=np.float64)
        nu_t = flow.nu_t
        k = flow.turb_k
        omega = flow.turb_omega
        grad_u = flow.grad_u
        sigma_k = self.blend(F1, self.sigma_k1, self.sigma_k2)
        P_k, S_mag = self.compute_production(grad_u, nu_t, k, omega)
        for fid in range(mesh.n_faces):
            c1 = mesh.owner[fid]
            c2 = mesh.neighbour[fid]
            if c2 < 0:
                continue
            d = np.linalg.norm(mesh.cell_centers[c2] - mesh.cell_centers[c1])
            d_coeff = mesh.face_areas[fid] / (d + 1e-15)
            area = mesh.face_areas[fid]
            mf = flow.mass_flux[fid]
            nu_eff = nu + nu_t[c1] / sigma_k[c1]
            diff_coeff = nu_eff * d_coeff
            if mf > 0:
                conv_coeff = mf
                A.add_value(c1, c1, conv_coeff + diff_coeff)
                A.add_value(c1, c2, -diff_coeff)
                A.add_value(c2, c2, diff_coeff)
                A.add_value(c2, c1, -conv_coeff - diff_coeff)
            else:
                conv_coeff = -mf
                A.add_value(c2, c2, conv_coeff + diff_coeff)
                A.add_value(c2, c1, -diff_coeff)
                A.add_value(c1, c1, diff_coeff)
                A.add_value(c1, c2, -conv_coeff - diff_coeff)
        for cid in range(n):
            vol = mesh.cell_volumes[cid]
            A.add_value(cid, cid, vol / dt + vol * self.beta_star * omega[cid])
            b[cid] = vol * P_k[cid] + vol * k[cid] / dt
        return A, b

    def assemble_omega_equation(self, mesh, flow, nu, dt, F1, F2):
        n = mesh.n_cells
        A = SparseMatrix(n)
        b = np.zeros(n, dtype=np.float64)
        nu_t = flow.nu_t
        k = flow.turb_k
        omega = flow.turb_omega
        grad_u = flow.grad_u
        sigma_omega = self.blend(F1, self.sigma_omega1, self.sigma_omega2)
        beta = self.blend(F1, self.beta1, self.beta2)
        gamma = self.blend(F1, self.gamma1, self.gamma2)
        P_k, S_mag = self.compute_production(grad_u, nu_t, k, omega)
        P_omega = gamma * S_mag * S_mag
        cross_diff = self._cross_diffusion(mesh, omega, F1)
        for fid in range(mesh.n_faces):
            c1 = mesh.owner[fid]
            c2 = mesh.neighbour[fid]
            if c2 < 0:
                continue
            d = np.linalg.norm(mesh.cell_centers[c2] - mesh.cell_centers[c1])
            d_coeff = mesh.face_areas[fid] / (d + 1e-15)
            area = mesh.face_areas[fid]
            mf = flow.mass_flux[fid]
            nu_eff = nu + nu_t[c1] / sigma_omega[c1]
            diff_coeff = nu_eff * d_coeff
            if mf > 0:
                conv_coeff = mf
                A.add_value(c1, c1, conv_coeff + diff_coeff)
                A.add_value(c1, c2, -diff_coeff)
                A.add_value(c2, c2, diff_coeff)
                A.add_value(c2, c1, -conv_coeff - diff_coeff)
            else:
                conv_coeff = -mf
                A.add_value(c2, c2, conv_coeff + diff_coeff)
                A.add_value(c2, c1, -diff_coeff)
                A.add_value(c1, c1, diff_coeff)
                A.add_value(c1, c2, -conv_coeff - diff_coeff)
        for cid in range(n):
            vol = mesh.cell_volumes[cid]
            A.add_value(cid, cid, vol / dt + vol * beta[cid] * omega[cid])
            b[cid] = vol * P_omega[cid] + vol * cross_diff[cid] + vol * omega[cid] / dt
        return A, b

    def _cross_diffusion(self, mesh, omega, F1):
        n = mesh.n_cells
        cd = np.zeros(n, dtype=np.float64)
        grad_omega = _compute_scalar_gradient(mesh, omega)
        grad_k = _compute_scalar_gradient(mesh, mesh.cell_centers[:, 0])
        for cid in range(n):
            gko = np.dot(grad_k[cid], grad_omega[cid])
            cd[cid] = 2.0 * (1 - F1[cid]) * self.sigma_omega2 / (omega[cid] + 1e-15) * gko
        return cd

def solve_k_omega_sst(mesh, flow, nu, dt, underrelaxation, bc_manager=None):
    model = KOmegaSSTModel()
    n = mesh.n_cells
    flow.grad_u = _compute_velocity_gradient(mesh, flow)
    y = np.ones(n) * 0.01
    F1 = model._F1(flow.turb_k, flow.turb_omega, nu, y)
    F2 = model._F2(flow.turb_k, flow.turb_omega, nu, y)
    flow.nu_t = model.compute_eddy_viscosity(flow.turb_k, flow.turb_omega, np.sqrt(np.sum(flow.grad_u * flow.grad_u, axis=(1, 2))), F2)
    A_k, b_k = model.assemble_k_equation(mesh, flow, nu, dt, F1, F2)
    if bc_manager:
        A_k, b_k = bc_manager.apply_scalar_bc(A_k, b_k, flow.turb_k, 'k')
    k_new = A_k.solve(b_k, method='spsolve')
    A_omega, b_omega = model.assemble_omega_equation(mesh, flow, nu, dt, F1, F2)
    if bc_manager:
        A_omega, b_omega = bc_manager.apply_scalar_bc(A_omega, b_omega, flow.turb_omega, 'omega')
    omega_new = A_omega.solve(b_omega, method='spsolve')
    ur_k = underrelaxation.get('k', 0.8)
    ur_omega = underrelaxation.get('omega', 0.8)
    k_new = flow.turb_k + ur_k * (k_new - flow.turb_k)
    omega_new = flow.turb_omega + ur_omega * (omega_new - flow.turb_omega)
    k_new = np.maximum(k_new, 1e-10)
    omega_new = np.maximum(omega_new, 1e-10)
    nu_t = model.compute_eddy_viscosity(k_new, omega_new, np.sqrt(np.sum(flow.grad_u * flow.grad_u, axis=(1, 2))), F2)
    return k_new, omega_new, nu_t

def _compute_scalar_gradient(mesh, phi):
    n = mesh.n_cells
    grad = np.zeros((n, mesh.ndim), dtype=np.float64)
    for fid in range(mesh.n_faces):
        c1 = mesh.owner[fid]
        c2 = mesh.neighbour[fid]
        if c2 < 0:
            continue
        dphi = phi[c2] - phi[c1]
        nf = mesh.face_normals[fid]
        area = mesh.face_areas[fid]
        grad[c1] += dphi * nf * area
        grad[c2] -= dphi * nf * area
    for cid in range(n):
        grad[cid] /= mesh.cell_volumes[cid]
    return grad

def _compute_velocity_gradient(mesh, flow):
    n = mesh.n_cells
    ndim = mesh.ndim
    grad = np.zeros((n, ndim, ndim), dtype=np.float64)
    for d in range(ndim):
        u = flow.u[:, d]
        grad[:, d, :] = _compute_scalar_gradient(mesh, u)
    return grad
