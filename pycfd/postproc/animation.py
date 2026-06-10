import numpy as np
import os
import tempfile
import shutil
from typing import List, Callable, Optional, Tuple, Dict, Union
from pathlib import Path

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

from ..core.hdf5_io import HDF5Reader


class TimeSeriesAnimator:
    """Animator for time series CFD data from HDF5 files.
    
    Features:
    - Lazy loading of time steps to save memory for large grids
    - Smooth interpolation between frames
    - Streamline overlay
    - Multiple output formats (MP4, GIF)
    - Support for both structured and unstructured meshes
    """
    
    def __init__(self, hdf5_file: Union[str, Path], mesh=None, cache_size: int = 5):
        """Initialize the time series animator.
        
        Args:
            hdf5_file: Path to HDF5 file containing time series data
            mesh: Optional mesh object (if None, will try to load from HDF5)
            cache_size: Number of time steps to keep in memory cache
        """
        self.hdf5_file = str(hdf5_file)
        self.mesh = mesh
        self.cache_size = cache_size
        self._field_cache: Dict[int, np.ndarray] = {}
        self._reader = None
        
        self._open_reader()
        self.timesteps = self._reader.get_timesteps()
        
        if self.mesh is None:
            self._load_mesh_from_hdf5()
        
        if len(self.timesteps) == 0:
            raise ValueError("No time steps found in HDF5 file")
        
        self.times = np.arange(len(self.timesteps))
        
        if hasattr(self.mesh, 'nx') and hasattr(self.mesh, 'ny'):
            self._is_structured = True
        else:
            self._is_structured = False
    
    def _open_reader(self):
        """Open the HDF5 reader."""
        if self._reader is None:
            self._reader = HDF5Reader(self.hdf5_file)
            self._reader.open()
    
    def _close_reader(self):
        """Close the HDF5 reader."""
        if self._reader is not None:
            self._reader.close()
            self._reader = None
    
    def _load_mesh_from_hdf5(self):
        """Load mesh data from HDF5 file."""
        from ..mesh.structured import StructuredMesh
        from ..mesh.unstructured import UnstructuredMesh
        
        mesh_grp = self._reader._file['mesh']
        ndim = mesh_grp.attrs.get('ndim', 2)
        mesh_type = mesh_grp.attrs.get('type', 'structured')
        
        points = np.array(mesh_grp['points'])
        cells = np.array(mesh_grp['cells'])
        faces = np.array(mesh_grp['faces'])
        
        if mesh_type == 'structured' and 'nx' in mesh_grp.attrs:
            nx = mesh_grp.attrs.get('nx')
            ny = mesh_grp.attrs.get('ny')
            nz = mesh_grp.attrs.get('nz', 1)
            x_range = (points[:, 0].min(), points[:, 0].max())
            y_range = (points[:, 1].min(), points[:, 1].max())
            if ndim == 2:
                if nx is not None and ny is not None:
                    self.mesh = StructuredMesh(nx, ny, x_range=x_range, y_range=y_range)
                else:
                    self.mesh = UnstructuredMesh(points, cells)
            else:
                z_range = (points[:, 2].min(), points[:, 2].max())
                if nx is not None and ny is not None and nz is not None:
                    self.mesh = StructuredMesh(nx, ny, nz, x_range=x_range, y_range=y_range, z_range=z_range)
                else:
                    self.mesh = UnstructuredMesh(points, cells)
        else:
            self.mesh = UnstructuredMesh(points, cells)
        
        if 'boundaries' in mesh_grp:
            self.mesh.boundary_faces = {}
            for name in mesh_grp['boundaries'].keys():
                self.mesh.boundary_faces[name] = np.array(mesh_grp['boundaries'][name])
        
        self.mesh.cell_centers = np.array(mesh_grp['cell_centers'])
        self.mesh.cell_volumes = np.array(mesh_grp['cell_volumes'])
        self.mesh.face_areas = np.array(mesh_grp['face_areas'])
        self.mesh.face_normals = np.array(mesh_grp['face_normals'])
        self.mesh.face_centers = np.array(mesh_grp['face_centers'])
    
    def get_field(self, field_name: str, timestep: int) -> np.ndarray:
        """Get field data for a specific time step with caching.
        
        Args:
            field_name: Name of the field to load
            timestep: Time step index
            
        Returns:
            Field data array
        """
        cache_key = (field_name, timestep)
        
        if cache_key in self._field_cache:
            return self._field_cache[cache_key]
        
        self._open_reader()
        ts = self.timesteps[timestep]
        data = self._reader.read_field(field_name, ts)
        
        if len(self._field_cache) >= self.cache_size:
            oldest_key = next(iter(self._field_cache))
            del self._field_cache[oldest_key]
        
        self._field_cache[cache_key] = data
        return data
    
    def get_field_names(self) -> List[str]:
        """Get available field names."""
        self._open_reader()
        return self._reader.get_field_names(self.timesteps[0])
    
    def interpolate_frames(self, field_data: np.ndarray, n_interp: int = 2, 
                          method: str = 'linear') -> Tuple[np.ndarray, np.ndarray]:
        """Interpolate additional frames between time steps for smoother animation.
        
        Args:
            field_data: Array of field data (n_timesteps, n_cells, ...)
            n_interp: Number of interpolation frames between each time step
            method: Interpolation method ('linear' or 'spline')
            
        Returns:
            Tuple of (interpolated_data, interpolated_times)
        """
        n_timesteps = field_data.shape[0]
        n_out = n_timesteps + (n_timesteps - 1) * n_interp
        
        original_times = np.arange(n_timesteps)
        new_times = np.linspace(0, n_timesteps - 1, n_out)
        
        original_shape = field_data.shape
        flat_data = field_data.reshape(n_timesteps, -1)
        interpolated = np.zeros((n_out, flat_data.shape[1]), dtype=np.float64)
        
        if method == 'linear':
            for i in range(flat_data.shape[1]):
                interpolated[:, i] = np.interp(new_times, original_times, flat_data[:, i])
        elif method == 'spline':
            from scipy.interpolate import CubicSpline
            for i in range(flat_data.shape[1]):
                cs = CubicSpline(original_times, flat_data[:, i])
                interpolated[:, i] = cs(new_times)
        else:
            raise ValueError(f"Unknown interpolation method: {method}")
        
        interpolated = interpolated.reshape((n_out,) + original_shape[1:])
        
        return interpolated, new_times
    
    def create_animation(self, field_name: str, filename: Optional[str] = None,
                        fps: int = 15, cmap: str = 'viridis', levels: int = 20,
                        n_interp: int = 1, interpolation_method: str = 'linear',
                        overlay_streamlines: bool = False,
                        start_points: Optional[np.ndarray] = None,
                        streamlines_every: int = 5,
                        **kwargs) -> animation.FuncAnimation:
        """Create animation from time series data.
        
        Args:
            field_name: Name of the field to animate
            filename: Output filename (MP4 or GIF)
            fps: Frames per second
            cmap: Colormap
            levels: Number of contour levels
            n_interp: Number of interpolation frames between time steps
            interpolation_method: Interpolation method
            overlay_streamlines: Whether to overlay streamlines
            start_points: Starting points for streamlines
            streamlines_every: How often to update streamlines
            **kwargs: Additional arguments passed to plotting functions
            
        Returns:
            Matplotlib animation object
        """
        if not HAS_MATPLOTLIB:
            raise ImportError("matplotlib is required for animation")
        
        print(f"Loading field '{field_name}' for {len(self.timesteps)} time steps...")
        field_data = []
        for i in range(len(self.timesteps)):
            field_data.append(self.get_field(field_name, i))
        field_data = np.array(field_data)
        
        if n_interp > 1:
            print(f"Interpolating {n_interp} frames between each time step...")
            field_data, anim_times = self.interpolate_frames(
                field_data, n_interp, interpolation_method
            )
        else:
            anim_times = np.arange(len(self.timesteps))
        
        if overlay_streamlines:
            velocity_data = []
            for i in range(len(self.timesteps)):
                vel = self.get_field('u', i)
                velocity_data.append(vel)
            velocity_data = np.array(velocity_data)
            
            if n_interp > 1:
                velocity_data, _ = self.interpolate_frames(
                    velocity_data, n_interp, interpolation_method
                )
        
        print("Creating animation...")
        fig, ax = plt.subplots(figsize=(10, 8))
        ax.set_xlabel('x')
        ax.set_ylabel('y')
        title = ax.set_title(f'{field_name} at t = {anim_times[0]:.1f}')
        
        contour_obj = [None]
        streamline_obj = []
        
        def get_grid_data(field):
            if self._is_structured and hasattr(self.mesh, 'nx') and hasattr(self.mesh, 'ny'):
                nx, ny = self.mesh.nx - 1, self.mesh.ny - 1
                X = self.mesh.cell_centers[:, 0].reshape(nx, ny)
                Y = self.mesh.cell_centers[:, 1].reshape(nx, ny)
                Z = field.reshape(nx, ny)
                return X, Y, Z, True
            else:
                x = self.mesh.cell_centers[:, 0]
                y = self.mesh.cell_centers[:, 1]
                return x, y, field, False
        
        X, Y, Z0, is_struct = get_grid_data(field_data[0])
        
        if is_struct:
            contour_obj[0] = ax.contourf(X, Y, Z0, levels=levels, cmap=cmap)
        else:
            contour_obj[0] = ax.tricontourf(X, Y, Z0, levels=levels, cmap=cmap)
        
        plt.colorbar(contour_obj[0], ax=ax, label=field_name)
        
        if overlay_streamlines and start_points is not None:
            from .streamline import trace_streamline
            for _ in start_points:
                line, = ax.plot([], [], 'k-', linewidth=1, alpha=0.7)
                streamline_obj.append(line)
        
        ax.set_aspect('equal')
        
        def update(frame):
            for c in ax.collections:
                c.remove()
            
            X, Y, Z, _ = get_grid_data(field_data[frame])
            
            if is_struct:
                contour_obj[0] = ax.contourf(X, Y, Z, levels=levels, cmap=cmap)
            else:
                contour_obj[0] = ax.tricontourf(X, Y, Z, levels=levels, cmap=cmap)
            
            title.set_text(f'{field_name} at t = {anim_times[frame]:.1f}')
            
            if overlay_streamlines and start_points is not None and frame % streamlines_every == 0:
                vel_frame = velocity_data[frame]
                for i, sp in enumerate(start_points):
                    sl = trace_streamline(self.mesh, vel_frame, sp, max_steps=500)
                    if len(sl) > 0:
                        streamline_obj[i].set_data(sl[:, 0], sl[:, 1])
                    else:
                        streamline_obj[i].set_data([], [])
            
            return ax.collections + streamline_obj + [title]
        
        anim = animation.FuncAnimation(
            fig, update, frames=len(anim_times),
            interval=1000 / fps, blit=False
        )
        
        if filename is not None:
            export_animation(anim, filename, fps)
        
        plt.close(fig)
        self._close_reader()
        
        return anim
    
    def create_vector_animation(self, filename: Optional[str] = None,
                               fps: int = 15, step: int = 5, scale: float = 0.1,
                               n_interp: int = 1, **kwargs) -> animation.FuncAnimation:
        """Create velocity vector animation.
        
        Args:
            filename: Output filename
            fps: Frames per second
            step: Subsampling step for vectors
            scale: Vector scale
            n_interp: Number of interpolation frames
            
        Returns:
            Matplotlib animation object
        """
        if not HAS_MATPLOTLIB:
            raise ImportError("matplotlib is required for animation")
        
        print("Loading velocity field...")
        velocity_data = []
        for i in range(len(self.timesteps)):
            velocity_data.append(self.get_field('u', i))
        velocity_data = np.array(velocity_data)
        
        if n_interp > 1:
            velocity_data, anim_times = self.interpolate_frames(
                velocity_data, n_interp, 'linear'
            )
        else:
            anim_times = np.arange(len(self.timesteps))
        
        fig, ax = plt.subplots(figsize=(10, 8))
        centers = self.mesh.cell_centers[::step]
        v0 = velocity_data[0][::step]
        
        title = ax.set_title(f'Velocity field at t = {anim_times[0]:.1f}')
        Q = ax.quiver(centers[:, 0], centers[:, 1], v0[:, 0], v0[:, 1], 
                     scale=scale, **kwargs)
        
        ax.set_xlabel('x')
        ax.set_ylabel('y')
        ax.set_aspect('equal')
        
        def update(frame):
            vf = velocity_data[frame][::step]
            Q.set_UVC(vf[:, 0], vf[:, 1])
            title.set_text(f'Velocity field at t = {anim_times[frame]:.1f}')
            return Q, title
        
        anim = animation.FuncAnimation(
            fig, update, frames=len(anim_times),
            interval=1000 / fps, blit=False
        )
        
        if filename is not None:
            export_animation(anim, filename, fps)
        
        plt.close(fig)
        self._close_reader()
        
        return anim
    
    def __del__(self):
        self._close_reader()


def animate_field(mesh, field_series, times=None, field_name='Field', 
                  filename=None, fps=10, cmap='viridis', levels=20):
    """Create animation from field series (legacy function)."""
    if not HAS_MATPLOTLIB:
        raise ImportError("matplotlib is required for animation")
    if times is None:
        times = np.arange(len(field_series))
    
    fig, ax = plt.subplots(figsize=(10, 8))
    ax.set_xlabel('x')
    ax.set_ylabel('y')
    title = ax.set_title(f'{field_name} at t = {times[0]:.4f}')
    
    if hasattr(mesh, 'nx') and hasattr(mesh, 'ny'):
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
    """Create vector animation (legacy function)."""
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
    """Create side-by-side pressure and velocity animation (legacy function)."""
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
    """Create streamline animation (legacy function)."""
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
    """Export animation to file."""
    ext = os.path.splitext(filename)[1].lower()
    
    if ext in ['.mp4', '.avi', '.mov']:
        try:
            writer = animation.FFMpegWriter(fps=fps)
            anim.save(filename, writer=writer)
        except Exception as e:
            try:
                writer = animation.PillowWriter(fps=fps)
                anim.save(filename, writer=writer)
            except Exception as e2:
                raise RuntimeError(f"Failed to save animation: {e}, {e2}")
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
        try:
            from PIL import Image
            images = [Image.open(ff) for ff in frame_files]
            if ext == '.png':
                images[0].save(filename, save_all=True, append_images=images[1:], duration=1000/fps, loop=0)
            else:
                for i, img in enumerate(images):
                    img.save(filename.replace('.', f'_{i:04d}.'))
        except ImportError:
            import shutil
            shutil.copytree(temp_dir, filename.replace(ext, '_frames'))
        shutil.rmtree(temp_dir)
    else:
        writer = animation.FFMpegWriter(fps=fps)
        anim.save(filename, writer=writer)
