use nalgebra::{Vector3, Point3};

#[derive(Debug, Clone)]
pub struct FlowFields {
    pub u: Vec<f64>,
    pub v: Vec<f64>,
    pub w: Vec<f64>,
    pub p: Vec<f64>,
    pub p_corr: Vec<f64>,
    pub u_face: Vec<f64>,
    pub v_face: Vec<f64>,
    pub w_face: Vec<f64>,
    pub mass_flux: Vec<f64>,
    pub nu_t: Vec<f64>,
    pub k: Vec<f64>,
    pub epsilon: Vec<f64>,
    pub omega: Vec<f64>,
}

impl FlowFields {
    pub fn new(num_cells: usize, num_faces: usize) -> Self {
        FlowFields {
            u: vec![0.0; num_cells],
            v: vec![0.0; num_cells],
            w: vec![0.0; num_cells],
            p: vec![0.0; num_cells],
            p_corr: vec![0.0; num_cells],
            u_face: vec![0.0; num_faces],
            v_face: vec![0.0; num_faces],
            w_face: vec![0.0; num_faces],
            mass_flux: vec![0.0; num_faces],
            nu_t: vec![0.0; num_cells],
            k: vec![0.001; num_cells],
            epsilon: vec![0.001; num_cells],
            omega: vec![10.0; num_cells],
        }
    }

    pub fn velocity(&self, cell_idx: usize) -> Vector3<f64> {
        Vector3::new(self.u[cell_idx], self.v[cell_idx], self.w[cell_idx])
    }

    pub fn set_velocity(&mut self, cell_idx: usize, vel: Vector3<f64>) {
        self.u[cell_idx] = vel.x;
        self.v[cell_idx] = vel.y;
        self.w[cell_idx] = vel.z;
    }

    pub fn velocity_magnitude(&self, cell_idx: usize) -> f64 {
        (self.u[cell_idx].powi(2) + self.v[cell_idx].powi(2) + self.w[cell_idx].powi(2)).sqrt()
    }

    pub fn correct_velocity(
        &mut self,
        cell_idx: usize,
        d_u: f64,
        d_v: f64,
        d_w: f64,
        grad_p_corr: Vector3<f64>,
    ) {
        self.u[cell_idx] -= d_u * grad_p_corr.x;
        self.v[cell_idx] -= d_v * grad_p_corr.y;
        self.w[cell_idx] -= d_w * grad_p_corr.z;
    }
}

#[derive(Debug, Clone)]
pub struct GradientFields {
    pub grad_u: Vec<Vector3<f64>>,
    pub grad_v: Vec<Vector3<f64>>,
    pub grad_w: Vec<Vector3<f64>>,
    pub grad_p: Vec<Vector3<f64>>,
    pub grad_k: Vec<Vector3<f64>>,
    pub grad_epsilon: Vec<Vector3<f64>>,
    pub grad_omega: Vec<Vector3<f64>>,
}

impl GradientFields {
    pub fn new(num_cells: usize) -> Self {
        GradientFields {
            grad_u: vec![Vector3::zeros(); num_cells],
            grad_v: vec![Vector3::zeros(); num_cells],
            grad_w: vec![Vector3::zeros(); num_cells],
            grad_p: vec![Vector3::zeros(); num_cells],
            grad_k: vec![Vector3::zeros(); num_cells],
            grad_epsilon: vec![Vector3::zeros(); num_cells],
            grad_omega: vec![Vector3::zeros(); num_cells],
        }
    }
}

pub fn compute_face_velocity(
    face_idx: usize,
    owner: usize,
    neighbor: Option<usize>,
    fields: &FlowFields,
    lambda: f64,
) -> Vector3<f64> {
    let u_owner = fields.u[owner];
    let v_owner = fields.v[owner];
    let w_owner = fields.w[owner];

    if let Some(neigh) = neighbor {
        let u_neigh = fields.u[neigh];
        let v_neigh = fields.v[neigh];
        let w_neigh = fields.w[neigh];
        
        Vector3::new(
            lambda * u_owner + (1.0 - lambda) * u_neigh,
            lambda * v_owner + (1.0 - lambda) * v_neigh,
            lambda * w_owner + (1.0 - lambda) * w_neigh,
        )
    } else {
        Vector3::new(u_owner, v_owner, w_owner)
    }
}
