import numpy as np
import h5py
from .base import UnstructuredMesh, StructuredMesh

def read_cgns(filename, base_index=0, zone_index=0):
    try:
        import h5py
        return _read_cgns_hdf5(filename, base_index, zone_index)
    except ImportError:
        raise ImportError("h5py is required to read CGNS files")

def _read_cgns_hdf5(filename, base_index, zone_index):
    with h5py.File(filename, 'r') as f:
        bases = [k for k in f.keys() if k.startswith('Base')]
        if not bases:
            bases = list(f.keys())
        base_name = bases[base_index]
        base = f[base_name]
        zones = [k for k in base.keys() if k.startswith('Zone')]
        if not zones:
            zones = [k for k in base.keys() if isinstance(base[k], h5py.Group)]
        zone_name = zones[zone_index]
        zone = base[zone_name]
        zone_type = zone.attrs.get('ZoneType', b'Unstructured').decode()
        if 'GridCoordinates' in zone:
            grid = zone['GridCoordinates']
            coords = []
            for axis in ['CoordinateX', 'CoordinateY', 'CoordinateZ']:
                if axis in grid:
                    coords.append(np.array(grid[axis]))
            if len(coords) == 3 and np.all(coords[2] == 0):
                coords = coords[:2]
            points = np.column_stack(coords)
        else:
            raise ValueError("GridCoordinates not found")
        cells = []
        if zone_type == 'Structured':
            if 'ZoneType' in zone.attrs:
                zdim = np.array(zone[' data ']) if ' data ' in zone else None
            if len(coords) == 2:
                nx, ny = coords[0].shape[0], coords[1].shape[0]
                mesh = StructuredMesh(nx, ny)
                mesh.points = points
                mesh._compute_geometry()
                mesh._setup_structured_boundaries()
                return mesh
            elif len(coords) == 3:
                nx, ny, nz = coords[0].shape[0], coords[1].shape[0], coords[2].shape[0]
                mesh = StructuredMesh(nx, ny, nz)
                mesh.points = points
                mesh._compute_geometry()
                return mesh
        else:
            if 'Elements' in zone:
                for elem_name in zone['Elements'].keys():
                    elem_group = zone['Elements'][elem_name]
                    if 'ElementConnectivity' in elem_group:
                        conn = np.array(elem_group['ElementConnectivity'])
                        if 'ElementStartOffset' in elem_group:
                            offsets = np.array(elem_group['ElementStartOffset'])
                            for i in range(len(offsets) - 1):
                                cells.append(conn[offsets[i]:offsets[i+1]].tolist())
                        else:
                            elem_size = elem_group.attrs.get('ElementSize', 0)
                            if elem_size > 0:
                                n_elems = len(conn) // elem_size
                                for i in range(n_elems):
                                    cells.append(conn[i*elem_size:(i+1)*elem_size].tolist())
        mesh = UnstructuredMesh(points, cells, mesh_type='cgns_imported')
        if 'ZoneBC' in zone:
            zb = zone['ZoneBC']
            for bc_name in zb.keys():
                bc = zb[bc_name]
                if 'GridLocation' in bc.attrs:
                    location = bc.attrs['GridLocation'].decode()
                    if location == 'FaceCenter':
                        if 'PointList' in bc:
                            face_ids = np.array(bc['PointList']).flatten() - 1
                            mesh.boundary_faces[bc_name] = face_ids.astype(np.int64)
        return mesh

def write_cgns(mesh, filename, base_name='Base', zone_name='Zone'):
    try:
        import h5py
    except ImportError:
        raise ImportError("h5py is required to write CGNS files")
    with h5py.File(filename, 'w') as f:
        base = f.create_group(base_name)
        base.attrs['CellDimension'] = np.int32(mesh.ndim)
        base.attrs['PhysicalDimension'] = np.int32(mesh.ndim)
        zone = base.create_group(zone_name)
        zone_type = 'Structured' if mesh.mesh_type == 'structured' else 'Unstructured'
        zone.attrs['ZoneType'] = np.string_(zone_type)
        if mesh.mesh_type == 'structured':
            if mesh.ndim == 2:
                zone_dims = np.array([mesh.nx, mesh.ny, 0, mesh.nx-1, mesh.ny-1, 0], dtype=np.int32)
            else:
                zone_dims = np.array([mesh.nx, mesh.ny, mesh.nz, mesh.nx-1, mesh.ny-1, mesh.nz-1], dtype=np.int32)
            zone.create_dataset(' data ', data=zone_dims)
        grid = zone.create_group('GridCoordinates')
        for i, axis in enumerate(['CoordinateX', 'CoordinateY', 'CoordinateZ']):
            if i < mesh.ndim:
                grid.create_dataset(axis, data=mesh.points[:, i])
            else:
                grid.create_dataset(axis, data=np.zeros(mesh.n_nodes))
        if mesh.mesh_type != 'structured':
            elems = zone.create_group('Elements')
            elem_counts = {}
            for cell in mesh.cells:
                n = len(cell)
                elem_counts[n] = elem_counts.get(n, 0) + 1
            elem_id = 0
            for n_nodes, count in elem_counts.items():
                elem_name = f'Elem_{elem_id}'
                if n_nodes == 2:
                    etype = 'BAR_2'
                elif n_nodes == 3:
                    etype = 'TRI_3'
                elif n_nodes == 4 and mesh.ndim == 2:
                    etype = 'QUAD_4'
                elif n_nodes == 4 and mesh.ndim == 3:
                    etype = 'TETRA_4'
                elif n_nodes == 8:
                    etype = 'HEXA_8'
                else:
                    etype = f'NGON_n{n_nodes}'
                eg = elems.create_group(elem_name)
                eg.attrs['ElementType'] = np.string_(etype)
                eg.attrs['ElementSize'] = np.int32(n_nodes)
                conn = []
                for cell in mesh.cells:
                    if len(cell) == n_nodes:
                        conn.extend(cell)
                eg.create_dataset('ElementConnectivity', data=np.array(conn, dtype=np.int32) + 1)
                offsets = np.arange(0, len(conn) + 1, n_nodes, dtype=np.int32)
                eg.create_dataset('ElementStartOffset', data=offsets)
                elem_id += 1
        if mesh.boundary_faces:
            zb = zone.create_group('ZoneBC')
            for name, fids in mesh.boundary_faces.items():
                bc = zb.create_group(name)
                bc.attrs['GridLocation'] = np.string_('FaceCenter')
                bc.attrs['BCType'] = np.string_('BCGeneral')
                bc.create_dataset('PointList', data=np.array(fids, dtype=np.int32) + 1)
