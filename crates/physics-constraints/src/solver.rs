use slotmap::SlotMap;

use physics_core::{Body, BodyHandle};

use crate::constraint::{Constraint, ConstraintSolverData};

pub struct ConstraintSolver {
    pub velocity_iterations: usize,
    pub position_iterations: usize,
}

impl Default for ConstraintSolver {
    fn default() -> Self {
        ConstraintSolver {
            velocity_iterations: 8,
            position_iterations: 3,
        }
    }
}

impl ConstraintSolver {
    pub fn new(velocity_iterations: usize, position_iterations: usize) -> Self {
        ConstraintSolver {
            velocity_iterations,
            position_iterations,
        }
    }

    pub fn solve<C: Constraint>(
        &self,
        constraints: &mut [C],
        bodies: &mut SlotMap<BodyHandle, Body>,
        dt: f32,
    ) {
        if constraints.is_empty() {
            return;
        }

        let inv_dt = if dt > f32::EPSILON { 1.0 / dt } else { 0.0 };

        let mut data = ConstraintSolverData { bodies, dt, inv_dt };

        for constraint in constraints.iter_mut() {
            constraint.prepare(&data);
        }

        for _ in 0..self.velocity_iterations {
            for constraint in constraints.iter_mut() {
                constraint.solve_velocity(&mut data);
            }
        }

        for _ in 0..self.position_iterations {
            let mut all_solved = true;
            for constraint in constraints.iter_mut() {
                if !constraint.solve_position(&mut data) {
                    all_solved = false;
                }
            }
            if all_solved {
                break;
            }
        }
    }
}
