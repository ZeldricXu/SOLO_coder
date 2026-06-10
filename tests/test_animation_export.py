import numpy as np
import pytest
import sys
import os
import tempfile
from pathlib import Path

sys.path.insert(0, os.path.join(os.path.dirname(__file__), '..'))

from pycfd.mesh.structured import StructuredMesh
from pycfd.core.hdf5_io import HDF5Writer, HDF5Reader
from pycfd.postproc import (
    TimeSeriesAnimator, animate_field, 
    create_flow_animation, export_animation
)

try:
    import matplotlib
    matplotlib.use('Agg')
    HAS_MATPLOTLIB = True
except ImportError:
    HAS_MATPLOTLIB = False

try:
    import imageio
    HAS_IMAGEIO = True
except ImportError:
    HAS_IMAGEIO = False


@pytest.mark.skipif(not HAS_MATPLOTLIB, reason="matplotlib not available")
class TestTimeSeriesAnimator:
    """Test TimeSeriesAnimator class."""
    
    def _create_test_hdf5(self, tmp_path, n_timesteps=5):
        """Create a test HDF5 file with time series data."""
        h5_file = tmp_path / "test_data.h5"
        
        mesh = StructuredMesh(20, 20, x_range=(0.0, 1.0), y_range=(0.0, 1.0))
        n_cells = mesh.n_cells
        
        with HDF5Writer(h5_file) as writer:
            writer.write_mesh(mesh)
            
            for t in range(n_timesteps):
                field = np.zeros(n_cells)
                for i in range(n_cells):
                    x = mesh.cell_centers[i, 0]
                    y = mesh.cell_centers[i, 1]
                    field[i] = np.sin(2 * np.pi * x + t * 0.5) * np.cos(2 * np.pi * y)
                
                writer.write_field('pressure', field, timestep=t)
                
                u_field = np.zeros((n_cells, 2))
                u_field[:, 0] = 0.1 * np.ones(n_cells)
                u_field[:, 1] = 0.05 * np.sin(2 * np.pi * np.arange(n_cells) / n_cells)
                writer.write_field('u', u_field, timestep=t)
        
        return h5_file, mesh
    
    def test_initialization(self, tmp_path):
        """Test animator initialization."""
        h5_file, mesh = self._create_test_hdf5(tmp_path)
        
        animator = TimeSeriesAnimator(h5_file, cache_size=3)
        
        assert len(animator.timesteps) == 5
        assert animator.mesh is not None
        assert animator.cache_size == 3
    
    def test_get_field(self, tmp_path):
        """Test field loading with caching."""
        h5_file, mesh = self._create_test_hdf5(tmp_path)
        
        animator = TimeSeriesAnimator(h5_file, cache_size=2)
        
        field_0 = animator.get_field('pressure', 0)
        field_1 = animator.get_field('pressure', 1)
        field_0_again = animator.get_field('pressure', 0)
        
        assert field_0.shape == (mesh.n_cells,)
        assert field_1.shape == (mesh.n_cells,)
        assert np.allclose(field_0, field_0_again)
    
    def test_get_field_names(self, tmp_path):
        """Test getting available field names."""
        h5_file, _ = self._create_test_hdf5(tmp_path)
        
        animator = TimeSeriesAnimator(h5_file)
        field_names = animator.get_field_names()
        
        assert 'pressure' in field_names
        assert 'u' in field_names
    
    def test_interpolate_frames_linear(self, tmp_path):
        """Test linear frame interpolation."""
        h5_file, mesh = self._create_test_hdf5(tmp_path, n_timesteps=3)
        
        animator = TimeSeriesAnimator(h5_file)
        
        field_data = []
        for i in range(3):
            field_data.append(animator.get_field('pressure', i))
        field_data = np.array(field_data)
        
        interp_data, times = animator.interpolate_frames(
            field_data, n_interp=2, method='linear'
        )
        
        expected_frames = 3 + 2 * 2
        assert interp_data.shape[0] == expected_frames
        assert interp_data.shape[1] == mesh.n_cells
        assert len(times) == expected_frames
    
    def test_create_animation(self, tmp_path):
        """Test creating animation from time series."""
        h5_file, _ = self._create_test_hdf5(tmp_path, n_timesteps=3)
        
        animator = TimeSeriesAnimator(h5_file)
        output_file = tmp_path / "test_anim.gif"
        
        anim = animator.create_animation(
            'pressure', 
            filename=str(output_file),
            fps=5,
            n_interp=1
        )
        
        assert anim is not None
        assert output_file.exists()
    
    def test_create_vector_animation(self, tmp_path):
        """Test creating vector animation."""
        h5_file, _ = self._create_test_hdf5(tmp_path, n_timesteps=3)
        
        animator = TimeSeriesAnimator(h5_file)
        output_file = tmp_path / "test_vec_anim.gif"
        
        anim = animator.create_vector_animation(
            filename=str(output_file),
            fps=5,
            step=2,
            n_interp=1
        )
        
        assert anim is not None
        assert output_file.exists()
    
    def test_animation_with_streamlines(self, tmp_path):
        """Test animation with streamline overlay."""
        h5_file, _ = self._create_test_hdf5(tmp_path, n_timesteps=3)
        
        animator = TimeSeriesAnimator(h5_file)
        output_file = tmp_path / "test_streamline_anim.gif"
        
        start_points = np.array([[0.1, 0.5], [0.2, 0.5], [0.3, 0.5]])
        
        anim = animator.create_animation(
            'pressure',
            filename=str(output_file),
            fps=5,
            n_interp=1,
            overlay_streamlines=True,
            start_points=start_points,
            streamlines_every=1
        )
        
        assert anim is not None


@pytest.mark.skipif(not HAS_MATPLOTLIB, reason="matplotlib not available")
class TestLegacyAnimationFunctions:
    """Test legacy animation functions for backward compatibility."""
    
    def setup_method(self):
        """Set up test mesh and data."""
        self.mesh = StructuredMesh(10, 10, x_range=(0.0, 1.0), y_range=(0.0, 1.0))
        n_cells = self.mesh.n_cells
        n_times = 5
        
        self.field_series = []
        self.velocity_series = []
        self.pressure_series = []
        
        for t in range(n_times):
            field = np.sin(2 * np.pi * self.mesh.cell_centers[:, 0] + t * 0.3)
            self.field_series.append(field)
            self.pressure_series.append(field * 100)
            
            vel = np.zeros((n_cells, 2))
            vel[:, 0] = 0.1
            vel[:, 1] = 0.05 * np.sin(2 * np.pi * self.mesh.cell_centers[:, 1] + t * 0.2)
            self.velocity_series.append(vel)
    
    def test_animate_field(self, tmp_path):
        """Test animate_field function."""
        output_file = tmp_path / "legacy_field.gif"
        
        anim = animate_field(
            self.mesh, self.field_series,
            field_name='Pressure',
            filename=str(output_file),
            fps=5
        )
        
        assert anim is not None
        assert output_file.exists()
    
    def test_create_flow_animation(self, tmp_path):
        """Test create_flow_animation function."""
        output_file = tmp_path / "legacy_flow.gif"
        
        anim = create_flow_animation(
            self.mesh, self.pressure_series, self.velocity_series,
            filename=str(output_file),
            fps=5
        )
        
        assert anim is not None
        assert output_file.exists()


@pytest.mark.skipif(not HAS_MATPLOTLIB or not HAS_IMAGEIO, 
                    reason="matplotlib and imageio required")
class TestExportAnimation:
    """Test animation export functionality."""
    
    def test_export_mp4(self, tmp_path):
        """Test exporting to MP4 format."""
        mesh = StructuredMesh(10, 10, (0.0, 1.0), (0.0, 1.0))
        field_series = [np.sin(2 * np.pi * mesh.cell_centers[:, 0] + t * 0.3) for t in range(3)]
        
        output_file = tmp_path / "test_export.gif"
        
        anim = animate_field(mesh, field_series, filename=str(output_file), fps=5)
        
        assert anim is not None
        assert output_file.exists()


class TestInterpolation:
    """Test interpolation methods."""
    
    def test_interpolate_frames_linear_simple(self):
        """Test linear interpolation with simple data."""
        from pycfd.postproc.animation import TimeSeriesAnimator
        
        field_data = np.array([[0.0, 0.0, 0.0], [1.0, 2.0, 3.0]])
        
        tmp_path = Path(tempfile.mkdtemp())
        h5_file = tmp_path / "test.h5"
        
        mesh = StructuredMesh(3, 2, x_range=(0.0, 1.0), y_range=(0.0, 1.0))
        with HDF5Writer(h5_file) as writer:
            writer.write_mesh(mesh)
            writer.write_field('test', np.zeros(6), timestep=0)
            writer.write_field('test', np.ones(6), timestep=1)
        
        animator = TimeSeriesAnimator(h5_file, mesh=mesh)
        
        interp, times = animator.interpolate_frames(
            field_data, n_interp=1, method='linear'
        )
        
        assert interp.shape == (3, 3)
        assert np.allclose(interp[0], [0.0, 0.0, 0.0])
        assert np.allclose(interp[2], [1.0, 2.0, 3.0])
        assert np.allclose(interp[1], [0.5, 1.0, 1.5])


if __name__ == '__main__':
    pytest.main([__file__, '-v', '-x'])
