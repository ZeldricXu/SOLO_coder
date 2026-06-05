import numpy as np
from ..core.jit import njit, prange
from ..core.sparse import SparseMatrix

def solve_pressure_correction(mesh, velocity, pressure, mass_flux, ap, dt, rho):
    n = mesh.n_cells
    A = SparseMatrix(n)
    b = np.zeros(n, dtype=np.float64)
    face_areas = mesh.face_areas
    face_normals = mesh.face_normals
    owner = mesh.owner
    neighbour = mesh.neighbour
    cell_volumes = mesh.cell_volumes
    for fid in range(mesh.n_faces):
        c1 = owner[fid]
        c2 = neighbour[fid]
        if c2 < 0:
            continue
        d = np.linalg.norm(mesh.cell_centers[c2] - mesh.cell_centers[c1])
        d_coeff = face_areas[fid] / (d + 1e-15)
        a1 = rho * d_coeff * face_areas[fid] / (0.5 * (ap[c1] + ap[c2]))
        A.add_value(c1, c1, a1)
        A.add_value(c1, c2, -a1)
        A.add_value(c2, c2, a1)
        A.add_value(c2, c1, -a1)
        b[c1] -= mass_flux[fid]
        b[c2] += mass_flux[fid]
    p_prime = A.solve(b, method='spsolve')
    return p_prime, A

def rhie_chow_interpolation(mesh, velocity, pressure, ap, rho):
    n_faces = mesh.n_faces
    face_velocities = np.zeros((n_faces, mesh.ndim), dtype=np.float64)
    face_normals = mesh.face_normals
    owner = mesh.owner
    neighbour = mesh.neighbour
    grad_p = _compute_gradient(mesh, pressure)
    for fid in range(n_faces):
        c1 = owner[fid]
        c2 = neighbour[fid]
        if c2 < 0:
            face_velocities[fid] = velocity[c1]
        else:
            w = 0.5
            u_face = w * velocity[c1] + (1 - w) * velocity[c2]
            dpdn = np.dot(grad_p[c2] - grad_p[c1], mesh.cell_centers[c2] - mesh.cell_centers[c1])
            dpdn_correction = 0.5 * (1.0 / ap[c1] + 1.0 / ap[c2]) * dpdn
            face_velocities[fid] = u_face - dpdn_correction
    return face_velocities

def _compute_gradient(mesh, phi):
    grad = np.zeros((mesh.n_cells, mesh.ndim), dtype=np.float64)
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
    for cid in range(mesh.n_cells):
        grad[cid] /= mesh.cell_volumes[cid]
    return grad

def pressure_weighted_interpolation(mesh, phi, pressure, density=1.0):
    n_faces = mesh.n_faces
    face_values = np.zeros(n_faces, dtype=np.float64)
    for fid in range(n_faces):
        c1 = mesh.owner[fid]
        c2 = mesh.neighbour[fid]
        if c2 < 0:
            face_values[fid] = phi[c1]
        else:
            p1 = pressure[c1]
            p2 = pressure[c2]
            w = p2 / (p1 + p2 + 1e-15)
            face_values[fid] = w * phi[c1] + (1 - w) * phi[c2]
    return face_values

def compute_face_pressure_gradient(mesh, pressure):
    n_faces = mesh.n_faces
    grad_p = np.zeros((n_faces, mesh.ndim), dtype=np.float64)
    for fid in range(n_faces):
        c1 = mesh.owner[fid]
        c2 = mesh.neighbour[fid]
        if c2 < 0:
            c2 = c1
        d = mesh.cell_centers[c2] - mesh.cell_centers[c1]
        d_mag = np.linalg.norm(d) + 1e-15
        dp = pressure[c2] - pressure[c1]
        grad_p[fid] = dp * d / (d_mag * d_mag)
    return grad_p
