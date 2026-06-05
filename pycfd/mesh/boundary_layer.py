import numpy as np
from .base import UnstructuredMesh
from ..core.jit import njit, prange

def generate_boundary_layer(mesh, boundary_name=None, n_layers=None, first_layer_thickness=None, 
                            growth_rate=1.2, method='extrude', wall_boundary_ids=None,
                            first_layer_height=None, stretching_ratio=None):
    if first_layer_height is not None:
        first_layer_thickness = first_layer_height
    if stretching_ratio is not None:
        growth_rate = stretching_ratio
    
    if wall_boundary_ids is not None:
        boundary_fids = []
        for bid in wall_boundary_ids:
            if isinstance(bid, str):
                if bid in mesh.boundary_faces:
                    boundary_fids.extend(mesh.boundary_faces[bid].tolist())
            else:
                faces = mesh.get_boundary_faces(bid)
                if len(faces) > 0:
                    boundary_fids.extend(faces.tolist())
        boundary_fids = np.array(boundary_fids, dtype=np.int64)
    elif boundary_name is not None:
        if boundary_name not in mesh.boundary_faces:
            raise ValueError(f"Boundary {boundary_name} not found")
        boundary_fids = mesh.boundary_faces[boundary_name]
    else:
        raise ValueError("Either boundary_name or wall_boundary_ids must be provided")
    
    if n_layers is None:
        n_layers = 1
    if first_layer_thickness is None:
        first_layer_thickness = 0.01
    
    if method == 'extrude':
        return _extrude_boundary_layer(mesh, boundary_fids, n_layers, 
                                       first_layer_thickness, growth_rate, boundary_name)
    else:
        raise ValueError(f"Unknown method: {method}")

def _extrude_boundary_layer(mesh, boundary_fids, n_layers, first_thickness, growth_rate, boundary_name=None):
    ndim = mesh.ndim
    new_points = mesh.points.copy().tolist()
    new_cells = [c.tolist() for c in mesh.cells]
    boundary_normals = _compute_boundary_normals(mesh, boundary_fids)
    node_offsets = {}
    thicknesses = first_thickness * np.cumprod([1.0] + [growth_rate] * (n_layers - 1))
    layer_points = []
    for layer in range(n_layers):
        layer_map = {}
        for fid_idx, fid in enumerate(boundary_fids):
            face = mesh.faces[fid]
            normal = boundary_normals[fid_idx]
            for nid in face:
                if nid not in layer_map:
                    if layer == 0:
                        new_p = mesh.points[nid] + normal * thicknesses[0]
                    else:
                        prev_pid = layer_points[layer - 1][nid]
                        prev_p = new_points[prev_pid]
                        new_p = prev_p + normal * (thicknesses[layer] - thicknesses[layer - 1])
                    new_pid = len(new_points)
                    new_points.append(new_p.tolist())
                    layer_map[nid] = new_pid
        layer_points.append(layer_map)
    for fid_idx, fid in enumerate(boundary_fids):
        face = mesh.faces[fid]
        normal = boundary_normals[fid_idx]
        if ndim == 2 and len(face) == 2:
            n0, n1 = face
            for layer in range(n_layers):
                nn0 = layer_points[layer][n0]
                nn1 = layer_points[layer][n1]
                if layer == 0:
                    pn0 = n0
                    pn1 = n1
                else:
                    pn0 = layer_points[layer-1][n0]
                    pn1 = layer_points[layer-1][n1]
                new_cells.append([pn0, pn1, nn1])
                new_cells.append([pn0, nn1, nn0])
        elif ndim == 3 and len(face) == 3:
            n0, n1, n2 = face
            for layer in range(n_layers):
                nn0 = layer_points[layer][n0]
                nn1 = layer_points[layer][n1]
                nn2 = layer_points[layer][n2]
                if layer == 0:
                    pn0, pn1, pn2 = n0, n1, n2
                else:
                    pn0 = layer_points[layer-1][n0]
                    pn1 = layer_points[layer-1][n1]
                    pn2 = layer_points[layer-1][n2]
                new_cells.append([pn0, pn1, pn2, nn0])
                new_cells.append([pn1, pn2, nn2, nn0])
                new_cells.append([pn1, nn1, nn2, nn0])
        elif ndim == 3 and len(face) == 4:
            n0, n1, n2, n3 = face
            for layer in range(n_layers):
                nn0 = layer_points[layer][n0]
                nn1 = layer_points[layer][n1]
                nn2 = layer_points[layer][n2]
                nn3 = layer_points[layer][n3]
                if layer == 0:
                    pn0, pn1, pn2, pn3 = n0, n1, n2, n3
                else:
                    pn0 = layer_points[layer-1][n0]
                    pn1 = layer_points[layer-1][n1]
                    pn2 = layer_points[layer-1][n2]
                    pn3 = layer_points[layer-1][n3]
                new_cells.append([pn0, pn1, nn1, nn0])
                new_cells.append([pn1, pn2, nn2, nn1])
                new_cells.append([pn2, pn3, nn3, nn2])
                new_cells.append([pn3, pn0, nn0, nn3])
                new_cells.append([pn0, pn1, pn2, pn3])
                new_cells.append([nn0, nn1, nn2, nn3])
    new_mesh = UnstructuredMesh(np.array(new_points, dtype=np.float64), new_cells, mesh.mesh_type)
    _transfer_boundaries_after_extrusion(mesh, new_mesh, boundary_fids, boundary_name,
                                         layer_points, n_layers, ndim)
    return new_mesh

def _compute_boundary_normals(mesh, boundary_fids):
    normals = np.zeros((len(boundary_fids), mesh.ndim), dtype=np.float64)
    for i, fid in enumerate(boundary_fids):
        normals[i] = mesh.face_normals[fid]
        owner = mesh.owner[fid]
        if np.dot(mesh.face_centers[fid] - mesh.cell_centers[owner], normals[i]) < 0:
            normals[i] *= -1
    return normals

def _transfer_boundaries_after_extrusion(old_mesh, new_mesh, boundary_fids, boundary_name,
                                          layer_points, n_layers, ndim):
    new_mesh.boundary_faces = {}
    outer_boundary = []
    if ndim == 2:
        last_layer = layer_points[-1]
        for fid in boundary_fids:
            face = old_mesh.faces[fid]
            n0, n1 = face
            nn0 = last_layer[n0]
            nn1 = last_layer[n1]
            for new_fid, new_face in enumerate(new_mesh.faces):
                if len(new_face) == 2:
                    nf = set(new_face.tolist())
                    if nf == {nn0, nn1}:
                        outer_boundary.append(new_fid)
    if boundary_name is not None:
        new_mesh.boundary_faces[boundary_name] = np.array(outer_boundary, dtype=np.int64)
    for name, fids in old_mesh.boundary_faces.items():
        if boundary_name is not None and name == boundary_name:
            continue
        new_fids = []
        for fid in fids:
            if fid in boundary_fids:
                continue
            face = old_mesh.faces[fid].tolist()
            for new_fid, new_face in enumerate(new_mesh.faces):
                if set(new_face.tolist()) == set(face):
                    new_fids.append(new_fid)
                    break
        if new_fids:
            new_mesh.boundary_faces[name] = np.array(new_fids, dtype=np.int64)

def extrude_boundary_layer(points, faces, normals, n_layers, first_thickness, growth_rate=1.2):
    new_points = [p.copy() for p in points]
    new_cells = []
    n_faces = len(faces)
    thicknesses = first_thickness * np.cumprod([1.0] + [growth_rate] * (n_layers - 1))
    point_layers = []
    for layer in range(n_layers):
        layer_points = {}
        for fid in range(n_faces):
            face = faces[fid]
            normal = normals[fid]
            for pid in face:
                if pid not in layer_points:
                    if layer == 0:
                        disp = thicknesses[0]
                    else:
                        disp = thicknesses[layer] - thicknesses[layer - 1]
                    old_pid = point_layers[layer - 1][pid] if layer > 0 else pid
                    new_p = np.array(new_points[old_pid]) + disp * normal
                    new_pid = len(new_points)
                    new_points.append(new_p.tolist())
                    layer_points[pid] = new_pid
        point_layers.append(layer_points)
    for fid in range(n_faces):
        face = faces[fid]
        n_nodes = len(face)
        for layer in range(n_layers):
            old_nodes = [point_layers[layer-1][pid] if layer > 0 else pid for pid in face]
            new_nodes = [point_layers[layer][pid] for pid in face]
            if n_nodes == 2:
                n0, n1 = old_nodes
                nn0, nn1 = new_nodes
                new_cells.append([n0, n1, nn1])
                new_cells.append([n0, nn1, nn0])
            elif n_nodes == 3:
                n0, n1, n2 = old_nodes
                nn0, nn1, nn2 = new_nodes
                new_cells.append([n0, n1, n2, nn0])
                new_cells.append([n1, n2, nn2, nn0])
                new_cells.append([n1, nn1, nn2, nn0])
            elif n_nodes == 4:
                n0, n1, n2, n3 = old_nodes
                nn0, nn1, nn2, nn3 = new_nodes
                new_cells.append([n0, n1, nn1, nn0])
                new_cells.append([n1, n2, nn2, nn1])
                new_cells.append([n2, n3, nn3, nn2])
                new_cells.append([n3, n0, nn0, nn3])
    return np.array(new_points, dtype=np.float64), new_cells

def compute_y_plus(mesh, velocity, nu, rho=1.0, boundary_name='wall'):
    if boundary_name not in mesh.boundary_faces:
        raise ValueError(f"Boundary {boundary_name} not found")
    y_plus = np.zeros(mesh.n_cells, dtype=np.float64)
    for fid in mesh.boundary_faces[boundary_name]:
        cid = mesh.owner[fid]
        center = mesh.cell_centers[cid]
        face_center = mesh.face_centers[fid]
        normal = mesh.face_normals[fid]
        dy = abs(np.dot(center - face_center, normal))
        u_tau = compute_friction_velocity(mesh, velocity, cid, fid)
        y_plus[cid] = u_tau * dy / nu
    return y_plus

def compute_friction_velocity(mesh, velocity, cell_id, face_id):
    face = mesh.faces[face_id]
    normal = mesh.face_normals[face_id]
    u_cell = velocity[cell_id]
    u_tangential = u_cell - np.dot(u_cell, normal) * normal
    u_mag = np.linalg.norm(u_tangential)
    dy = np.linalg.norm(mesh.cell_centers[cell_id] - mesh.face_centers[face_id])
    nu = 1e-6
    tau_wall = nu * u_mag / dy
    return np.sqrt(abs(tau_wall))
