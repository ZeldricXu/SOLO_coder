use std::collections::HashMap;
use nalgebra::Point3;
use crate::element::{Element, Face};

#[derive(Debug, Clone)]
pub struct CsrTopology {
    pub cell_neighbors_indptr: Vec<usize>,
    pub cell_neighbors_indices: Vec<usize>,
    pub cell_faces_indptr: Vec<usize>,
    pub cell_faces_indices: Vec<usize>,
    pub face_owner: Vec<Option<usize>>,
    pub face_neighbor: Vec<Option<usize>>,
    pub node_cells_indptr: Vec<usize>,
    pub node_cells_indices: Vec<usize>,
}

impl CsrTopology {
    pub fn new() -> Self {
        CsrTopology {
            cell_neighbors_indptr: Vec::new(),
            cell_neighbors_indices: Vec::new(),
            cell_faces_indptr: Vec::new(),
            cell_faces_indices: Vec::new(),
            face_owner: Vec::new(),
            face_neighbor: Vec::new(),
            node_cells_indptr: Vec::new(),
            node_cells_indices: Vec::new(),
        }
    }

    pub fn build(&mut self, elements: &[Element], faces: &[Face]) {
        let n_cells = elements.len();
        let n_faces = faces.len();
        let n_nodes = elements.iter()
            .map(|e| e.node_indices.iter().copied().max().unwrap_or(0))
            .max()
            .unwrap_or(0) + 1;

        let mut cell_neighbors: Vec<Vec<usize>> = vec![Vec::new(); n_cells];
        let mut cell_faces: Vec<Vec<usize>> = vec![Vec::new(); n_cells];
        self.face_owner.resize(n_faces, None);
        self.face_neighbor.resize(n_faces, None);
        let mut node_cells: Vec<Vec<usize>> = vec![Vec::new(); n_nodes];

        for (cell_idx, element) in elements.iter().enumerate() {
            for &node_idx in &element.node_indices {
                node_cells[node_idx].push(cell_idx);
            }
        }

        for (face_idx, face) in faces.iter().enumerate() {
            if let Some(owner) = face.owner_cell {
                self.face_owner[face_idx] = Some(owner);
                cell_faces[owner].push(face_idx);
            }
            if let Some(neighbor) = face.neighbor_cell {
                self.face_neighbor[face_idx] = Some(neighbor);
                cell_faces[neighbor].push(face_idx);

                if let Some(owner) = face.owner_cell {
                    cell_neighbors[owner].push(neighbor);
                    cell_neighbors[neighbor].push(owner);
                }
            }
        }

        Self::build_csr_from_vecs(&cell_neighbors, &mut self.cell_neighbors_indptr, &mut self.cell_neighbors_indices);
        Self::build_csr_from_vecs(&cell_faces, &mut self.cell_faces_indptr, &mut self.cell_faces_indices);
        Self::build_csr_from_vecs(&node_cells, &mut self.node_cells_indptr, &mut self.node_cells_indices);
    }

    fn build_csr_from_vecs(
        vec_data: &[Vec<usize>],
        indptr: &mut Vec<usize>,
        indices: &mut Vec<usize>,
    ) {
        let n = vec_data.len();
        indptr.resize(n + 1, 0);
        indices.clear();

        let mut total = 0;
        for (i, row) in vec_data.iter().enumerate() {
            total += row.len();
            indptr[i + 1] = total;
        }

        indices.reserve(total);
        for row in vec_data {
            let mut sorted = row.clone();
            sorted.sort();
            sorted.dedup();
            indices.extend_from_slice(&sorted);
        }

        indptr[0] = 0;
        let mut cumsum = 0;
        for (i, row) in vec_data.iter().enumerate() {
            let mut sorted = row.clone();
            sorted.sort();
            sorted.dedup();
            cumsum += sorted.len();
            indptr[i + 1] = cumsum;
        }

        indices.clear();
        for row in vec_data {
            let mut sorted = row.clone();
            sorted.sort();
            sorted.dedup();
            indices.extend_from_slice(&sorted);
        }
    }

    pub fn get_cell_neighbors(&self, cell_idx: usize) -> &[usize] {
        let start = self.cell_neighbors_indptr[cell_idx];
        let end = self.cell_neighbors_indptr[cell_idx + 1];
        &self.cell_neighbors_indices[start..end]
    }

    pub fn get_cell_faces(&self, cell_idx: usize) -> &[usize] {
        let start = self.cell_faces_indptr[cell_idx];
        let end = self.cell_faces_indptr[cell_idx + 1];
        &self.cell_faces_indices[start..end]
    }

    pub fn get_node_cells(&self, node_idx: usize) -> &[usize] {
        let start = self.node_cells_indptr[node_idx];
        let end = self.node_cells_indptr[node_idx + 1];
        &self.node_cells_indices[start..end]
    }

    pub fn num_cell_neighbors(&self, cell_idx: usize) -> usize {
        self.cell_neighbors_indptr[cell_idx + 1] - self.cell_neighbors_indptr[cell_idx]
    }
}

#[derive(Debug, Clone)]
pub struct Topology {
    pub cell_neighbors: Vec<Vec<usize>>,
    pub cell_faces: Vec<Vec<usize>>,
    pub face_owner: Vec<Option<usize>>,
    pub face_neighbor: Vec<Option<usize>>,
    pub node_cells: Vec<Vec<usize>>,
    pub csr: CsrTopology,
}

impl Topology {
    pub fn new() -> Self {
        Topology {
            cell_neighbors: Vec::new(),
            cell_faces: Vec::new(),
            face_owner: Vec::new(),
            face_neighbor: Vec::new(),
            node_cells: Vec::new(),
            csr: CsrTopology::new(),
        }
    }

    pub fn build(&mut self, elements: &[Element], faces: &[Face]) {
        let n_cells = elements.len();
        let n_faces = faces.len();
        let n_nodes = elements.iter()
            .map(|e| e.node_indices.iter().copied().max().unwrap_or(0))
            .max()
            .unwrap_or(0) + 1;

        self.cell_neighbors.resize(n_cells, Vec::new());
        self.cell_faces.resize(n_cells, Vec::new());
        self.face_owner.resize(n_faces, None);
        self.face_neighbor.resize(n_faces, None);
        self.node_cells.resize(n_nodes, Vec::new());

        for (cell_idx, element) in elements.iter().enumerate() {
            for &node_idx in &element.node_indices {
                self.node_cells[node_idx].push(cell_idx);
            }
        }

        for (face_idx, face) in faces.iter().enumerate() {
            if let Some(owner) = face.owner_cell {
                self.face_owner[face_idx] = Some(owner);
                self.cell_faces[owner].push(face_idx);
            }
            if let Some(neighbor) = face.neighbor_cell {
                self.face_neighbor[face_idx] = Some(neighbor);
                self.cell_faces[neighbor].push(face_idx);

                if let Some(owner) = face.owner_cell {
                    self.cell_neighbors[owner].push(neighbor);
                    self.cell_neighbors[neighbor].push(owner);
                }
            }
        }

        self.csr.build(elements, faces);
    }
}

fn compute_face_hash(sorted_nodes: &[usize]) -> u64 {
    let mut hash: u64 = 14695981039346656037;
    for &node in sorted_nodes {
        hash ^= node as u64;
        hash = hash.wrapping_mul(1099511628211);
    }
    hash
}

pub fn build_faces(elements: &mut [Element], nodes: &[Point3<f64>]) -> Vec<Face> {
    let mut face_map: HashMap<u64, Vec<(Vec<usize>, usize)>> = HashMap::new();
    let mut faces: Vec<Face> = Vec::new();

    for (cell_idx, element) in elements.iter_mut().enumerate() {
        let num_faces = element.element_type.num_faces();

        for face_local_idx in 0..num_faces {
            let mut face_nodes = element.get_local_face_nodes(face_local_idx);
            face_nodes.sort();

            let hash = compute_face_hash(&face_nodes);

            let mut found = false;
            if let Some(entries) = face_map.get_mut(&hash) {
                for (stored_nodes, stored_idx) in entries.iter() {
                    if *stored_nodes == face_nodes {
                        faces[*stored_idx].neighbor_cell = Some(cell_idx);
                        element.face_indices.push(*stored_idx);
                        found = true;
                        break;
                    }
                }
            }

            if !found {
                let face_nodes_orig = element.get_local_face_nodes(face_local_idx);
                let mut face = Face::new(face_nodes_orig.clone());
                face.owner_cell = Some(cell_idx);
                face.compute_geometry(nodes);

                let face_idx = faces.len();
                faces.push(face);
                element.face_indices.push(face_idx);

                face_map.entry(hash).or_insert_with(Vec::new).push((face_nodes, face_idx));
            }
        }
    }

    for face in &mut faces {
        face.is_boundary = face.neighbor_cell.is_none();
    }

    faces
}
