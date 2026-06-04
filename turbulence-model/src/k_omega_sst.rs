use nalgebra::Vector3;
use std::collections::HashMap;

use mesh_generator::Mesh;
use linear_system_solver::{BiCGSTAB, Ilu0Preconditioner, SolverConfig};
use boundary_condition::BoundaryCondition;

#[derive(Debug, Clone, Copy)]
pub struct SSTConstants {
    pub alpha1: f64,
    pub alpha2: f64,
    pub beta1: f64,
    pub beta2: f64,
    pub gamma1: f64,
    pub gamma2: f64,
    pub sigma_k1: f64,
    pub sigma_k2: f64,
    pub sigma_omega1: f64,
    pub sigma_omega2: f64,
    pub a1: f64,
    pub c1: f64,
    pub kappa: f64,
}

impl Default for SSTConstants {
    fn default() -> Self {
        SSTConstants {
            alpha1: 5.0 / 9.0,
            alpha2: 0.44,
            beta1: 3.0 / 40.0,
            beta2: 0.0828,
            gamma1: 5.0 / 9.0,
            gamma2: 0.44,
            sigma_k1: 0.85,
            sigma_k2: 1.0,
            sigma_omega1: 0.5,
            sigma_omega2: 0.856,
            a1: 0.31,
            c1: 10.0,
            kappa: 0.41,
        }
    }
}

pub enum TurbulenceModelType {
    KEpsilon,
    KOmegaSST,
}

pub struct KOmegaSSTModel {
    constants: SSTConstants,
    a_p_k: Vec<f64>,
    a_neighbors_k: Vec<HashMap<usize, f64>>,
    b_k: Vec<f64>,
    a_p_omega: Vec<f64>,
    a_neighbors_omega: Vec<HashMap<usize, f64>>,
    b_omega: Vec<f64>,
    f1: Vec<f64>,
    f2: Vec<f64>,
}

impl KOmegaSSTModel {
    pub fn new(num_cells: usize, constants: SSTConstants) -> Self {
        KOmegaSSTModel {
            constants,
            a_p_k: vec![0.0; num_cells],
            a_neighbors_k: vec![HashMap::new(); num_cells],
            b_k: vec![0.0; num_cells],
            a_p_omega: vec![0.0; num_cells],
            a_neighbors_omega: vec![HashMap::new(); num_cells],
            b_omega: vec![0.0; num_cells],
            f1: vec![0.0; num_cells],
            f2: vec![0.0; num_cells],
        }
    }

    pub fn compute_blending_functions(
        &mut self,
        mesh: &Mesh,
        k: &[f64],
        omega: &[f64],
        nu: f64,
        wall_distance: &[f64],
    ) {
        let num_cells = mesh.num_cells();

        for cell_idx in 0..num_cells {
            let k_val = k[cell_idx].max(1e-15);
            let omega_val = omega[cell_idx].max(1e-15);
            let y = wall_distance[cell_idx].max(1e-15);

            let nu_t = k_val / omega_val;

            let arg1 = (k_val / (self.constants.beta1 * omega_val * y * y)).sqrt();
            let arg2 = (500.0 * nu) / (y * y * omega_val);
            let arg3 = (4.0 * self.constants.sigma_omega2 * k_val)
                / (self.cd_kw(cell_idx, k, omega, mesh) * y * y).max(1e-20);
            
            let arg_max = arg1.max(arg2).max(arg3);
            let f1_val = arg_max.tanh().powi(4);
            self.f1[cell_idx] = f1_val.clamp(0.0, 1.0);

            let arg_f2 = (2.0 * (k_val / (self.constants.beta1 * omega_val * y * y)).sqrt())
                .max(500.0 * nu / (y * y * omega_val));
            let f2_val = arg_f2.tanh().powi(2);
            self.f2[cell_idx] = f2_val.clamp(0.0, 1.0);
        }
    }

    fn cd_kw(&self, cell_idx: usize, k: &[f64], omega: &[f64], mesh: &Mesh) -> f64 {
        let mut grad_k = Vector3::zeros();
        let mut grad_omega = Vector3::zeros();

        for face in &mesh.faces {
            if let Some(owner) = face.owner_cell {
                if owner == cell_idx {
                    if let Some(neigh) = face.neighbor_cell {
                        let dk = k[neigh] - k[owner];
                        let domega = omega[neigh] - omega[owner];
                        grad_k += dk * face.normal;
                        grad_omega += domega * face.normal;
                    }
                }
            }
        }

        let cross_diff = grad_k.dot(&grad_omega).max(1e-20);
        2.0 * self.constants.sigma_omega2 * cross_diff / omega[cell_idx].max(1e-15)
    }

    pub fn compute_turbulent_viscosity(
        &mut self,
        k: &[f64],
        omega: &[f64],
        nu_t: &mut [f64],
    ) {
        for i in 0..k.len() {
            let k_val = k[i].max(1e-15);
            let omega_val = omega[i].max(1e-15);
            let f2_val = self.f2[i];
            
            let arg = f2_val * omega_val.max(self.constants.a1 * omega_val);
            nu_t[i] = self.constants.a1 * k_val / arg;
        }
    }

    pub fn solve<BC: BoundaryCondition>(
        &mut self,
        mesh: &Mesh,
        u: &[f64],
        v: &[f64],
        w: &[f64],
        grad_u: &[Vector3<f64>],
        grad_v: &[Vector3<f64>],
        grad_w: &[Vector3<f64>],
        k: &mut [f64],
        omega: &mut [f64],
        nu: f64,
        nu_t: &[f64],
        mass_flux: &[f64],
        volume: &[f64],
        rho: f64,
        dt: f64,
        wall_distance: &[f64],
        boundary_conditions: &[BC],
    ) {
        self.compute_blending_functions(mesh, k, omega, nu, wall_distance);

        let p_k = self.compute_production(
            mesh, u, v, w, grad_u, grad_v, grad_w, nu_t, k, omega
        );

        self.assemble_k_equation(
            mesh, k, omega, nu, nu_t, mass_flux, volume, rho, dt, &p_k,
        );
        self.solve_k(mesh, k, boundary_conditions);

        self.assemble_omega_equation(
            mesh, k, omega, nu, nu_t, mass_flux, volume, rho, dt, &p_k,
        );
        self.solve_omega(mesh, omega, boundary_conditions);
    }

    fn compute_production(
        &self,
        mesh: &Mesh,
        _u: &[f64],
        _v: &[f64],
        _w: &[f64],
        grad_u: &[Vector3<f64>],
        grad_v: &[Vector3<f64>],
        grad_w: &[Vector3<f64>],
        nu_t: &[f64],
        _k: &[f64],
        _omega: &[f64],
    ) -> Vec<f64> {
        let num_cells = mesh.num_cells();
        let mut p_k = vec![0.0; num_cells];

        for cell_idx in 0..num_cells {
            let strain_mag = self.compute_strain_magnitude(
                grad_u[cell_idx], grad_v[cell_idx], grad_w[cell_idx]
            );
            p_k[cell_idx] = nu_t[cell_idx] * strain_mag * strain_mag;
        }

        p_k
    }

    fn compute_strain_magnitude(
        &self,
        grad_u: Vector3<f64>,
        grad_v: Vector3<f64>,
        grad_w: Vector3<f64>,
    ) -> f64 {
        let s_xx = grad_u.x;
        let s_yy = grad_v.y;
        let s_zz = grad_w.z;
        let s_xy = 0.5 * (grad_u.y + grad_v.x);
        let s_xz = 0.5 * (grad_u.z + grad_w.x);
        let s_yz = 0.5 * (grad_v.z + grad_w.y);

        (2.0 * (s_xx * s_xx + s_yy * s_yy + s_zz * s_zz
            + 2.0 * s_xy * s_xy + 2.0 * s_xz * s_xz + 2.0 * s_yz * s_yz)).sqrt()
    }

    fn assemble_k_equation(
        &mut self,
        mesh: &Mesh,
        k: &[f64],
        _omega: &[f64],
        nu: f64,
        nu_t: &[f64],
        mass_flux: &[f64],
        volume: &[f64],
        rho: f64,
        dt: f64,
        p_k: &[f64],
    ) {
        let num_cells = mesh.num_cells();

        for cell_idx in 0..num_cells {
            self.a_p_k[cell_idx] = 0.0;
            self.a_neighbors_k[cell_idx].clear();
            self.b_k[cell_idx] = 0.0;
        }

        for (face_idx, face) in mesh.faces.iter().enumerate() {
            if let Some(owner) = face.owner_cell {
                let neighbor = face.neighbor_cell;
                let m_dot = mass_flux[face_idx];
                let area = face.area;

                if let Some(neigh) = neighbor {
                    let f1_val = self.f1[owner].max(self.f1[neigh]);
                    let sigma_k = f1_val * self.constants.sigma_k1 
                        + (1.0 - f1_val) * self.constants.sigma_k2;
                    let gamma_eff = nu + nu_t[owner].max(nu_t[neigh]) / sigma_k;

                    let d_cf = self.compute_distance(
                        &mesh.elements[owner].centroid,
                        &mesh.elements[neigh].centroid
                    );
                    let diffusion = rho * gamma_eff * area / d_cf.max(1e-15);

                    if m_dot > 0.0 {
                        self.a_p_k[owner] += m_dot + diffusion;
                        self.a_neighbors_k[owner].insert(neigh, -diffusion);
                        self.a_p_k[neigh] += diffusion;
                        self.a_neighbors_k[neigh].insert(owner, -m_dot - diffusion);
                    } else {
                        self.a_p_k[owner] += -m_dot + diffusion;
                        self.a_neighbors_k[owner].insert(neigh, m_dot - diffusion);
                        self.a_p_k[neigh] += diffusion;
                        self.a_neighbors_k[neigh].insert(owner, -diffusion);
                    }
                }
            }
        }

        for cell_idx in 0..num_cells {
            let transient = rho * volume[cell_idx] / dt;
            let k_val = k[cell_idx].max(1e-15);
            let f1_val = self.f1[cell_idx];
            let beta = f1_val * self.constants.beta1 + (1.0 - f1_val) * self.constants.beta2;

            self.a_p_k[cell_idx] += transient + rho * beta * k_val * volume[cell_idx];
            self.b_k[cell_idx] = transient * k_val 
                + p_k[cell_idx].min(10.0 * self.constants.beta1 * k_val * k_val) * volume[cell_idx];
        }
    }

    fn assemble_omega_equation(
        &mut self,
        mesh: &Mesh,
        k: &[f64],
        omega: &[f64],
        nu: f64,
        nu_t: &[f64],
        mass_flux: &[f64],
        volume: &[f64],
        rho: f64,
        dt: f64,
        p_k: &[f64],
    ) {
        let num_cells = mesh.num_cells();

        for cell_idx in 0..num_cells {
            self.a_p_omega[cell_idx] = 0.0;
            self.a_neighbors_omega[cell_idx].clear();
            self.b_omega[cell_idx] = 0.0;
        }

        for (face_idx, face) in mesh.faces.iter().enumerate() {
            if let Some(owner) = face.owner_cell {
                let neighbor = face.neighbor_cell;
                let m_dot = mass_flux[face_idx];
                let area = face.area;

                if let Some(neigh) = neighbor {
                    let f1_val = self.f1[owner].max(self.f1[neigh]);
                    let sigma_omega = f1_val * self.constants.sigma_omega1 
                        + (1.0 - f1_val) * self.constants.sigma_omega2;
                    let gamma_eff = nu + nu_t[owner].max(nu_t[neigh]) / sigma_omega;

                    let d_cf = self.compute_distance(
                        &mesh.elements[owner].centroid,
                        &mesh.elements[neigh].centroid
                    );
                    let diffusion = rho * gamma_eff * area / d_cf.max(1e-15);

                    if m_dot > 0.0 {
                        self.a_p_omega[owner] += m_dot + diffusion;
                        self.a_neighbors_omega[owner].insert(neigh, -diffusion);
                        self.a_p_omega[neigh] += diffusion;
                        self.a_neighbors_omega[neigh].insert(owner, -m_dot - diffusion);
                    } else {
                        self.a_p_omega[owner] += -m_dot + diffusion;
                        self.a_neighbors_omega[owner].insert(neigh, m_dot - diffusion);
                        self.a_p_omega[neigh] += diffusion;
                        self.a_neighbors_omega[neigh].insert(owner, -diffusion);
                    }
                }
            }
        }

        for cell_idx in 0..num_cells {
            let transient = rho * volume[cell_idx] / dt;
            let omega_val = omega[cell_idx].max(1e-15);
            let k_val = k[cell_idx].max(1e-15);
            let f1_val = self.f1[cell_idx];
            let gamma = f1_val * self.constants.gamma1 + (1.0 - f1_val) * self.constants.gamma2;
            let beta = f1_val * self.constants.beta1 + (1.0 - f1_val) * self.constants.beta2;

            let f2_val = self.f2[cell_idx];
            let cd_kw = self.cd_kw(cell_idx, k, omega, mesh);

            self.a_p_omega[cell_idx] += transient + rho * beta * omega_val * volume[cell_idx];
            self.b_omega[cell_idx] = transient * omega_val 
                + gamma * p_k[cell_idx] / nu_t[cell_idx].max(1e-15) * volume[cell_idx]
                + rho * (1.0 - f1_val) * cd_kw * volume[cell_idx];
        }
    }

    fn solve_k<BC: BoundaryCondition>(
        &mut self,
        mesh: &Mesh,
        k: &mut [f64],
        boundary_conditions: &[BC],
    ) {
        self.apply_k_boundary_conditions(mesh, k, boundary_conditions);

        let (mat, rhs) = self.to_csr_k();
        let precond = Ilu0Preconditioner::new_simplified(&mat);
        let config = SolverConfig::new(1e-6, 50);

        BiCGSTAB::solve(&mat, &rhs, k, &precond, &config);
    }

    fn solve_omega<BC: BoundaryCondition>(
        &mut self,
        mesh: &Mesh,
        omega: &mut [f64],
        boundary_conditions: &[BC],
    ) {
        self.apply_omega_boundary_conditions(mesh, omega, boundary_conditions);

        let (mat, rhs) = self.to_csr_omega();
        let precond = Ilu0Preconditioner::new_simplified(&mat);
        let config = SolverConfig::new(1e-6, 50);

        BiCGSTAB::solve(&mat, &rhs, omega, &precond, &config);
    }

    fn apply_k_boundary_conditions<BC: BoundaryCondition>(
        &mut self,
        mesh: &Mesh,
        _k: &[f64],
        boundary_conditions: &[BC],
    ) {
        for bc in boundary_conditions {
            for face_idx in 0..mesh.num_faces() {
                if mesh.faces[face_idx].is_boundary {
                    if let Some(owner) = mesh.faces[face_idx].owner_cell {
                        bc.apply_scalar(
                            face_idx,
                            owner,
                            &mut self.a_p_k[owner],
                            &mut self.a_neighbors_k[owner],
                            &mut self.b_k[owner],
                            0.0,
                            "k",
                        );
                    }
                }
            }
        }
    }

    fn apply_omega_boundary_conditions<BC: BoundaryCondition>(
        &mut self,
        mesh: &Mesh,
        _omega: &[f64],
        boundary_conditions: &[BC],
    ) {
        for bc in boundary_conditions {
            for face_idx in 0..mesh.num_faces() {
                if mesh.faces[face_idx].is_boundary {
                    if let Some(owner) = mesh.faces[face_idx].owner_cell {
                        bc.apply_scalar(
                            face_idx,
                            owner,
                            &mut self.a_p_omega[owner],
                            &mut self.a_neighbors_omega[owner],
                            &mut self.b_omega[owner],
                            0.0,
                            "omega",
                        );
                    }
                }
            }
        }
    }

    fn to_csr_k(&self) -> (linear_system_solver::CsrMatrix, Vec<f64>) {
        let n = self.a_p_k.len();
        let mut rows = Vec::new();
        let mut cols = Vec::new();
        let mut values = Vec::new();

        for i in 0..n {
            rows.push(i);
            cols.push(i);
            values.push(self.a_p_k[i]);

            for (&j, &val) in &self.a_neighbors_k[i] {
                rows.push(i);
                cols.push(j);
                values.push(val);
            }
        }

        let mat = linear_system_solver::CsrMatrix::from_triplets(n, n, &rows, &cols, &values);
        (mat, self.b_k.clone())
    }

    fn to_csr_omega(&self) -> (linear_system_solver::CsrMatrix, Vec<f64>) {
        let n = self.a_p_omega.len();
        let mut rows = Vec::new();
        let mut cols = Vec::new();
        let mut values = Vec::new();

        for i in 0..n {
            rows.push(i);
            cols.push(i);
            values.push(self.a_p_omega[i]);

            for (&j, &val) in &self.a_neighbors_omega[i] {
                rows.push(i);
                cols.push(j);
                values.push(val);
            }
        }

        let mat = linear_system_solver::CsrMatrix::from_triplets(n, n, &rows, &cols, &values);
        (mat, self.b_omega.clone())
    }

    fn compute_distance(&self, p1: &nalgebra::Point3<f64>, p2: &nalgebra::Point3<f64>) -> f64 {
        (p1 - p2).norm()
    }
}

pub fn compute_wall_distance(mesh: &Mesh) -> Vec<f64> {
    let num_cells = mesh.num_cells();
    let mut wall_distance = vec![f64::INFINITY; num_cells];

    let mut boundary_centroids = Vec::new();
    for face in &mesh.faces {
        if face.is_boundary {
            boundary_centroids.push(face.centroid);
        }
    }

    if boundary_centroids.is_empty() {
        return vec![1.0; num_cells];
    }

    for (cell_idx, cell) in mesh.elements.iter().enumerate() {
        for bc in &boundary_centroids {
            let dist = (cell.centroid - bc).norm();
            wall_distance[cell_idx] = wall_distance[cell_idx].min(dist);
        }
    }

    wall_distance
}
