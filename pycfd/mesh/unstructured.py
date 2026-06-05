import numpy as np
from .base import UnstructuredMesh

def create_triangular_mesh(points, boundary_edges=None):
    try:
        from scipy.spatial import Delaunay
    except ImportError:
        raise ImportError("scipy is required for Delaunay triangulation")
    points = np.asarray(points, dtype=np.float64)
    tri = Delaunay(points)
    cells = tri.simplices.tolist()
    mesh = UnstructuredMesh(points, cells, mesh_type='unstructured_triangular')
    if boundary_edges is not None:
        _setup_boundary_from_edges(mesh, boundary_edges)
    return mesh

def _setup_boundary_from_edges(mesh, boundary_edges):
    mesh.boundary_faces = {}
    edge_to_fid = {}
    for fid, face in enumerate(mesh.faces):
        if mesh.neighbour[fid] < 0:
            key = tuple(sorted(face.tolist()))
            edge_to_fid[key] = fid
    for name, edges in boundary_edges.items():
        fids = []
        for edge in edges:
            key = tuple(sorted(edge))
            if key in edge_to_fid:
                fids.append(edge_to_fid[key])
        if fids:
            mesh.boundary_faces[name] = np.array(fids, dtype=np.int64)

def create_rectangular_tri_mesh(nx, ny, x_range=(0, 1), y_range=(0, 1)):
    x = np.linspace(x_range[0], x_range[1], nx)
    y = np.linspace(y_range[0], y_range[1], ny)
    points = []
    for j in range(ny):
        for i in range(nx):
            points.append([x[i], y[j]])
    points = np.array(points, dtype=np.float64)
    cells = []
    for j in range(ny - 1):
        for i in range(nx - 1):
            n0 = j * nx + i
            n1 = j * nx + i + 1
            n2 = (j + 1) * nx + i
            n3 = (j + 1) * nx + i + 1
            cells.append([n0, n1, n2])
            cells.append([n1, n3, n2])
    boundary_edges = {}
    bottom = [[j * nx + i, j * nx + i + 1] for i in range(nx - 1) for j in [0]]
    top = [[j * nx + i, j * nx + i + 1] for i in range(nx - 1) for j in [ny - 1]]
    left = [[j * nx + i, (j + 1) * nx + i] for j in range(ny - 1) for i in [0]]
    right = [[j * nx + i, (j + 1) * nx + i] for j in range(ny - 1) for i in [nx - 1]]
    boundary_edges['bottom'] = bottom
    boundary_edges['top'] = top
    boundary_edges['inlet'] = left
    boundary_edges['outlet'] = right
    mesh = UnstructuredMesh(points, cells, mesh_type='unstructured_triangular')
    _setup_boundary_from_edges(mesh, boundary_edges)
    return mesh

def create_tetrahedral_mesh(points):
    try:
        from scipy.spatial import Delaunay
    except ImportError:
        raise ImportError("scipy is required for Delaunay tetrahedralization")
    points = np.asarray(points, dtype=np.float64)
    tri = Delaunay(points)
    cells = tri.simplices.tolist()
    return UnstructuredMesh(points, cells, mesh_type='unstructured_tetrahedral')

def create_box_tet_mesh(nx, ny, nz, x_range=(0, 1), y_range=(0, 1), z_range=(0, 1)):
    x = np.linspace(x_range[0], x_range[1], nx)
    y = np.linspace(y_range[0], y_range[1], ny)
    z = np.linspace(z_range[0], z_range[1], nz)
    points = []
    for k in range(nz):
        for j in range(ny):
            for i in range(nx):
                points.append([x[i], y[j], z[k]])
    points = np.array(points, dtype=np.float64)
    cells = []
    def node_idx(i, j, k):
        return (k * ny + j) * nx + i
    for k in range(nz - 1):
        for j in range(ny - 1):
            for i in range(nx - 1):
                n0 = node_idx(i, j, k)
                n1 = node_idx(i+1, j, k)
                n2 = node_idx(i+1, j+1, k)
                n3 = node_idx(i, j+1, k)
                n4 = node_idx(i, j, k+1)
                n5 = node_idx(i+1, j, k+1)
                n6 = node_idx(i+1, j+1, k+1)
                n7 = node_idx(i, j+1, k+1)
                cells.append([n0, n1, n2, n5])
                cells.append([n0, n2, n7, n5])
                cells.append([n0, n4, n7, n5])
                cells.append([n2, n6, n7, n5])
                cells.append([n0, n2, n3, n7])
    return UnstructuredMesh(points, cells, mesh_type='unstructured_tetrahedral')

def mesh_quality(mesh, metric='aspect_ratio'):
    n = mesh.n_cells
    quality = np.zeros(n, dtype=np.float64)
    if metric == 'aspect_ratio':
        for cid in range(n):
            cell = mesh.cells[cid]
            nodes = mesh.points[cell]
            if len(cell) == 3:
                quality[cid] = _triangle_aspect_ratio(nodes)
            elif len(cell) == 4 and mesh.ndim == 3:
                quality[cid] = _tet_aspect_ratio(nodes)
            elif len(cell) == 4:
                quality[cid] = _quad_aspect_ratio(nodes)
    elif metric == 'skewness':
        for cid in range(n):
            quality[cid] = _cell_skewness(mesh, cid)
    return quality

@np.vectorize
def _triangle_aspect_ratio(nodes):
    a = np.linalg.norm(nodes[1] - nodes[0])
    b = np.linalg.norm(nodes[2] - nodes[1])
    c = np.linalg.norm(nodes[0] - nodes[2])
    s = (a + b + c) / 2.0
    area = np.sqrt(s * (s - a) * (s - b) * (s - c))
    if area < 1e-15:
        return 1e15
    return (a * b * c) / (8 * area)

def _quad_aspect_ratio(nodes):
    edges = []
    for i in range(4):
        edges.append(np.linalg.norm(nodes[(i+1)%4] - nodes[i]))
    return max(edges) / min(edges)

def _tet_aspect_ratio(nodes):
    edges = []
    for i in range(4):
        for j in range(i+1, 4):
            edges.append(np.linalg.norm(nodes[j] - nodes[i]))
    R = _tet_circumradius(nodes)
    r = _tet_inradius(nodes)
    if r < 1e-15:
        return 1e15
    return R / r * 0.75

def _tet_circumradius(nodes):
    a = np.linalg.norm(nodes[1] - nodes[0])
    b = np.linalg.norm(nodes[2] - nodes[0])
    c = np.linalg.norm(nodes[3] - nodes[0])
    p = np.linalg.norm(nodes[2] - nodes[1])
    q = np.linalg.norm(nodes[3] - nodes[1])
    r = np.linalg.norm(nodes[3] - nodes[2])
    V = abs(np.dot(nodes[1] - nodes[0], np.cross(nodes[2] - nodes[0], nodes[3] - nodes[0]))) / 6.0
    if V < 1e-15:
        return 1e15
    return np.sqrt((a*p + b*q + c*r) * (a*p + b*q - c*r) * (a*p - b*q + c*r) * (-a*p + b*q + c*r)) / (24 * V)

def _tet_inradius(nodes):
    V = abs(np.dot(nodes[1] - nodes[0], np.cross(nodes[2] - nodes[0], nodes[3] - nodes[0]))) / 6.0
    area_face1 = 0.5 * np.linalg.norm(np.cross(nodes[2] - nodes[1], nodes[3] - nodes[1]))
    area_face2 = 0.5 * np.linalg.norm(np.cross(nodes[2] - nodes[0], nodes[3] - nodes[0]))
    area_face3 = 0.5 * np.linalg.norm(np.cross(nodes[1] - nodes[0], nodes[3] - nodes[0]))
    area_face4 = 0.5 * np.linalg.norm(np.cross(nodes[1] - nodes[0], nodes[2] - nodes[0]))
    total_area = area_face1 + area_face2 + area_face3 + area_face4
    if total_area < 1e-15:
        return 1e15
    return 3 * V / total_area

def _cell_skewness(mesh, cid):
    cell = mesh.cells[cid]
    center = mesh.cell_centers[cid]
    nodes = mesh.points[cell]
    face_centers = []
    for i in range(len(cell)):
        face_nodes = nodes[[i, (i+1)%len(cell)]] if mesh.ndim == 2 else nodes[list(range(i)) + list(range(i+1, len(cell)))]
        face_centers.append(np.mean(face_nodes, axis=0))
    max_skew = 0.0
    for fc in face_centers:
        d = fc - center
        n = d / (np.linalg.norm(d) + 1e-15)
        fn = fc - center
        ideal = fn / (np.linalg.norm(fn) + 1e-15)
        skew = 1 - np.dot(n, ideal)
        max_skew = max(max_skew, skew)
    return max_skew
