import numpy as np
import os
import tempfile
import shutil
from typing import List, Callable, Optional

try:
    import matplotlib.pyplot as plt
    from matplotlib import animation
    HAS_MATPLOTLIB = True
except ImportError:
    HAS_MATPLOTLIB = False

try:
    import imageio
    HAS_IMAGEIO = True
except ImportError:
    HAS_IMAGEIO = False

def animate_field(mesh, field_series, times=None, field_name='Field', 
                  filename=None, fps=10, cmap='viridis', levels=20):
    if not HAS_MATPLOTLIB:
        raise ImportError("matplotlib is required for animation")
    if times is None:
        times = np.arange(len(field_series))
    fig, ax = plt.subplots(figsize=(10, 8))
    ax.set_xlabel('x')
    ax.set_ylabel('y')
    title = ax.set_title(f'{field_name} at t = {times[0]:.4f}')
    if mesh.ndim == 2 and hasattr(mesh, 'nx') and hasattr(mesh, 'ny'):
        X = mesh.cell_centers[:, 0].reshape(mesh.nx-1, mesh.ny-1)
        Y = mesh.cell_centers[:, 1].reshape(mesh.nx-1, mesh.ny-1)
        Z = field_series[0].reshape(mesh.nx-1, mesh.ny-1)
        contour = ax.contourf(X, Y, Z, levels=levels, cmap=cmap)
        plt.colorbar(contour, ax=ax, label=field_name)
        def update(frame):
            for c in ax.collections:
                c.remove()
            Z = field_series[frame].reshape(mesh.nx-1, mesh.ny-1)
            ax.contourf(X, Y, Z, levels=levels, cmap=cmap)
            title.set_text(f'{field_name} at t = {times[frame]:.4f}')
            return ax.collections
    else:
        x = mesh.cell_centers[:, 0]
        y = mesh.cell_centers[:, 1]
        Z = field_series[0]
        contour = ax.tricontourf(x, y, Z, levels=levels, cmap=cmap)
        plt.colorbar(contour, ax=ax, label=field_name)
        def update(frame):
            for c in ax.collections:
                c.remove()
            Z = field_series[frame]
            ax.tricontourf(x, y, Z, levels=levels, cmap=cmap)
            title.set_text(f'{field_name} at t = {times[frame]:.4f}')
            return ax.collections
    ax.set_aspect('equal')
    anim = animation.FuncAnimation(
        fig, update, frames=len(field_series),
        interval=1000/fps, blit=False
    )
    if filename is not None:
        export_animation(anim, filename, fps)
    plt.close(fig)
    return anim

def animate_vectors(mesh, velocity_series, times=None, filename=None, 
                    fps=10, step=5, scale=0.1, **kwargs):
    if not HAS_MATPLOTLIB:
        raise ImportError("matplotlib is required for animation")
    if times is None:
        times = np.arange(len(velocity_series))
    fig, ax = plt.subplots(figsize=(10, 8))
    centers = mesh.cell_centers[::step]
    v0 = velocity_series[0][::step]
    u0, v0 = v0[:, 0], v0[:, 1]
    title = ax.set_title(f'Velocity field at t = {times[0]:.4f}')
    Q = ax.quiver(centers[:, 0], centers[:, 1], u0, v0, scale=scale, **kwargs)
    ax.set_xlabel('x')
    ax.set_ylabel('y')
    ax.set_aspect('equal')
    def update(frame):
        Q.set_UVC(velocity_series[frame][::step, 0], velocity_series[frame][::step, 1])
        title.set_text(f'Velocity field at t = {times[frame]:.4f}')
        return Q,
    anim = animation.FuncAnimation(
        fig, update, frames=len(velocity_series),
        interval=1000/fps, blit=False
    )
    if filename is not None:
        export_animation(anim, filename, fps)
    plt.close(fig)
    return anim

def create_flow_animation(mesh, pressure_series, velocity_series, times=None,
                          filename=None, fps=10, **kwargs):
    if not HAS_MATPLOTLIB:
        raise ImportError("matplotlib is required for animation")
    if times is None:
        times = np.arange(len(pressure_series))
    fig, axes = plt.subplots(1, 2, figsize=(16, 6))
    if hasattr(mesh, 'nx') and hasattr(mesh, 'ny'):
        X = mesh.cell_centers[:, 0].reshape(mesh.nx-1, mesh.ny-1)
        Y = mesh.cell_centers[:, 1].reshape(mesh.nx-1, mesh.ny-1)
        P0 = pressure_series[0].reshape(mesh.nx-1, mesh.ny-1)
        V0 = np.linalg.norm(velocity_series[0], axis=1).reshape(mesh.nx-1, mesh.ny-1)
        cont_p = axes[0].contourf(X, Y, P0, cmap='jet', levels=20)
        cont_v = axes[1].contourf(X, Y, V0, cmap='hot', levels=20)
        plt.colorbar(cont_p, ax=axes[0], label='Pressure')
        plt.colorbar(cont_v, ax=axes[1], label='Velocity magnitude')
        def update(frame):
            for c in axes[0].collections:
                c.remove()
            for c in axes[1].collections:
                c.remove()
            P = pressure_series[frame].reshape(mesh.nx-1, mesh.ny-1)
            V = np.linalg.norm(velocity_series[frame], axis=1).reshape(mesh.nx-1, mesh.ny-1)
            axes[0].contourf(X, Y, P, cmap='jet', levels=20)
            axes[1].contourf(X, Y, V, cmap='hot', levels=20)
            fig.suptitle(f't = {times[frame]:.4f}')
    else:
        x = mesh.cell_centers[:, 0]
        y = mesh.cell_centers[:, 1]
        cont_p = axes[0].tricontourf(x, y, pressure_series[0], cmap='jet', levels=20)
        cont_v = axes[1].tricontourf(x, y, np.linalg.norm(velocity_series[0], axis=1), cmap='hot', levels=20)
        plt.colorbar(cont_p, ax=axes[0], label='Pressure')
        plt.colorbar(cont_v, ax=axes[1], label='Velocity magnitude')
        def update(frame):
            for c in axes[0].collections:
                c.remove()
            for c in axes[1].collections:
                c.remove()
            axes[0].tricontourf(x, y, pressure_series[frame], cmap='jet', levels=20)
            axes[1].tricontourf(x, y, np.linalg.norm(velocity_series[frame], axis=1), cmap='hot', levels=20)
            fig.suptitle(f't = {times[frame]:.4f}')
    axes[0].set_title('Pressure')
    axes[1].set_title('Velocity magnitude')
    for ax in axes:
        ax.set_xlabel('x')
        ax.set_ylabel('y')
        ax.set_aspect('equal')
    anim = animation.FuncAnimation(
        fig, update, frames=len(pressure_series),
        interval=1000/fps, blit=False
    )
    if filename is not None:
        export_animation(anim, filename, fps)
    plt.close(fig)
    return anim

def animate_streamlines(mesh, velocity_series, start_points, times=None,
                        filename=None, fps=10, **kwargs):
    from .streamline import trace_streamline
    if not HAS_MATPLOTLIB:
        raise ImportError("matplotlib is required for animation")
    if times is None:
        times = np.arange(len(velocity_series))
    fig, ax = plt.subplots(figsize=(10, 8))
    ax.set_xlabel('x')
    ax.set_ylabel('y')
    ax.set_aspect('equal')
    title = ax.set_title(f'Streamlines at t = {times[0]:.4f}')
    lines = []
    for _ in start_points:
        line, = ax.plot([], [], 'b-', linewidth=1)
        lines.append(line)
    ax.set_xlim(mesh.x_range[0], mesh.x_range[1])
    ax.set_ylim(mesh.y_range[0], mesh.y_range[1])
    def update(frame):
        vel = velocity_series[frame]
        for i, sp in enumerate(start_points):
            sl = trace_streamline(mesh, vel, sp, max_steps=500)
            if len(sl) > 0:
                lines[i].set_data(sl[:, 0], sl[:, 1])
            else:
                lines[i].set_data([], [])
        title.set_text(f'Streamlines at t = {times[frame]:.4f}')
        return lines
    anim = animation.FuncAnimation(
        fig, update, frames=len(velocity_series),
        interval=1000/fps, blit=False
    )
    if filename is not None:
        export_animation(anim, filename, fps)
    plt.close(fig)
    return anim

def export_animation(anim, filename, fps=10):
    if not HAS_IMAGEIO:
        raise ImportError("imageio is required for exporting animations")
    ext = os.path.splitext(filename)[1].lower()
    if ext in ['.mp4', '.avi', '.mov']:
        writer = animation.FFMpegWriter(fps=fps)
        anim.save(filename, writer=writer)
    elif ext in ['.gif']:
        writer = animation.PillowWriter(fps=fps)
        anim.save(filename, writer=writer)
    elif ext in ['.png', '.jpg']:
        temp_dir = tempfile.mkdtemp()
        frame_files = []
        for i, frame in enumerate(anim._iter_frames()):
            frame_file = os.path.join(temp_dir, f'frame_{i:06d}.png')
            anim._fig.savefig(frame_file, dpi=100, bbox_inches='tight')
            frame_files.append(frame_file)
        images = []
        for ff in frame_files:
            images.append(imageio.imread(ff))
        if ext == '.png':
            imageio.mimsave(filename, images, fps=fps)
        else:
            for i, img in enumerate(images):
                imageio.imwrite(filename.replace('.', f'_{i:04d}.'), img)
        shutil.rmtree(temp_dir)
    else:
        writer = animation.FFMpegWriter(fps=fps)
        anim.save(filename, writer=writer)
