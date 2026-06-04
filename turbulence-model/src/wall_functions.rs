use nalgebra::{Point3, Vector3};
use mesh_generator::Mesh;

pub struct WallFunction {
    y_plus: Vec<f64>,
    u_plus: Vec<f64>,
    constants: super::k_epsilon::TurbulenceConstants,
}

impl WallFunction {
    pub fn new(constants: super::k_epsilon::TurbulenceConstants) -> Self {
        WallFunction {
            y_plus: Vec::new(),
            u_plus: Vec::new(),
            constants,
        }
    }

    pub fn compute_wall_y_plus(
        &mut self,
        mesh: &Mesh,
        u: &[f64],
        v: &[f64],
        w: &[f64],
        nu: f64,
        nu_t: &[f64],
        k: &[f64],
    ) {
        let num_faces = mesh.num_faces();
        self.y_plus.resize(num_faces, 0.0);
        self.u_plus.resize(num_faces, 0.0);

        for (face_idx, face) in mesh.faces.iter().enumerate() {
            if face.is_boundary {
                if let Some(owner) = face.owner_cell {
                    let u_tau = (self.constants.c_mu * k[owner].powi(2) / 4.0).sqrt();
                    
                    let y = self.compute_wall_distance(
                        &mesh.elements[owner].centroid,
                        &face.centroid,
                    );
                    
                    let vel_mag = (u[owner] * u[owner] + v[owner] * v[owner] + w[owner] * w[owner]).sqrt();
                    let dudy = vel_mag / y.max(1e-12);
                    let dudy_safe = dudy.max(1e-12);
                    
                    let re_tau = u_tau * y / nu;
                    self.y_plus[face_idx] = re_tau;
                    
                    self.u_plus[face_idx] = if re_tau < 11.0 {
                        re_tau
                    } else {
                        1.0 / self.constants.kappa * re_tau.ln() + self.constants.e
                    };
                    
                    let _ = dudy_safe;
                }
            }
        }
    }

    fn compute_wall_distance(&self, cell_center: &Point3<f64>, face_center: &Point3<f64>) -> f64 {
        (cell_center - face_center).norm()
    }

    pub fn get_utau(&self, cell_idx: usize, k_cell: f64) -> f64 {
        (self.constants.c_mu * k_cell.powi(2) / 4.0).sqrt()
    }

    pub fn apply_wall_function(
        &self,
        face_idx: usize,
        owner_cell: usize,
        u: f64,
        v: f64,
        w: f64,
        nu: f64,
        k_cell: f64,
    ) -> (f64, f64) {
        let y_plus = self.y_plus[face_idx];
        let y_plus_safe = y_plus.max(1e-12);
        
        if y_plus_safe < 1.0 {
            let wall_viscosity = nu * 1e6;
            return (wall_viscosity, 0.0);
        }

        let u_tau = self.get_utau(owner_cell, k_cell);
        let vel_mag = (u * u + v * v + w * w).sqrt();
        let y = nu * y_plus_safe / u_tau.max(1e-10);
        
        let tau_wall = self.constants.c_mu.sqrt() * k_cell * vel_mag 
            / (1.0 / self.constants.kappa * y_plus_safe.ln() + self.constants.e);
        
        let nu_t_wall = tau_wall * y / vel_mag.max(1e-10);
        
        (nu_t_wall, tau_wall)
    }

    pub fn get_wall_k(&self, face_idx: usize) -> f64 {
        let y_plus_safe = self.y_plus[face_idx].max(1e-12);
        if y_plus_safe < 11.0 {
            0.0
        } else {
            self.constants.c_mu.powf(-0.25)
        }
    }

    pub fn get_wall_epsilon(&self, face_idx: usize, k_cell: f64, y: f64) -> f64 {
        let y_plus_safe = self.y_plus[face_idx].max(1e-12);
        let u_tau = self.get_utau(0, k_cell);
        if y_plus_safe < 11.0 {
            2.0 * k_cell * u_tau / (y.max(1e-10))
        } else {
            self.constants.c_mu.powf(0.75) * k_cell.powf(1.5) 
                / (self.constants.kappa * y.max(1e-10))
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::k_epsilon::TurbulenceConstants;
    use mesh_generator::{Mesh, Element, ElementType, Face};
    use nalgebra::Point3;

    fn create_test_mesh_with_boundary() -> Mesh {
        let nodes = vec![
            Point3::new(0.0, 0.0, 0.0),
            Point3::new(1.0, 0.0, 0.0),
            Point3::new(1.0, 1.0, 0.0),
            Point3::new(0.0, 1.0, 0.0),
            Point3::new(0.5, 0.5, 0.0),
        ];

        let mut elements = vec![
            Element::new(ElementType::Triangle, vec![0, 1, 4], 0),
            Element::new(ElementType::Triangle, vec![1, 2, 4], 0),
            Element::new(ElementType::Triangle, vec![2, 3, 4], 0),
            Element::new(ElementType::Triangle, vec![3, 0, 4], 0),
        ];

        let faces = vec![
            Face {
                node_indices: vec![0, 1],
                owner_cell: Some(0),
                neighbor_cell: None,
                area: 1.0,
                normal: nalgebra::Vector3::new(0.0, -1.0, 0.0),
                centroid: Point3::new(0.5, 0.0, 0.0),
                is_boundary: true,
            },
            Face {
                node_indices: vec![1, 2],
                owner_cell: Some(1),
                neighbor_cell: None,
                area: 1.0,
                normal: nalgebra::Vector3::new(1.0, 0.0, 0.0),
                centroid: Point3::new(1.0, 0.5, 0.0),
                is_boundary: true,
            },
            Face {
                node_indices: vec![0, 4],
                owner_cell: Some(0),
                neighbor_cell: Some(3),
                area: 1.0,
                normal: nalgebra::Vector3::new(0.0, 0.0, 0.0),
                centroid: Point3::new(0.25, 0.25, 0.0),
                is_boundary: false,
            },
        ];

        Mesh {
            nodes,
            elements,
            faces,
            topology: mesh_generator::topology::Topology::new(),
            is_2d: true,
        }
    }

    #[test]
    fn test_wall_function_zero_y_plus_protection() {
        let constants = TurbulenceConstants::default();
        let mut wf = WallFunction::new(constants);
        let mesh = create_test_mesh_with_boundary();

        let u = vec![0.0; 4];
        let v = vec![0.0; 4];
        let w = vec![0.0; 4];
        let nu = 1e-5;
        let nu_t = vec![0.0; 4];
        let k = vec![1e-12; 4];

        wf.compute_wall_y_plus(&mesh, &u, &v, &w, nu, &nu_t, &k);

        for &y_plus in &wf.y_plus {
            assert!(!y_plus.is_nan(), "y+ should not be NaN");
            assert!(!y_plus.is_infinite(), "y+ should not be infinite");
        }
    }

    #[test]
    fn test_apply_wall_function_zero_velocity() {
        let constants = TurbulenceConstants::default();
        let mut wf = WallFunction::new(constants);
        let mesh = create_test_mesh_with_boundary();

        let u = vec![0.0; 4];
        let v = vec![0.0; 4];
        let w = vec![0.0; 4];
        let nu = 1e-5;
        let nu_t = vec![0.0; 4];
        let k = vec![1e-12; 4];

        wf.compute_wall_y_plus(&mesh, &u, &v, &w, nu, &nu_t, &k);

        let (nu_t_wall, tau_wall) = wf.apply_wall_function(0, 0, 0.0, 0.0, 0.0, nu, k[0]);

        assert!(!nu_t_wall.is_nan(), "nu_t should not be NaN");
        assert!(!tau_wall.is_nan(), "tau_wall should not be NaN");
        assert!(nu_t_wall.is_finite(), "nu_t should be finite");
        assert!(tau_wall.is_finite(), "tau_wall should be finite");
    }

    #[test]
    fn test_apply_wall_function_viscous_sublayer() {
        let constants = TurbulenceConstants::default();
        let mut wf = WallFunction::new(constants);

        wf.y_plus = vec![0.5];
        wf.u_plus = vec![0.5];

        let (nu_t_wall, tau_wall) = wf.apply_wall_function(0, 0, 1.0, 0.0, 0.0, 1e-5, 0.01);

        let expected_nu_t = 1e-5 * 1e6;
        assert!((nu_t_wall - expected_nu_t).abs() < 1e-10, "In viscous sublayer, should use high wall viscosity");
        assert!(tau_wall.abs() < 1e-10, "In viscous sublayer, tau_wall should be ~0");
    }

    #[test]
    fn test_apply_wall_function_log_layer() {
        let constants = TurbulenceConstants::default();
        let mut wf = WallFunction::new(constants);

        wf.y_plus = vec![50.0];
        wf.u_plus = vec![1.0 / 0.41 * 50.0f64.ln() + 5.0];

        let (nu_t_wall, tau_wall) = wf.apply_wall_function(0, 0, 10.0, 0.0, 0.0, 1e-5, 0.01);

        assert!(!nu_t_wall.is_nan(), "nu_t should not be NaN in log layer");
        assert!(!tau_wall.is_nan(), "tau_wall should not be NaN in log layer");
        assert!(nu_t_wall > 1e-5, "nu_t should be larger than molecular viscosity in log layer");
        assert!(tau_wall > 0.0, "tau_wall should be positive in log layer");
    }

    #[test]
    fn test_get_wall_epsilon_protection() {
        let constants = TurbulenceConstants::default();
        let mut wf = WallFunction::new(constants);

        wf.y_plus = vec![0.0];
        let eps = wf.get_wall_epsilon(0, 0.01, 0.0);
        assert!(!eps.is_nan(), "epsilon should not be NaN for y=0");
        assert!(eps.is_finite(), "epsilon should be finite for y=0");
    }
}
