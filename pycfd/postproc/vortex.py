import numpy as np
from ..core.jit import njit, prange

def compute_vorticity(mesh, velocity):
    ndim = mesh.ndim
    n = mesh.n_cells
    if ndim == 2:
        vorticity = np.zeros(n, dtype=np.float64)
        _compute_vorticity_2d_jit(
            mesh.cell_centers, mesh.faces, mesh.face_centers, mesh.face_normals,
            mesh.face_areas, mesh.owner, mesh.neighbour, velocity, vorticity
        )
        return vorticity
    else:
        vorticity = np.zeros((n, 3), dtype=np.float64)
        _compute_vorticity_3d_jit(
            mesh.cell_centers, mesh.faces, mesh.face_centers, mesh.face_normals,
            mesh.face_areas, mesh.owner, mesh.neighbour, velocity, vorticity
        )
        return vorticity

@njit
def _compute_vorticity_2d_jit(cell_centers, faces, face_centers, face_normals, face_areas,
                               owner, neighbour, velocity, vorticity):
    n_cells = len(cell_centers)
    n_faces = len(face_centers)
    for fid in range(n_faces):
        c1 = owner[fid]
        c2 = neighbour[fid]
        if c2 < 0:
            continue
        nf = face_normals[fid]
        area = face_areas[fid]
        dudy = velocity[c2, 1] - velocity[c1, 1]
        dvdx = velocity[c2, 0] - velocity[c1, 0]
        vorticity[c1] += (dvdx * nf[1] - dudy * nf[0]) * area
        vorticity[c2] -= (dvdx * nf[1] - dudy * nf[0]) * area
    for cid in range(n_cells):
        vorticity[cid] /= max(cell_centers.shape[0], 1)

@njit
def _compute_vorticity_3d_jit(cell_centers, faces, face_centers, face_normals, face_areas,
                               owner, neighbour, velocity, vorticity):
    n_cells = len(cell_centers)
    n_faces = len(face_centers)
    for fid in range(n_faces):
        c1 = owner[fid]
        c2 = neighbour[fid]
        if c2 < 0:
            continue
        nf = face_normals[fid]
        area = face_areas[fid]
        du = velocity[c2] - velocity[c1]
        omega = np.array([
            du[2] * nf[1] - du[1] * nf[2],
            du[0] * nf[2] - du[2] * nf[0],
            du[1] * nf[0] - du[0] * nf[1]
        ])
        vorticity[c1] += omega * area
        vorticity[c2] -= omega * area
    for cid in range(n_cells):
        vorticity[cid] /= max(cell_centers.shape[0], 1)

def compute_q_criterion(mesh, velocity):
    grad_u = _compute_velocity_gradient(mesh, velocity)
    ndim = mesh.ndim
    n = mesh.n_cells
    Q = np.zeros(n, dtype=np.float64)
    for cid in range(n):
        S = 0.5 * (grad_u[cid] + grad_u[cid].T)
        Omega = 0.5 * (grad_u[cid] - grad_u[cid].T)
        Q[cid] = 0.5 * (np.linalg.norm(Omega) ** 2 - np.linalg.norm(S) ** 2)
    return Q

def compute_lambda2(mesh, velocity):
    grad_u = _compute_velocity_gradient(mesh, velocity)
    ndim = mesh.ndim
    n = mesh.n_cells
    lambda2 = np.zeros(n, dtype=np.float64)
    for cid in range(n):
        S = 0.5 * (grad_u[cid] + grad_u[cid].T)
        Omega = 0.5 * (grad_u[cid] - grad_u[cid].T)
        S2_Omega2 = S @ S + Omega @ Omega
        eigvals = np.linalg.eigvalsh(S2_Omega2)
        eigvals_sorted = np.sort(eigvals)
        if len(eigvals_sorted) >= 2:
            lambda2[cid] = eigvals_sorted[1]
        else:
            lambda2[cid] = eigvals_sorted[0]
    return lambda2

def compute_delta_criterion(mesh, velocity):
    grad_u = _compute_velocity_gradient(mesh, velocity)
    ndim = mesh.ndim
    n = mesh.n_cells
    Delta = np.zeros(n, dtype=np.float64)
    for cid in range(n):
        J = grad_u[cid]
        if ndim == 2:
            q = np.linalg.det(J)
            p = np.trace(J)
            Delta[cid] = (p / 3) ** 3 - p * np.trace(J @ J) / 6 + q
        else:
            eigvals = np.linalg.eigvals(J)
            if np.all(np.iscomplex(eigvals)):
                Delta[cid] = 1.0
            else:
                Delta[cid] = -1.0
    return Delta

def extract_vortices(mesh, vorticity, threshold=None, method='q_criterion'):
    if threshold is None:
        threshold = np.mean(vorticity) + 2 * np.std(vorticity)
    if method == 'q_criterion':
        indicator = compute_q_criterion(mesh, vorticity) if vorticity.ndim > 1 else vorticity
    elif method == 'lambda2':
        indicator = compute_lambda2(mesh, vorticity) if vorticity.ndim > 1 else vorticity
    else:
        indicator = vorticity
    vortex_cells = np.where(np.abs(indicator) > threshold)[0]
    return vortex_cells, indicator

def extract_isosurface(mesh, field, isovalue):
    pass

def identify_vortex_cores(mesh, vorticity, velocity):
    Q = compute_q_criterion(mesh, velocity)
    cores = []
    visited = set()
    for cid in range(mesh.n_cells):
        if cid in visited or Q[cid] <= 0:
            continue
        region = []
        stack = [cid]
        while stack:
            current = stack.pop()
            if current in visited:
                continue
            visited.add(current)
            region.append(current)
            for nb in mesh.get_neighbors(current):
                if nb not in visited and Q[nb] > 0:
                    stack.append(nb)
        if len(region) > 3:
            cores.append({
                'cells': region,
                'center': np.mean(mesh.cell_centers[region], axis=0),
                'strength': np.mean(np.linalg.norm(vorticity[region], axis=1) if vorticity.ndim > 1 else vorticity[region]),
                'volume': np.sum(mesh.cell_volumes[region])
            })
    return cores

def compute_vortex_stretching(mesh, velocity, vorticity):
    grad_u = _compute_velocity_gradient(mesh, velocity)
    n = mesh.n_cells
    stretching = np.zeros(n, dtype=np.float64)
    for cid in range(n):
        omega = vorticity[cid] if vorticity.ndim > 1 else np.array([0, 0, vorticity[cid]])
        S = 0.5 * (grad_u[cid] + grad_u[cid].T)
        if grad_u.shape[1] == 2:
            omega_3d = np.array([0, 0, omega[2] if len(omega) > 2 else omega[0]])
            S_3d = np.zeros((3, 3))
            S_3d[:2, :2] = S
            stretching[cid] = np.dot(omega_3d, S_3d @ omega_3d) / (np.linalg.norm(omega_3d) ** 2 + 1e-15)
        else:
            stretching[cid] = np.dot(omega, S @ omega) / (np.linalg.norm(omega) ** 2 + 1e-15)
    return stretching

def _compute_velocity_gradient(mesh, velocity):
    n = mesh.n_cells
    ndim = mesh.ndim
    grad = np.zeros((n, ndim, ndim), dtype=np.float64)
    for d in range(ndim):
        u = velocity[:, d]
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

def compute_helicity(mesh, velocity, vorticity):
    n = mesh.n_cells
    helicity = np.zeros(n, dtype=np.float64)
    for cid in range(n):
        u = velocity[cid]
        omega = vorticity[cid] if vorticity.ndim > 1 else np.array([0, 0, vorticity[cid]])
        if len(u) == 2:
            u_3d = np.array([u[0], u[1], 0])
            helicity[cid] = np.dot(u_3d, omega)
        else:
            helicity[cid] = np.dot(u, omega)
    return helicity
