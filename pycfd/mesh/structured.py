import numpy as np
from .base import StructuredMesh

def create_2d_structured_mesh(nx, ny, x_range=(0, 1), y_range=(0, 1), 
                              stretching='uniform', stretch_params=None,
                              tanh_strength=None, stretching_ratio=None):
    if stretch_params is None:
        stretch_params = {}
    if tanh_strength is not None:
        stretch_params['x_beta'] = tanh_strength
        stretch_params['y_beta'] = tanh_strength
    if stretching_ratio is not None:
        stretch_params['ratio'] = stretching_ratio
    if stretching == 'uniform':
        return StructuredMesh(nx, ny, x_range=x_range, y_range=y_range)
    elif stretching == 'tanh':
        return _create_2d_tanh_stretched(nx, ny, x_range, y_range, stretch_params)
    elif stretching == 'geometric':
        return _create_2d_geometric_stretched(nx, ny, x_range, y_range, stretch_params)
    else:
        raise ValueError(f"Unknown stretching type: {stretching}")

def _create_2d_tanh_stretched(nx, ny, x_range, y_range, stretch_params):
    params = stretch_params or {}
    x_beta = params.get('x_beta', 1.0)
    y_beta = params.get('y_beta', 1.0)
    x = np.linspace(-1, 1, nx)
    y = np.linspace(-1, 1, ny)
    x_stretched = np.tanh(x_beta * x) / np.tanh(x_beta)
    y_stretched = np.tanh(y_beta * y) / np.tanh(y_beta)
    x_scaled = x_range[0] + (x_range[1] - x_range[0]) * (x_stretched + 1) / 2
    y_scaled = y_range[0] + (y_range[1] - y_range[0]) * (y_stretched + 1) / 2
    mesh = StructuredMesh(nx, ny, x_range=x_range, y_range=y_range)
    for i in range(nx):
        for j in range(ny):
            idx = i * ny + j
            mesh.points[idx, 0] = x_scaled[i]
            mesh.points[idx, 1] = y_scaled[j]
    mesh._compute_geometry()
    mesh._setup_structured_boundaries()
    return mesh

def _create_2d_geometric_stretched(nx, ny, x_range, y_range, stretch_params):
    params = stretch_params or {}
    x_ratio = params.get('x_ratio', 1.0)
    y_ratio = params.get('y_ratio', 1.0)
    x = _geometric_sequence(nx, x_range[0], x_range[1], x_ratio)
    y = _geometric_sequence(ny, y_range[0], y_range[1], y_ratio)
    mesh = StructuredMesh(nx, ny, x_range=x_range, y_range=y_range)
    for i in range(nx):
        for j in range(ny):
            idx = i * ny + j
            mesh.points[idx, 0] = x[i]
            mesh.points[idx, 1] = y[j]
    mesh._compute_geometry()
    mesh._setup_structured_boundaries()
    return mesh

def _geometric_sequence(n, start, end, ratio):
    if ratio == 1.0:
        return np.linspace(start, end, n)
    L = end - start
    if ratio > 1:
        r = ratio ** (1.0 / (n - 1))
        positions = np.zeros(n)
        total = (1 - r ** (n - 1)) / (1 - r)
        for i in range(n):
            if i == 0:
                positions[i] = 0
            else:
                positions[i] = positions[i-1] + r ** (i - 1) / total
    else:
        r = (1.0 / ratio) ** (1.0 / (n - 1))
        positions = np.zeros(n)
        total = (1 - r ** (n - 1)) / (1 - r)
        for i in range(n):
            if i == 0:
                positions[i] = 0
            else:
                positions[i] = positions[i-1] + r ** (i - 1) / total
        positions = positions[::-1]
    return start + L * positions

def create_3d_structured_mesh(nx, ny, nz, x_range=(0, 1), y_range=(0, 1), z_range=(0, 1)):
    return StructuredMesh(nx, ny, nz, x_range, y_range, z_range)

def refine_2d_structured(mesh, refinement_levels=1):
    for _ in range(refinement_levels):
        if mesh.ndim != 2:
            raise ValueError("Only 2D mesh supported for structured refinement")
        nx_new = 2 * mesh.nx - 1
        ny_new = 2 * mesh.ny - 1
        new_mesh = StructuredMesh(nx_new, ny_new, mesh.x_range, mesh.y_range)
        old_x = np.array([mesh.points[i * mesh.ny, 0] for i in range(mesh.nx)])
        old_y = np.array([mesh.points[j, 1] for j in range(mesh.ny)])
        new_x = np.zeros(nx_new)
        new_y = np.zeros(ny_new)
        new_x[0::2] = old_x
        new_x[1::2] = 0.5 * (old_x[:-1] + old_x[1:])
        new_y[0::2] = old_y
        new_y[1::2] = 0.5 * (old_y[:-1] + old_y[1:])
        for i in range(nx_new):
            for j in range(ny_new):
                idx = i * ny_new + j
                new_mesh.points[idx, 0] = new_x[i]
                new_mesh.points[idx, 1] = new_y[j]
        new_mesh._compute_geometry()
        new_mesh._setup_structured_boundaries()
        mesh = new_mesh
    return mesh

def coarsen_2d_structured(mesh, coarsening_levels=1):
    for _ in range(coarsening_levels):
        if mesh.ndim != 2:
            raise ValueError("Only 2D mesh supported for structured coarsening")
        nx_new = (mesh.nx + 1) // 2
        ny_new = (mesh.ny + 1) // 2
        if nx_new < 2 or ny_new < 2:
            raise ValueError("Cannot coarsen further")
        new_mesh = StructuredMesh(nx_new, ny_new, mesh.x_range, mesh.y_range)
        old_x = np.array([mesh.points[i * mesh.ny, 0] for i in range(mesh.nx)])
        old_y = np.array([mesh.points[j, 1] for j in range(mesh.ny)])
        for i in range(nx_new):
            for j in range(ny_new):
                idx = i * ny_new + j
                new_mesh.points[idx, 0] = old_x[2*i]
                new_mesh.points[idx, 1] = old_y[2*j]
        new_mesh._compute_geometry()
        new_mesh._setup_structured_boundaries()
        mesh = new_mesh
    return mesh
