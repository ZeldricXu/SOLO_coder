import numpy as np
from ..core.jit import njit
from ..core.sparse import SparseMatrix

class KEpsilonModel:
    def __init__(self, C_mu=0.09, C1=1.44, C2=1.92, sigma_k=1.0, sigma_epsilon=1.3):
        self.C_mu = C_mu
        self.C1 = C1
        self.C2 = C2
        self.sigma_k = sigma_k
        self.sigma_epsilon = sigma_epsilon

    def compute_eddy_viscosity(self, k, epsilon, rho=1.0):
        nu_t = self.C_mu * k * k / (epsilon + 1e-15)
        return nu_t

    def compute_production(self, grad_u, nu_t, k=None, epsilon=None):
        ndim = grad_u.shape[1]
        S = np.zeros_like(grad_u)
        for i in range(ndim):
            for j in range(ndim):
                S[:, i, j] = 0.5 * (grad_u[:, i, j] + grad_u[:, j, i])
        S_mag = np.zeros(grad_u.shape[0])
        for cid in range(grad_u.shape[0]):
            S_mag[cid] = np.sqrt(2.0 * np.sum(S[cid] * S[cid]))
        P_k = 2.0 * nu_t * S_mag * S_mag
        if k is not None and epsilon is not None:
            P_k = np.minimum(P_k, 20.0 * epsilon)
        return P_k

    def assemble_k_equation(self, mesh, flow, nu, dt, underrelaxation):
        n = mesh.n_cells
        A = SparseMatrix(n)
        b = np.zeros(n, dtype=np.float64)
        nu_t = flow.nu_t
        k = flow.turb_k
        epsilon = flow.turb_epsilon
        grad_u = flow.grad_u
        P_k = self.compute_production(grad_u, nu_t)
        for fid in range(mesh.n_faces):
            c1 = mesh.owner[fid]
            c2 = mesh.neighbour[fid]
            if c2 < 0:
                continue
            d = np.linalg.norm(mesh.cell_centers[c2] - mesh.cell_centers[c1])
            d_coeff = mesh.face_areas[fid] / (d + 1e-15)
            area = mesh.face_areas[fid]
            mf = flow.mass_flux[fid]
            nu_eff = nu + nu_t[c1] / self.sigma_k
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
            A.add_value(cid, cid, vol / dt + vol * epsilon[cid] / (k[cid] + 1e-15))
            b[cid] = vol * P_k[cid] + vol * k[cid] / dt
        return A, b

    def assemble_epsilon_equation(self, mesh, flow, nu, dt, underrelaxation):
        n = mesh.n_cells
        A = SparseMatrix(n)
        b = np.zeros(n, dtype=np.float64)
        nu_t = flow.nu_t
        k = flow.turb_k
        epsilon = flow.turb_epsilon
        grad_u = flow.grad_u
        P_k = self.compute_production(grad_u, nu_t)
        for fid in range(mesh.n_faces):
            c1 = mesh.owner[fid]
            c2 = mesh.neighbour[fid]
            if c2 < 0:
                continue
            d = np.linalg.norm(mesh.cell_centers[c2] - mesh.cell_centers[c1])
            d_coeff = mesh.face_areas[fid] / (d + 1e-15)
            area = mesh.face_areas[fid]
            mf = flow.mass_flux[fid]
            nu_eff = nu + nu_t[c1] / self.sigma_epsilon
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
            A.add_value(cid, cid, vol / dt + vol * self.C2 * epsilon[cid] / (k[cid] + 1e-15))
            b[cid] = vol * self.C1 * P_k[cid] * epsilon[cid] / (k[cid] + 1e-15) + vol * epsilon[cid] / dt
        return A, b

def solve_k_epsilon(mesh, flow, nu, dt, underrelaxation, bc_manager=None):
    model = KEpsilonModel()
    flow.grad_u = _compute_velocity_gradient(mesh, flow)
    flow.nu_t = model.compute_eddy_viscosity(flow.turb_k, flow.turb_epsilon)
    A_k, b_k = model.assemble_k_equation(mesh, flow, nu, dt, underrelaxation)
    if bc_manager:
        A_k, b_k = bc_manager.apply_scalar_bc(A_k, b_k, flow.turb_k, 'k')
    k_new = A_k.solve(b_k, method='spsolve')
    A_eps, b_eps = model.assemble_epsilon_equation(mesh, flow, nu, dt, underrelaxation)
    if bc_manager:
        A_eps, b_eps = bc_manager.apply_scalar_bc(A_eps, b_eps, flow.turb_epsilon, 'epsilon')
    epsilon_new = A_eps.solve(b_eps, method='spsolve')
    ur_k = underrelaxation.get('k', 0.8)
    ur_eps = underrelaxation.get('epsilon', 0.8)
    k_new = flow.turb_k + ur_k * (k_new - flow.turb_k)
    epsilon_new = flow.turb_epsilon + ur_eps * (epsilon_new - flow.turb_epsilon)
    k_new = np.maximum(k_new, 1e-10)
    epsilon_new = np.maximum(epsilon_new, 1e-10)
    nu_t = model.compute_eddy_viscosity(k_new, epsilon_new)
    return k_new, epsilon_new, nu_t

def _compute_velocity_gradient(mesh, flow):
    n = mesh.n_cells
    ndim = mesh.ndim
    grad = np.zeros((n, ndim, ndim), dtype=np.float64)
    for d in range(ndim):
        u = flow.u[:, d]
        for fid in range(mesh.n_faces):
            c1 = mesh.owner[fid]
            c2 = mesh.neighbour[fid]
            if c2 < 0:
                continue
            du = u[c2] - u[c1]
            nf = mesh.face_normals[fid]
            area = mesh.face_areas[fid]
            grad[c1, d, :] += du * nf * area
            grad[c2, d, :] -= du * nf * area
    for cid in range(n):
        grad[cid] /= mesh.cell_volumes[cid]
    return grad
