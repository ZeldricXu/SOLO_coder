use std::fs::File;
use std::io::{self, Write};
use std::path::Path;
use thiserror::Error;
use mesh_generator::{Mesh, ElementType};

#[derive(Error, Debug)]
pub enum VtkExportError {
    #[error("IO error: {0}")]
    Io(#[from] io::Error),
}

pub struct VtkExporter;

impl VtkExporter {
    pub fn export<P: AsRef<Path>>(
        mesh: &Mesh,
        output_path: P,
        u: &[f64],
        v: &[f64],
        w: &[f64],
        p: &[f64],
        k: Option<&[f64]>,
        epsilon: Option<&[f64]>,
        omega: Option<&[f64]>,
    ) -> Result<(), VtkExportError> {
        let path_str = output_path.as_ref().to_string_lossy();
        if path_str.ends_with(".vtu") {
            Self::export_vtu(mesh, output_path, u, v, w, p, k, epsilon, omega)
        } else {
            Self::export_vtk(mesh, output_path, u, v, w, p, k, epsilon, omega)
        }
    }

    fn export_vtk<P: AsRef<Path>>(
        mesh: &Mesh,
        output_path: P,
        u: &[f64],
        v: &[f64],
        w: &[f64],
        p: &[f64],
        k: Option<&[f64]>,
        epsilon: Option<&[f64]>,
        omega: Option<&[f64]>,
    ) -> Result<(), VtkExportError> {
        let mut file = File::create(output_path)?;
        
        Self::write_header(&mut file)?;
        Self::write_points(mesh, &mut file)?;
        Self::write_cells_vtk(mesh, &mut file)?;
        Self::write_cell_data(mesh, &mut file, u, v, w, p, k, epsilon, omega)?;
        
        Ok(())
    }

    fn write_header<W: Write>(file: &mut W) -> io::Result<()> {
        writeln!(file, "# vtk DataFile Version 3.0")?;
        writeln!(file, "CFD Solver Results")?;
        writeln!(file, "ASCII")?;
        writeln!(file, "DATASET UNSTRUCTURED_GRID")?;
        Ok(())
    }

    fn write_points<W: Write>(mesh: &Mesh, file: &mut W) -> io::Result<()> {
        writeln!(file, "POINTS {} float", mesh.num_nodes())?;
        
        for node in &mesh.nodes {
            writeln!(file, "{} {} {}", node.x, node.y, node.z)?;
        }
        
        writeln!(file)?;
        Ok(())
    }

    fn write_cells_vtk<W: Write>(mesh: &Mesh, file: &mut W) -> io::Result<()> {
        let mut total_size = 0;
        for element in &mesh.elements {
            total_size += 1 + element.node_indices.len();
        }
        
        writeln!(file, "CELLS {} {}", mesh.num_cells(), total_size)?;
        
        for element in &mesh.elements {
            write!(file, "{}", element.node_indices.len())?;
            for &node_idx in &element.node_indices {
                write!(file, " {}", node_idx)?;
            }
            writeln!(file)?;
        }
        
        writeln!(file)?;
        
        writeln!(file, "CELL_TYPES {}", mesh.num_cells())?;
        for element in &mesh.elements {
            let vtk_type = Self::element_to_vtk_type(element.element_type);
            writeln!(file, "{}", vtk_type)?;
        }
        
        writeln!(file)?;
        Ok(())
    }

    fn element_to_vtk_type(element_type: ElementType) -> i32 {
        match element_type {
            ElementType::Triangle => 5,
            ElementType::Quadrilateral => 9,
            ElementType::Tetrahedron => 10,
            ElementType::Hexahedron => 12,
        }
    }

    fn write_cell_data<W: Write>(
        mesh: &Mesh,
        file: &mut W,
        u: &[f64],
        v: &[f64],
        w: &[f64],
        p: &[f64],
        k: Option<&[f64]>,
        epsilon: Option<&[f64]>,
        omega: Option<&[f64]>,
    ) -> io::Result<()> {
        writeln!(file, "CELL_DATA {}", mesh.num_cells())?;
        
        writeln!(file, "VECTORS Velocity float")?;
        for i in 0..mesh.num_cells() {
            writeln!(file, "{} {} {}", u[i], v[i], w[i])?;
        }
        writeln!(file)?;
        
        writeln!(file, "SCALARS Pressure float 1")?;
        writeln!(file, "LOOKUP_TABLE default")?;
        for &pressure in p {
            writeln!(file, "{}", pressure)?;
        }
        writeln!(file)?;
        
        writeln!(file, "SCALARS VelocityMagnitude float 1")?;
        writeln!(file, "LOOKUP_TABLE default")?;
        for i in 0..mesh.num_cells() {
            let mag = (u[i] * u[i] + v[i] * v[i] + w[i] * w[i]).sqrt();
            writeln!(file, "{}", mag)?;
        }
        writeln!(file)?;
        
        if let Some(k_values) = k {
            writeln!(file, "SCALARS TurbulentKineticEnergy float 1")?;
            writeln!(file, "LOOKUP_TABLE default")?;
            for &k_val in k_values {
                writeln!(file, "{}", k_val)?;
            }
            writeln!(file)?;
        }
        
        if let Some(eps_values) = epsilon {
            writeln!(file, "SCALARS TurbulentDissipation float 1")?;
            writeln!(file, "LOOKUP_TABLE default")?;
            for &eps_val in eps_values {
                writeln!(file, "{}", eps_val)?;
            }
            writeln!(file)?;
        }
        
        if let Some(omega_values) = omega {
            writeln!(file, "SCALARS SpecificDissipationRate float 1")?;
            writeln!(file, "LOOKUP_TABLE default")?;
            for &omega_val in omega_values {
                writeln!(file, "{}", omega_val)?;
            }
            writeln!(file)?;
        }
        
        Ok(())
    }

    pub fn export_with_node_data<P: AsRef<Path>>(
        mesh: &Mesh,
        output_path: P,
        u_node: &[f64],
        v_node: &[f64],
        w_node: &[f64],
        p_node: &[f64],
        k_node: Option<&[f64]>,
        epsilon_node: Option<&[f64]>,
    ) -> Result<(), VtkExportError> {
        let mut file = File::create(output_path)?;
        
        Self::write_header(&mut file)?;
        Self::write_points(mesh, &mut file)?;
        Self::write_cells_vtk(mesh, &mut file)?;
        
        writeln!(file, "POINT_DATA {}", mesh.num_nodes())?;
        
        writeln!(file, "VECTORS Velocity float")?;
        for i in 0..mesh.num_nodes() {
            writeln!(file, "{} {} {}", u_node[i], v_node[i], w_node[i])?;
        }
        writeln!(file)?;
        
        writeln!(file, "SCALARS Pressure float 1")?;
        writeln!(file, "LOOKUP_TABLE default")?;
        for &pressure in p_node {
            writeln!(file, "{}", pressure)?;
        }
        writeln!(file)?;
        
        writeln!(file, "SCALARS VelocityMagnitude float 1")?;
        writeln!(file, "LOOKUP_TABLE default")?;
        for i in 0..mesh.num_nodes() {
            let mag = (u_node[i] * u_node[i] + v_node[i] * v_node[i] + w_node[i] * w_node[i]).sqrt();
            writeln!(file, "{}", mag)?;
        }
        writeln!(file)?;
        
        if let Some(k_values) = k_node {
            writeln!(file, "SCALARS TurbulentKineticEnergy float 1")?;
            writeln!(file, "LOOKUP_TABLE default")?;
            for &k_val in k_values {
                writeln!(file, "{}", k_val)?;
            }
            writeln!(file)?;
        }
        
        if let Some(eps_values) = epsilon_node {
            writeln!(file, "SCALARS TurbulentDissipation float 1")?;
            writeln!(file, "LOOKUP_TABLE default")?;
            for &eps_val in eps_values {
                writeln!(file, "{}", eps_val)?;
            }
            writeln!(file)?;
        }
        
        Ok(())
    }

    fn export_vtu<P: AsRef<Path>>(
        mesh: &Mesh,
        output_path: P,
        u: &[f64],
        v: &[f64],
        w: &[f64],
        p: &[f64],
        k: Option<&[f64]>,
        epsilon: Option<&[f64]>,
        omega: Option<&[f64]>,
    ) -> Result<(), VtkExportError> {
        let mut file = File::create(output_path)?;

        writeln!(file, "<?xml version=\"1.0\"?>")?;
        writeln!(file, "<VTKFile type=\"UnstructuredGrid\" version=\"0.1\" byte_order=\"LittleEndian\">")?;
        writeln!(file, "  <UnstructuredGrid>")?;
        writeln!(file, "    <Piece NumberOfPoints=\"{}\" NumberOfCells=\"{}\">", mesh.num_nodes(), mesh.num_cells())?;

        Self::write_points_vtu(mesh, &mut file)?;
        Self::write_cells_vtu(mesh, &mut file)?;
        Self::write_cell_data_vtu(mesh, &mut file, u, v, w, p, k, epsilon, omega)?;

        writeln!(file, "    </Piece>")?;
        writeln!(file, "  </UnstructuredGrid>")?;
        writeln!(file, "</VTKFile>")?;

        Ok(())
    }

    fn write_points_vtu<W: Write>(mesh: &Mesh, file: &mut W) -> io::Result<()> {
        writeln!(file, "      <Points>")?;
        writeln!(file, "        <DataArray type=\"Float32\" NumberOfComponents=\"3\" format=\"ascii\">")?;
        for node in &mesh.nodes {
            writeln!(file, "          {} {} {}", node.x, node.y, node.z)?;
        }
        writeln!(file, "        </DataArray>")?;
        writeln!(file, "      </Points>")?;
        Ok(())
    }

    fn write_cells_vtu<W: Write>(mesh: &Mesh, file: &mut W) -> io::Result<()> {
        use std::collections::HashMap;

        let mut connectivity: Vec<usize> = Vec::new();
        let mut offsets: Vec<usize> = Vec::new();
        let mut types: Vec<u8> = Vec::new();

        for element in &mesh.elements {
            for &node_idx in &element.node_indices {
                connectivity.push(node_idx);
            }
            offsets.push(connectivity.len());
            types.push(Self::element_to_vtk_type(element.element_type) as u8);
        }

        writeln!(file, "      <Cells>")?;
        writeln!(file, "        <DataArray type=\"Int32\" Name=\"connectivity\" format=\"ascii\">")?;
        write!(file, "          ")?;
        for &node_idx in &connectivity {
            write!(file, "{} ", node_idx)?;
        }
        writeln!(file)?;
        writeln!(file, "        </DataArray>")?;

        writeln!(file, "        <DataArray type=\"Int32\" Name=\"offsets\" format=\"ascii\">")?;
        write!(file, "          ")?;
        for &offset in &offsets {
            write!(file, "{} ", offset)?;
        }
        writeln!(file)?;
        writeln!(file, "        </DataArray>")?;

        writeln!(file, "        <DataArray type=\"UInt8\" Name=\"types\" format=\"ascii\">")?;
        write!(file, "          ")?;
        for &t in &types {
            write!(file, "{} ", t)?;
        }
        writeln!(file)?;
        writeln!(file, "        </DataArray>")?;
        writeln!(file, "      </Cells>")?;
        Ok(())
    }

    fn write_cell_data_vtu<W: Write>(
        mesh: &Mesh,
        file: &mut W,
        u: &[f64],
        v: &[f64],
        w: &[f64],
        p: &[f64],
        k: Option<&[f64]>,
        epsilon: Option<&[f64]>,
        omega: Option<&[f64]>,
    ) -> io::Result<()> {
        writeln!(file, "      <CellData>")?;

        writeln!(file, "        <DataArray type=\"Float32\" Name=\"Velocity\" NumberOfComponents=\"3\" format=\"ascii\">")?;
        for i in 0..mesh.num_cells() {
            writeln!(file, "          {} {} {}", u[i], v[i], w[i])?;
        }
        writeln!(file, "        </DataArray>")?;

        writeln!(file, "        <DataArray type=\"Float32\" Name=\"Pressure\" format=\"ascii\">")?;
        for &pressure in p {
            writeln!(file, "          {}", pressure)?;
        }
        writeln!(file, "        </DataArray>")?;

        writeln!(file, "        <DataArray type=\"Float32\" Name=\"VelocityMagnitude\" format=\"ascii\">")?;
        for i in 0..mesh.num_cells() {
            let mag = (u[i] * u[i] + v[i] * v[i] + w[i] * w[i]).sqrt();
            writeln!(file, "          {}", mag)?;
        }
        writeln!(file, "        </DataArray>")?;

        if let Some(k_values) = k {
            writeln!(file, "        <DataArray type=\"Float32\" Name=\"TurbulentKineticEnergy\" format=\"ascii\">")?;
            for &k_val in k_values {
                writeln!(file, "          {}", k_val)?;
            }
            writeln!(file, "        </DataArray>")?;
        }

        if let Some(eps_values) = epsilon {
            writeln!(file, "        <DataArray type=\"Float32\" Name=\"TurbulentDissipation\" format=\"ascii\">")?;
            for &eps_val in eps_values {
                writeln!(file, "          {}", eps_val)?;
            }
            writeln!(file, "        </DataArray>")?;
        }

        if let Some(omega_values) = omega {
            writeln!(file, "        <DataArray type=\"Float32\" Name=\"SpecificDissipationRate\" format=\"ascii\">")?;
            for &omega_val in omega_values {
                writeln!(file, "          {}", omega_val)?;
            }
            writeln!(file, "        </DataArray>")?;
        }

        writeln!(file, "      </CellData>")?;
        Ok(())
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use mesh_generator::{Mesh, Element, ElementType, Face};
    use nalgebra::Point3;

    fn create_mixed_mesh() -> Mesh {
        let nodes = vec![
            Point3::new(0.0, 0.0, 0.0),
            Point3::new(1.0, 0.0, 0.0),
            Point3::new(1.0, 1.0, 0.0),
            Point3::new(0.0, 1.0, 0.0),
            Point3::new(0.0, 0.0, 1.0),
            Point3::new(1.0, 0.0, 1.0),
            Point3::new(1.0, 1.0, 1.0),
            Point3::new(0.0, 1.0, 1.0),
            Point3::new(0.5, 0.5, 0.5),
            Point3::new(2.0, 0.0, 0.0),
            Point3::new(2.0, 1.0, 0.0),
            Point3::new(2.0, 0.0, 1.0),
            Point3::new(2.0, 1.0, 1.0),
        ];

        let elements = vec![
            Element::new(ElementType::Hexahedron, vec![0, 1, 2, 3, 4, 5, 6, 7], 0),
            Element::new(ElementType::Tetrahedron, vec![1, 9, 10, 8], 0),
            Element::new(ElementType::Tetrahedron, vec![1, 10, 2, 8], 0),
        ];

        let faces = vec![
            Face {
                node_indices: vec![0, 1, 5, 4],
                owner_cell: Some(0),
                neighbor_cell: None,
                area: 1.0,
                normal: nalgebra::Vector3::new(-1.0, 0.0, 0.0),
                centroid: Point3::new(0.0, 0.5, 0.5),
                is_boundary: true,
            },
        ];

        Mesh {
            nodes,
            elements,
            faces,
            topology: mesh_generator::topology::Topology::new(),
            is_2d: false,
        }
    }

    #[test]
    fn test_vtu_export_mixed_elements_offsets() {
        let mesh = create_mixed_mesh();
        let output_path = std::env::temp_dir().join("test_mixed_mesh.vtu");

        let u = vec![1.0; mesh.num_cells()];
        let v = vec![0.0; mesh.num_cells()];
        let w = vec![0.0; mesh.num_cells()];
        let p = vec![0.0; mesh.num_cells()];

        let result = VtkExporter::export(&mesh, &output_path, &u, &v, &w, &p, None, None, None);
        assert!(result.is_ok(), "Export should succeed");

        let content = std::fs::read_to_string(&output_path).expect("Failed to read output");

        let expected_connectivity = "0 1 2 3 4 5 6 7 1 9 10 8 1 10 2 8";
        assert!(content.contains(expected_connectivity), "Connectivity should be correct");

        let expected_offsets = "8 12 16";
        assert!(content.contains(expected_offsets), "Offsets should accumulate correctly: hex 8, tet+4=12, tet+4=16");

        let expected_types = "12 10 10";
        assert!(content.contains(expected_types), "Types should be: 12 (hex), 10 (tet), 10 (tet)");

        let _ = std::fs::remove_file(&output_path);
    }

    #[test]
    fn test_vtu_export_valid_xml() {
        let mesh = create_mixed_mesh();
        let output_path = std::env::temp_dir().join("test_valid_xml.vtu");

        let u = vec![0.0; mesh.num_cells()];
        let v = vec![0.0; mesh.num_cells()];
        let w = vec![0.0; mesh.num_cells()];
        let p = vec![0.0; mesh.num_cells()];

        VtkExporter::export(&mesh, &output_path, &u, &v, &w, &p, None, None, None).unwrap();

        let content = std::fs::read_to_string(&output_path).unwrap();
        assert!(content.starts_with("<?xml version=\"1.0\"?>"));
        assert!(content.contains("<VTKFile type=\"UnstructuredGrid\""));
        assert!(content.contains("</VTKFile>"));
        assert!(content.contains("<Points>") && content.contains("</Points>"));
        assert!(content.contains("<Cells>") && content.contains("</Cells>"));
        assert!(content.contains("<CellData>") && content.contains("</CellData>"));

        let _ = std::fs::remove_file(&output_path);
    }

    #[test]
    fn test_vtk_export_legacy_format() {
        let mesh = create_mixed_mesh();
        let output_path = std::env::temp_dir().join("test_legacy.vtk");

        let u = vec![0.0; mesh.num_cells()];
        let v = vec![0.0; mesh.num_cells()];
        let w = vec![0.0; mesh.num_cells()];
        let p = vec![0.0; mesh.num_cells()];

        VtkExporter::export(&mesh, &output_path, &u, &v, &w, &p, None, None, None).unwrap();

        let content = std::fs::read_to_string(&output_path).unwrap();
        assert!(content.starts_with("# vtk DataFile Version 3.0"));
        assert!(content.contains("DATASET UNSTRUCTURED_GRID"));

        let _ = std::fs::remove_file(&output_path);
    }

    #[test]
    fn test_vtu_export_with_turbulence_fields() {
        let mesh = create_mixed_mesh();
        let output_path = std::env::temp_dir().join("test_turbulence.vtu");

        let u = vec![1.0; mesh.num_cells()];
        let v = vec![0.0; mesh.num_cells()];
        let w = vec![0.0; mesh.num_cells()];
        let p = vec![0.0; mesh.num_cells()];
        let k = vec![0.01; mesh.num_cells()];
        let epsilon = vec![0.001; mesh.num_cells()];
        let omega = vec![10.0; mesh.num_cells()];

        VtkExporter::export(&mesh, &output_path, &u, &v, &w, &p, Some(&k), Some(&epsilon), Some(&omega)).unwrap();

        let content = std::fs::read_to_string(&output_path).unwrap();
        assert!(content.contains("Name=\"TurbulentKineticEnergy\""));
        assert!(content.contains("Name=\"TurbulentDissipation\""));
        assert!(content.contains("Name=\"SpecificDissipationRate\""));

        let _ = std::fs::remove_file(&output_path);
    }
}

