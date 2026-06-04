use std::collections::HashMap;
use nalgebra::Vector3;
use log::info;

use mesh_generator::Mesh;
use linear_system_solver::{BiCGSTAB, GMRES, Ilu0Preconditioner, SolverConfig, SolverResult};
use boundary_condition::{BoundaryCondition, Periodic};

use crate::fields::{FlowFields, GradientFields};
use crate::equations::{MomentumEquation, PressureCorrectionEquation};
use crate::schemes::ConvectionScheme;

#[derive(Debug, Clone)]
pub struct SolverParameters {
    pub rho: f64,
    pub nu: f64,
    pub dt: f64,
    pub max_iter: usize,
    pub p_corr_max_iter: usize,
    pub momentum_max_iter: usize,
    pub tol: f64,
    pub p_relax: f64,
    pub u_relax: f64,
}

impl Default for SolverParameters {
    fn default() -> Self {
        SolverParameters {
            rho: 1.225,
            nu: 1.5e-5,
            dt: 0.1,
            max_iter: 1000,
            p_corr_max_iter: 100,
            momentum_max_iter: 50,
            tol: 1e-6,
            p_relax: 0.3,
            u_relax: 0.7,
        }
    }
}

pub struct NavierStokesSolver {
    params: SolverParameters,
    momentum_eq_u: MomentumEquation,
    momentum_eq_v: MomentumEquation,
    momentum_eq_w: MomentumEquation,
    pressure_corr_eq: PressureCorrectionEquation,
}

impl NavierStokesSolver {
    pub fn new(num_cells: usize, params: SolverParameters) -> Self {
        NavierStokesSolver {
            params,
            momentum_eq_u: MomentumEquation::new(num_cells),
            momentum_eq_v: MomentumEquation::new(num_cells),
            momentum_eq_w: MomentumEquation::new(num_cells),
            pressure_corr_eq: PressureCorrectionEquation::new(num_cells),
        }
    }

    pub fn solve<S: ConvectionScheme, BC: BoundaryCondition>(
        &mut self,
        mesh: &Mesh,
        fields: &mut FlowFields,
        grad_fields: &mut GradientFields,
        scheme: &S,
        boundary_conditions: &[BC],
    ) -> bool {
        let num_cells = mesh.num_cells();
        let volume: Vec<f64> = mesh.cell_volumes();
        
        let mut residuals = Vec::new();
        let mut converged = false;

        self.compute_mass_flux(mesh, fields);

        for iter in 0..self.params.max_iter {
            let (res_u, res_v, res_w) = self.solve_momentum(
                mesh,
                fields,
                grad_fields,
                &volume,
                boundary_conditions,
            );

            let res_p = self.solve_pressure_correction(
                mesh,
                fields,
                grad_fields,
                &volume,
                boundary_conditions,
            );

            self.correct_velocity(mesh, fields, grad_fields);
            self.correct_pressure(fields);
            self.compute_mass_flux(mesh, fields);

            let max_res = res_u.max(res_v).max(res_w).max(res_p);
            residuals.push(max_res);

            if iter % 10 == 0 {
                info!(
                    "Iteration {}: residuals: u={:.2e}, v={:.2e}, w={:.2e}, p={:.2e}",
                    iter, res_u, res_v, res_w, res_p
                );
            }

            if max_res < self.params.tol {
                info!("Converged in {} iterations", iter);
                converged = true;
                break;
            }
        }

        converged
    }

    fn solve_momentum<BC: BoundaryCondition>(
        &mut self,
        mesh: &Mesh,
        fields: &mut FlowFields,
        grad_fields: &mut GradientFields,
        volume: &[f64],
        boundary_conditions: &[BC],
    ) -> (f64, f64, f64) {
        self.compute_gradients(mesh, fields, grad_fields);

        self.momentum_eq_u.assemble(
            mesh,
            &fields.u,
            &grad_fields.grad_u,
            &fields.mass_flux,
            self.params.nu,
            &fields.nu_t,
            &grad_fields.grad_p,
            volume,
            self.params.rho,
            self.params.dt,
        );

        self.momentum_eq_v.assemble(
            mesh,
            &fields.v,
            &grad_fields.grad_v,
            &fields.mass_flux,
            self.params.nu,
            &fields.nu_t,
            &grad_fields.grad_p,
            volume,
            self.params.rho,
            self.params.dt,
        );

        self.momentum_eq_w.assemble(
            mesh,
            &fields.w,
            &grad_fields.grad_w,
            &fields.mass_flux,
            self.params.nu,
            &fields.nu_t,
            &grad_fields.grad_p,
            volume,
            self.params.rho,
            self.params.dt,
        );

        self.apply_momentum_boundary_conditions(mesh, fields, boundary_conditions);

        let tol = self.params.tol;
        let momentum_max_iter = self.params.momentum_max_iter;

        let res_u = Self::solve_momentum_component(&mut fields.u, &mut self.momentum_eq_u, tol, momentum_max_iter);
        let res_v = Self::solve_momentum_component(&mut fields.v, &mut self.momentum_eq_v, tol, momentum_max_iter);
        let res_w = Self::solve_momentum_component(&mut fields.w, &mut self.momentum_eq_w, tol, momentum_max_iter);

        self.relax_velocity(fields);

        (res_u, res_v, res_w)
    }

    fn solve_momentum_component(
        field: &mut [f64],
        equation: &mut MomentumEquation,
        tol: f64,
        max_iter: usize,
    ) -> f64 {
        let (mat, rhs) = equation.to_csr();
        let precond = Ilu0Preconditioner::new_simplified(&mat);
        let config = SolverConfig::new(tol, max_iter);
        
        let result = BiCGSTAB::solve(&mat, &rhs, field, &precond, &config);
        result.residual
    }

    fn solve_pressure_correction<BC: BoundaryCondition>(
        &mut self,
        mesh: &Mesh,
        fields: &mut FlowFields,
        grad_fields: &mut GradientFields,
        volume: &[f64],
        boundary_conditions: &[BC],
    ) -> f64 {
        let periodic_ref = boundary_conditions.iter()
            .find(|bc| bc.boundary_type() == boundary_condition::BoundaryType::Periodic);

        let periodic = periodic_ref.and_then(|bc| {
            bc.as_any().downcast_ref::<Periodic>()
        });

        self.pressure_corr_eq.assemble_with_periodic(
            mesh,
            &fields.mass_flux,
            &self.momentum_eq_u.d,
            &self.momentum_eq_v.d,
            &self.momentum_eq_w.d,
            self.params.rho,
            periodic,
        );

        self.apply_pressure_boundary_conditions(mesh, fields, boundary_conditions);

        for pc in fields.p_corr.iter_mut() {
            *pc = 0.0;
        }

        let (mat, rhs) = self.pressure_corr_eq.to_csr();
        let precond = Ilu0Preconditioner::new_simplified(&mat);
        let config = SolverConfig::new(self.params.tol, self.params.p_corr_max_iter);
        
        let result = BiCGSTAB::solve(&mat, &rhs, &mut fields.p_corr, &precond, &config);
        result.residual
    }

    fn correct_velocity(
        &mut self,
        mesh: &Mesh,
        fields: &mut FlowFields,
        grad_fields: &mut GradientFields,
    ) {
        self.compute_gradient(mesh, &fields.p_corr, &mut grad_fields.grad_p);

        for cell_idx in 0..mesh.num_cells() {
            let d_u = self.momentum_eq_u.d[cell_idx];
            let d_v = self.momentum_eq_v.d[cell_idx];
            let d_w = self.momentum_eq_w.d[cell_idx];
            let grad_p_corr = grad_fields.grad_p[cell_idx];

            fields.u[cell_idx] -= d_u * grad_p_corr.x;
            fields.v[cell_idx] -= d_v * grad_p_corr.y;
            fields.w[cell_idx] -= d_w * grad_p_corr.z;
        }
    }

    fn correct_pressure(&mut self, fields: &mut FlowFields) {
        for cell_idx in 0..fields.p.len() {
            fields.p[cell_idx] += self.params.p_relax * fields.p_corr[cell_idx];
        }
    }

    fn relax_velocity(&mut self, fields: &mut FlowFields) {
        for cell_idx in 0..fields.u.len() {
            fields.u[cell_idx] *= self.params.u_relax;
            fields.v[cell_idx] *= self.params.u_relax;
            fields.w[cell_idx] *= self.params.u_relax;
        }
    }

    fn compute_mass_flux(&self, mesh: &Mesh, fields: &mut FlowFields) {
        for (face_idx, face) in mesh.faces.iter().enumerate() {
            if let Some(owner) = face.owner_cell {
                let neighbor = face.neighbor_cell;
                let lambda = 0.5;

                let u_face = if let Some(neigh) = neighbor {
                    lambda * fields.u[owner] + (1.0 - lambda) * fields.u[neigh]
                } else {
                    fields.u[owner]
                };

                let v_face = if let Some(neigh) = neighbor {
                    lambda * fields.v[owner] + (1.0 - lambda) * fields.v[neigh]
                } else {
                    fields.v[owner]
                };

                let w_face = if let Some(neigh) = neighbor {
                    lambda * fields.w[owner] + (1.0 - lambda) * fields.w[neigh]
                } else {
                    fields.w[owner]
                };

                fields.u_face[face_idx] = u_face;
                fields.v_face[face_idx] = v_face;
                fields.w_face[face_idx] = w_face;

                let vel_dot_n = u_face * face.normal.x + v_face * face.normal.y + w_face * face.normal.z;
                fields.mass_flux[face_idx] = self.params.rho * vel_dot_n * face.area;
            }
        }
    }

    fn compute_gradients(&self, mesh: &Mesh, fields: &FlowFields, grad_fields: &mut GradientFields) {
        self.compute_gradient(mesh, &fields.u, &mut grad_fields.grad_u);
        self.compute_gradient(mesh, &fields.v, &mut grad_fields.grad_v);
        self.compute_gradient(mesh, &fields.w, &mut grad_fields.grad_w);
        self.compute_gradient(mesh, &fields.p, &mut grad_fields.grad_p);
        self.compute_gradient(mesh, &fields.k, &mut grad_fields.grad_k);
        self.compute_gradient(mesh, &fields.epsilon, &mut grad_fields.grad_epsilon);
        self.compute_gradient(mesh, &fields.omega, &mut grad_fields.grad_omega);
    }

    fn compute_gradient(&self, mesh: &Mesh, phi: &[f64], grad_phi: &mut [Vector3<f64>]) {
        let num_cells = mesh.num_cells();
        
        for cell_idx in 0..num_cells {
            grad_phi[cell_idx] = Vector3::zeros();
        }

        for face in &mesh.faces {
            if let (Some(owner), Some(neigh)) = (face.owner_cell, face.neighbor_cell) {
                let phi_f = 0.5 * (phi[owner] + phi[neigh]);
                let flux = phi_f * face.area;
                
                grad_phi[owner] += flux * face.normal;
                grad_phi[neigh] -= flux * face.normal;
            }
        }

        for cell_idx in 0..num_cells {
            let vol = mesh.elements[cell_idx].volume;
            if vol > 1e-15 {
                grad_phi[cell_idx] /= vol;
            }
        }
    }

    fn apply_momentum_boundary_conditions<BC: BoundaryCondition>(
        &mut self,
        mesh: &Mesh,
        fields: &FlowFields,
        boundary_conditions: &[BC],
    ) {
        for bc in boundary_conditions {
            for face_idx in 0..mesh.num_faces() {
                if mesh.faces[face_idx].is_boundary {
                    if let Some(owner) = mesh.faces[face_idx].owner_cell {
                        let vel = fields.velocity(owner);
                        
                        bc.apply_velocity(
                            face_idx,
                            owner,
                            &mut self.momentum_eq_u.a_p[owner],
                            &mut self.momentum_eq_u.a_neighbors[owner],
                            &mut self.momentum_eq_u.b[owner],
                            &vel,
                        );
                        
                        bc.apply_velocity(
                            face_idx,
                            owner,
                            &mut self.momentum_eq_v.a_p[owner],
                            &mut self.momentum_eq_v.a_neighbors[owner],
                            &mut self.momentum_eq_v.b[owner],
                            &vel,
                        );
                        
                        bc.apply_velocity(
                            face_idx,
                            owner,
                            &mut self.momentum_eq_w.a_p[owner],
                            &mut self.momentum_eq_w.a_neighbors[owner],
                            &mut self.momentum_eq_w.b[owner],
                            &vel,
                        );
                    }
                }
            }
        }
    }

    fn apply_pressure_boundary_conditions<BC: BoundaryCondition>(
        &mut self,
        mesh: &Mesh,
        fields: &FlowFields,
        boundary_conditions: &[BC],
    ) {
        for bc in boundary_conditions {
            for face_idx in 0..mesh.num_faces() {
                if mesh.faces[face_idx].is_boundary {
                    if let Some(owner) = mesh.faces[face_idx].owner_cell {
                        bc.apply_pressure(
                            face_idx,
                            owner,
                            &mut self.pressure_corr_eq.a_p[owner],
                            &mut self.pressure_corr_eq.a_neighbors[owner],
                            &mut self.pressure_corr_eq.b[owner],
                            fields.p[owner],
                        );
                    }
                }
            }
        }
    }

    pub fn add_transient_contribution(
        &mut self,
        cell_idx: usize,
        transient_coeff: f64,
        rhs_u: f64,
        rhs_v: f64,
        rhs_w: f64,
    ) {
        self.momentum_eq_u.a_p[cell_idx] += transient_coeff;
        self.momentum_eq_u.b[cell_idx] += rhs_u;
        
        self.momentum_eq_v.a_p[cell_idx] += transient_coeff;
        self.momentum_eq_v.b[cell_idx] += rhs_v;
        
        self.momentum_eq_w.a_p[cell_idx] += transient_coeff;
        self.momentum_eq_w.b[cell_idx] += rhs_w;
    }
}
