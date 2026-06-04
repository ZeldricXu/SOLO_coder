use nalgebra::{Point3, Vector3};
use std::fmt;

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum ElementType {
    Triangle,
    Quadrilateral,
    Tetrahedron,
    Hexahedron,
}

impl ElementType {
    pub fn num_nodes(&self) -> usize {
        match self {
            ElementType::Triangle => 3,
            ElementType::Quadrilateral => 4,
            ElementType::Tetrahedron => 4,
            ElementType::Hexahedron => 8,
        }
    }

    pub fn num_faces(&self) -> usize {
        match self {
            ElementType::Triangle => 3,
            ElementType::Quadrilateral => 4,
            ElementType::Tetrahedron => 4,
            ElementType::Hexahedron => 6,
        }
    }

    pub fn is_2d(&self) -> bool {
        matches!(self, ElementType::Triangle | ElementType::Quadrilateral)
    }

    pub fn is_3d(&self) -> bool {
        !self.is_2d()
    }
}

impl fmt::Display for ElementType {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        match self {
            ElementType::Triangle => write!(f, "Triangle"),
            ElementType::Quadrilateral => write!(f, "Quadrilateral"),
            ElementType::Tetrahedron => write!(f, "Tetrahedron"),
            ElementType::Hexahedron => write!(f, "Hexahedron"),
        }
    }
}

#[derive(Debug, Clone)]
pub struct Face {
    pub node_indices: Vec<usize>,
    pub owner_cell: Option<usize>,
    pub neighbor_cell: Option<usize>,
    pub is_boundary: bool,
    pub normal: Vector3<f64>,
    pub area: f64,
    pub centroid: Point3<f64>,
}

impl Face {
    pub fn new(node_indices: Vec<usize>) -> Self {
        Face {
            node_indices,
            owner_cell: None,
            neighbor_cell: None,
            is_boundary: false,
            normal: Vector3::zeros(),
            area: 0.0,
            centroid: Point3::origin(),
        }
    }

    pub fn compute_geometry(&mut self, nodes: &[Point3<f64>]) {
        let n = self.node_indices.len();
        if n < 3 {
            return;
        }

        let mut center = Point3::origin();
        for &idx in &self.node_indices {
            center += nodes[idx].coords / n as f64;
        }
        self.centroid = center;

        self.area = 0.0;
        self.normal = Vector3::zeros();

        for i in 0..n {
            let p1 = nodes[self.node_indices[i]];
            let p2 = nodes[self.node_indices[(i + 1) % n]];

            let edge1 = p1 - center;
            let edge2 = p2 - center;
            let cross = edge1.cross(&edge2);
            let tri_area = cross.norm() * 0.5;

            self.area += tri_area;
            self.normal += cross * 0.5;
        }

        let norm = self.normal.norm();
        if norm > 1e-15 {
            self.normal /= norm;
        }
    }
}

#[derive(Debug, Clone)]
pub struct Element {
    pub element_type: ElementType,
    pub node_indices: Vec<usize>,
    pub face_indices: Vec<usize>,
    pub centroid: Point3<f64>,
    pub volume: f64,
    pub tag: i32,
}

impl Element {
    pub fn new(element_type: ElementType, node_indices: Vec<usize>, tag: i32) -> Self {
        Element {
            element_type,
            node_indices,
            face_indices: Vec::new(),
            centroid: Point3::origin(),
            volume: 0.0,
            tag,
        }
    }

    pub fn compute_centroid(&mut self, nodes: &[Point3<f64>]) {
        let n = self.node_indices.len();
        let mut center = Point3::origin();
        for &idx in &self.node_indices {
            center += nodes[idx].coords / n as f64;
        }
        self.centroid = center;
    }

    pub fn compute_volume(&mut self, nodes: &[Point3<f64>]) {
        match self.element_type {
            ElementType::Triangle => {
                let p0 = nodes[self.node_indices[0]];
                let p1 = nodes[self.node_indices[1]];
                let p2 = nodes[self.node_indices[2]];
                let v1 = p1 - p0;
                let v2 = p2 - p0;
                self.volume = v1.cross(&v2).norm() * 0.5;
            }
            ElementType::Quadrilateral => {
                let p0 = nodes[self.node_indices[0]];
                let p1 = nodes[self.node_indices[1]];
                let p2 = nodes[self.node_indices[2]];
                let p3 = nodes[self.node_indices[3]];
                let v1 = p1 - p0;
                let v2 = p2 - p0;
                let v3 = p3 - p0;
                let cross1 = v1.cross(&v2);
                let cross2 = v2.cross(&v3);
                self.volume = (cross1.norm() + cross2.norm()) * 0.5;
            }
            ElementType::Tetrahedron => {
                let p0 = nodes[self.node_indices[0]];
                let p1 = nodes[self.node_indices[1]];
                let p2 = nodes[self.node_indices[2]];
                let p3 = nodes[self.node_indices[3]];
                let v1 = p1 - p0;
                let v2 = p2 - p0;
                let v3 = p3 - p0;
                self.volume = v1.dot(&v2.cross(&v3)).abs() / 6.0;
            }
            ElementType::Hexahedron => {
                let p0 = nodes[self.node_indices[0]];
                let p1 = nodes[self.node_indices[1]];
                let p2 = nodes[self.node_indices[2]];
                let p3 = nodes[self.node_indices[3]];
                let p4 = nodes[self.node_indices[4]];
                let p5 = nodes[self.node_indices[5]];
                let p6 = nodes[self.node_indices[6]];
                let p7 = nodes[self.node_indices[7]];

                let dx = (p1 - p0).norm();
                let dy = (p3 - p0).norm();
                let dz = (p4 - p0).norm();
                self.volume = dx * dy * dz;
            }
        }
    }

    pub fn get_local_face_nodes(&self, face_idx: usize) -> Vec<usize> {
        match self.element_type {
            ElementType::Triangle => match face_idx {
                0 => vec![self.node_indices[0], self.node_indices[1]],
                1 => vec![self.node_indices[1], self.node_indices[2]],
                2 => vec![self.node_indices[2], self.node_indices[0]],
                _ => Vec::new(),
            },
            ElementType::Quadrilateral => match face_idx {
                0 => vec![self.node_indices[0], self.node_indices[1]],
                1 => vec![self.node_indices[1], self.node_indices[2]],
                2 => vec![self.node_indices[2], self.node_indices[3]],
                3 => vec![self.node_indices[3], self.node_indices[0]],
                _ => Vec::new(),
            },
            ElementType::Tetrahedron => match face_idx {
                0 => vec![self.node_indices[0], self.node_indices[1], self.node_indices[2]],
                1 => vec![self.node_indices[0], self.node_indices[1], self.node_indices[3]],
                2 => vec![self.node_indices[1], self.node_indices[2], self.node_indices[3]],
                3 => vec![self.node_indices[0], self.node_indices[2], self.node_indices[3]],
                _ => Vec::new(),
            },
            ElementType::Hexahedron => match face_idx {
                0 => vec![self.node_indices[0], self.node_indices[1], self.node_indices[2], self.node_indices[3]],
                1 => vec![self.node_indices[4], self.node_indices[5], self.node_indices[6], self.node_indices[7]],
                2 => vec![self.node_indices[0], self.node_indices[1], self.node_indices[5], self.node_indices[4]],
                3 => vec![self.node_indices[2], self.node_indices[3], self.node_indices[7], self.node_indices[6]],
                4 => vec![self.node_indices[0], self.node_indices[3], self.node_indices[7], self.node_indices[4]],
                5 => vec![self.node_indices[1], self.node_indices[2], self.node_indices[6], self.node_indices[5]],
                _ => Vec::new(),
            },
        }
    }
}
