import numpy as np
import matplotlib.pyplot as plt
from matplotlib import cm
from typing import List, Tuple, Optional

def plot_along_line(mesh, field, start, end, n_points=100, ax=None, **kwargs):
    start = np.asarray(start, dtype=np.float64)
    end = np.asarray(end, dtype=np.float64)
    t = np.linspace(0, 1, n_points)
    points = start + np.outer(t, end - start)
    values = np.zeros(n_points, dtype=np.float64)
    for i, p in enumerate(points):
        values[i] = _interpolate_field(mesh, field, p)
    distances = np.linalg.norm(points - start, axis=1)
    if ax is None:
        fig, ax = plt.subplots()
    ax.plot(distances, values, **kwargs)
    ax.set_xlabel('Distance')
    ax.set_ylabel('Field value')
    ax.grid(True, alpha=0.3)
    return ax

def plot_velocity_profile(mesh, velocity, axis='x', y=None, z=0, ax=None, save_path=None, **kwargs):
    import matplotlib.pyplot as plt
    if ax is None:
        fig, ax = plt.subplots()
    if mesh.ndim == 2:
        if y is None:
            y = (mesh.y_range[0] + mesh.y_range[1]) / 2
        if axis == 'x':
            start = [mesh.x_range[0], y]
            end = [mesh.x_range[1], y]
        else:
            start = [y, mesh.y_range[0]]
            end = [y, mesh.y_range[1]]
    else:
        if y is None:
            y = (mesh.y_range[0] + mesh.y_range[1]) / 2
        if z is None:
            z = (mesh.z_range[0] + mesh.z_range[1]) / 2
        if axis == 'x':
            start = [mesh.x_range[0], y, z]
            end = [mesh.x_range[1], y, z]
        elif axis == 'y':
            start = [y, mesh.y_range[0], z]
            end = [y, mesh.y_range[1], z]
        else:
            start = [y, z, mesh.z_range[0]]
            end = [y, z, mesh.z_range[1]]
    vel_mag = np.linalg.norm(velocity, axis=1)
    ax = plot_along_line(mesh, vel_mag, start, end, ax=ax, **kwargs)
    ax.set_ylabel('Velocity magnitude')
    if save_path is not None:
        plt.savefig(save_path, dpi=150, bbox_inches='tight')
        plt.close()
    return ax

def plot_pressure_distribution(mesh, pressure, y=None, z=0, ax=None, **kwargs):
    if mesh.ndim == 2:
        if y is None:
            y = (mesh.y_range[0] + mesh.y_range[1]) / 2
        start = [mesh.x_range[0], y]
        end = [mesh.x_range[1], y]
    else:
        if y is None:
            y = (mesh.y_range[0] + mesh.y_range[1]) / 2
        if z is None:
            z = (mesh.z_range[0] + mesh.z_range[1]) / 2
        start = [mesh.x_range[0], y, z]
        end = [mesh.x_range[1], y, z]
    ax = plot_along_line(mesh, pressure, start, end, ax=ax, **kwargs)
    ax.set_ylabel('Pressure')
    return ax

def plot_contour(mesh, field, levels=20, ax=None, cmap='viridis', **kwargs):
    if mesh.ndim != 2:
        raise ValueError("Contour plot only supported for 2D meshes")
    if hasattr(mesh, 'nx') and hasattr(mesh, 'ny'):
        X = mesh.points[:, 0].reshape(mesh.nx, mesh.ny)
        Y = mesh.points[:, 1].reshape(mesh.nx, mesh.ny)
        cell_centers_x = mesh.cell_centers[:, 0].reshape(mesh.nx-1, mesh.ny-1)
        cell_centers_y = mesh.cell_centers[:, 1].reshape(mesh.nx-1, mesh.ny-1)
        Z = field.reshape(mesh.nx-1, mesh.ny-1)
        if ax is None:
            fig, ax = plt.subplots()
        contour = ax.contourf(cell_centers_x, cell_centers_y, Z, levels=levels, cmap=cmap, **kwargs)
        plt.colorbar(contour, ax=ax)
    else:
        x = mesh.cell_centers[:, 0]
        y = mesh.cell_centers[:, 1]
        if ax is None:
            fig, ax = plt.subplots()
        contour = ax.tricontourf(x, y, field, levels=levels, cmap=cmap, **kwargs)
        plt.colorbar(contour, ax=ax)
    ax.set_xlabel('x')
    ax.set_ylabel('y')
    return ax

def plot_residuals(residuals, ax=None, log_scale=True, **kwargs):
    if ax is None:
        fig, ax = plt.subplots()
    for name, res in residuals.items():
        if len(res) > 0:
            ax.plot(res, label=name, **kwargs)
    if log_scale:
        ax.set_yscale('log')
    ax.set_xlabel('Iteration')
    ax.set_ylabel('Residual')
    ax.legend()
    ax.grid(True, alpha=0.3)
    return ax

def plot_mesh(mesh, ax=None, show_centers=False, **kwargs):
    if ax is None:
        fig, ax = plt.subplots()
    if mesh.ndim == 2:
        for cell in mesh.cells:
            nodes = mesh.points[cell]
            nodes = np.vstack([nodes, nodes[0:1]])
            ax.plot(nodes[:, 0], nodes[:, 1], 'k-', linewidth=0.5, **kwargs)
        if show_centers:
            ax.plot(mesh.cell_centers[:, 0], mesh.cell_centers[:, 1], 'ro', markersize=3)
    else:
        from mpl_toolkits.mplot3d import Axes3D
        if not hasattr(ax, 'get_zlim'):
            fig = plt.gcf()
            ax = fig.add_subplot(111, projection='3d')
        for cell in mesh.cells:
            faces = mesh._get_cell_faces(cell)
            for face in faces:
                nodes = mesh.points[face]
                nodes = np.vstack([nodes, nodes[0:1]])
                ax.plot(nodes[:, 0], nodes[:, 1], nodes[:, 2], 'k-', linewidth=0.5, **kwargs)
        if show_centers:
            ax.plot(mesh.cell_centers[:, 0], mesh.cell_centers[:, 1], mesh.cell_centers[:, 2], 'ro', markersize=3)
    ax.set_xlabel('x')
    ax.set_ylabel('y')
    if mesh.ndim == 3:
        ax.set_zlabel('z')
    ax.set_aspect('equal')
    return ax

def plot_vector_field(mesh, velocity, ax=None, step=1, scale=1.0, **kwargs):
    if mesh.ndim != 2:
        raise ValueError("Vector field plot only supported for 2D")
    if ax is None:
        fig, ax = plt.subplots()
    centers = mesh.cell_centers[::step]
    u = velocity[::step, 0]
    v = velocity[::step, 1]
    ax.quiver(centers[:, 0], centers[:, 1], u, v, scale=scale, **kwargs)
    ax.set_xlabel('x')
    ax.set_ylabel('y')
    ax.set_aspect('equal')
    return ax

def _interpolate_field(mesh, field, point):
    point = np.asarray(point, dtype=np.float64)
    distances = np.sum((mesh.cell_centers - point) ** 2, axis=1)
    nearest = np.argsort(distances)[:4]
    weights = 1.0 / (np.sqrt(distances[nearest]) + 1e-15)
    weights /= np.sum(weights)
    return np.sum(weights * field[nearest])

def compare_profiles(mesh_list, field_list, labels, start, end, ax=None, **kwargs):
    if ax is None:
        fig, ax = plt.subplots()
    for mesh, field, label in zip(mesh_list, field_list, labels):
        plot_along_line(mesh, field, start, end, ax=ax, label=label, **kwargs)
    ax.legend()
    return ax
