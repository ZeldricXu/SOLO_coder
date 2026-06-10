import numpy as np
from ..core.jit import njit

def _set_matrix_value(A, row, col, value):
    """Set matrix value, works for both SparseMatrix and scipy sparse matrices."""
    if hasattr(A, 'set_value'):
        A.set_value(row, col, value)
    else:
        # Scipy sparse matrix - convert to lil for modification if needed
        if hasattr(A, 'tolil'):
            A = A.tolil()
            A[row, col] = value
            return A.tocsr()
        else:
            A[row, col] = value
    return A

def _add_matrix_value(A, row, col, value):
    """Add matrix value, works for both SparseMatrix and scipy sparse matrices."""
    if hasattr(A, 'add_value'):
        A.add_value(row, col, value)
    else:
        if hasattr(A, 'tolil'):
            A = A.tolil()
            A[row, col] += value
            return A.tocsr()
        else:
            A[row, col] += value
    return A

class BoundaryCondition:
    def __init__(self, boundary_name, bc_type='generic'):
        self.boundary_name = boundary_name
        self.bc_type = bc_type
        self.faces = None
        self._initialized = False
        self._validation_errors = []

    def initialize(self, mesh):
        if isinstance(self.boundary_name, str) and self.boundary_name in mesh.boundary_faces:
            self.faces = mesh.boundary_faces[self.boundary_name]
        elif isinstance(self.boundary_name, int):
            self.faces = mesh.get_boundary_faces(self.boundary_name)
        elif isinstance(self.boundary_name, np.ndarray):
            self.faces = self.boundary_name
        else:
            self.faces = np.array([], dtype=np.int64)
        self._initialized = True

    def validate(self, mesh):
        """Validate boundary condition face indices against mesh."""
        errors = []
        
        if self.faces is None:
            self.initialize(mesh)
        
        faces = np.asarray(self.faces, dtype=np.int64)
        
        negative_mask = faces < 0
        if np.any(negative_mask):
            bad = faces[negative_mask]
            errors.append(f"Negative face IDs: {bad.tolist()}")
        
        oob_mask = faces >= mesh.n_faces
        if np.any(oob_mask):
            bad = faces[oob_mask]
            errors.append(f"Face IDs exceed bounds (max {mesh.n_faces-1}): {bad.tolist()}")
        
        if len(faces) != len(np.unique(faces)):
            seen = {}
            dupes = []
            for f in faces:
                if f in seen:
                    dupes.append(f)
                seen[f] = True
            if dupes:
                errors.append(f"Duplicate face IDs: {dupes}")
        
        self._validation_errors = errors
        self._validated = True
        return len(errors) == 0, errors

    def get_boundary_cells(self, mesh):
        if self._validation_errors:
            raise ValueError(f"BC has validation errors: {self._validation_errors}")
        if self.faces is None or len(self.faces) == 0:
            return np.array([], dtype=np.int64)
        if mesh is None:
            return np.array([], dtype=np.int64)
        if not getattr(self, '_validated', False):
            for f in self.faces:
                if f < 0 or f >= mesh.n_faces:
                    raise IndexError(f"Face ID {f} out of bounds [0, {mesh.n_faces-1}]")
        return np.array([mesh.owner[f] for f in self.faces], dtype=np.int64)

    def apply_velocity(self, A, b, direction, flow, mesh):
        raise NotImplementedError

    def apply_pressure(self, A, b, flow, mesh):
        raise NotImplementedError

    def apply_scalar(self, A, b, phi, scalar_name, flow, mesh):
        raise NotImplementedError

class BoundaryManager:
    def __init__(self):
        self.boundary_conditions = {}

    def add_bc(self, name_or_bc, bc=None):
        if bc is None:
            bc = name_or_bc
            if isinstance(bc.boundary_name, str):
                name = bc.boundary_name
            else:
                name = f"bc_{len(self.boundary_conditions)}"
            self.boundary_conditions[name] = bc
        else:
            if not isinstance(bc.boundary_name, np.ndarray):
                bc.boundary_name = name_or_bc
            self.boundary_conditions[name_or_bc] = bc

    def initialize(self, mesh):
        for bc in self.boundary_conditions.values():
            bc.initialize(mesh)
            is_valid, errors = bc.validate(mesh)
            if not is_valid:
                raise ValueError(f"Boundary condition '{bc.boundary_name}' validation failed: {errors}")

    def apply_velocity_bc(self, A, b, direction, flow, mesh=None):
        for bc in self.boundary_conditions.values():
            A, b = bc.apply_velocity(A, b, direction, flow, mesh)
        return A, b

    def apply_pressure_bc(self, A, b, flow, mesh=None):
        for bc in self.boundary_conditions.values():
            A, b = bc.apply_pressure(A, b, flow, mesh)
        return A, b

    def apply_scalar_bc(self, A, b, phi, scalar_name, flow, mesh=None):
        for bc in self.boundary_conditions.values():
            A, b = bc.apply_scalar(A, b, phi, scalar_name, flow, mesh)
        return A, b

    def get_bc(self, name):
        return self.boundary_conditions.get(name)

class VelocityInletBC(BoundaryCondition):
    def __init__(self, boundary_name, velocity=None, udf=None):
        super().__init__(boundary_name, bc_type='velocity_inlet')
        self.velocity = np.asarray(velocity) if velocity is not None else None
        self.udf = udf

    def get_velocity(self, mesh, time=0.0):
        if self.udf is not None:
            faces = mesh.boundary_faces[self.boundary_name]
            centers = np.array([mesh.face_centers[f] for f in faces])
            return self.udf.evaluate(centers, time)
        return self.velocity

    def apply_velocity(self, A, b, direction, flow, mesh):
        cells = self.get_boundary_cells(mesh)
        vel = self.get_velocity(mesh)
        if vel is None:
            return A, b
        vel_dir = vel[direction] if vel.ndim > 0 else vel
        for cid in cells:
            A = _set_matrix_value(A, cid, cid, 1e15)
            b[cid] = 1e15 * vel_dir
        return A, b

    def apply_pressure(self, A, b, flow, mesh):
        return A, b

    def apply_scalar(self, A, b, phi, scalar_name, flow, mesh):
        cells = self.get_boundary_cells(mesh)
        if scalar_name == 'k':
            value = 0.01
        elif scalar_name == 'epsilon':
            value = 0.001
        elif scalar_name == 'omega':
            value = 10.0
        else:
            return A, b
        for cid in cells:
            A = _set_matrix_value(A, cid, cid, 1e15)
            b[cid] = 1e15 * value
        return A, b

class PressureInletBC(BoundaryCondition):
    def __init__(self, boundary_name, total_pressure=0.0, direction=None):
        super().__init__(boundary_name, bc_type='pressure_inlet')
        self.total_pressure = total_pressure
        self.direction = np.asarray(direction) if direction is not None else np.array([1.0, 0.0])

    def apply_velocity(self, A, b, direction, flow, mesh):
        cells = self.get_boundary_cells(mesh)
        if cells.size == 0:
            return A, b
        d = self.direction[direction] if self.direction.ndim > 0 else self.direction
        for cid in cells:
            neighbor_cells = mesh.get_neighbors(cid)
            if len(neighbor_cells) > 0:
                interior_value = np.mean(flow.u[neighbor_cells, direction])
                vel = interior_value * d
                A = _set_matrix_value(A, cid, cid, 1e15)
                b[cid] = 1e15 * vel
        return A, b

    def apply_pressure(self, A, b, flow, mesh):
        cells = self.get_boundary_cells(mesh)
        for cid in cells:
            A = _set_matrix_value(A, cid, cid, 1e15)
            b[cid] = 1e15 * self.total_pressure
        return A, b

    def apply_scalar(self, A, b, phi, scalar_name, flow, mesh):
        return A, b

class PressureOutletBC(BoundaryCondition):
    def __init__(self, boundary_name, static_pressure=0.0, pressure=None):
        super().__init__(boundary_name, bc_type='pressure_outlet')
        if pressure is not None:
            self.static_pressure = pressure
        else:
            self.static_pressure = static_pressure

    def apply_velocity(self, A, b, direction, flow, mesh):
        cells = self.get_boundary_cells(mesh)
        for cid in cells:
            neighbor_cells = mesh.get_neighbors(cid)
            if len(neighbor_cells) > 0:
                interior_value = np.mean(flow.u[neighbor_cells, direction])
                A = _set_matrix_value(A, cid, cid, 1e15)
                b[cid] = 1e15 * interior_value
        return A, b

    def apply_pressure(self, A, b, flow, mesh):
        cells = self.get_boundary_cells(mesh)
        for cid in cells:
            A = _set_matrix_value(A, cid, cid, 1e15)
            b[cid] = 0.0
        return A, b

    def apply_scalar(self, A, b, phi, scalar_name, flow, mesh):
        cells = self.get_boundary_cells(mesh)
        for cid in cells:
            neighbor_cells = mesh.get_neighbors(cid)
            if len(neighbor_cells) > 0:
                interior_value = np.mean(phi[neighbor_cells])
                A = _set_matrix_value(A, cid, cid, 1e15)
                b[cid] = 1e15 * interior_value
        return A, b

class OutflowBC(BoundaryCondition):
    def __init__(self, boundary_name):
        super().__init__(boundary_name, bc_type='outflow')

    def apply_velocity(self, A, b, direction, flow, mesh):
        cells = self.get_boundary_cells(mesh)
        for cid in cells:
            neighbor_cells = mesh.get_neighbors(cid)
            if len(neighbor_cells) > 0:
                interior_value = np.mean(flow.u[neighbor_cells, direction])
                A = _set_matrix_value(A, cid, cid, 1e15)
                b[cid] = 1e15 * interior_value
        return A, b

    def apply_pressure(self, A, b, flow, mesh):
        cells = self.get_boundary_cells(mesh)
        for cid in cells:
            neighbor_cells = mesh.get_neighbors(cid)
            if len(neighbor_cells) > 0:
                neighbors = neighbor_cells
                for nb in neighbors:
                    A = _add_matrix_value(A, cid, nb, -1.0)
                A = _set_matrix_value(A, cid, cid, len(neighbors))
                b[cid] = 0.0
        return A, b

    def apply_scalar(self, A, b, phi, scalar_name, flow, mesh):
        cells = self.get_boundary_cells(mesh)
        for cid in cells:
            neighbor_cells = mesh.get_neighbors(cid)
            if len(neighbor_cells) > 0:
                interior_value = np.mean(phi[neighbor_cells])
                A = _set_matrix_value(A, cid, cid, 1e15)
                b[cid] = 1e15 * interior_value
        return A, b

class WallBC(BoundaryCondition):
    def __init__(self, boundary_name, no_slip=True, udf=None, velocity=None):
        super().__init__(boundary_name, bc_type='wall')
        self.no_slip = no_slip
        self.udf = udf
        self.velocity = np.asarray(velocity, dtype=np.float64) if velocity is not None else None

    def apply_velocity(self, A, b, direction, flow, mesh):
        cells = self.get_boundary_cells(mesh)
        for cid in cells:
            if self.udf is not None:
                centers = mesh.cell_centers[cid]
                vel = self.udf.evaluate(centers.reshape(1, -1), 0.0)[0]
                value = vel[direction] if hasattr(vel, '__len__') else vel
            elif self.velocity is not None:
                value = self.velocity[direction] if hasattr(self.velocity, '__len__') else self.velocity
            elif self.no_slip:
                value = 0.0
            else:
                return A, b
            A = _set_matrix_value(A, cid, cid, 1e15)
            b[cid] = 1e15 * value
        return A, b

    def apply_pressure(self, A, b, flow, mesh):
        cells = self.get_boundary_cells(mesh)
        for cid in cells:
            neighbor_cells = mesh.get_neighbors(cid)
            if len(neighbor_cells) > 0:
                for nb in neighbor_cells:
                    A = _add_matrix_value(A, cid, nb, -1.0)
                A = _set_matrix_value(A, cid, cid, len(neighbor_cells))
                b[cid] = 0.0
        return A, b

    def apply_scalar(self, A, b, phi, scalar_name, flow, mesh):
        cells = self.get_boundary_cells(mesh)
        if scalar_name == 'k':
            value = 0.0
        elif scalar_name == 'epsilon':
            cells = self.get_boundary_cells(mesh)
            for cid in cells:
                dy = np.linalg.norm(mesh.cell_centers[cid] - mesh.face_centers[self.faces[0]])
                nu = 1e-6
                u_tau = 0.01
                value = nu * u_tau * u_tau / (0.4187 * dy * dy)
                A = _set_matrix_value(A, cid, cid, 1e15)
                b[cid] = 1e15 * value
            return A, b
        elif scalar_name == 'omega':
            cells = self.get_boundary_cells(mesh)
            for cid in cells:
                dy = np.linalg.norm(mesh.cell_centers[cid] - mesh.face_centers[self.faces[0]])
                nu = 1e-6
                value = 6.0 * nu / (0.075 * dy * dy)
                A = _set_matrix_value(A, cid, cid, 1e15)
                b[cid] = 1e15 * value
            return A, b
        else:
            return A, b
        for cid in cells:
            A = _set_matrix_value(A, cid, cid, 1e15)
            b[cid] = 1e15 * value
        return A, b

class SymmetryBC(BoundaryCondition):
    def __init__(self, boundary_name):
        super().__init__(boundary_name, bc_type='symmetry')

    def apply_velocity(self, A, b, direction, flow, mesh):
        if self.faces is None or len(self.faces) == 0:
            return A, b
        for fid in self.faces:
            cid = mesh.owner[fid]
            normal = mesh.face_normals[fid]
            if np.abs(normal[direction]) > 0.5:
                A = _set_matrix_value(A, cid, cid, 1e15)
                b[cid] = 0.0
            else:
                neighbor_cells = mesh.get_neighbors(cid)
                if len(neighbor_cells) > 0:
                    best_nb = None
                    best_dot = 0.0
                    for nb in neighbor_cells:
                        d = mesh.cell_centers[nb] - mesh.cell_centers[cid]
                        d_norm = np.linalg.norm(d)
                        if d_norm > 0:
                            d = d / d_norm
                            dot = np.abs(np.dot(d, normal))
                            if dot > best_dot:
                                best_dot = dot
                                best_nb = nb
                    if best_nb is not None and best_dot > 0.5:
                        A = _set_matrix_value(A, cid, cid, 1.0)
                        A = _add_matrix_value(A, cid, best_nb, -1.0)
                        b[cid] = 0.0
                    else:
                        A = _set_matrix_value(A, cid, cid, 1e15)
                        b[cid] = 1e15 * flow.u[cid, direction]
        return A, b

    def apply_pressure(self, A, b, flow, mesh):
        cells = self.get_boundary_cells(mesh)
        for cid in cells:
            neighbor_cells = mesh.get_neighbors(cid)
            if len(neighbor_cells) > 0:
                best_nb = None
                best_dot = 0.0
                for fid in self.faces:
                    if mesh.owner[fid] == cid:
                        normal = mesh.face_normals[fid]
                        for nb in neighbor_cells:
                            d = mesh.cell_centers[nb] - mesh.cell_centers[cid]
                            d_norm = np.linalg.norm(d)
                            if d_norm > 0:
                                d = d / d_norm
                                dot = np.abs(np.dot(d, normal))
                                if dot > best_dot:
                                    best_dot = dot
                                    best_nb = nb
                        break
                if best_nb is not None and best_dot > 0.5:
                    A = _set_matrix_value(A, cid, cid, 1.0)
                    A = _add_matrix_value(A, cid, best_nb, -1.0)
                    b[cid] = 0.0
        return A, b

    def apply_scalar(self, A, b, phi, scalar_name, flow, mesh):
        cells = self.get_boundary_cells(mesh)
        for cid in cells:
            neighbor_cells = mesh.get_neighbors(cid)
            if len(neighbor_cells) > 0:
                for nb in neighbor_cells:
                    A = _add_matrix_value(A, cid, nb, -1.0)
                A = _set_matrix_value(A, cid, cid, len(neighbor_cells))
                b[cid] = 0.0
        return A, b

class PeriodicBC(BoundaryCondition):
    def __init__(self, boundary_name, paired_boundary, translation_vector=None):
        super().__init__(boundary_name, bc_type='periodic')
        self.paired_boundary = paired_boundary
        self.translation_vector = np.asarray(translation_vector) if translation_vector is not None else None
        self.paired_faces = None
        self.face_mapping = {}

    def initialize(self, mesh):
        super().initialize(mesh)
        if self.paired_boundary in mesh.boundary_faces:
            self.paired_faces = mesh.boundary_faces[self.paired_boundary]
            self._build_face_mapping(mesh)

    def _build_face_mapping(self, mesh):
        if self.faces is None or self.paired_faces is None:
            return
        for fid1 in self.faces:
            fc1 = mesh.face_centers[fid1]
            best_match = -1
            min_dist = np.inf
            for fid2 in self.paired_faces:
                fc2 = mesh.face_centers[fid2]
                if self.translation_vector is not None:
                    target = fc1 + self.translation_vector
                    dist = np.linalg.norm(fc2 - target)
                else:
                    dist = np.linalg.norm(fc2 - fc1)
                if dist < min_dist:
                    min_dist = dist
                    best_match = fid2
            if best_match >= 0:
                self.face_mapping[fid1] = best_match

    def apply_velocity(self, A, b, direction, flow, mesh):
        for fid1, fid2 in self.face_mapping.items():
            c1 = mesh.owner[fid1]
            c2 = mesh.owner[fid2]
            A = _set_matrix_value(A, c1, c1, 1.0)
            A = _set_matrix_value(A, c1, c2, -1.0)
            b[c1] = 0.0
        return A, b

    def apply_pressure(self, A, b, flow, mesh):
        return A, b

    def apply_scalar(self, A, b, phi, scalar_name, flow, mesh):
        for fid1, fid2 in self.face_mapping.items():
            c1 = mesh.owner[fid1]
            c2 = mesh.owner[fid2]
            A = _set_matrix_value(A, c1, c1, 1.0)
            A = _set_matrix_value(A, c1, c2, -1.0)
            b[c1] = 0.0
        return A, b

class UDFBoundaryCondition(BoundaryCondition):
    def __init__(self, boundary_name, bc_type='generic', udf=None, func=None):
        super().__init__(boundary_name, bc_type=bc_type)
        if func is not None:
            class _FuncWrapper:
                def __init__(self, f):
                    self.f = f
                def evaluate(self, positions, time=0.0):
                    return self.f(positions, time)
            self.udf = _FuncWrapper(func)
        else:
            self.udf = udf

    def apply_velocity(self, A, b, direction, flow, mesh):
        cells = self.get_boundary_cells(mesh)
        centers = mesh.cell_centers[cells]
        values = self.udf.evaluate(centers, mesh.solver.time if hasattr(mesh, 'solver') else 0.0)
        for i, cid in enumerate(cells):
            val = values[i, direction] if values.ndim > 1 else values[i]
            A = _set_matrix_value(A, cid, cid, 1e15)
            b[cid] = 1e15 * val
        return A, b

    def apply_pressure(self, A, b, flow, mesh):
        if self.bc_type == 'pressure' or self.bc_type == 'generic':
            cells = self.get_boundary_cells(mesh)
            centers = mesh.cell_centers[cells]
            values = self.udf.evaluate(centers, mesh.solver.time if hasattr(mesh, 'solver') else 0.0)
            for i, cid in enumerate(cells):
                val = values[i, 0] if values.ndim > 1 else (values[i] if values.ndim > 0 else values)
                A = _set_matrix_value(A, cid, cid, 1e15)
                b[cid] = 1e15 * val
        return A, b

    def apply_scalar(self, A, b, phi, scalar_name, flow, mesh):
        return A, b
