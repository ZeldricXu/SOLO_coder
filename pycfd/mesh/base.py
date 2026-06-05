import numpy as np
from ..core.jit import njit, prange

class Mesh:
    def __init__(self, points, cells, mesh_type='unstructured'):
        self.points = np.asarray(points, dtype=np.float64)
        self.cells = [np.asarray(c, dtype=np.int64) for c in cells]
        self.mesh_type = mesh_type
        self.ndim = self.points.shape[1]
        self.n_nodes = self.points.shape[0]
        self.n_cells = len(self.cells)
        self.cell_centers = np.zeros((self.n_cells, self.ndim), dtype=np.float64)
        self.cell_volumes = np.zeros(self.n_cells, dtype=np.float64)
        self.faces = []
        self.face_centers = []
        self.face_normals = []
        self.face_areas = []
        self.n_faces = 0
        self.owner = []
        self.neighbour = []
        self.boundary_faces = {}
        self.boundary_map = {}
        self._compute_geometry()

    @property
    def n_points(self):
        return self.n_nodes

    def _compute_geometry(self):
        self._compute_cell_centers_and_volumes()
        self._extract_faces()
        self._compute_face_geometry()

    def _compute_cell_centers_and_volumes(self):
        for cid, cell in enumerate(self.cells):
            node_coords = self.points[cell]
            self.cell_centers[cid] = np.mean(node_coords, axis=0)
            if self.ndim == 2:
                self.cell_volumes[cid] = self._polygon_area(node_coords)
            else:
                self.cell_volumes[cid] = self._polyhedron_volume(cell)

    @staticmethod
    @njit
    def _polygon_area(points):
        n = len(points)
        area = 0.0
        for i in range(n):
            j = (i + 1) % n
            area += points[i, 0] * points[j, 1]
            area -= points[j, 0] * points[i, 1]
        return abs(area) / 2.0

    def _polyhedron_volume(self, cell):
        nodes = self.points[cell]
        center = np.mean(nodes, axis=0)
        volume = 0.0
        if len(cell) == 4:
            for i in range(4):
                face_nodes = np.array([
                    nodes[cell[(i+1)%4]],
                    nodes[cell[(i+2)%4]],
                    nodes[cell[(i+3)%4]],
                    center
                ])
                volume += self._tetrahedron_volume(face_nodes)
        return abs(volume)

    @staticmethod
    @njit
    def _tetrahedron_volume(points):
        v1 = points[1] - points[0]
        v2 = points[2] - points[0]
        v3 = points[3] - points[0]
        return abs(np.dot(v1, np.cross(v2, v3))) / 6.0

    def _extract_faces(self):
        face_map = {}
        self.faces = []
        self.owner = []
        self.neighbour = []
        for cid, cell in enumerate(self.cells):
            cell_faces = self._get_cell_faces(cell)
            for face in cell_faces:
                key = tuple(sorted(face))
                if key in face_map:
                    fid = face_map[key]
                    self.neighbour[fid] = cid
                else:
                    fid = len(self.faces)
                    self.faces.append(np.array(face, dtype=np.int64))
                    self.owner.append(cid)
                    self.neighbour.append(-1)
                    face_map[key] = fid
        self.n_faces = len(self.faces)

    def _get_cell_faces(self, cell):
        n = len(cell)
        faces = []
        if self.ndim == 2:
            for i in range(n):
                faces.append([cell[i], cell[(i+1) % n]])
        else:
            if n == 4:
                faces.append([cell[0], cell[1], cell[2]])
                faces.append([cell[0], cell[1], cell[3]])
                faces.append([cell[0], cell[2], cell[3]])
                faces.append([cell[1], cell[2], cell[3]])
            elif n == 8:
                faces.append([cell[0], cell[1], cell[2], cell[3]])
                faces.append([cell[4], cell[5], cell[6], cell[7]])
                faces.append([cell[0], cell[1], cell[5], cell[4]])
                faces.append([cell[1], cell[2], cell[6], cell[5]])
                faces.append([cell[2], cell[3], cell[7], cell[6]])
                faces.append([cell[3], cell[0], cell[4], cell[7]])
        return faces

    def _compute_face_geometry(self):
        self.face_centers = np.zeros((self.n_faces, self.ndim), dtype=np.float64)
        self.face_normals = np.zeros((self.n_faces, self.ndim), dtype=np.float64)
        self.face_areas = np.zeros(self.n_faces, dtype=np.float64)
        _compute_face_geometry_jit(
            self.points, self.faces,
            self.face_centers, self.face_normals, self.face_areas, self.ndim
        )
        self._correct_face_normals()

    def _correct_face_normals(self):
        for fid in range(self.n_faces):
            owner = self.owner[fid]
            neighbour = self.neighbour[fid]
            if owner >= 0:
                cf = self.face_centers[fid] - self.cell_centers[owner]
                if np.dot(cf, self.face_normals[fid]) < 0:
                    self.face_normals[fid] *= -1

    def get_boundary_cells(self, boundary_name):
        if boundary_name not in self.boundary_faces:
            return np.array([], dtype=np.int64)
        faces = self.boundary_faces[boundary_name]
        cells = np.array([self.owner[f] for f in faces], dtype=np.int64)
        return np.unique(cells)

    def get_neighbors(self, cell_id):
        neighbors = []
        for fid in range(self.n_faces):
            if self.owner[fid] == cell_id and self.neighbour[fid] >= 0:
                neighbors.append(self.neighbour[fid])
            elif self.neighbour[fid] == cell_id:
                neighbors.append(self.owner[fid])
        return np.array(neighbors, dtype=np.int64)

@njit
def _compute_face_geometry_jit(points, faces, centers, normals, areas, ndim):
    for fid in prange(len(faces)):
        face = faces[fid]
        node_coords = points[face]
        centers[fid] = np.mean(node_coords, axis=0)
        if ndim == 2:
            dx = node_coords[1, 0] - node_coords[0, 0]
            dy = node_coords[1, 1] - node_coords[0, 1]
            areas[fid] = np.sqrt(dx*dx + dy*dy)
            nx = -dy / areas[fid]
            ny = dx / areas[fid]
            normals[fid, 0] = nx
            normals[fid, 1] = ny
        else:
            n = len(face)
            total_area = 0.0
            normal = np.zeros(3, dtype=np.float64)
            for i in range(1, n-1):
                v1 = node_coords[i] - node_coords[0]
                v2 = node_coords[i+1] - node_coords[0]
                cross = np.cross(v1, v2)
                tri_area = np.sqrt(np.sum(cross*cross)) / 2.0
                total_area += tri_area
                normal += cross
            areas[fid] = total_area
            norm = np.sqrt(np.sum(normal*normal))
            if norm > 1e-12:
                normals[fid] = normal / norm

class StructuredMesh(Mesh):
    def __init__(self, nx, ny, nz=None, x_range=(0,1), y_range=(0,1), z_range=(0,1)):
        self.nx = nx
        self.ny = ny
        self.nz = nz
        self.x_range = x_range
        self.y_range = y_range
        self.z_range = z_range
        self.dx = (x_range[1] - x_range[0]) / (nx - 1)
        self.dy = (y_range[1] - y_range[0]) / (ny - 1)
        if nz is not None:
            self.dz = (z_range[1] - z_range[0]) / (nz - 1)
            self.ndim = 3
        else:
            self.ndim = 2
        points, cells = self._generate_structured()
        super().__init__(points, cells, mesh_type='structured')
        self._setup_structured_boundaries()

    def _generate_structured(self):
        if self.ndim == 2:
            n_points = self.nx * self.ny
            points = np.zeros((n_points, 2), dtype=np.float64)
            x = np.linspace(self.x_range[0], self.x_range[1], self.nx)
            y = np.linspace(self.y_range[0], self.y_range[1], self.ny)
            for i in range(self.nx):
                for j in range(self.ny):
                    idx = i * self.ny + j
                    points[idx, 0] = x[i]
                    points[idx, 1] = y[j]
            n_cells = (self.nx - 1) * (self.ny - 1)
            cells = []
            for i in range(self.nx - 1):
                for j in range(self.ny - 1):
                    idx = i * (self.ny - 1) + j
                    n0 = i * self.ny + j
                    n1 = (i + 1) * self.ny + j
                    n2 = (i + 1) * self.ny + (j + 1)
                    n3 = i * self.ny + (j + 1)
                    cells.append([n0, n1, n2, n3])
        else:
            n_points = self.nx * self.ny * self.nz
            points = np.zeros((n_points, 3), dtype=np.float64)
            x = np.linspace(self.x_range[0], self.x_range[1], self.nx)
            y = np.linspace(self.y_range[0], self.y_range[1], self.ny)
            z = np.linspace(self.z_range[0], self.z_range[1], self.nz)
            for i in range(self.nx):
                for j in range(self.ny):
                    for k in range(self.nz):
                        idx = (i * self.ny + j) * self.nz + k
                        points[idx, 0] = x[i]
                        points[idx, 1] = y[j]
                        points[idx, 2] = z[k]
            n_cells = (self.nx - 1) * (self.ny - 1) * (self.nz - 1)
            cells = []
            for i in range(self.nx - 1):
                for j in range(self.ny - 1):
                    for k in range(self.nz - 1):
                        n0 = (i * self.ny + j) * self.nz + k
                        n1 = ((i + 1) * self.ny + j) * self.nz + k
                        n2 = ((i + 1) * self.ny + (j + 1)) * self.nz + k
                        n3 = (i * self.ny + (j + 1)) * self.nz + k
                        n4 = (i * self.ny + j) * self.nz + (k + 1)
                        n5 = ((i + 1) * self.ny + j) * self.nz + (k + 1)
                        n6 = ((i + 1) * self.ny + (j + 1)) * self.nz + (k + 1)
                        n7 = (i * self.ny + (j + 1)) * self.nz + (k + 1)
                        cells.append([n0, n1, n2, n3, n4, n5, n6, n7])
        return points, cells

    def _setup_structured_boundaries(self):
        self.boundary_faces = {}
        self.boundary_map = {}
        if self.ndim == 2:
            inlet = []
            outlet = []
            bottom = []
            top = []
            left = []
            right = []
            for fid in range(self.n_faces):
                if self.neighbour[fid] < 0:
                    fc = self.face_centers[fid]
                    if abs(fc[0] - self.x_range[0]) < 1e-10:
                        inlet.append(fid)
                        left.append(fid)
                    elif abs(fc[0] - self.x_range[1]) < 1e-10:
                        outlet.append(fid)
                        right.append(fid)
                    elif abs(fc[1] - self.y_range[0]) < 1e-10:
                        bottom.append(fid)
                    elif abs(fc[1] - self.y_range[1]) < 1e-10:
                        top.append(fid)
            self.boundary_faces['inlet'] = np.array(inlet, dtype=np.int64)
            self.boundary_faces['outlet'] = np.array(outlet, dtype=np.int64)
            self.boundary_faces['bottom'] = np.array(bottom, dtype=np.int64)
            self.boundary_faces['top'] = np.array(top, dtype=np.int64)
            self.boundary_faces['left'] = np.array(left, dtype=np.int64)
            self.boundary_faces['right'] = np.array(right, dtype=np.int64)
            self.boundary_map['left'] = 0
            self.boundary_map['right'] = 1
            self.boundary_map['bottom'] = 2
            self.boundary_map['top'] = 3
            self.boundary_map['inlet'] = 0
            self.boundary_map['outlet'] = 1
        else:
            pass

    def get_boundary_faces(self, boundary_id):
        if isinstance(boundary_id, str):
            return self.boundary_faces.get(boundary_id, np.array([], dtype=np.int64))
        for name, bid in self.boundary_map.items():
            if bid == boundary_id and name in self.boundary_faces:
                return self.boundary_faces[name]
        return np.array([], dtype=np.int64)

    def cell_index(self, i, j, k=None):
        if self.ndim == 2:
            return i * (self.ny - 1) + j
        else:
            return (i * (self.ny - 1) + j) * (self.nz - 1) + k

    def cell_ij(self, idx):
        if self.ndim == 2:
            i = idx // (self.ny - 1)
            j = idx % (self.ny - 1)
            return i, j
        else:
            plane = (self.ny - 1) * (self.nz - 1)
            i = idx // plane
            rem = idx % plane
            j = rem // (self.nz - 1)
            k = rem % (self.nz - 1)
            return i, j, k

class UnstructuredMesh(Mesh):
    def __init__(self, points, cells, mesh_type='unstructured'):
        super().__init__(points, cells, mesh_type=mesh_type)
