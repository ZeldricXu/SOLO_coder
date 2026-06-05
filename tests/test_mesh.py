"""Unit tests for mesh module."""
import sys
import os
sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

import numpy as np
import pytest
from pycfd.mesh import create_2d_structured_mesh, create_rectangular_tri_mesh
from pycfd.mesh.adaptive import adaptive_refine, gradient_indicator
from pycfd.mesh.boundary_layer import generate_boundary_layer

def test_structured_mesh_2d():
    nx, ny = 10, 8
    mesh = create_2d_structured_mesh(nx, ny, [0, 2], [0, 1], stretching='uniform')
    assert mesh.nx == nx
    assert mesh.ny == ny
    assert mesh.n_cells == (nx - 1) * (ny - 1)
    assert mesh.n_points == nx * ny
    assert np.isclose(mesh.cell_volumes[0], (2 / (nx - 1)) * (1 / (ny - 1)))
    unique_areas = np.unique(mesh.face_areas)
    expected_dx = 2 / (nx - 1)
    expected_dy = 1 / (ny - 1)
    assert np.any(np.isclose(unique_areas, expected_dx))
    assert np.any(np.isclose(unique_areas, expected_dy))

def test_structured_mesh_stretching():
    nx, ny = 10, 8
    mesh = create_2d_structured_mesh(nx, ny, [0, 1], [0, 1], stretching='tanh', tanh_strength=2.0)
    dy = np.diff(mesh.points[:ny, 1])
    assert dy[0] < dy[len(dy) // 2]
    assert dy[-1] < dy[len(dy) // 2]

def test_unstructured_mesh_2d():
    nx, ny = 8, 6
    mesh = create_rectangular_tri_mesh(nx, ny, [0, 1], [0, 1])
    assert mesh.ndim == 2
    assert mesh.n_cells == 2 * (nx - 1) * (ny - 1)
    assert mesh.n_points == nx * ny
    assert all(len(cell) == 3 for cell in mesh.cells)

def test_mesh_geometry():
    nx, ny = 6, 6
    mesh = create_2d_structured_mesh(nx, ny, [0, 1], [0, 1])
    assert np.isclose(np.sum(mesh.cell_volumes), 1.0)
    for fid in range(mesh.n_faces):
        if mesh.neighbour[fid] >= 0:
            c1 = mesh.owner[fid]
            c2 = mesh.neighbour[fid]
            n = mesh.face_normals[fid]
            d = mesh.cell_centers[c2] - mesh.cell_centers[c1]
            assert np.dot(n, d) > 0

def test_adaptive_refinement():
    nx, ny = 8, 8
    mesh = create_2d_structured_mesh(nx, ny, [0, 1], [0, 1])
    field = np.sin(2 * np.pi * mesh.cell_centers[:, 0]) * \
            np.cos(2 * np.pi * mesh.cell_centers[:, 1])
    indicator = gradient_indicator(mesh, field)
    threshold = np.percentile(indicator, 70)
    refine_cells = np.where(indicator > threshold)[0]
    refined_mesh = adaptive_refine(mesh, refine_cells, method='bisection')
    assert refined_mesh.n_cells > mesh.n_cells

def test_boundary_layer():
    nx, ny = 10, 6
    mesh = create_2d_structured_mesh(nx, ny, [0, 2], [0, 1])
    wall_boundary = mesh.boundary_map.get('bottom', 1)
    bl_mesh = generate_boundary_layer(
        mesh, wall_boundary_ids=[wall_boundary], 
        n_layers=3, first_layer_height=0.01, stretching_ratio=1.3
    )
    wall_faces = mesh.get_boundary_faces(wall_boundary)
    wall_cells = set(mesh.owner[fid] for fid in wall_faces)
    assert bl_mesh.n_cells >= mesh.n_cells

if __name__ == '__main__':
    test_structured_mesh_2d()
    test_structured_mesh_stretching()
    test_unstructured_mesh_2d()
    test_mesh_geometry()
    test_adaptive_refinement()
    test_boundary_layer()
    print("All mesh tests passed!")
