import numpy as np
from .base import UnstructuredMesh, Mesh
from ..core.jit import njit, prange

def adaptive_refine(mesh, indicator_or_cells, threshold=None, max_cells=None, method='regular'):
    if indicator_or_cells.dtype in [np.int64, np.int32, np.intp] and indicator_or_cells.ndim == 1:
        cells_to_refine = indicator_or_cells
    else:
        indicator = indicator_or_cells
        if threshold is None:
            threshold = np.mean(indicator) + 2 * np.std(indicator)
        cells_to_refine = np.where(indicator > threshold)[0]
        if max_cells is not None and len(cells_to_refine) > max_cells:
            sorted_idx = np.argsort(indicator[cells_to_refine])[::-1]
            cells_to_refine = cells_to_refine[sorted_idx[:max_cells]]
    if method == 'regular':
        return _regular_refine(mesh, cells_to_refine)
    elif method == 'bisection':
        return _bisection_refine(mesh, cells_to_refine)
    else:
        raise ValueError(f"Unknown refinement method: {method}")

def _regular_refine(mesh, cells_to_refine):
    if mesh.ndim != 2:
        raise NotImplementedError("Regular refinement currently only for 2D")
    new_points = mesh.points.tolist()
    new_cells = []
    edge_to_new_node = {}
    def get_edge_midpoint(n1, n2):
        key = tuple(sorted([n1, n2]))
        if key not in edge_to_new_node:
            p1 = mesh.points[n1]
            p2 = mesh.points[n2]
            mid = (p1 + p2) / 2.0
            edge_to_new_node[key] = len(new_points)
            new_points.append(mid.tolist())
        return edge_to_new_node[key]
    for cid in range(mesh.n_cells):
        cell = mesh.cells[cid]
        if cid not in cells_to_refine:
            new_cells.append(cell.tolist())
            continue
        if len(cell) == 3:
            n0, n1, n2 = cell
            m01 = get_edge_midpoint(n0, n1)
            m12 = get_edge_midpoint(n1, n2)
            m20 = get_edge_midpoint(n2, n0)
            new_cells.append([n0, m01, m20])
            new_cells.append([n1, m12, m01])
            new_cells.append([n2, m20, m12])
            new_cells.append([m01, m12, m20])
        elif len(cell) == 4:
            n0, n1, n2, n3 = cell
            m01 = get_edge_midpoint(n0, n1)
            m12 = get_edge_midpoint(n1, n2)
            m23 = get_edge_midpoint(n2, n3)
            m30 = get_edge_midpoint(n3, n0)
            pc = len(new_points)
            new_points.append(mesh.cell_centers[cid].tolist())
            new_cells.append([n0, m01, pc, m30])
            new_cells.append([n1, m12, pc, m01])
            new_cells.append([n2, m23, pc, m12])
            new_cells.append([n3, m30, pc, m23])
        else:
            new_cells.append(cell.tolist())
    new_mesh = UnstructuredMesh(np.array(new_points, dtype=np.float64), new_cells, mesh.mesh_type)
    _transfer_boundary_info(mesh, new_mesh, edge_to_new_node)
    return new_mesh

def _bisection_refine(mesh, cells_to_refine):
    if mesh.ndim != 2:
        raise NotImplementedError("Bisection refinement only for 2D meshes")
    
    is_tri = all(len(c) == 3 for c in mesh.cells)
    is_quad = all(len(c) == 4 for c in mesh.cells)
    
    if not is_tri and not is_quad:
        raise NotImplementedError("Mixed cell types not supported for bisection refinement")
    
    if is_quad:
        return _regular_refine(mesh, cells_to_refine)
    
    new_points = mesh.points.tolist()
    new_cells = []
    edge_to_new_node = {}
    marked_edges = set()
    for cid in cells_to_refine:
        cell = mesh.cells[cid]
        edges = [tuple(sorted([cell[i], cell[(i+1)%3]])) for i in range(3)]
        for e in edges:
            marked_edges.add(e)
    def get_edge_midpoint(n1, n2):
        key = tuple(sorted([n1, n2]))
        if key not in edge_to_new_node:
            p1 = mesh.points[n1]
            p2 = mesh.points[n2]
            mid = (p1 + p2) / 2.0
            edge_to_new_node[key] = len(new_points)
            new_points.append(mid.tolist())
        return edge_to_new_node[key]
    for cid in range(mesh.n_cells):
        cell = mesh.cells[cid]
        edges = [tuple(sorted([cell[i], cell[(i+1)%3]])) for i in range(3)]
        n_marked = sum(1 for e in edges if e in marked_edges)
        if n_marked == 0:
            new_cells.append(cell.tolist())
        elif n_marked == 1:
            for i, e in enumerate(edges):
                if e in marked_edges:
                    m = get_edge_midpoint(*e)
                    n0, n1, n2 = cell
                    if i == 0:
                        new_cells.append([n0, m, n2])
                        new_cells.append([m, n1, n2])
                    elif i == 1:
                        new_cells.append([n1, m, n0])
                        new_cells.append([m, n2, n0])
                    else:
                        new_cells.append([n2, m, n1])
                        new_cells.append([m, n0, n1])
                    break
        elif n_marked == 2:
            m_nodes = []
            for e in edges:
                if e in marked_edges:
                    m_nodes.append(get_edge_midpoint(*e))
            n0, n1, n2 = cell
            unmarked_idx = [i for i, e in enumerate(edges) if e not in marked_edges][0]
            if unmarked_idx == 0:
                m1 = get_edge_midpoint(n1, n2)
                m2 = get_edge_midpoint(n2, n0)
                new_cells.append([n0, m2, n1])
                new_cells.append([n1, m2, m1])
                new_cells.append([m2, n2, m1])
            elif unmarked_idx == 1:
                m0 = get_edge_midpoint(n0, n1)
                m2 = get_edge_midpoint(n2, n0)
                new_cells.append([n1, m0, n2])
                new_cells.append([n2, m0, m2])
                new_cells.append([m0, n0, m2])
            else:
                m0 = get_edge_midpoint(n0, n1)
                m1 = get_edge_midpoint(n1, n2)
                new_cells.append([n2, m1, n0])
                new_cells.append([n0, m1, m0])
                new_cells.append([m1, n1, m0])
        else:
            n0, n1, n2 = cell
            m01 = get_edge_midpoint(n0, n1)
            m12 = get_edge_midpoint(n1, n2)
            m20 = get_edge_midpoint(n2, n0)
            new_cells.append([n0, m01, m20])
            new_cells.append([n1, m12, m01])
            new_cells.append([n2, m20, m12])
            new_cells.append([m01, m12, m20])
    new_mesh = UnstructuredMesh(np.array(new_points, dtype=np.float64), new_cells, mesh.mesh_type)
    _transfer_boundary_info(mesh, new_mesh, edge_to_new_node)
    return new_mesh

def adaptive_coarsen(mesh, indicator, threshold=None):
    if threshold is None:
        threshold = np.mean(indicator) - np.std(indicator)
    cells_to_coarsen = np.where(indicator < threshold)[0]
    return _coarsen_cells(mesh, cells_to_coarsen)

def _coarsen_cells(mesh, cells_to_remove):
    if mesh.ndim != 2 or not all(len(c) == 3 for c in mesh.cells):
        raise NotImplementedError("Coarsening only for 2D triangular meshes")
    cells_to_remove = set(cells_to_remove)
    remaining_cells = [cid for cid in range(mesh.n_cells) if cid not in cells_to_remove]
    node_map = {}
    new_points = []
    for cid in remaining_cells:
        for n in mesh.cells[cid]:
            if n not in node_map:
                node_map[n] = len(new_points)
                new_points.append(mesh.points[n].tolist())
    new_cells = []
    for cid in remaining_cells:
        cell = mesh.cells[cid]
        new_cell = [node_map[n] for n in cell]
        new_cells.append(new_cell)
    new_mesh = UnstructuredMesh(np.array(new_points, dtype=np.float64), new_cells, mesh.mesh_type)
    return new_mesh

def refine_by_indicator(mesh, indicator, refine_fraction=0.2, coarsen_fraction=0.1,
                        max_iterations=1, min_cell_volume=None, max_cell_volume=None):
    current_mesh = mesh
    for _ in range(max_iterations):
        n_cells = current_mesh.n_cells
        n_refine = int(n_cells * refine_fraction)
        n_coarsen = int(n_cells * coarsen_fraction)
        sorted_idx = np.argsort(indicator)
        refine_threshold = indicator[sorted_idx[-n_refine]] if n_refine > 0 else np.inf
        coarsen_threshold = indicator[sorted_idx[n_coarsen]] if n_coarsen > 0 else -np.inf
        if min_cell_volume is not None:
            too_small = current_mesh.cell_volumes < min_cell_volume
            refine_threshold = min(refine_threshold, np.min(indicator[too_small]) if np.any(too_small) else refine_threshold)
        if max_cell_volume is not None:
            too_large = current_mesh.cell_volumes > max_cell_volume
            coarsen_threshold = max(coarsen_threshold, np.max(indicator[too_large]) if np.any(too_large) else coarsen_threshold)
        if n_coarsen > 0:
            current_mesh = adaptive_coarsen(current_mesh, indicator, coarsen_threshold)
        if n_refine > 0:
            current_mesh = adaptive_refine(current_mesh, indicator, refine_threshold)
        if hasattr(current_mesh, 'n_cells') and current_mesh.n_cells == n_cells:
            break
        indicator = _interpolate_indicator(mesh, current_mesh, indicator)
    return current_mesh

def _interpolate_indicator(old_mesh, new_mesh, old_indicator):
    new_indicator = np.zeros(new_mesh.n_cells, dtype=np.float64)
    for cid in range(new_mesh.n_cells):
        center = new_mesh.cell_centers[cid]
        distances = np.sum((old_mesh.cell_centers - center)**2, axis=1)
        nearest = np.argmin(distances)
        new_indicator[cid] = old_indicator[nearest]
    return new_indicator

def gradient_indicator(mesh, field):
    grad = compute_gradient(mesh, field)
    indicator = np.linalg.norm(grad, axis=1)
    h = np.power(mesh.cell_volumes, 1.0 / mesh.ndim)
    indicator = indicator * h
    return indicator

def curvature_indicator(mesh, field):
    grad = compute_gradient(mesh, field)
    hessian = compute_hessian(mesh, field)
    indicator = np.zeros(mesh.n_cells, dtype=np.float64)
    for cid in range(mesh.n_cells):
        eigvals = np.linalg.eigvalsh(hessian[cid])
        indicator[cid] = np.max(np.abs(eigvals))
    h = np.power(mesh.cell_volumes, 2.0 / mesh.ndim)
    return indicator * h

@njit
def _compute_gradient_jit(mesh_cell_centers, mesh_cells, face_centers, face_normals, face_areas, 
                     owner, neighbour, field, ndim):
    n_cells = len(mesh_cell_centers)
    grad = np.zeros((n_cells, ndim), dtype=np.float64)
    for fid in range(len(owner)):
        c1 = owner[fid]
        c2 = neighbour[fid]
        if c2 < 0:
            continue
        f = field[c2] - field[c1]
        nf = face_normals[fid]
        area = face_areas[fid]
        grad[c1] += f * nf * area
        grad[c2] -= f * nf * area
    for cid in range(n_cells):
        grad[cid] /= mesh_cell_centers.shape[0]
    return grad

def compute_gradient(mesh, field):
    ndim = mesh.ndim
    owner = np.asarray(mesh.owner, dtype=np.int64)
    neighbour = np.asarray(mesh.neighbour, dtype=np.int64)
    face_normals = np.asarray(mesh.face_normals, dtype=np.float64)
    face_areas = np.asarray(mesh.face_areas, dtype=np.float64)
    return _compute_gradient_jit(
        mesh.cell_centers, mesh.cells, mesh.face_centers, face_normals,
        face_areas, owner, neighbour, field, ndim
    )

def compute_hessian(mesh, field):
    n_cells = mesh.n_cells
    ndim = mesh.ndim
    hessian = np.zeros((n_cells, ndim, ndim), dtype=np.float64)
    grad = compute_gradient(mesh, field)
    for d in range(ndim):
        grad_d = compute_gradient(mesh, grad[:, d])
        hessian[:, d, :] = grad_d
    return hessian + np.transpose(hessian, (0, 2, 1))

def _transfer_boundary_info(old_mesh, new_mesh, edge_to_new_node):
    new_mesh.boundary_faces = {}
    old_faces = old_mesh.faces
    old_faces_arr = [f.tolist() for f in old_faces]
    for name, old_fids in old_mesh.boundary_faces.items():
        new_fids = []
        for old_fid in old_fids:
            old_face = old_faces_arr[old_fid]
            if len(old_face) == 2:
                n1, n2 = old_face
                key = tuple(sorted([n1, n2]))
                if key in edge_to_new_node:
                    mid = edge_to_new_node[key]
                    for new_fid, new_face in enumerate(new_mesh.faces):
                        nf = set(new_face.tolist())
                        if {n1, mid}.issubset(nf) and len(nf) == 2:
                            new_fids.append(new_fid)
                        elif {mid, n2}.issubset(nf) and len(nf) == 2:
                            new_fids.append(new_fid)
                else:
                    for new_fid, new_face in enumerate(new_mesh.faces):
                        nf = set(new_face.tolist())
                        if set(old_face).issubset(nf) and len(nf) == 2:
                            new_fids.append(new_fid)
        if new_fids:
            new_mesh.boundary_faces[name] = np.array(new_fids, dtype=np.int64)
