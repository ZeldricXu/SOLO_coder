use slotmap::SlotMap;

use physics_core::{Body, BodyHandle};

use crate::constraint::{Constraint, ConstraintSolveStep, ConstraintSolverData};

pub struct ConstraintSolver {
    pub velocity_iterations: usize,
    pub position_iterations: usize,
    pub use_warm_starting: bool,
}

impl Default for ConstraintSolver {
    fn default() -> Self {
        ConstraintSolver {
            velocity_iterations: 8,
            position_iterations: 3,
            use_warm_starting: true,
        }
    }
}

impl ConstraintSolver {
    pub fn new(velocity_iterations: usize, position_iterations: usize) -> Self {
        ConstraintSolver {
            velocity_iterations,
            position_iterations,
            use_warm_starting: true,
        }
    }

    pub fn with_iterations(velocity_iterations: usize, position_iterations: usize) -> Self {
        Self::new(velocity_iterations, position_iterations)
    }

    pub fn solve(
        &self,
        constraints: &mut [&mut dyn Constraint],
        bodies: &mut SlotMap<BodyHandle, Body>,
        dt: f32,
    ) {
        if constraints.is_empty() {
            return;
        }

        let inv_dt = if dt > f32::EPSILON { 1.0 / dt } else { 0.0 };

        let mut data = ConstraintSolverData { bodies, dt, inv_dt };

        for constraint in constraints.iter_mut() {
            constraint.apply(&mut data, ConstraintSolveStep::Prepare);
        }

        for _ in 0..self.velocity_iterations {
            for constraint in constraints.iter_mut() {
                constraint.apply(&mut data, ConstraintSolveStep::Velocity);
            }
        }

        for _ in 0..self.position_iterations {
            let mut all_solved = true;
            for constraint in constraints.iter_mut() {
                if !constraint.apply(&mut data, ConstraintSolveStep::Position) {
                    all_solved = false;
                }
            }
            if all_solved {
                break;
            }
        }
    }

    pub fn solve_boxed(
        &self,
        constraints: &mut [Box<dyn Constraint>],
        bodies: &mut SlotMap<BodyHandle, Body>,
        dt: f32,
    ) {
        if constraints.is_empty() {
            return;
        }

        let inv_dt = if dt > f32::EPSILON { 1.0 / dt } else { 0.0 };

        let mut data = ConstraintSolverData { bodies, dt, inv_dt };

        for constraint in constraints.iter_mut() {
            constraint.apply(&mut data, ConstraintSolveStep::Prepare);
        }

        for _ in 0..self.velocity_iterations {
            for constraint in constraints.iter_mut() {
                constraint.apply(&mut data, ConstraintSolveStep::Velocity);
            }
        }

        for _ in 0..self.position_iterations {
            let mut all_solved = true;
            for constraint in constraints.iter_mut() {
                if !constraint.apply(&mut data, ConstraintSolveStep::Position) {
                    all_solved = false;
                }
            }
            if all_solved {
                break;
            }
        }
    }

    pub fn solve_all<'a, C1, C2, C3, C4, C5>(
        &self,
        contacts: &'a mut [C1],
        revolute: &'a mut [C2],
        distance: &'a mut [C3],
        prismatic: &'a mut [C4],
        weld: &'a mut [C5],
        bodies: &mut SlotMap<BodyHandle, Body>,
        dt: f32,
    ) where
        C1: Constraint,
        C2: Constraint,
        C3: Constraint,
        C4: Constraint,
        C5: Constraint,
    {
        let inv_dt = if dt > f32::EPSILON { 1.0 / dt } else { 0.0 };
        let mut data = ConstraintSolverData { bodies, dt, inv_dt };

        for c in contacts.iter_mut() {
            c.apply(&mut data, ConstraintSolveStep::Prepare);
        }
        for c in revolute.iter_mut() {
            c.apply(&mut data, ConstraintSolveStep::Prepare);
        }
        for c in distance.iter_mut() {
            c.apply(&mut data, ConstraintSolveStep::Prepare);
        }
        for c in prismatic.iter_mut() {
            c.apply(&mut data, ConstraintSolveStep::Prepare);
        }
        for c in weld.iter_mut() {
            c.apply(&mut data, ConstraintSolveStep::Prepare);
        }

        for _ in 0..self.velocity_iterations {
            for c in contacts.iter_mut() {
                c.apply(&mut data, ConstraintSolveStep::Velocity);
            }
            for c in revolute.iter_mut() {
                c.apply(&mut data, ConstraintSolveStep::Velocity);
            }
            for c in distance.iter_mut() {
                c.apply(&mut data, ConstraintSolveStep::Velocity);
            }
            for c in prismatic.iter_mut() {
                c.apply(&mut data, ConstraintSolveStep::Velocity);
            }
            for c in weld.iter_mut() {
                c.apply(&mut data, ConstraintSolveStep::Velocity);
            }
        }

        for _ in 0..self.position_iterations {
            let mut all_solved = true;
            for c in contacts.iter_mut() {
                if !c.apply(&mut data, ConstraintSolveStep::Position) {
                    all_solved = false;
                }
            }
            for c in revolute.iter_mut() {
                if !c.apply(&mut data, ConstraintSolveStep::Position) {
                    all_solved = false;
                }
            }
            for c in distance.iter_mut() {
                if !c.apply(&mut data, ConstraintSolveStep::Position) {
                    all_solved = false;
                }
            }
            for c in prismatic.iter_mut() {
                if !c.apply(&mut data, ConstraintSolveStep::Position) {
                    all_solved = false;
                }
            }
            for c in weld.iter_mut() {
                if !c.apply(&mut data, ConstraintSolveStep::Position) {
                    all_solved = false;
                }
            }
            if all_solved {
                break;
            }
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::constraint::Jacobian;
    use physics_core::{Body, BodyType, Material, Shape, Circle};
    use physics_math::Vec2;
    use slotmap::SlotMap;

    #[derive(Clone)]
    struct TestConstraint {
        body_a: BodyHandle,
        body_b: BodyHandle,
        prepared: bool,
        velocity_solved: bool,
        position_solved: bool,
    }

    impl Constraint for TestConstraint {
        fn body_a(&self) -> BodyHandle {
            self.body_a
        }

        fn body_b(&self) -> BodyHandle {
            self.body_b
        }

        fn prepare(&mut self, _data: &ConstraintSolverData) {
            self.prepared = true;
        }

        fn solve_velocity(&mut self, _data: &mut ConstraintSolverData) {
            self.velocity_solved = true;
        }

        fn solve_position(&mut self, _data: &mut ConstraintSolverData) -> bool {
            self.position_solved = true;
            true
        }
    }

    fn create_test_body(
        bodies: &mut SlotMap<BodyHandle, Body>,
        position: Vec2,
    ) -> BodyHandle {
        let shape = Shape::Circle(Circle::new(1.0));
        bodies.insert_with_key(|handle| {
            Body::new(
                handle,
                shape,
                position,
                0.0,
                BodyType::Dynamic,
                Material::DEFAULT,
            )
        })
    }

    #[test]
    fn test_constraint_solver_solve() {
        let mut bodies = SlotMap::with_key();
        let h1 = create_test_body(&mut bodies, Vec2::ZERO);
        let h2 = create_test_body(&mut bodies, Vec2::new(2.0, 0.0));

        let solver = ConstraintSolver::new(2, 2);

        let mut c1 = TestConstraint {
            body_a: h1,
            body_b: h2,
            prepared: false,
            velocity_solved: false,
            position_solved: false,
        };
        let mut c2 = TestConstraint {
            body_a: h1,
            body_b: h2,
            prepared: false,
            velocity_solved: false,
            position_solved: false,
        };

        let mut constraint_refs: Vec<&mut dyn Constraint> = vec![&mut c1, &mut c2];
        solver.solve(&mut constraint_refs, &mut bodies, 1.0 / 60.0);

        assert!(c1.prepared);
        assert!(c1.velocity_solved);
        assert!(c1.position_solved);
        assert!(c2.prepared);
        assert!(c2.velocity_solved);
        assert!(c2.position_solved);
    }

    #[test]
    fn test_constraint_solver_boxed() {
        let mut bodies = SlotMap::with_key();
        let h1 = create_test_body(&mut bodies, Vec2::ZERO);
        let h2 = create_test_body(&mut bodies, Vec2::new(2.0, 0.0));

        let solver = ConstraintSolver::new(2, 2);

        let c1: Box<dyn Constraint> = Box::new(TestConstraint {
            body_a: h1,
            body_b: h2,
            prepared: false,
            velocity_solved: false,
            position_solved: false,
        });
        let c2: Box<dyn Constraint> = Box::new(TestConstraint {
            body_a: h1,
            body_b: h2,
            prepared: false,
            velocity_solved: false,
            position_solved: false,
        });

        let mut constraints = vec![c1, c2];
        solver.solve_boxed(&mut constraints, &mut bodies, 1.0 / 60.0);

        // 验证约束被正确求解
        assert!(constraints[0].body_a() == h1);
        assert!(constraints[0].body_b() == h2);
    }
}

