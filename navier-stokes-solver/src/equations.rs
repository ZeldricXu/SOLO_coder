use nalgebra::Vector3;
use mesh_generator::Mesh;
use linear_system_solver::CsrMatrix;
use std::collections::HashMap;
use boundary_condition::Periodic;

pub struct MomentumEquation {
    pub a_p: Vec<f64>,
    pub a_neighbors: Vec<HashMap<usize, f64>>,
    pub b: Vec<f64>,
    pub d: Vec<f64>,
}

impl MomentumEquation {
    pub fn new(num_cells: usize) -> Self {
        MomentumEquation {
            a_p: vec![0.0; num_cells],
            a_neighbors: vec![HashMap::new(); num_cells],
            b: vec![0.0; num_cells],
            d: vec![0.0; num_cells],
        }
    }

    pub fn assemble(
        &mut self,
        mesh: &Mesh,
        velocity: &[f64],
        grad_vel: &[Vector3<f64>],
        mass_flux: &[f64],
        nu: f64,
        nu_t: &[f64],
        grad_p: &[Vector3<f64>],
        volume: &[f64],
        rho: f64,
        dt: f64,
    ) {
        let num_cells = mesh.num_cells();
        
        for cell_idx in 0..num_cells {
            self.a_p[cell_idx] = 0.0;
            self.a_neighbors[cell_idx].clear();
            self.b[cell_idx] = 0.0;
        }

        for (face_idx, face) in mesh.faces.iter().enumerate() {
            if let Some(owner) = face.owner_cell {
                let neighbor = face.neighbor_cell;
                let area = face.area;
                let normal = face.normal;
                let m_dot = mass_flux[face_idx];

                let nu_eff_owner = nu + nu_t[owner];
                let nu_eff_neigh = neighbor.map(|n| nu + nu_t[n]).unwrap_or(nu_eff_owner);
                let nu_eff = 0.5 * (nu_eff_owner + nu_eff_neigh);

                let d_cf = if let Some(neigh) = neighbor {
                    (face.centroid - mesh.elements[owner].centroid).norm()
                } else {
                    (face.centroid - mesh.elements[owner].centroid).norm()
                };

                let diffusion = rho * nu_eff * area / d_cf;

                if let Some(neigh) = neighbor {
                    if m_dot > 0.0 {
                        self.a_p[owner] += m_dot + diffusion;
                        let val_owner_neigh = *self.a_neighbors[owner].get(&neigh).unwrap_or(&0.0) - diffusion;
                        self.a_neighbors[owner].insert(neigh, val_owner_neigh);
                        self.a_p[neigh] += diffusion;
                        let val_neigh_owner = *self.a_neighbors[neigh].get(&owner).unwrap_or(&0.0) - m_dot - diffusion;
                        self.a_neighbors[neigh].insert(owner, val_neigh_owner);
                    } else {
                        self.a_p[owner] += -m_dot + diffusion;
                        let val_owner_neigh = *self.a_neighbors[owner].get(&neigh).unwrap_or(&0.0) + m_dot - diffusion;
                        self.a_neighbors[owner].insert(neigh, val_owner_neigh);
                        self.a_p[neigh] += diffusion;
                        let val_neigh_owner = *self.a_neighbors[neigh].get(&owner).unwrap_or(&0.0) - diffusion;
                        self.a_neighbors[neigh].insert(owner, val_neigh_owner);
                    }
                } else {
                    self.a_p[owner] += m_dot.abs() + diffusion;
                }
            }
        }

        for cell_idx in 0..num_cells {
            let transient = rho * volume[cell_idx] / dt;
            self.a_p[cell_idx] += transient;
            self.b[cell_idx] = transient * velocity[cell_idx] 
                - grad_p[cell_idx].dot(&Vector3::new(1.0, 0.0, 0.0)) * volume[cell_idx];
        }

        for cell_idx in 0..num_cells {
            if self.a_p[cell_idx].abs() > 1e-15 {
                self.d[cell_idx] = 1.0 / self.a_p[cell_idx];
            } else {
                self.d[cell_idx] = 0.0;
            }
        }
    }

    pub fn to_csr(&self) -> (CsrMatrix, Vec<f64>) {
        let n = self.a_p.len();
        let mut rows = Vec::new();
        let mut cols = Vec::new();
        let mut values = Vec::new();

        for i in 0..n {
            rows.push(i);
            cols.push(i);
            values.push(self.a_p[i]);

            for (&j, &val) in &self.a_neighbors[i] {
                if val.abs() > 1e-15 {
                    rows.push(i);
                    cols.push(j);
                    values.push(val);
                }
            }
        }

        let mat = CsrMatrix::from_triplets(n, n, &rows, &cols, &values);
        (mat, self.b.clone())
    }
}

pub struct PressureCorrectionEquation {
    pub a_p: Vec<f64>,
    pub a_neighbors: Vec<HashMap<usize, f64>>,
    pub b: Vec<f64>,
}

impl PressureCorrectionEquation {
    pub fn new(num_cells: usize) -> Self {
        PressureCorrectionEquation {
            a_p: vec![0.0; num_cells],
            a_neighbors: vec![HashMap::new(); num_cells],
            b: vec![0.0; num_cells],
        }
    }

    pub fn assemble(
        &mut self,
        mesh: &Mesh,
        mass_flux: &[f64],
        d_u: &[f64],
        d_v: &[f64],
        d_w: &[f64],
        rho: f64,
    ) {
        self.assemble_with_periodic(mesh, mass_flux, d_u, d_v, d_w, rho, None);
    }

    pub fn assemble_with_periodic(
        &mut self,
        mesh: &Mesh,
        mass_flux: &[f64],
        d_u: &[f64],
        d_v: &[f64],
        d_w: &[f64],
        rho: f64,
        periodic: Option<&Periodic>,
    ) {
        let num_cells = mesh.num_cells();
        
        for cell_idx in 0..num_cells {
            self.a_p[cell_idx] = 0.0;
            self.a_neighbors[cell_idx].clear();
            self.b[cell_idx] = 0.0;
        }

        let slave_to_master = if let Some(p) = periodic {
            p.periodic_cell_map.clone()
        } else {
            HashMap::new()
        };

        for face in &mesh.faces {
            if let Some(owner) = face.owner_cell {
                let neighbor = face.neighbor_cell;
                let area = face.area;
                let normal = face.normal;

                let owner_map = slave_to_master.get(&owner).copied().unwrap_or(owner);
                
                let d_owner = (d_u[owner_map] * normal.x + d_v[owner_map] * normal.y + d_w[owner_map] * normal.z) * area;
                
                if let Some(neigh) = neighbor {
                    let neigh_map = slave_to_master.get(&neigh).copied().unwrap_or(neigh);

                    if owner_map == neigh_map {
                        continue;
                    }
                    
                    let d_neigh = (d_u[neigh_map] * normal.x + d_v[neigh_map] * normal.y + d_w[neigh_map] * normal.z) * area;
                    let d_avg = 0.5 * (d_owner + d_neigh);
                    
                    self.a_p[owner_map] += rho * d_avg;
                    let val_owner_neigh = *self.a_neighbors[owner_map].get(&neigh_map).unwrap_or(&0.0) - rho * d_avg;
                    self.a_neighbors[owner_map].insert(neigh_map, val_owner_neigh);
                    self.a_p[neigh_map] += rho * d_avg;
                    let val_neigh_owner = *self.a_neighbors[neigh_map].get(&owner_map).unwrap_or(&0.0) - rho * d_avg;
                    self.a_neighbors[neigh_map].insert(owner_map, val_neigh_owner);
                } else {
                    self.a_p[owner_map] += rho * d_owner;
                }
            }
        }

        for (face_idx, face) in mesh.faces.iter().enumerate() {
            if let Some(owner) = face.owner_cell {
                let owner_map = slave_to_master.get(&owner).copied().unwrap_or(owner);
                self.b[owner_map] -= mass_flux[face_idx];
                if let Some(neigh) = face.neighbor_cell {
                    let neigh_map = slave_to_master.get(&neigh).copied().unwrap_or(neigh);
                    self.b[neigh_map] += mass_flux[face_idx];
                }
            }
        }
    }

    pub fn to_csr(&self) -> (CsrMatrix, Vec<f64>) {
        let n = self.a_p.len();
        let mut rows = Vec::new();
        let mut cols = Vec::new();
        let mut values = Vec::new();

        for i in 0..n {
            rows.push(i);
            cols.push(i);
            values.push(self.a_p[i]);

            for (&j, &val) in &self.a_neighbors[i] {
                if val.abs() > 1e-15 {
                    rows.push(i);
                    cols.push(j);
                    values.push(val);
                }
            }
        }

        let mat = CsrMatrix::from_triplets(n, n, &rows, &cols, &values);
        (mat, self.b.clone())
    }
}

#[cfg(test)]
mod tests_periodic_boundary {
    use super::*;
    use boundary_condition::{Periodic, BoundaryCondition};
    use nalgebra::Vector3;
    use mesh_generator::topology::Topology;

    fn create_mesh_2d(nodes: Vec<nalgebra::Point3<f64>>, 
                        elements: Vec<mesh_generator::Element>, 
                        faces: Vec<mesh_generator::Face>) -> Mesh {
        Mesh {
            nodes,
            elements,
            faces,
            topology: Topology::new(),
            is_2d: true,
        }
    }

    #[test]
    fn test_periodic_boundary_pressure_no_oscillation() {
        let nodes = vec![
            nalgebra::Point3::new(0.0, 0.0, 0.0),
            nalgebra::Point3::new(1.0, 0.0, 0.0),
            nalgebra::Point3::new(2.0, 0.0, 0.0),
            nalgebra::Point3::new(0.0, 1.0, 0.0),
            nalgebra::Point3::new(1.0, 1.0, 0.0),
            nalgebra::Point3::new(2.0, 1.0, 0.0),
        ];

        let elements = vec![
            mesh_generator::Element::new(
                mesh_generator::ElementType::Quadrilateral,
                vec![0, 1, 4, 3],
                0,
            ),
            mesh_generator::Element::new(
                mesh_generator::ElementType::Quadrilateral,
                vec![1, 2, 5, 4],
                0,
            ),
        ];

        let faces = vec![
            mesh_generator::Face {
                node_indices: vec![0, 3],
                owner_cell: Some(0),
                neighbor_cell: None,
                area: 1.0,
                normal: Vector3::new(-1.0, 0.0, 0.0),
                centroid: nalgebra::Point3::new(0.0, 0.5, 0.0),
                is_boundary: true,
            },
            mesh_generator::Face {
                node_indices: vec![1, 0, 3, 4],
                owner_cell: Some(0),
                neighbor_cell: Some(1),
                area: 1.0,
                normal: Vector3::new(1.0, 0.0, 0.0),
                centroid: nalgebra::Point3::new(1.0, 0.5, 0.0),
                is_boundary: false,
            },
            mesh_generator::Face {
                node_indices: vec![2, 5],
                owner_cell: Some(1),
                neighbor_cell: None,
                area: 1.0,
                normal: Vector3::new(1.0, 0.0, 0.0),
                centroid: nalgebra::Point3::new(2.0, 0.5, 0.0),
                is_boundary: true,
            },
        ];

        let mesh = create_mesh_2d(nodes, elements, faces);

        let mut pressure_eq = PressureCorrectionEquation::new(2);
        let mass_flux = vec![0.0; 3];
        let d_u = vec![1.0, 1.0];
        let d_v = vec![0.0, 0.0];
        let d_w = vec![0.0, 0.0];
        let rho = 1.0;

        pressure_eq.assemble(&mesh, &mass_flux, &d_u, &d_v, &d_w, rho);
        let diag_0_without = pressure_eq.a_p[0];
        let diag_1_without = pressure_eq.a_p[1];
        let off_diag_without = pressure_eq.a_neighbors[0].get(&1).copied().unwrap_or(0.0);

        let mut pressure_eq_periodic = PressureCorrectionEquation::new(2);
        let mut periodic = Periodic::new(Vector3::new(2.0, 0.0, 0.0));
        periodic.register_periodic_cell_pair(0, 1);
        pressure_eq_periodic.assemble_with_periodic(&mesh, &mass_flux, &d_u, &d_v, &d_w, rho, Some(&periodic));

        for i in 0..2 {
            assert!(!pressure_eq_periodic.a_p[i].is_nan(), "Diagonal should not be NaN for cell {}", i);
            assert!(!pressure_eq_periodic.b[i].is_nan(), "RHS should not be NaN for cell {}", i);
            assert!(pressure_eq_periodic.a_p[i].is_finite(), "Diagonal should be finite for cell {}", i);
            assert!(pressure_eq_periodic.b[i].is_finite(), "RHS should be finite for cell {}", i);
        }

        let (mat, rhs) = pressure_eq_periodic.to_csr();
        for val in mat.data.iter() {
            assert!(val.is_finite(), "Matrix values should be finite");
            assert!(!val.is_nan(), "Matrix values should not be NaN");
        }
        for val in rhs.iter() {
            assert!(val.is_finite(), "RHS values should be finite");
            assert!(!val.is_nan(), "RHS values should not be NaN");
        }

        assert!(!pressure_eq_periodic.a_p[0].is_nan());
        assert!(!pressure_eq_periodic.a_p[1].is_nan());
        
        let total_diag_without = diag_0_without + diag_1_without;
        let total_diag_with = pressure_eq_periodic.a_p[0] + pressure_eq_periodic.a_p[1];
        assert!(total_diag_with <= total_diag_without + 1e-12,
            "Total diagonal with periodic should not exceed without (no double counting): with={}, without={}",
            total_diag_with, total_diag_without);
    }

    #[test]
    fn test_periodic_boundary_diagonal_not_double_counted() {
        let nodes = vec![
            nalgebra::Point3::new(0.0, 0.0, 0.0),
            nalgebra::Point3::new(1.0, 0.0, 0.0),
            nalgebra::Point3::new(0.0, 1.0, 0.0),
            nalgebra::Point3::new(1.0, 1.0, 0.0),
        ];

        let elements = vec![
            mesh_generator::Element::new(
                mesh_generator::ElementType::Quadrilateral,
                vec![0, 1, 3, 2],
                0,
            ),
        ];

        let faces = vec![
            mesh_generator::Face {
                node_indices: vec![0, 2],
                owner_cell: Some(0),
                neighbor_cell: None,
                area: 1.0,
                normal: Vector3::new(-1.0, 0.0, 0.0),
                centroid: nalgebra::Point3::new(0.0, 0.5, 0.0),
                is_boundary: true,
            },
            mesh_generator::Face {
                node_indices: vec![1, 3],
                owner_cell: Some(0),
                neighbor_cell: None,
                area: 1.0,
                normal: Vector3::new(1.0, 0.0, 0.0),
                centroid: nalgebra::Point3::new(1.0, 0.5, 0.0),
                is_boundary: true,
            },
        ];

        let mesh = create_mesh_2d(nodes, elements, faces);

        let mut pressure_eq = PressureCorrectionEquation::new(1);
        let mass_flux = vec![1.0, -1.0];
        let d_u = vec![1.0];
        let d_v = vec![0.0];
        let d_w = vec![0.0];
        let rho = 1.0;

        pressure_eq.assemble(&mesh, &mass_flux, &d_u, &d_v, &d_w, rho);
        let diag_without_periodic = pressure_eq.a_p[0];

        let mut pressure_eq2 = PressureCorrectionEquation::new(1);
        pressure_eq2.assemble(&mesh, &mass_flux, &d_u, &d_v, &d_w, rho);
        let diag_with_periodic = pressure_eq2.a_p[0];

        assert_eq!(
            diag_without_periodic, diag_with_periodic,
            "Diagonal should be consistent with/without periodic BC"
        );
    }
}
