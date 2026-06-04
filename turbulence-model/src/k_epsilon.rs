use nalgebra::Vector3;
use std::collections::HashMap;

use mesh_generator::Mesh;
use linear_system_solver::{BiCGSTAB, Ilu0Preconditioner, SolverConfig};
use boundary_condition::BoundaryCondition;

#[derive(Debug, Clone, Copy)]
pub struct TurbulenceConstants {
    pub c_mu: f64,
    pub c1_epsilon: f64,
    pub c2_epsilon: f64,
    pub sigma_k: f64,
    pub sigma_epsilon: f64,
    pub kappa: f64,
    pub e: f64,
}

impl Default for TurbulenceConstants {
    fn default() -> Self {
        TurbulenceConstants {
            c_mu: 0.09,
            c1_epsilon: 1.44,
            c2_epsilon: 1.92,
            sigma_k: 1.0,
            sigma_epsilon: 1.3,
            kappa: 0.41,
            e: 9.793,
        }
    }
}

pub struct KEpsilonModel {
    constants: TurbulenceConstants,
    a_p_k: Vec<f64>,
    a_neighbors_k: Vec<HashMap<usize, f64>>,
    b_k: Vec<f64>,
    a_p_epsilon: Vec<f64>,
    a_neighbors_epsilon: Vec<HashMap<usize, f64>>,
    b_epsilon: Vec<f64>,
}

impl KEpsilonModel {
    pub fn new(num_cells: usize, constants: TurbulenceConstants) -> Self {
        KEpsilonModel {
            constants,
            a_p_k: vec![0.0; num_cells],
            a_neighbors_k: vec![HashMap::new(); num_cells],
            b_k: vec![0.0; num_cells],
            a_p_epsilon: vec![0.0; num_cells],
            a_neighbors_epsilon: vec![HashMap::new(); num_cells],
            b_epsilon: vec![0.0; num_cells],
        }
    }

    pub fn compute_turbulent_viscosity(
        &mut self,
        k: &[f64],
        epsilon: &[f64],
        nu_t: &mut [f64],
    ) {
        for i in 0..k.len() {
            let k_val = k[i].max(1e-10);
            let eps_val = epsilon[i].max(1e-10);
            nu_t[i] = self.constants.c_mu * k_val * k_val / eps_val;
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
        epsilon: &mut [f64],
        nu: f64,
        nu_t: &[f64],
        mass_flux: &[f64],
        volume: &[f64],
        rho: f64,
        dt: f64,
        boundary_conditions: &[BC],
    ) {
        let (p_k, g_k) = self.compute_production_terms(
            mesh,
            u,
            v,
            w,
            grad_u,
            grad_v,
            grad_w,
            nu_t,
            volume,
        );

        self.assemble_k_equation(
            mesh,
            k,
            nu,
            nu_t,
            &p_k,
            mass_flux,
            volume,
            rho,
            dt,
        );

        self.assemble_epsilon_equation(
            mesh,
            k,
            epsilon,
            nu,
            nu_t,
            &p_k,
            mass_flux,
            volume,
            rho,
            dt,
        );

        self.apply_boundary_conditions(mesh, k, epsilon, boundary_conditions);

        self.solve_k_equation(k);
        self.solve_epsilon_equation(epsilon);

        self.clip_values(k, epsilon);
    }

    fn compute_production_terms(
        &self,
        mesh: &Mesh,
        u: &[f64],
        v: &[f64],
        w: &[f64],
        grad_u: &[Vector3<f64>],
        grad_v: &[Vector3<f64>],
        grad_w: &[Vector3<f64>],
        nu_t: &[f64],
        volume: &[f64],
    ) -> (Vec<f64>, Vec<f64>) {
        let num_cells = mesh.num_cells();
        let mut p_k = vec![0.0; num_cells];
        let mut g_k = vec![0.0; num_cells];

        for cell_idx in 0..num_cells {
            let du_dx = grad_u[cell_idx].x;
            let du_dy = grad_u[cell_idx].y;
            let du_dz = grad_u[cell_idx].z;
            let dv_dx = grad_v[cell_idx].x;
            let dv_dy = grad_v[cell_idx].y;
            let dv_dz = grad_v[cell_idx].z;
            let dw_dx = grad_w[cell_idx].x;
            let dw_dy = grad_w[cell_idx].y;
            let dw_dz = grad_w[cell_idx].z;

            let s_ij_2 = 2.0 * (
                du_dx * du_dx + dv_dy * dv_dy + dw_dz * dw_dz
                + 0.5 * ((du_dy + dv_dx) * (du_dy + dv_dx)
                      + (du_dz + dw_dx) * (du_dz + dw_dx)
                      + (dv_dz + dw_dy) * (dv_dz + dw_dy))
            );

            p_k[cell_idx] = nu_t[cell_idx] * s_ij_2 * volume[cell_idx];
            g_k[cell_idx] = 0.0;
        }

        (p_k, g_k)
    }

    fn assemble_k_equation(
        &mut self,
        mesh: &Mesh,
        k: &[f64],
        nu: f64,
        nu_t: &[f64],
        p_k: &[f64],
        mass_flux: &[f64],
        volume: &[f64],
        rho: f64,
        dt: f64,
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
                let area = face.area;
                let m_dot = mass_flux[face_idx];

                let nu_eff_owner = nu + nu_t[owner] / self.constants.sigma_k;
                let nu_eff_neigh = neighbor.map(|n| nu + nu_t[n] / self.constants.sigma_k).unwrap_or(nu_eff_owner);
                let nu_eff = 0.5 * (nu_eff_owner + nu_eff_neigh);

                let d_cf = if let Some(neigh) = neighbor {
                    (face.centroid - mesh.elements[owner].centroid).norm()
                } else {
                    (face.centroid - mesh.elements[owner].centroid).norm()
                };

                let diffusion = rho * nu_eff * area / d_cf;

                if let Some(neigh) = neighbor {
                    if m_dot > 0.0 {
                        self.a_p_k[owner] += m_dot + diffusion;
                        *self.a_neighbors_k[owner].entry(neigh).or_insert(0.0) -= diffusion;
                        self.a_p_k[neigh] += diffusion;
                        *self.a_neighbors_k[neigh].entry(owner).or_insert(0.0) -= m_dot + diffusion;
                    } else {
                        self.a_p_k[owner] += -m_dot + diffusion;
                        *self.a_neighbors_k[owner].entry(neigh).or_insert(0.0) += m_dot - diffusion;
                        self.a_p_k[neigh] += diffusion;
                        *self.a_neighbors_k[neigh].entry(owner).or_insert(0.0) -= diffusion;
                    }
                } else {
                    self.a_p_k[owner] += m_dot.abs() + diffusion;
                }
            }
        }

        for cell_idx in 0..num_cells {
            let eps_val = 1e-10;
            let transient = rho * volume[cell_idx] / dt;
            let destruction = rho * volume[cell_idx] * eps_val / k[cell_idx].max(1e-10);
            
            self.a_p_k[cell_idx] += transient + destruction;
            self.b_k[cell_idx] = transient * k[cell_idx] + p_k[cell_idx];
        }
    }

    fn assemble_epsilon_equation(
        &mut self,
        mesh: &Mesh,
        k: &[f64],
        epsilon: &[f64],
        nu: f64,
        nu_t: &[f64],
        p_k: &[f64],
        mass_flux: &[f64],
        volume: &[f64],
        rho: f64,
        dt: f64,
    ) {
        let num_cells = mesh.num_cells();
        
        for cell_idx in 0..num_cells {
            self.a_p_epsilon[cell_idx] = 0.0;
            self.a_neighbors_epsilon[cell_idx].clear();
            self.b_epsilon[cell_idx] = 0.0;
        }

        for (face_idx, face) in mesh.faces.iter().enumerate() {
            if let Some(owner) = face.owner_cell {
                let neighbor = face.neighbor_cell;
                let area = face.area;
                let m_dot = mass_flux[face_idx];

                let nu_eff_owner = nu + nu_t[owner] / self.constants.sigma_epsilon;
                let nu_eff_neigh = neighbor.map(|n| nu + nu_t[n] / self.constants.sigma_epsilon).unwrap_or(nu_eff_owner);
                let nu_eff = 0.5 * (nu_eff_owner + nu_eff_neigh);

                let d_cf = if let Some(neigh) = neighbor {
                    (face.centroid - mesh.elements[owner].centroid).norm()
                } else {
                    (face.centroid - mesh.elements[owner].centroid).norm()
                };

                let diffusion = rho * nu_eff * area / d_cf;

                if let Some(neigh) = neighbor {
                    if m_dot > 0.0 {
                        self.a_p_epsilon[owner] += m_dot + diffusion;
                        *self.a_neighbors_epsilon[owner].entry(neigh).or_insert(0.0) -= diffusion;
                        self.a_p_epsilon[neigh] += diffusion;
                        *self.a_neighbors_epsilon[neigh].entry(owner).or_insert(0.0) -= m_dot + diffusion;
                    } else {
                        self.a_p_epsilon[owner] += -m_dot + diffusion;
                        *self.a_neighbors_epsilon[owner].entry(neigh).or_insert(0.0) += m_dot - diffusion;
                        self.a_p_epsilon[neigh] += diffusion;
                        *self.a_neighbors_epsilon[neigh].entry(owner).or_insert(0.0) -= diffusion;
                    }
                } else {
                    self.a_p_epsilon[owner] += m_dot.abs() + diffusion;
                }
            }
        }

        for cell_idx in 0..num_cells {
            let k_val = k[cell_idx].max(1e-10);
            let eps_val = epsilon[cell_idx].max(1e-10);
            
            let transient = rho * volume[cell_idx] / dt;
            let production = self.constants.c1_epsilon * p_k[cell_idx] * eps_val / k_val;
            let destruction = rho * self.constants.c2_epsilon * volume[cell_idx] * eps_val / k_val;
            
            self.a_p_epsilon[cell_idx] += transient + destruction;
            self.b_epsilon[cell_idx] = transient * epsilon[cell_idx] + production;
        }
    }

    fn apply_boundary_conditions<BC: BoundaryCondition>(
        &mut self,
        mesh: &Mesh,
        k: &[f64],
        epsilon: &[f64],
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
                            k[owner],
                            "k",
                        );
                        
                        bc.apply_scalar(
                            face_idx,
                            owner,
                            &mut self.a_p_epsilon[owner],
                            &mut self.a_neighbors_epsilon[owner],
                            &mut self.b_epsilon[owner],
                            epsilon[owner],
                            "epsilon",
                        );
                    }
                }
            }
        }
    }

    fn solve_k_equation(&mut self, k: &mut [f64]) {
        let (mat, rhs) = self.to_csr_k();
        let precond = Ilu0Preconditioner::new_simplified(&mat);
        let config = SolverConfig::new(1e-6, 100);
        
        BiCGSTAB::solve(&mat, &rhs, k, &precond, &config);
    }

    fn solve_epsilon_equation(&mut self, epsilon: &mut [f64]) {
        let (mat, rhs) = self.to_csr_epsilon();
        let precond = Ilu0Preconditioner::new_simplified(&mat);
        let config = SolverConfig::new(1e-6, 100);
        
        BiCGSTAB::solve(&mat, &rhs, epsilon, &precond, &config);
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
                if val.abs() > 1e-15 {
                    rows.push(i);
                    cols.push(j);
                    values.push(val);
                }
            }
        }

        let mat = linear_system_solver::CsrMatrix::from_triplets(n, n, &rows, &cols, &values);
        (mat, self.b_k.clone())
    }

    fn to_csr_epsilon(&self) -> (linear_system_solver::CsrMatrix, Vec<f64>) {
        let n = self.a_p_epsilon.len();
        let mut rows = Vec::new();
        let mut cols = Vec::new();
        let mut values = Vec::new();

        for i in 0..n {
            rows.push(i);
            cols.push(i);
            values.push(self.a_p_epsilon[i]);

            for (&j, &val) in &self.a_neighbors_epsilon[i] {
                if val.abs() > 1e-15 {
                    rows.push(i);
                    cols.push(j);
                    values.push(val);
                }
            }
        }

        let mat = linear_system_solver::CsrMatrix::from_triplets(n, n, &rows, &cols, &values);
        (mat, self.b_epsilon.clone())
    }

    fn clip_values(&self, k: &mut [f64], epsilon: &mut [f64]) {
        for i in 0..k.len() {
            k[i] = k[i].max(1e-10);
            epsilon[i] = epsilon[i].max(1e-10);
        }
    }
}

pub mod matrix {
    pub use linear_system_solver::CsrMatrix;
}
