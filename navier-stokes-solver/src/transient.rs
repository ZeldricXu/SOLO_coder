use std::collections::HashMap;
use nalgebra::Vector3;
use log::info;

use mesh_generator::Mesh;
use boundary_condition::BoundaryCondition;

use crate::fields::{FlowFields, GradientFields};
use crate::equations::MomentumEquation;
use crate::schemes::ConvectionScheme;
use crate::solver::{NavierStokesSolver, SolverParameters};

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum TimeDiscretization {
    FirstOrderImplicit,
    SecondOrderImplicit,
}

#[derive(Debug, Clone)]
pub struct TransientParameters {
    pub base: SolverParameters,
    pub time_discretization: TimeDiscretization,
    pub total_time: f64,
    pub physical_dt: f64,
    pub max_inner_iter: usize,
    pub inner_tol: f64,
}

impl Default for TransientParameters {
    fn default() -> Self {
        TransientParameters {
            base: SolverParameters::default(),
            time_discretization: TimeDiscretization::FirstOrderImplicit,
            total_time: 1.0,
            physical_dt: 0.01,
            max_inner_iter: 50,
            inner_tol: 1e-4,
        }
    }
}

pub struct TransientSolver {
    steady_solver: NavierStokesSolver,
    params: TransientParameters,
    u_prev: Vec<f64>,
    v_prev: Vec<f64>,
    w_prev: Vec<f64>,
    u_prev_prev: Vec<f64>,
    v_prev_prev: Vec<f64>,
    w_prev_prev: Vec<f64>,
    p_prev: Vec<f64>,
    current_time: f64,
    time_step: usize,
}

impl TransientSolver {
    pub fn new(num_cells: usize, params: TransientParameters) -> Self {
        let steady_solver = NavierStokesSolver::new(num_cells, params.base.clone());
        
        TransientSolver {
            steady_solver,
            params,
            u_prev: vec![0.0; num_cells],
            v_prev: vec![0.0; num_cells],
            w_prev: vec![0.0; num_cells],
            u_prev_prev: vec![0.0; num_cells],
            v_prev_prev: vec![0.0; num_cells],
            w_prev_prev: vec![0.0; num_cells],
            p_prev: vec![0.0; num_cells],
            current_time: 0.0,
            time_step: 0,
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
        let num_time_steps = (self.params.total_time / self.params.physical_dt).ceil() as usize;
        
        info!("Starting transient simulation:");
        info!("  Total time: {:.4} s", self.params.total_time);
        info!("  Time step: {:.4} s", self.params.physical_dt);
        info!("  Number of steps: {}", num_time_steps);
        info!("  Time discretization: {:?}", self.params.time_discretization);

        while self.current_time < self.params.total_time {
            self.time_step += 1;
            let dt = self.params.physical_dt.min(self.params.total_time - self.current_time);
            
            info!(
                "Time step {}: t = {:.4} / {:.4} s, dt = {:.4} s",
                self.time_step, self.current_time + dt, self.params.total_time, dt
            );

            self.apply_time_discretization(mesh, fields);

            let mut inner_iter = 0;
            let mut converged = false;

            while inner_iter < self.params.max_inner_iter && !converged {
                let inner_converged = self.steady_solver.solve(
                    mesh,
                    fields,
                    grad_fields,
                    scheme,
                    boundary_conditions,
                );

                if inner_converged {
                    converged = true;
                }
                inner_iter += 1;
            }

            info!(
                "  Inner iterations: {}, converged: {}",
                inner_iter, converged
            );

            self.update_previous_fields(fields);
            self.current_time += dt;
        }

        info!("Transient simulation completed!");
        true
    }

    fn apply_time_discretization(
        &mut self,
        mesh: &Mesh,
        fields: &mut FlowFields,
    ) {
        let num_cells = mesh.num_cells();
        let volume = mesh.cell_volumes();
        let dt = self.params.physical_dt;
        let rho = self.params.base.rho;

        match self.params.time_discretization {
            TimeDiscretization::FirstOrderImplicit => {
                for cell_idx in 0..num_cells {
                    let vol = volume[cell_idx];
                    let coeff = rho * vol / dt;

                    let transient_rhs_u = coeff * self.u_prev[cell_idx];
                    let transient_rhs_v = coeff * self.v_prev[cell_idx];
                    let transient_rhs_w = coeff * self.w_prev[cell_idx];

                    self.steady_solver.add_transient_contribution(
                        cell_idx,
                        coeff,
                        transient_rhs_u,
                        transient_rhs_v,
                        transient_rhs_w,
                    );
                }
            }
            TimeDiscretization::SecondOrderImplicit => {
                for cell_idx in 0..num_cells {
                    let vol = volume[cell_idx];
                    let coeff = 1.5 * rho * vol / dt;

                    let rhs_u = (2.0 * self.u_prev[cell_idx] - 0.5 * self.u_prev_prev[cell_idx]) 
                        * rho * vol / dt;
                    let rhs_v = (2.0 * self.v_prev[cell_idx] - 0.5 * self.v_prev_prev[cell_idx]) 
                        * rho * vol / dt;
                    let rhs_w = (2.0 * self.w_prev[cell_idx] - 0.5 * self.w_prev_prev[cell_idx]) 
                        * rho * vol / dt;

                    self.steady_solver.add_transient_contribution(
                        cell_idx,
                        coeff,
                        rhs_u,
                        rhs_v,
                        rhs_w,
                    );
                }
            }
        }
    }

    fn update_previous_fields(&mut self, fields: &FlowFields) {
        match self.params.time_discretization {
            TimeDiscretization::FirstOrderImplicit => {
                self.u_prev.copy_from_slice(&fields.u);
                self.v_prev.copy_from_slice(&fields.v);
                self.w_prev.copy_from_slice(&fields.w);
                self.p_prev.copy_from_slice(&fields.p);
            }
            TimeDiscretization::SecondOrderImplicit => {
                self.u_prev_prev.copy_from_slice(&self.u_prev);
                self.v_prev_prev.copy_from_slice(&self.v_prev);
                self.w_prev_prev.copy_from_slice(&self.w_prev);
                self.u_prev.copy_from_slice(&fields.u);
                self.v_prev.copy_from_slice(&fields.v);
                self.w_prev.copy_from_slice(&fields.w);
                self.p_prev.copy_from_slice(&fields.p);
            }
        }
    }

    pub fn current_time(&self) -> f64 {
        self.current_time
    }

    pub fn time_step(&self) -> usize {
        self.time_step
    }

    pub fn set_physical_dt(&mut self, dt: f64) {
        self.params.physical_dt = dt;
    }

    pub fn get_steady_solver(&self) -> &NavierStokesSolver {
        &self.steady_solver
    }

    pub fn get_steady_solver_mut(&mut self) -> &mut NavierStokesSolver {
        &mut self.steady_solver
    }
}
