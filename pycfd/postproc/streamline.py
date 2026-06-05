import numpy as np
from ..core.jit import njit, prange

def trace_streamline(mesh, velocity, start_point, max_steps=1000, dt=0.01, max_length=None):
    start = np.asarray(start_point, dtype=np.float64)
    positions = [start.copy()]
    current = start.copy()
    direction = 1
    for step in range(max_steps):
        vel = _interpolate_velocity(mesh, velocity, current)
        if np.linalg.norm(vel) < 1e-15:
            break
        vel_norm = vel / np.linalg.norm(vel)
        if max_length is not None:
            ds = min(dt * np.linalg.norm(vel), max_length)
        else:
            ds = dt * np.linalg.norm(vel)
        next_pos = current + direction * ds * vel_norm
        if not _point_in_domain(mesh, next_pos):
            break
        positions.append(next_pos.copy())
        current = next_pos
    return np.array(positions)

def trace_streamlines(mesh, velocity, start_points, **kwargs):
    streamlines = []
    for sp in start_points:
        sl = trace_streamline(mesh, velocity, sp, **kwargs)
        streamlines.append(sl)
    return streamlines

@njit
def _interpolate_velocity_jit(cell_centers, velocity, point, n_cells, ndim):
    distances = np.zeros(n_cells, dtype=np.float64)
    for cid in range(n_cells):
        dd = 0.0
        for d in range(ndim):
            dd += (cell_centers[cid, d] - point[d]) ** 2
        distances[cid] = dd
    idx = 0
    min_d = distances[0]
    for cid in range(1, n_cells):
        if distances[cid] < min_d:
            min_d = distances[cid]
            idx = cid
    return velocity[idx].copy()

def _interpolate_velocity(mesh, velocity, point):
    point = np.asarray(point, dtype=np.float64)
    distances = np.sum((mesh.cell_centers - point) ** 2, axis=1)
    nearest = np.argsort(distances)[:4]
    weights = 1.0 / (np.sqrt(distances[nearest]) + 1e-15)
    weights /= np.sum(weights)
    vel = np.zeros(mesh.ndim, dtype=np.float64)
    for i, cid in enumerate(nearest):
        vel += weights[i] * velocity[cid]
    return vel

def _point_in_domain(mesh, point):
    if hasattr(mesh, 'x_range'):
        if point[0] < mesh.x_range[0] or point[0] > mesh.x_range[1]:
            return False
    if hasattr(mesh, 'y_range') and mesh.ndim >= 2:
        if point[1] < mesh.y_range[0] or point[1] > mesh.y_range[1]:
            return False
    if hasattr(mesh, 'z_range') and mesh.ndim >= 3:
        if point[2] < mesh.z_range[0] or point[2] > mesh.z_range[1]:
            return False
    return True

def compute_stream_function(mesh, velocity):
    if mesh.ndim != 2:
        raise ValueError("Stream function only defined for 2D flows")
    if hasattr(mesh, 'nx') and hasattr(mesh, 'ny'):
        nx, ny = mesh.nx, mesh.ny
        psi = np.zeros((nx, ny), dtype=np.float64)
        u = velocity[:, 0].reshape(nx-1, ny-1)
        v = velocity[:, 1].reshape(nx-1, ny-1)
        centers_x = mesh.cell_centers[:, 0].reshape(nx-1, ny-1)
        centers_y = mesh.cell_centers[:, 1].reshape(nx-1, ny-1)
        for j in range(ny):
            for i in range(nx):
                if i > 0 and j > 0:
                    dx = centers_x[i-1, j-1] - (mesh.x_range[0] if i == 1 else centers_x[i-2, j-1])
                    dy = centers_y[i-1, j-1] - (mesh.y_range[0] if j == 1 else centers_y[i-1, j-2])
                    psi[i, j] = psi[i-1, j] - (v[i-1, j-1] if j-1 < ny-1 else 0) * dx
                    psi[i, j] = psi[i, j-1] + (u[i-1, j-1] if i-1 < nx-1 else 0) * dy
                elif i > 0:
                    dx = centers_x[i-1, 0] - mesh.x_range[0]
                    psi[i, 0] = psi[i-1, 0] - (v[i-1, 0] if j < ny-1 else 0) * dx
                elif j > 0:
                    dy = centers_y[0, j-1] - mesh.y_range[0]
                    psi[0, j] = psi[0, j-1] + (u[0, j-1] if i < nx-1 else 0) * dy
        return psi
    else:
        psi = np.zeros(mesh.n_cells, dtype=np.float64)
        for cid in range(mesh.n_cells):
            cx, cy = mesh.cell_centers[cid, 0], mesh.cell_centers[cid, 1]
            for fid in range(mesh.n_faces):
                if mesh.owner[fid] == cid:
                    nf = mesh.face_normals[fid]
                    area = mesh.face_areas[fid]
                    fc = mesh.face_centers[fid]
                    psi[cid] += (nf[1] * fc[0] - nf[0] * fc[1]) * area * 0.5
        return psi

def compute_velocity_magnitude(velocity):
    return np.linalg.norm(velocity, axis=1)

def compute_particle_pathline(mesh, velocity_field, start_point, times, dt=0.001):
    positions = [np.asarray(start_point, dtype=np.float64)]
    current = positions[0].copy()
    n_steps = int((times[-1] - times[0]) / dt)
    for t_idx in range(len(times) - 1):
        vel_field = velocity_field[t_idx]
        local_dt = times[t_idx + 1] - times[t_idx]
        for _ in range(int(local_dt / dt)):
            k1 = _interpolate_velocity(mesh, vel_field, current)
            k2 = _interpolate_velocity(mesh, vel_field, current + 0.5 * dt * k1)
            k3 = _interpolate_velocity(mesh, vel_field, current + 0.5 * dt * k2)
            k4 = _interpolate_velocity(mesh, vel_field, current + dt * k3)
            current = current + dt * (k1 + 2*k2 + 2*k3 + k4) / 6.0
            if not _point_in_domain(mesh, current):
                break
        positions.append(current.copy())
    return np.array(positions)

def compute_recirculation_zones(mesh, velocity, threshold=1e-3):
    u_mag = np.linalg.norm(velocity, axis=1)
    recirc_cells = u_mag < threshold
    zones = []
    visited = set()
    for cid in range(mesh.n_cells):
        if cid in visited or not recirc_cells[cid]:
            continue
        zone = []
        stack = [cid]
        while stack:
            current = stack.pop()
            if current in visited:
                continue
            visited.add(current)
            zone.append(current)
            for nb in mesh.get_neighbors(current):
                if nb not in visited and recirc_cells[nb]:
                    stack.append(nb)
        if len(zone) > 3:
            zones.append({
                'cells': zone,
                'center': np.mean(mesh.cell_centers[zone], axis=0),
                'volume': np.sum(mesh.cell_volumes[zone]),
                'mean_velocity': np.mean(velocity[zone], axis=0)
            })
    return zones

def compute_mass_flow_rate(mesh, velocity, face_indices=None):
    if face_indices is None:
        face_indices = range(mesh.n_faces)
    mdot = 0.0
    for fid in face_indices:
        nf = mesh.face_normals[fid]
        area = mesh.face_areas[fid]
        c1 = mesh.owner[fid]
        c2 = mesh.neighbour[fid]
        if c2 >= 0:
            vel = 0.5 * (velocity[c1] + velocity[c2])
        else:
            vel = velocity[c1]
        mdot += np.dot(vel, nf) * area
    return mdot

def compute_total_pressure(mesh, pressure, velocity, rho=1.0):
    u_mag = np.linalg.norm(velocity, axis=1)
    return pressure + 0.5 * rho * u_mag ** 2

def compute_flow_separation_points(mesh, velocity, wall_boundary_ids=None):
    separation_points = []
    if wall_boundary_ids is None:
        wall_boundary_ids = [id for id in mesh.boundary_map.keys() if 'wall' in mesh.boundary_map[id].lower()]
    for bid in wall_boundary_ids:
        faces = mesh.get_boundary_faces(bid)
        prev_shear_sign = None
        for fid in faces:
            c1 = mesh.owner[fid]
            nf = mesh.face_normals[fid]
            tangent = np.array([-nf[1], nf[0]]) if mesh.ndim == 2 else np.cross(nf, np.array([0, 0, 1]))
            shear = np.dot(velocity[c1], tangent)
            if prev_shear_sign is not None and np.sign(shear) != np.sign(prev_shear_sign):
                if prev_shear_sign > 0:
                    separation_points.append({
                        'face_id': fid,
                        'position': mesh.face_centers[fid],
                        'type': 'separation' if shear < 0 else 'reattachment'
                    })
            prev_shear_sign = shear
    return separation_points
