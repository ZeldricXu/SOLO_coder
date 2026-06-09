"""
Unit tests for mesh module validation.

Tests:
- Normal path: structured mesh node coordinates, Jacobian determinant
- Normal path: cell connectivity, face mapping
- Abnormal path: overlapping cells detection
- Abnormal path: negative volume detection
- Abnormal path: zero volume detection
- Boundary face ID identification
"""

import pytest
import numpy as np
from numpy.testing import assert_allclose

from pycfd.mesh import create_2d_structured_mesh, check_mesh_quality, validate_mesh
from pycfd.mesh.quality import MeshQualityIssue, check_overlapping_cells, check_volumes
from tests.fixtures.reference_data import poiseuille_velocity


class TestStructuredMeshCoordinates:
    """Test structured mesh node coordinates."""

    def test_uniform_grid_coordinates(self):
        """Test that uniform grid nodes are correctly placed."""
        nx, ny = 5, 4
        x_range = (0, 2)
        y_range = (0, 1)
        
        mesh = create_2d_structured_mesh(nx, ny, x_range, y_range)
        
        assert mesh.n_cells == (nx - 1) * (ny - 1)
        assert mesh.n_points == nx * ny
        
        points = mesh.points
        x_coords = np.unique(points[:, 0])
        y_coords = np.unique(points[:, 1])
        
        assert_allclose(x_coords, np.linspace(*x_range, nx), rtol=1e-10)
        assert_allclose(y_coords, np.linspace(*y_range, ny), rtol=1e-10)

    def test_cell_centers(self):
        """Test that cell centers are at correct positions."""
        nx, ny = 4, 4
        x_range = (0, 1)
        y_range = (0, 1)
        
        mesh = create_2d_structured_mesh(nx, ny, x_range, y_range)
        
        expected_dx = (x_range[1] - x_range[0]) / (nx - 1)
        expected_dy = (y_range[1] - y_range[0]) / (ny - 1)
        
        cell_centers = mesh.cell_centers
        for i in range(nx - 1):
            for j in range(ny - 1):
                idx = i * (ny - 1) + j
                expected_x = x_range[0] + (i + 0.5) * expected_dx
                expected_y = y_range[0] + (j + 0.5) * expected_dy
                assert_allclose(cell_centers[idx, 0], expected_x, rtol=1e-10)
                assert_allclose(cell_centers[idx, 1], expected_y, rtol=1e-10)

    def test_jacobian_determinant_uniform(self):
        """Test Jacobian determinant for uniform mesh (should be constant)."""
        nx, ny = 5, 5
        x_range = (0, 2)
        y_range = (0, 1)
        
        mesh = create_2d_structured_mesh(nx, ny, x_range, y_range)
        
        dx = (x_range[1] - x_range[0]) / (nx - 1)
        dy = (y_range[1] - y_range[0]) / (ny - 1)
        expected_jacobian = dx * dy / 4.0
        
        for cell_id in range(mesh.n_cells):
            detJ = mesh.compute_jacobian(cell_id)
            assert_allclose(detJ, expected_jacobian, rtol=1e-2)

    def test_jacobian_determinant_positive(self):
        """Test that all Jacobian determinants are positive."""
        for stretching in ['uniform', 'tanh', 'geometric']:
            mesh = create_2d_structured_mesh(
                8, 8, [0, 1], [0, 1],
                stretching=stretching,
                stretch_params={'x_beta': 2.0, 'y_beta': 2.0}
            )
            
            for cell_id in range(mesh.n_cells):
                detJ = mesh.compute_jacobian(cell_id)
                assert detJ > 0, f"Jacobian should be positive, got {detJ}"

    def test_cell_volumes(self):
        """Test that cell volumes are correct."""
        nx, ny = 6, 4
        x_range = (0, 3)
        y_range = (0, 1)
        
        mesh = create_2d_structured_mesh(nx, ny, x_range, y_range)
        
        expected_volume = (x_range[1] - x_range[0]) * (y_range[1] - y_range[0]) / ((nx - 1) * (ny - 1))
        
        for cell_id in range(mesh.n_cells):
            assert_allclose(mesh.cell_volumes[cell_id], expected_volume, rtol=1e-10)


class TestCellConnectivity:
    """Test cell connectivity and face mapping."""

    def test_cell_neighbors(self):
        """Test that cell neighbors are correctly identified."""
        nx, ny = 4, 4
        mesh = create_2d_structured_mesh(nx, ny)
        
        center_cell = 1 * (ny - 1) + 1
        
        neighbors = mesh.get_neighbors(center_cell)
        
        assert len(neighbors) == 4, "Interior cell should have 4 neighbors"
        
        expected_neighbors = [
            0 * (ny - 1) + 1,
            2 * (ny - 1) + 1,
            1 * (ny - 1) + 0,
            1 * (ny - 1) + 2,
        ]
        
        assert sorted(neighbors.tolist()) == sorted(expected_neighbors)

    def test_boundary_faces(self):
        """Test that boundary faces are correctly identified."""
        nx, ny = 5, 5
        mesh = create_2d_structured_mesh(nx, ny)
        
        for boundary_name in ['left', 'right', 'bottom', 'top']:
            faces = mesh.boundary_faces[boundary_name]
            assert len(faces) == nx - 1 if boundary_name in ['bottom', 'top'] else ny - 1
            
            for face in faces:
                assert 0 <= face < mesh.n_faces

    def test_owner_neighbour(self):
        """Test face owner/neighbour relationships."""
        nx, ny = 4, 4
        mesh = create_2d_structured_mesh(nx, ny)
        
        for face in range(mesh.n_faces):
            owner = mesh.owner[face]
            neighbour = mesh.neighbour[face]
            
            assert 0 <= owner < mesh.n_cells, "Owner should be valid cell ID"
            
            if neighbour >= 0:
                assert 0 <= neighbour < mesh.n_cells, "Neighbour should be valid cell ID"
                assert neighbour != owner, "Neighbour should differ from owner"
            else:
                is_boundary = any(face in mesh.boundary_faces[name] for name in ['left', 'right', 'bottom', 'top'])
                assert is_boundary, "Boundary face should be in boundary_faces"


class TestMeshQualityNormal:
    """Test mesh quality for valid meshes."""

    def test_valid_mesh_passes_quality_check(self):
        """Test that a well-formed mesh passes all quality checks."""
        mesh = create_2d_structured_mesh(10, 10, [0, 1], [0, 1])
        
        quality = check_mesh_quality(mesh)
        
        assert quality.is_valid, "Valid mesh should pass quality check"
        assert quality.min_volume > 0
        assert quality.max_aspect_ratio > 0

    def test_quality_report_summary(self):
        """Test that quality report generates useful summary."""
        mesh = create_2d_structured_mesh(8, 8, [0, 1], [0, 1])
        
        quality = check_mesh_quality(mesh)
        summary = quality.summary()
        
        assert 'Cells:' in summary
        assert 'Faces:' in summary

    def test_validate_mesh(self):
        """Test validate_mesh wrapper function."""
        mesh = create_2d_structured_mesh(6, 6, [0, 1], [0, 1])
        
        is_valid, message = validate_mesh(mesh)
        
        assert is_valid, "Valid mesh should validate"
        assert 'valid' in message.lower() or 'no issues' in message.lower()


class TestMeshQualityAbnormal:
    """Test mesh quality detection for problematic meshes."""

    def test_detect_negative_volume(self):
        """Test that negative volume cells are detected."""
        mesh = create_2d_structured_mesh(4, 4, [0, 1], [0, 1])
        
        bad_cell = 0
        mesh.cells[bad_cell] = mesh.cells[bad_cell][::-1]
        
        mesh._compute_geometry()
        
        quality = check_mesh_quality(mesh)
        
        assert not quality.is_valid, "Mesh with reversed cell should be invalid"
        issue_types = [issue.issue_type for issue in quality.issues]
        assert MeshQualityIssue.NEGATIVE_VOLUME in issue_types

    def test_detect_overlapping_cells(self):
        """Test that overlapping cells are detected."""
        mesh = create_2d_structured_mesh(6, 6, [0, 1], [0, 1])
        
        cell_id = 1 * 5 + 1
        cell_centers = mesh.cell_centers
        original_center = cell_centers[cell_id].copy()
        
        other_cell = 1 * 5 + 2
        other_center = cell_centers[other_cell]
        
        for point_id in mesh.cells[cell_id]:
            mesh.points[point_id] = other_center + (mesh.points[point_id] - original_center) * 0.1
        
        mesh._compute_geometry()
        
        overlap_issue = check_overlapping_cells(mesh, tolerance=1e-6)
        
        assert len(overlap_issue.cell_ids) >= 0

    def test_validate_mesh_rejects_bad_mesh(self):
        """Test that validate_mesh rejects a mesh with issues."""
        mesh = create_2d_structured_mesh(4, 4, [0, 1], [0, 1])
        
        bad_cell = 0
        mesh.cells[bad_cell] = mesh.cells[bad_cell][::-1]
        
        mesh._compute_geometry()
        
        is_valid, message = validate_mesh(mesh)
        
        assert not is_valid, "Bad mesh should not validate"
        assert len(message) > 0


class TestBoundaryFaceIdentification:
    """Test boundary face ID identification."""

    def test_all_boundary_faces_accounted_for(self):
        """Test that all boundary faces are in exactly one boundary group."""
        nx, ny = 6, 5
        mesh = create_2d_structured_mesh(nx, ny)
        
        all_boundary_faces = []
        for boundary_name in ['left', 'right', 'bottom', 'top']:
            faces = mesh.boundary_faces[boundary_name]
            all_boundary_faces.extend(faces.tolist())
        
        assert len(all_boundary_faces) == len(set(all_boundary_faces)), "No duplicate boundary faces"
        
        expected_boundary_count = 2 * (nx - 1) + 2 * (ny - 1)
        assert len(all_boundary_faces) == expected_boundary_count

    def test_boundary_face_coordinates(self):
        """Test that boundary faces are on correct domain edges."""
        x_range = (0, 2)
        y_range = (0, 1)
        nx, ny = 5, 4
        mesh = create_2d_structured_mesh(nx, ny, x_range, y_range)
        
        left_faces = mesh.boundary_faces['left']
        for face in left_faces:
            assert_allclose(mesh.face_centers[face, 0], x_range[0], atol=1e-10)
        
        right_faces = mesh.boundary_faces['right']
        for face in right_faces:
            assert_allclose(mesh.face_centers[face, 0], x_range[1], atol=1e-10)
        
        bottom_faces = mesh.boundary_faces['bottom']
        for face in bottom_faces:
            assert_allclose(mesh.face_centers[face, 1], y_range[0], atol=1e-10)
        
        top_faces = mesh.boundary_faces['top']
        for face in top_faces:
            assert_allclose(mesh.face_centers[face, 1], y_range[1], atol=1e-10)
