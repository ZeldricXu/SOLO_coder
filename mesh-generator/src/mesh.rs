use std::path::Path;
use nalgebra::Point3;
use rayon::prelude::*;
use crate::element::{Element, Face};
use crate::parser::GmshParser;
use crate::cgns_parser::{CgnsParser, CgnsError, PolyMeshParser, PolyMeshError};
use crate::topology::{Topology, build_faces};
use crate::csr_matrix::CsrMatrix;

#[derive(Debug, Clone)]
pub struct Mesh {
    pub nodes: Vec<Point3<f64>>,
    pub elements: Vec<Element>,
    pub faces: Vec<Face>,
    pub topology: Topology,
    pub is_2d: bool,
}

impl Mesh {
    pub fn new() -> Self {
        Mesh {
            nodes: Vec::new(),
            elements: Vec::new(),
            faces: Vec::new(),
            topology: Topology::new(),
            is_2d: true,
        }
    }

    pub fn from_gmsh<P: AsRef<Path>>(path: P) -> Result<Self, Box<dyn std::error::Error>> {
        let (nodes, mut elements) = GmshParser::parse(path)?;
        Self::from_nodes_elements(nodes, elements)
    }

    pub fn from_cgns<P: AsRef<Path>>(path: P) -> Result<Self, CgnsError> {
        CgnsParser::parse(path.as_ref())
    }

    pub fn from_polymesh<P: AsRef<Path>>(path: P) -> Result<Self, PolyMeshError> {
        PolyMeshParser::parse(path.as_ref())
    }

    pub fn from_nodes_elements(
        nodes: Vec<Point3<f64>>,
        mut elements: Vec<Element>,
    ) -> Result<Self, Box<dyn std::error::Error>> {
        let is_2d = elements.iter()
            .all(|e| e.element_type.is_2d());

        elements.par_iter_mut().for_each(|elem| {
            elem.compute_centroid(&nodes);
            elem.compute_volume(&nodes);
        });

        let faces = build_faces(&mut elements, &nodes);

        let mut topology = Topology::new();
        topology.build(&elements, &faces);

        Ok(Mesh {
            nodes,
            elements,
            faces,
            topology,
            is_2d,
        })
    }

    pub fn from_nodes_elements_faces(
        nodes: Vec<Point3<f64>>,
        mut elements: Vec<Element>,
        _internal_faces: Vec<Vec<usize>>,
        _boundary_faces: Vec<Vec<usize>>,
    ) -> Result<Self, Box<dyn std::error::Error>> {
        let is_2d = elements.iter()
            .all(|e| e.element_type.is_2d());

        elements.par_iter_mut().for_each(|elem| {
            elem.compute_centroid(&nodes);
            elem.compute_volume(&nodes);
        });

        let faces = build_faces(&mut elements, &nodes);

        let mut topology = Topology::new();
        topology.build(&elements, &faces);

        Ok(Mesh {
            nodes,
            elements,
            faces,
            topology,
            is_2d,
        })
    }

    pub fn num_cells(&self) -> usize {
        self.elements.len()
    }

    pub fn num_faces(&self) -> usize {
        self.faces.len()
    }

    pub fn num_nodes(&self) -> usize {
        self.nodes.len()
    }

    pub fn num_boundary_faces(&self) -> usize {
        self.faces.iter().filter(|f| f.is_boundary).count()
    }

    pub fn get_boundary_faces(&self) -> Vec<&Face> {
        self.faces.iter().filter(|f| f.is_boundary).collect()
    }

    pub fn get_cell_neighbors(&self, cell_idx: usize) -> &[usize] {
        if !self.topology.csr.cell_neighbors_indices.is_empty() {
            self.topology.csr.get_cell_neighbors(cell_idx)
        } else {
            &self.topology.cell_neighbors[cell_idx]
        }
    }

    pub fn get_cell_faces(&self, cell_idx: usize) -> &[usize] {
        if !self.topology.csr.cell_faces_indices.is_empty() {
            self.topology.csr.get_cell_faces(cell_idx)
        } else {
            &self.topology.cell_faces[cell_idx]
        }
    }

    pub fn build_adjacency_matrix(&self) -> CsrMatrix {
        let n = self.num_cells();
        let mut rows = Vec::new();
        let mut cols = Vec::new();
        let mut values = Vec::new();

        for (cell_idx, neighbors) in self.topology.cell_neighbors.iter().enumerate() {
            rows.push(cell_idx);
            cols.push(cell_idx);
            values.push(1.0);

            for &neighbor in neighbors {
                rows.push(cell_idx);
                cols.push(neighbor);
                values.push(1.0);
            }
        }

        CsrMatrix::from_triplets(n, n, &rows, &cols, &values)
    }

    pub fn cell_centers(&self) -> Vec<Point3<f64>> {
        self.elements.iter().map(|e| e.centroid).collect()
    }

    pub fn cell_volumes(&self) -> Vec<f64> {
        self.elements.iter().map(|e| e.volume).collect()
    }
}

impl Default for Mesh {
    fn default() -> Self {
        Self::new()
    }
}
