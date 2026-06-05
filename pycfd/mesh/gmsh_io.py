import numpy as np
import struct
from .base import UnstructuredMesh

def read_gmsh(filename):
    try:
        import meshio
        return _read_with_meshio(filename)
    except ImportError:
        return _read_gmsh_native(filename)

def _read_with_meshio(filename):
    import meshio
    m = meshio.read(filename)
    points = m.points
    cells = []
    for cell_block in m.cells:
        cells.extend(cell_block.data.tolist())
    mesh = UnstructuredMesh(points, cells, mesh_type='gmsh_imported')
    if m.cell_data:
        pass
    if m.field_data:
        for name, data in m.field_data.items():
            if isinstance(data, np.ndarray) and len(data) == 2:
                tag = int(data[0])
                dim = int(data[1])
                if dim == mesh.ndim - 1:
                    _add_gmsh_boundary(mesh, m, name, tag)
    return mesh

def _add_gmsh_boundary(mesh, meshio_mesh, name, tag):
    boundary_fids = []
    face_to_fid = {}
    for fid, face in enumerate(mesh.faces):
        if mesh.neighbour[fid] < 0:
            key = tuple(sorted(face.tolist()))
            face_to_fid[key] = fid
    if hasattr(meshio_mesh, 'cell_sets') and name in meshio_mesh.cell_sets:
        cell_ids = meshio_mesh.cell_sets[name]
        for cid in cell_ids:
            if isinstance(cid, tuple):
                block_idx, local_idx = cid
                if block_idx < len(meshio_mesh.cells):
                    cell_type = meshio_mesh.cells[block_idx].type
                    if cell_type in ['line', 'line2', 'triangle', 'tri3', 'quad', 'quad4']:
                        cell_data = meshio_mesh.cells[block_idx].data[local_idx]
                        key = tuple(sorted(cell_data.tolist()))
                        if key in face_to_fid:
                            boundary_fids.append(face_to_fid[key])
    if boundary_fids:
        mesh.boundary_faces[name] = np.array(boundary_fids, dtype=np.int64)

def _read_gmsh_native(filename):
    with open(filename, 'r') as f:
        lines = f.readlines()
    mode = None
    points = []
    cells = []
    physical_names = {}
    i = 0
    while i < len(lines):
        line = lines[i].strip()
        if line == '$MeshFormat':
            mode = 'format'
        elif line == '$EndMeshFormat':
            mode = None
        elif line == '$PhysicalNames':
            mode = 'physical'
        elif line == '$EndPhysicalNames':
            mode = None
        elif line == '$Nodes':
            mode = 'nodes'
        elif line == '$EndNodes':
            mode = None
        elif line == '$Elements':
            mode = 'elements'
        elif line == '$EndElements':
            mode = None
        elif mode == 'physical' and line and not line.startswith('$'):
            if line.isdigit():
                n_names = int(line)
            else:
                parts = line.split()
                if len(parts) >= 3:
                    dim = int(parts[0])
                    tag = int(parts[1])
                    name = ' '.join(parts[2:]).strip('"')
                    physical_names[tag] = (dim, name)
        elif mode == 'nodes' and line and not line.startswith('$'):
            if line.isdigit():
                n_nodes = int(line)
            else:
                coords = list(map(float, line.split()))
                if len(coords) >= 3:
                    points.append([coords[1], coords[2], coords[3]])
        elif mode == 'elements' and line and not line.startswith('$'):
            if line.isdigit():
                n_elems = int(line)
            else:
                parts = list(map(int, line.split()))
                elem_type = parts[1]
                n_tags = parts[2]
                tags = parts[3:3+n_tags]
                nodes = parts[3+n_tags:]
                if elem_type in [2, 3]:
                    cells.append(nodes)
                elif elem_type in [4, 5, 6, 7]:
                    cells.append(nodes)
        i += 1
    if not points:
        raise ValueError("No nodes found in Gmsh file")
    points = np.array(points, dtype=np.float64)
    if np.all(points[:, 2] == 0):
        points = points[:, :2]
    mesh = UnstructuredMesh(points, cells, mesh_type='gmsh_imported')
    return mesh

def write_gmsh(mesh, filename):
    try:
        import meshio
        _write_with_meshio(mesh, filename)
    except ImportError:
        _write_gmsh_native(mesh, filename)

def _write_with_meshio(mesh, filename):
    import meshio
    points = mesh.points
    if mesh.ndim == 2:
        points = np.hstack([points, np.zeros((len(points), 1))])
    cell_blocks = []
    cell_type = None
    if all(len(c) == 3 for c in mesh.cells):
        cell_type = 'triangle'
    elif all(len(c) == 4 and mesh.ndim == 2):
        cell_type = 'quad'
    elif all(len(c) == 4 and mesh.ndim == 3):
        cell_type = 'tetra'
    elif all(len(c) == 8):
        cell_type = 'hexahedron'
    if cell_type:
        cells = np.array(mesh.cells, dtype=np.int64)
        cell_blocks.append(meshio.CellBlock(cell_type, cells))
    m = meshio.Mesh(points=points, cells=cell_blocks)
    meshio.write(filename, m)

def _write_gmsh_native(mesh, filename):
    with open(filename, 'w') as f:
        f.write('$MeshFormat\n')
        f.write('2.2 0 8\n')
        f.write('$EndMeshFormat\n')
        f.write('$Nodes\n')
        f.write(f'{mesh.n_nodes}\n')
        for i, p in enumerate(mesh.points):
            if mesh.ndim == 2:
                f.write(f'{i+1} {p[0]:.15g} {p[1]:.15g} 0.0\n')
            else:
                f.write(f'{i+1} {p[0]:.15g} {p[1]:.15g} {p[2]:.15g}\n')
        f.write('$EndNodes\n')
        f.write('$Elements\n')
        f.write(f'{mesh.n_cells + sum(len(v) for v in mesh.boundary_faces.values())}\n')
        elem_id = 1
        if mesh.boundary_faces:
            for name, fids in mesh.boundary_faces.items():
                for fid in fids:
                    face = mesh.faces[fid]
                    if len(face) == 2:
                        f.write(f'{elem_id} 1 2 1 1 {face[0]+1} {face[1]+1}\n')
                    elif len(face) == 3:
                        f.write(f'{elem_id} 2 2 1 1 {face[0]+1} {face[1]+1} {face[2]+1}\n')
                    elem_id += 1
        for cell in mesh.cells:
            if len(cell) == 3:
                f.write(f'{elem_id} 2 2 2 2 {cell[0]+1} {cell[1]+1} {cell[2]+1}\n')
            elif len(cell) == 4 and mesh.ndim == 2:
                f.write(f'{elem_id} 3 2 2 2 {cell[0]+1} {cell[1]+1} {cell[2]+1} {cell[3]+1}\n')
            elif len(cell) == 4 and mesh.ndim == 3:
                f.write(f'{elem_id} 4 2 2 2 {cell[0]+1} {cell[1]+1} {cell[2]+1} {cell[3]+1}\n')
            elem_id += 1
        f.write('$EndElements\n')

def import_gmsh_mesh(geo_file=None, msh_file=None, characteristic_length=0.1, 
                     dimension=2, algorithm='frontal'):
    if geo_file is None and msh_file is None:
        raise ValueError("Either geo_file or msh_file must be provided")
    if msh_file is None:
        try:
            import pygmsh
            msh_file = _generate_with_pygmsh(geo_file, characteristic_length, dimension)
        except ImportError:
            import subprocess
            msh_file = geo_file.replace('.geo', '.msh')
            subprocess.run(['gmsh', geo_file, '-2', '-o', msh_file], check=True)
    return read_gmsh(msh_file)

def _generate_with_pygmsh(geo_file, characteristic_length, dimension):
    import pygmsh
    with pygmsh.geo.Geometry() as geom:
        with open(geo_file, 'r') as f:
            geo_code = f.read()
        exec(geo_code, {'geom': geom, 'cl': characteristic_length})
        mesh = geom.generate_mesh(dim=dimension)
    msh_file = geo_file.replace('.geo', '.msh')
    mesh.write(msh_file)
    return msh_file
