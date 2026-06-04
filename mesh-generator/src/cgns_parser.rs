#[cfg(feature = "cgns")]
use hdf5::{File, Group, Dataset};
use nalgebra::Point3;
use std::path::Path;
use crate::element::{Element, ElementType};
use crate::mesh::Mesh;

#[derive(Debug, thiserror::Error)]
pub enum CgnsError {
    #[error("HDF5 error: {0}")]
    #[cfg(feature = "cgns")]
    Hdf5(#[from] hdf5::Error),
    
    #[error("CGNS format error: {0}")]
    Format(String),
    
    #[error("IO error: {0}")]
    Io(#[from] std::io::Error),
}

#[cfg(feature = "cgns")]
pub struct CgnsParser;

#[cfg(feature = "cgns")]
impl CgnsParser {
    pub fn parse(path: &Path) -> Result<Mesh, CgnsError> {
        let file = File::open(path)?;
        let root = file.group("/")?;
        
        let base = Self::find_base(&root)?;
        let zone = Self::find_zone(&base)?;
        
        let nodes = Self::read_grid_coordinates(&zone)?;
        let elements = Self::read_elements(&zone, &nodes)?;
        
        Ok(Mesh::from_nodes_and_elements(nodes, elements))
    }
    
    fn find_base(root: &Group) -> Result<Group, CgnsError> {
        for member in root.member_names()? {
            if member.starts_with("CGNSBase_") || member.starts_with("Base_") {
                return Ok(root.group(&member)?);
            }
        }
        Err(CgnsError::Format("No CGNS base found".to_string()))
    }
    
    fn find_zone(base: &Group) -> Result<Group, CgnsError> {
        for member in base.member_names()? {
            if member.starts_with("Zone_") {
                return Ok(base.group(&member)?);
            }
        }
        Err(CgnsError::Format("No Zone found in CGNS base".to_string()))
    }
    
    fn read_grid_coordinates(zone: &Group) -> Result<Vec<Point3<f64>>, CgnsError> {
        let grid_coords = zone.group("GridCoordinates")?;
        
        let x_ds = grid_coords.dataset("CoordinateX")?;
        let y_ds = grid_coords.dataset("CoordinateY")?;
        let z_ds = grid_coords.dataset("CoordinateZ")?;
        
        let x: Vec<f64> = x_ds.read_raw()?;
        let y: Vec<f64> = y_ds.read_raw()?;
        let z: Vec<f64> = z_ds.read_raw()?;
        
        let mut nodes = Vec::with_capacity(x.len());
        for i in 0..x.len() {
            nodes.push(Point3::new(x[i], y[i], z[i]));
        }
        
        Ok(nodes)
    }
    
    fn read_elements(zone: &Group, nodes: &[Point3<f64>]) -> Result<Vec<Element>, CgnsError> {
        let mut elements = Vec::new();
        
        for member in zone.member_names()? {
            if member.starts_with("Elements_") {
                let elem_group = zone.group(&member)?;
                let elem_type_ds = elem_group.dataset("Element_type")?;
                let elem_type: i32 = elem_type_ds.read_scalar()?;
                
                let conn_ds = elem_group.dataset("ElementConnectivity")?;
                let connectivity: Vec<i32> = conn_ds.read_raw()?;
                
                let element_type = match elem_type {
                    5 => ElementType::Triangle,
                    7 => ElementType::Quadrilateral,
                    10 => ElementType::Tetrahedron,
                    17 => ElementType::Hexahedron,
                    _ => continue,
                };
                
                let nodes_per_elem = match element_type {
                    ElementType::Triangle => 3,
                    ElementType::Quadrilateral => 4,
                    ElementType::Tetrahedron => 4,
                    ElementType::Hexahedron => 8,
                };
                
                for i in (0..connectivity.len()).step_by(nodes_per_elem) {
                    let mut node_indices = Vec::with_capacity(nodes_per_elem);
                    for j in 0..nodes_per_elem {
                        node_indices.push((connectivity[i + j] - 1) as usize);
                    }
                    
                    let mut elem = Element::new(element_type, node_indices, 0);
                    elem.compute_centroid(nodes);
                    elem.compute_volume(nodes);
                    elements.push(elem);
                }
            }
        }
        
        Ok(elements)
    }
}

#[cfg(not(feature = "cgns"))]
pub struct CgnsParser;

#[cfg(not(feature = "cgns"))]
impl CgnsParser {
    pub fn parse(_path: &Path) -> Result<Mesh, CgnsError> {
        Err(CgnsError::Format(
            "CGNS support not enabled. Compile with --features cgns".to_string()
        ))
    }
}

#[derive(Debug, thiserror::Error)]
pub enum PolyMeshError {
    #[error("IO error: {0}")]
    Io(#[from] std::io::Error),
    
    #[error("Parse error: {0}")]
    Parse(String),
}

pub struct PolyMeshParser;

impl PolyMeshParser {
    pub fn parse(path: &Path) -> Result<Mesh, PolyMeshError> {
        use std::fs::read_to_string;
        
        let points = Self::read_points(&path.join("points"))?;
        let faces_data = Self::read_faces(&path.join("faces"))?;
        let owner = Self::read_owner_neighbour(&path.join("owner"))?;
        let neighbour = Self::read_owner_neighbour(&path.join("neighbour"))?;
        
        let (internal_faces, boundary_faces) = Self::classify_faces(&faces_data, &owner, &neighbour);
        let elements = Self::build_elements(&points, &internal_faces, &boundary_faces, &owner, &neighbour)?;
        
        let mesh = Mesh::from_nodes_elements_faces(points, elements, internal_faces, boundary_faces)
            .map_err(|e| PolyMeshError::Parse(e.to_string()))?;
        Ok(mesh)
    }
    
    fn read_points(path: &Path) -> Result<Vec<Point3<f64>>, PolyMeshError> {
        let content = std::fs::read_to_string(path)?;
        let lines: Vec<&str> = content.lines().collect();
        
        let n_points: usize = lines[0].parse()
            .map_err(|e| PolyMeshError::Parse(format!("Invalid point count: {}", e)))?;
        
        let mut points = Vec::with_capacity(n_points);
        for i in 2..2 + n_points {
            let line = lines[i].trim();
            let coords: Vec<f64> = line[1..line.len()-1]
                .split_whitespace()
                .map(|s| s.parse().unwrap())
                .collect();
            points.push(Point3::new(coords[0], coords[1], coords[2]));
        }
        
        Ok(points)
    }
    
    fn read_faces(path: &Path) -> Result<Vec<Vec<usize>>, PolyMeshError> {
        let content = std::fs::read_to_string(path)?;
        let lines: Vec<&str> = content.lines().collect();
        
        let n_faces: usize = lines[0].parse()
            .map_err(|e| PolyMeshError::Parse(format!("Invalid face count: {}", e)))?;
        
        let mut faces = Vec::with_capacity(n_faces);
        for i in 2..2 + n_faces {
            let parts: Vec<&str> = lines[i].split_whitespace().collect();
            let n_nodes: usize = parts[0].parse().unwrap();
            let mut node_indices = Vec::with_capacity(n_nodes);
            for j in 1..=n_nodes {
                node_indices.push(parts[j].parse().unwrap());
            }
            faces.push(node_indices);
        }
        
        Ok(faces)
    }
    
    fn read_owner_neighbour(path: &Path) -> Result<Vec<usize>, PolyMeshError> {
        let content = std::fs::read_to_string(path)?;
        let lines: Vec<&str> = content.lines().collect();
        
        let n: usize = lines[0].parse()
            .map_err(|e| PolyMeshError::Parse(format!("Invalid count: {}", e)))?;
        
        let mut result = Vec::with_capacity(n);
        for i in 2..2 + n {
            result.push(lines[i].parse().unwrap());
        }
        
        Ok(result)
    }
    
    fn classify_faces(
        faces: &[Vec<usize>],
        owner: &[usize],
        neighbour: &[usize],
    ) -> (Vec<Vec<usize>>, Vec<Vec<usize>>) {
        let mut internal = Vec::new();
        let mut boundary = Vec::new();
        
        for (i, face) in faces.iter().enumerate() {
            if i < neighbour.len() && neighbour[i] < usize::MAX {
                internal.push(face.clone());
            } else {
                boundary.push(face.clone());
            }
        }
        
        (internal, boundary)
    }
    
    fn build_elements(
        points: &[Point3<f64>],
        _internal_faces: &[Vec<usize>],
        _boundary_faces: &[Vec<usize>],
        owner: &[usize],
        _neighbour: &[usize],
    ) -> Result<Vec<Element>, PolyMeshError> {
        use std::collections::HashMap;
        
        let mut cell_faces: HashMap<usize, Vec<usize>> = HashMap::new();
        for (face_idx, &cell_idx) in owner.iter().enumerate() {
            cell_faces.entry(cell_idx).or_insert_with(Vec::new).push(face_idx);
        }
        
        let mut elements = Vec::new();
        for (cell_idx, _face_indices) in cell_faces {
            let element_type = ElementType::Hexahedron;
            let node_indices = vec![];
            
            let mut elem = Element::new(element_type, node_indices, cell_idx as i32);
            elem.compute_centroid(points);
            elem.compute_volume(points);
            elements.push(elem);
        }
        
        elements.sort_by_key(|e| e.tag);
        Ok(elements)
    }
}
