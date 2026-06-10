import h5py
import numpy as np
from pathlib import Path
from datetime import datetime

class HDF5Writer:
    def __init__(self, filename, mode='w'):
        self.filename = str(filename)
        self.mode = mode
        self._file = None

    def __enter__(self):
        self.open()
        return self

    def __exit__(self, exc_type, exc_val, exc_tb):
        self.close()

    def open(self):
        self._file = h5py.File(self.filename, self.mode)
        if 'metadata' not in self._file:
            meta = self._file.create_group('metadata')
            meta.attrs['created'] = datetime.now().isoformat()
            meta.attrs['version'] = '0.1.0'

    def close(self):
        if self._file is not None:
            self._file.close()
            self._file = None

    def write_mesh(self, mesh):
        if 'mesh' in self._file:
            del self._file['mesh']
        grp = self._file.create_group('mesh')
        grp.attrs['type'] = mesh.mesh_type
        grp.attrs['ndim'] = mesh.ndim
        grp.attrs['n_cells'] = mesh.n_cells
        grp.attrs['n_faces'] = mesh.n_faces
        grp.attrs['n_nodes'] = mesh.n_nodes
        if hasattr(mesh, 'nx') and mesh.nx is not None:
            grp.attrs['nx'] = mesh.nx
        if hasattr(mesh, 'ny') and mesh.ny is not None:
            grp.attrs['ny'] = mesh.ny
        if hasattr(mesh, 'nz') and mesh.nz is not None:
            grp.attrs['nz'] = mesh.nz
        if hasattr(mesh, 'x_range') and mesh.x_range is not None:
            grp.attrs['x_range'] = mesh.x_range
        if hasattr(mesh, 'y_range') and mesh.y_range is not None:
            grp.attrs['y_range'] = mesh.y_range
        if hasattr(mesh, 'z_range') and mesh.z_range is not None:
            grp.attrs['z_range'] = mesh.z_range
        grp.create_dataset('points', data=mesh.points)
        
        if isinstance(mesh.faces, np.ndarray):
            grp.create_dataset('faces', data=mesh.faces.astype(np.int64))
        else:
            max_face_nodes = max(len(f) for f in mesh.faces) if mesh.faces else 0
            faces_arr = np.full((len(mesh.faces), max_face_nodes), -1, dtype=np.int64)
            for i, f in enumerate(mesh.faces):
                faces_arr[i, :len(f)] = f
            grp.create_dataset('faces', data=faces_arr)
        
        if isinstance(mesh.cells, np.ndarray):
            grp.create_dataset('cells', data=mesh.cells.astype(np.int64))
        else:
            max_cell_nodes = max(len(c) for c in mesh.cells) if mesh.cells else 0
            cells_arr = np.full((len(mesh.cells), max_cell_nodes), -1, dtype=np.int64)
            for i, c in enumerate(mesh.cells):
                cells_arr[i, :len(c)] = c
            grp.create_dataset('cells', data=cells_arr)
        
        grp.create_dataset('cell_centers', data=mesh.cell_centers)
        grp.create_dataset('face_centers', data=mesh.face_centers)
        grp.create_dataset('face_normals', data=mesh.face_normals)
        grp.create_dataset('cell_volumes', data=mesh.cell_volumes)
        grp.create_dataset('face_areas', data=mesh.face_areas)
        if hasattr(mesh, 'boundary_faces'):
            bnd_grp = grp.create_group('boundaries')
            for name, faces in mesh.boundary_faces.items():
                bnd_grp.create_dataset(name, data=np.array(faces, dtype=np.int64))

    def write_field(self, name, field, timestep=None, attrs=None):
        if timestep is not None:
            grp_path = f'fields/timestep_{timestep:08d}'
        else:
            grp_path = 'fields/initial'
        if grp_path not in self._file:
            grp = self._file.create_group(grp_path)
            if timestep is not None:
                grp.attrs['timestep'] = timestep
        else:
            grp = self._file[grp_path]
        if name in grp:
            del grp[name]
        ds = grp.create_dataset(name, data=field)
        if attrs:
            for k, v in attrs.items():
                ds.attrs[k] = v

    def write_checkpoint(self, timestep, fields, solver_state=None):
        ckpt_grp = f'checkpoints/timestep_{timestep:08d}'
        if ckpt_grp in self._file:
            del self._file[ckpt_grp]
        grp = self._file.create_group(ckpt_grp)
        grp.attrs['timestep'] = timestep
        grp.attrs['time'] = solver_state.get('time', 0.0) if solver_state else 0.0
        for name, field in fields.items():
            grp.create_dataset(name, data=field)
        if solver_state:
            state_grp = grp.create_group('solver_state')
            for k, v in solver_state.items():
                if isinstance(v, (int, float, str)):
                    state_grp.attrs[k] = v
                elif isinstance(v, np.ndarray):
                    state_grp.create_dataset(k, data=v)

class HDF5Reader:
    def __init__(self, filename):
        self.filename = str(filename)
        self._file = None

    def __enter__(self):
        self.open()
        return self

    def __exit__(self, exc_type, exc_val, exc_tb):
        self.close()

    def open(self):
        self._file = h5py.File(self.filename, 'r')

    def close(self):
        if self._file is not None:
            self._file.close()
            self._file = None

    def get_timesteps(self):
        if 'fields' not in self._file:
            return []
        timesteps = []
        for key in self._file['fields'].keys():
            if key.startswith('timestep_'):
                timesteps.append(int(key.split('_')[1]))
        return sorted(timesteps)

    def get_field_names(self, timestep=None):
        if timestep is not None:
            grp_path = f'fields/timestep_{timestep:08d}'
        else:
            grp_path = 'fields/initial'
        if grp_path not in self._file:
            return []
        return list(self._file[grp_path].keys())

    def read_field(self, name, timestep=None):
        if timestep is not None:
            grp_path = f'fields/timestep_{timestep:08d}'
        else:
            grp_path = 'fields/initial'
        return np.array(self._file[grp_path][name])

    def read_mesh(self):
        return self._file['mesh']

    def list_checkpoints(self):
        if 'checkpoints' not in self._file:
            return []
        checkpoints = []
        for key in self._file['checkpoints'].keys():
            if key.startswith('timestep_'):
                ts = int(key.split('_')[1])
                time = self._file['checkpoints'][key].attrs.get('time', 0.0)
                checkpoints.append((ts, time))
        return sorted(checkpoints)

    def read_checkpoint(self, timestep):
        ckpt_grp = f'checkpoints/timestep_{timestep:08d}'
        grp = self._file[ckpt_grp]
        fields = {}
        for name in grp.keys():
            if name != 'solver_state':
                fields[name] = np.array(grp[name])
        solver_state = {}
        if 'solver_state' in grp:
            state_grp = grp['solver_state']
            for k in state_grp.attrs:
                solver_state[k] = state_grp.attrs[k]
            for k in state_grp.keys():
                solver_state[k] = np.array(state_grp[k])
        return fields, solver_state
